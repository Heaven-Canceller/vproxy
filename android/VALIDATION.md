# Android port validation

Validated on 2026-11-08 (Asia/Shanghai).

## Deliverable

- APK: `vproxy-1.16.7-android.1-arm64-v8a.apk`
- Size: 25,092,947 bytes (about 23.9 MiB).
- SHA-256: `ab9cc4188f4c418ee8125bc98480625c53d2aff276981a5da5c29b510fe391af`
- Package: `io.github.heavencanceller.vproxy`.
- Minimum Android: 8.0 / API 26. Target and compile SDK: Android 15 / API 35.
- Signing: development RSA 3072 key, APK Signature Scheme v2 and v3 verified.
- Certificate SHA-256: `faced6f4dff58d2fed6ae416fb3ed2564d7d5d3d7d5b192b8d4ed24edb258b76`.

## Source and toolchain

- VProxy base: `e336d25bdb588be4f083e1b8af23f6414608de6b`, version 1.16.7.
- Mihomo: unmodified `3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8`, v1.19.33,
  built with `with_gvisor,cmfa` tags and NDK API 26.
- Arm64 Mihomo ELF SHA-256:
  `e196691af084558b6a7b57bb254c56f9d80c4052274c4af0031f3aec46d14d49`.
- Rust 1.99.1, JNI 0.21.1, JDK 17.0.17, Android NDK r29, Go 1.27.3.
- Scripts pin download checksums and source revision. Go modules are verified
  through the upstream `go.sum`; Rust dependencies are recorded in `Cargo.lock`.

## Packaging checks: PASS

- APK contains only `arm64-v8a` native files.
- `libvproxy.so`, `libvproxy_process.so`, and `libmihomo.so` are AArch64 ELF files.
- Every ELF LOAD segment uses `0x4000` (16 KiB) alignment.
- Native dependencies are Android system libraries (`libc`, `libdl`, `libm`).
- `apksigner verify --verbose --print-certs` and `zipalign -c -P 16 4` pass.
- Production manifest has no instrumentation or debuggable flag.
- App backup is disabled; node credentials and generated configuration use
  private app storage. Cleartext Java HTTP is restricted to loopback.
- Java, native process bridge, PowerShell parsing, UTF-8 without BOM, and
  whitespace checks pass. Rust release builds pass for arm64 and x86_64.
- Nine upstream desktop-only unused-code warnings remain in headless builds.

## Android runtime checks: PASS on x86_64 emulator

Environment: Android 15 / API 35 AOSP x86_64 system image, emulator 37.3.8,
Windows Hypervisor Platform acceleration. The simulator test APK uses the same
application code and includes explicit test instrumentation.

1. APK installs and the application opens; the main screen was inspected.
2. Rust JNI parses a local Shadowsocks fixture and extracts the expected server.
3. Mihomo, sing-box (Hysteria2 fixture), and Xray (Shadowsocks fixture) compile.
4. The control API receives a random per-configuration bearer secret.
5. The packaged engine starts, opens its mixed listener, forwards an HTTP request
   through a loopback HTTP proxy fixture, returns the expected body, and stops.
6. VpnService establishes IPv4 and IPv6 routes and passes its TUN FD across exec.
7. An independent app UID's Android resolver receives a fake-IP DNS answer from
   the VPN. Logs confirm UDP DNS hijacking at `172.19.0.2:53`.
8. The independent app receives `vproxy-tun-ok` through the VPN over both IPv4
   (`198.51.100.7`) and IPv6 (`2001:db8::7`) test destinations. These reserved
   addresses are served by the local proxy fixture; no public proxy is used.
9. The VPN service disconnects and the child process is reaped.

Exact passing instrumentation output:

```text
PASS: parser, three compilers, token, engine start, real local HTTP proxy response, engine stop
PASS: Android resolver received VPN DNS answer; independent application UID received vproxy-tun-ok over IPv4 and IPv6 through the VPN
PASS: VPN service, inherited TUN FD, separate-UID IPv4/IPv6 traffic reached local upstream, service stopped
```

## Fixes established by runtime evidence

- Standard Mihomo Android releases need privileged package-list access for TUN;
  the upstream `cmfa` build mode supports an ordinary VPN application.
- Android JSON slash escapes are normalized before Mihomo reads its YAML/JSON.
- A match-all rule selects the compiled node group; GLOBAL initially selects
  DIRECT and is unsuitable as an unconfigured default.
- Desktop GeoSite DNS policies are removed from this minimal mobile configuration
  to avoid a startup dependency on downloading a GeoSite database.
- Readiness requires both the authenticated control API and the TUN success log.
- Instrumentation now brings its separate probe activity to the foreground
  before DNS checks, allowing Android's UID network policy to settle.

## Remaining limits

No physical arm64 device was connected, so arm64 installation, vendor battery
management, and sustained mobile-network behavior remain unverified. No real
subscription or remote proxy credentials were used. The test proves local
end-to-end forwarding, not the availability of any external node. A 16 KiB
page-size device was not available; compatibility was checked structurally.

The port includes subscription import, node selection, TCP timing, export, and
one Mihomo VPN engine. Desktop collection scheduling, multiple managed engines,
smart policy editors, and failover UI remain future work. The upstream sing-box
compiler rejects some node protocols; those errors remain visible.

## Design choices

KISS/YAGNI: native Java widgets and direct Android tools avoid adding a web
frontend or desktop GUI layer. DRY: subscription parsing and configuration
compilation reuse upstream Rust code, and engine configuration normalization is
shared. Single responsibility: UI, VPN lifecycle, Rust JNI adaptation, native
process creation, and reproducible builds have separate files.

The next acceptance step is installing the arm64 APK on a physical phone,
importing a user-owned subscription, and checking browsing, DNS, switching
between Wi-Fi and cellular, and background behavior.
