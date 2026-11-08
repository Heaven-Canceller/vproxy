# Android port

The existing `1.16.7-android.1` build is **Android v1**. This branch builds
**Android v2**, `2.0.0-beta.1`; see [version boundaries](VERSIONS.md), the
[v2 migration matrix](V2_VALIDATION.md), and the [ExecPlan](../.agent/android-v2-plan.md).

This directory adds a native Android interface to VProxy's existing Rust parser
and configuration compiler. It starts a pinned upstream Mihomo Android executable
from the APK's extracted native library directory. The VPN service creates an
Android TUN interface and passes its file descriptor to the child process.
The app excludes its own UID from VPN routing to prevent outbound loops.

## v2 features

- Shared desktop Rust SQLite database, one-time v1 node migration, and preserved v1 files.
- Subscription CRUD, enable/disable, per-source refresh, bulk refresh, custom User-Agent,
  date templates, Telegram previews, explicit Gist discovery, OpenRung, and WARP registration.
- Node merge/deduplication, keyword filtering, pagination, status and speed sorting,
  deletion, TCP timing, and desktop kernel-verification task interfaces with cancellation.
- Persistent routing modes, custom rules, DNS, automatic policy groups, YAML/Rhai
  overrides, Geo data, and full canonical configuration access for chain/port mappings.
- Runtime traffic chart, connection termination, policy-group selection, rules,
  DNS queries, logs, health history, optional health-based failover, and IP inspection.
- Android JobScheduler subscription refresh and optional verification after refresh.
- App VPN exclusions, optional authenticated loopback management API, node exports,
  backup/merge restore, and explicit publishing to a user-configured server.
- Configuration export through all six upstream compilers, subject to each compiler's
  actual protocol support; Mihomo remains the packaged VPN engine.
- Foreground VPN service, disconnect notification, process exit monitoring, logs.
- Local HTTP/SOCKS5 listener at `127.0.0.1:9980` while connected.
- IPv4 and IPv6 default routes; Mihomo DNS hijacking through the TUN interface.

v2 is a beta migration milestone, not a claim that every desktop capability has
been reproduced. Five other VPN engines, full historical per-app traffic,
desktop theme/language parity, privileged process matching, and boot-persistent
scheduling are not implemented. Advanced configuration currently uses JSON/YAML
editors rather than all desktop graphical editors. TCP latency measures the
server port only; it does not assert successful proxy authentication.
Configuration export retains upstream compiler limits. In particular, the
current sing-box compiler rejects HTTP and Shadowsocks nodes; it supports a
smaller protocol set than Mihomo. Unsupported exports show the compiler error.

## Build on Windows

Install JDK 17+ and Rust stable. The GNU Windows host needs a MinGW-w64 GCC on
PATH; the MSVC host needs Visual C++ build tools. This machine used the GNU host
and an existing GCC. Then run:

```powershell
./android/build.ps1 -Abi arm64-v8a
# Optional local HTTP proxy for downloads:
./android/build.ps1 -Abi arm64-v8a -Proxy http://127.0.0.1:9980
# Separate test ABI for an x86_64 Android emulator:
./android/build.ps1 -Abi x86_64 -SmokeTest
```

The script downloads the Android 35 platform, build-tools 35.0.0, and NDK r29
from Google's official repository and verifies their repository SHA-1 values.
Mihomo v1.19.33 is compiled from pinned, unmodified source commit
`3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8` with `with_gvisor,cmfa` tags.
The `cmfa` tag uses the Android VPN host's UID routing and avoids privileged
`/data/system/packages.xml` access. The standard release binary cannot start
its TUN listener inside an ordinary Android application. The script installs
a checksum-verified Go 1.27.3 runtime in the tool directory when needed.
Tools and development signing material stay outside the checkout by default.
No global Gradle installation or Android Studio is required.

The default output is development signed. For a private distribution key,
provide `-KeyStore` and `-KeyAlias`, and set `VPROXY_STORE_PASSWORD` in the process
environment. Keep the key for future updates. APK output is under `dist/`.
The build requires Android SDK/NDK license acceptance; review their included
license files before using or distributing their tools.

## Install and test

```powershell
adb install -r dist/vproxy-2.0.0-beta.1-arm64-v8a.apk
adb shell am start -n io.github.heavencanceller.vproxy/.MainActivity
```

An arm64-only APK requires an arm64 Android device or compatible emulator.
Use the separate x86_64 build for a standard accelerated x86_64 AVD.
Grant notification permission and Android's VPN consent, import your own node,
select it, and connect. Android may require excluding the app from aggressive
vendor battery management for extended background use.

For repeatable emulator checks, build the x86_64 APK with `-SmokeTest`, install
it on the test emulator, then run:

```powershell
adb -s emulator-5556 shell am instrument -w io.github.heavencanceller.vproxy/.SmokeTest
adb -s emulator-5556 shell am instrument -w io.github.heavencanceller.vproxy/.ManagementTest
./android/build-probe.ps1
adb -s emulator-5556 install -r android/build/probe/vproxy-tunnel-probe.apk
# Grant test-emulator VPN consent and notifications:
adb -s emulator-5556 shell appops set io.github.heavencanceller.vproxy ACTIVATE_VPN allow
adb -s emulator-5556 shell pm grant io.github.heavencanceller.vproxy android.permission.POST_NOTIFICATIONS
# Terminal 1: wait for VPN_READY.
adb -s emulator-5556 shell am instrument -w io.github.heavencanceller.vproxy/.VpnSmokeTest
# Terminal 2: run while Terminal 1 is waiting.
adb -s emulator-5556 shell am instrument -w io.github.heavencanceller.vproxyprobe/.TunnelProbe
```

These fixtures use loopback upstreams and a reserved IPv4 destination. The
separate probe UID is essential: VProxy's own UID is excluded from its VPN.
The release arm64 build does not include instrumentation entry points.
The management upgrade fixture requires a prior v1 `files/nodes.json` containing
the synthetic `LegacyV1` node. This must only be seeded on a dedicated emulator;
never overwrite an actual user's node file to run it.

## Licensing and source

VProxy and this adapter retain the root MIT license. Mihomo is an independent
GPL-3.0 executable, distributed with its full license in the app's assets.
Exact corresponding source: <https://github.com/MetaCubeX/mihomo/tree/3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8>.
Upstream release: <https://github.com/MetaCubeX/mihomo/releases/tag/v1.19.33>.
The executable is launched as a separate process rather than linked into VProxy.

No real subscription credentials or signing keys belong in Git.
