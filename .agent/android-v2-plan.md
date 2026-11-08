# Android v2 desktop-feature migration ExecPlan

## Version contract

Android v1 is the existing, validated initial port at commit
`dd17c1613587d9946b8a168a866ff9aac6bd596d`. Its APK, signing identity, source
archive, and validation remain unchanged. Android v2 development belongs on
`codex/android-v2`; v2 is not represented as a finished APK by creating this plan.

The working v2 objective is functional parity with desktop business features,
with explicit Android equivalents for platform integration. The user was asked
whether the first v2 delivery should instead focus on subscriptions and nodes.
No scope answer had arrived during version setup, so full desktop-business
migration remains the stated default; it is an assumption, not a recorded
user confirmation of each milestone. Versioning and assessment can proceed
independently of the delivery scope.

No `.agent/PLANS.md` exists in this checkout. This plan is self-contained and
must be updated as implementation and validation progress.

## Existing evidence

- Desktop UI is organized into Sources, Nodes, Proxy, Extensions, Settings,
  API, and About (`src/gui/types.rs`). Proxy panels include charts, groups,
  connections, rules, DNS/TUN, kernels, and logs.
- The Rust library already contains collection, database, scheduler, speedtest,
  export, geo, IP inspection, and proxy modules. The v1 JNI adapter exposes
  only subscription parsing and configuration compilation.
- The HTTP API (`src/api/mod.rs`) covers sources, nodes, settings, tasks,
  statistics, speedtests, kernel management, proxy lifecycle, and exports.
  Desktop GUI methods also access Rust services directly, so exposing the
  existing HTTP API alone is not evidence of complete desktop parity.
- `KernelPlatform::current()` falls back to Linux on Android. Kernel download
  and execution require explicit Android ABI handling before reuse.
- v1 Mihomo runs as an APK-packaged Android executable with `with_gvisor,cmfa`
  tags, a foreground VpnService, and inherited TUN FD. Preserve this validated
  Android lifecycle instead of calling desktop process launch paths unchanged.

## Architecture

Reuse the existing Rust database and business services through a narrow JNI
management facade. Keep Android UI, foreground-service ownership, VPN consent,
document access, and application filtering in Java. Configuration compilation
continues through the canonical upstream representation and per-engine compiler.

Use persisted source/node identities rather than duplicating desktop business
rules in Java. Bridge engine operations to the Android lifecycle. A task or
network operation must have cancellation, a visible outcome, and error handling;
rendering a page or displaying a running process is not functional validation.

Do not enable public APIs, perform source discovery, register external accounts,
query IP-inspection services, or publish nodes automatically. These are explicit
user operations in the product. Local test fixtures must not contain user data.

## Milestones and acceptance

1. **Persistent management:** initialize app-private SQLite, migrate v1 node
   JSON without data loss, manage subscription sources, merge refreshed nodes,
   preserve selection, filter/search/sort/delete nodes, and report source errors.
2. **Collection and verification:** bridge subscription refresh and supported
   discovery flows; expose real kernel-backed HTTP latency, throughput, batching,
   cancellation, health history, and task progress. TCP reachability remains
   separately labelled.
3. **Policy configuration:** support routing modes, custom rules, strategy
   groups, DNS options, Geo data, YAML/Rhai overrides, chaining, and port mappings
   where the selected engine supports them. Do not silently discard unsupported
   capabilities or retain v1's forced match-all configuration for these modes.
4. **Operational visibility:** traffic charts, connection list and termination,
   policy selection, rule hits, DNS records, engine logs, health diagnostics,
   export/import, and per-node/domain statistics where Android exposes evidence.
5. **Automation and API:** adapt scheduling, automatic selection, failure
   recovery, background collection, API authentication, and preferences to
   Android lifecycle and resource constraints; validate background behavior
   separately from foreground operation.
6. **Engines and Android integration:** verify each requested engine's Android
   ARM64 build, licensing, configuration validation, start/stop, VPN FD adapter,
   and actual traffic. Add Android app filtering, notification controls,
   document access, and update installation as appropriate. Windows registry,
   UAC, firewall rules, and tray objects require Android equivalents rather than
   unchanged desktop code. Privileged process inspection must be identified
   as unavailable when ordinary Android permissions do not expose it.
7. **Compatibility and regression:** preserve package ID and signing key,
   increase Android versionCode, upgrade v1 in place, verify settings/node
   migration, and repeat DNS, IPv4/IPv6, process-exit, network-change, and
   background tests. Build arm64 and x86_64 artifacts and inspect signatures,
   ABI, 16 KiB ELF alignment, manifest, encoding, and artifact checksums.
8. **Release evidence:** maintain a per-feature matrix of implemented, tested,
   pending, and platform-constrained items. Physical-device results and real
   subscriptions require their own evidence. Do not label v2 complete while
   agreed desktop-business milestones remain pending.

## Progress

- [x] Preserve v1's verified source commit and APK checksum in a version ledger.
- [x] Inspect desktop page groups, API surface, reusable modules, and Android
  kernel-platform gaps.
- [x] Establish `codex/android-v2` and the immutable `android-v1` source tag.
- [ ] Push the version ledger and v2 ExecPlan.
- [ ] Refine the default full-parity scope if the user supplies further detail.
- [ ] Implement and validate the selected v2 scope.

## Design principles

KISS: retain the proven native Android service and reuse existing Rust services.
YAGNI: implement desktop features that are actually present; add no speculative
framework or protocol layer. DRY: one authoritative store and compiler pipeline.
SOLID: isolate business operations, Android lifecycle, UI, and engine adapters.
