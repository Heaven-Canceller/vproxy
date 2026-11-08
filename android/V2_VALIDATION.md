# Android v2 Beta validation and migration matrix

Build: `2.0.0-beta.1`, Android versionCode `1160702`. Base: upstream VProxy
1.16.7. Source baseline: Android v1 `dd17c1613587d9946b8a168a866ff9aac6bd596d`.
Validation date: 2026-11-08, Asia/Shanghai.

This beta is a substantial second Android build, not completion of the full
desktop-parity roadmap. Android v1 artifacts and their checksums remain intact.

## Feature matrix

| Desktop capability | v2 Android implementation | Evidence or limit |
| --- | --- | --- |
| Persistent sources and nodes | Shared Rust SQLite services, sharded nodes, one-time v1 JSON import | Emulator migration and source/node CRUD pass; original JSON retained |
| Subscription refresh | Single/all enabled refresh, source flags, User-Agent, existing collector date/Telegram support | Local HTTP source fixture fetched and stored; public subscriptions not exercised |
| Discovery | Explicit Gist, OpenRung, and WARP actions | Entry points implemented; external discovery/registration not run in validation |
| Node pool | Merge, dedupe, query, status/speed sorting, paging, delete | Import/filter/dedupe and idempotent restore tested |
| TCP and real verification | TCP result tracking, existing Rust kernel test tasks, cancel, optional throughput | TCP is explicitly separate; real remote-node performance and throughput not claimed |
| Routing and DNS | Canonical options, custom rule import, DNS settings, Geo provisioning | Rule persistence and YAML compilation tested; runtime custom-rule regression tracked below |
| Policy groups | Automatic/url-test/fallback/load-balance configuration and authenticated runtime selection | Local group/control regression tracked below; live automatic selection requires real nodes |
| Advanced config | YAML/Rhai and full canonical JSON for chains, security options, port mappings | YAML tested; advanced combinations are implemented through existing compilers but not exhaustively run |
| Live monitoring | Traffic chart, current connections, terminate one/all, rules, DNS query, logs | Uses authenticated engine controller; not historical accounting for closed connections |
| Health | Manual real HTTPS check, private health history, optional two-failure candidate selection | History tested; remote health/failover not triggered against real nodes |
| IP inspection | Explicit upstream IP-lookup action | Implemented, not externally exercised |
| Publishing | User-configured HTTPS server, explicit push of actually verified eligible nodes | No live upload performed; zero unrequested node publication |
| Scheduling | Android JobScheduler, minimum 15 minutes, optional verification after refresh | Scheduled-time delivery, process killing, Doze, and boot persistence remain unverified/not implemented |
| Local management API | Optional loopback server, bearer auth, only during VPN service lifetime | Unauthenticated rejection and authenticated stats tested |
| Backup and restore | Sources, nodes/group mapping, canonical config, settings, selected-node/app preferences | Repeated merge preserves counts, node IDs, and grouping; telemetry history is not backed up |
| Multi-engine | All six upstream configuration compilers available; Mihomo packaged for VPN | The other five engines do not yet run as Android VPN backends |
| OS integration | VpnService, foreground notification, app exclusions, document picker | IPv4/IPv6 and DNS regression; physical OEM behavior still requires a phone |
| Desktop-specific UI/OS | Native mobile pages and notification controls | Full desktop themes/languages, tray/UAC/registry/firewall and unrestricted process matching are not reproduced |

## Upgrade and privacy

The package ID and signing certificate remain the same as v1. Upgrade was tested
by installing v1 on the dedicated emulator, seeding a synthetic `LegacyV1`
node, and installing v2 using `adb install -r`. Native migration moves records
into the shared desktop database without deleting the v1 JSON file. Selection
is migrated from the v1 preference index.

The application stores nodes, generated configuration, logs, settings, and
history under private app storage with backup disabled. Discovery, WARP
registration, IP lookup, and publishing occur only after explicit in-app actions.
Scheduled refresh and automatic failover are opt-in. The management interface
binds only to loopback and requires a random persisted bearer token; it sends
no CORS headers. Publisher credentials require HTTPS except local loopback.

## Tests

The Android 15/API 35 x86_64 emulator runs the same application source with test
instrumentation explicitly enabled. Passing management checks cover real SQLite
and JNI requests, source fetches through a local HTTP server, source/node CRUD,
v1 migration, duplicate imports, rule persistence, YAML application, repeated
backup merge, node identity/grouping, health history, packaged-engine detection,
API authentication, cancellation, and the six page headings.

The proxy regressions use loopback upstreams and reserved IP destinations. No
real subscription credentials or public proxy node are used. The independent
probe application has a different UID, proving traffic crosses the VPN instead
of the host app's excluded UID.

Passing final runtime output:

```text
PASS: v1 migration, source CRUD and local HTTP fetch, node merge/filter, rules and YAML, backup idempotence, history, packaged engine, authenticated API, cancellation, six UI pages
PASS: parser, three compilers, token, engine start, real local HTTP proxy response, engine stop
PASS: Android resolver received VPN DNS answer; independent application UID received vproxy-tun-ok over IPv4 and IPv6 through the VPN
PASS: v2 database-compiled config, custom runtime rule, authenticated groups/switch/connections, service-owned API, VPN TUN, separate-UID IPv4/IPv6 traffic, service stopped, unsupported process rules rejected
```

This additionally confirms the Android service API's lifecycle and a custom
REJECT rule in the actual running kernel, with authenticated group selection
and connection inspection. The physical arm64 device remains untested.

## Final artifacts

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `vproxy-2.0.0-beta.1-arm64-v8a.apk` | 30,049,107 | `ed41b82eb2ef7cb4b0918f7485b257839151384a45630b64825efd271b9a3ed2` |
| `vproxy-2.0.0-beta.1-x86_64.apk` | 32,109,386 | `bd7031dd7db6988ffa0104ff3bc21893244b53c746346d6f2d744ff9e899b41f` |

The ARM64 production build has no instrumentation and is not debuggable. Its
APK v2/v3 signature verifies, and `zipalign -c -P 16 4` passes. All three
packaged ELF files are AArch64 with every LOAD segment aligned to `0x4000`.
Only Android system native libraries are required. The certificate SHA-256 is
`faced6f4dff58d2fed6ae416fb3ed2564d7d5d3d7d5b192b8d4ed24edb258b76`, matching v1.

The x86_64 artifact explicitly includes local test instrumentation. Both APKs
require Android 8.0/API 26 and target Android 15/API 35. Rust release builds,
Java compilation, PowerShell syntax, UTF-8 without BOM, and whitespace checks
pass. Nine upstream desktop-only unused-code warnings remain. Screenshots of
all six native pages were inspected. Windows-specific Go commands from CPA's
unrelated repository are not validation commands for this Rust Android port.

The build uses locked Rust dependencies and emits only the `cdylib` needed by
the APK; Mihomo's pinned unmodified source and build tags match the v1 adapter.
The source, build instructions, and per-feature limits are part of the release.

## Design and implementation

The JNI management facade calls the existing Rust API router in process instead
of rewriting desktop collection/database behavior in Java or exposing a public
listener. A narrow Android-specific layer handles migration, compilation context,
rules, backups, and Android lifecycle operations. The canonical routing plan and
per-engine compilers remain authoritative. Android controls the VPN/TUN FD and
APK-packaged engine process.

This applies DRY to parsing, persistence and policy compilation; single
responsibility to management transport, UI, controller client, API server,
scheduled job, and VPN lifecycle; and KISS/YAGNI by retaining native widgets
and the established engine instead of introducing an additional frontend stack.

The largest integration challenges were preserving per-source identities across
restore, keeping Android from flattening custom rules to v1 match-all behavior,
and preserving the proven service/TUN lifecycle while exposing desktop services.

## Next acceptance work

Install the arm64 APK on a physical phone and check an owned subscription,
authenticated protocols, real throughput, Wi-Fi/cellular transitions, background
jobs and battery restrictions, automatic failover, and advanced rule/chain/port
mapping combinations. Additional VPN engines and full appearance/language parity
remain separate development milestones. They are not represented as completed
by this beta release.
