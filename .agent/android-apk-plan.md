# Android APK implementation plan

## Objective

Produce an installable, signed Android arm64-v8a APK from upstream VProxy
e336d25bdb588be4f083e1b8af23f6414608de6b (1.16.7), preserving its MIT notice.
Reuse the existing Rust subscription parser and configuration compiler through JNI.
Provide import, node selection, TCP latency, configuration export, and a managed
Mihomo proxy process. Integrate Android VpnService with an inherited TUN file
descriptor if supported by the upstream Android engine. Clearly report runtime
validation separately from APK packaging.

No .agent/PLANS.md was supplied in this checkout or its parent directories. This
self-contained plan records scope, decisions, execution status, and validation.

## Steps

1. Inspect source, licenses, available tools, and GitHub fork authorization.
2. Install a minimal isolated Android SDK/NDK and Rust toolchain using the existing JDK.
3. Add a small JNI bridge without modifying the desktop GUI architecture.
4. Add an Android Java application, native process lifecycle bridge, and VpnService.
5. Package a pinned upstream Android Mihomo engine with its license and provenance.
6. Build and sign an arm64-v8a APK and verify ABI, manifest, signature, and native alignment.
7. Exercise parsing, configuration, start/stop, and proxy traffic in an emulator when feasible.
8. Deliver APK, reproducible build instructions, validation evidence, and fork status.

## Validation

- Build Rust with GUI disabled and compile Java against an Android platform SDK.
- Test the JNI parser and compiler with local fixture nodes, without private subscriptions.
- Validate Mihomo configuration before starting it; expose actual process errors.
- Use private app storage for node credentials, logs, and generated configuration.
- Sign installable builds and check them with apksigner and zipalign.
- Check arm64 ELF machine type and Android 16 KB page compatibility.
- Record what ran on Android and what remains untested on physical arm64 hardware.

## Status

- Source cloned successfully using the machine's configured local proxy.
- User created the fork at Heaven-Canceller/vproxy; the connector still returns 403 on writes.
- Rust stable 1.99.1 GNU, Android 35 platform/build-tools, NDK r29, and an API 35 x86_64 emulator are installed.
- Upstream Mihomo accepts a TUN file descriptor; native process inheritance needs implementation.
- Android UI, JNI adapter, native child-process bridge, VPN service, and repeatable build script are implemented.
- The first arm64-v8a APK passed APK v2/v3 signing and ZIP alignment checks. All three native ELF files are AArch64 with 16 KB load alignment.
- x86_64 emulator tests passed: parsing, three compilers, local mixed proxy traffic,
  inherited TUN FD, independent-UID Android DNS, IPv4 and IPv6 forwarding, and stop.
- The final arm64 APK was rebuilt with the VPN-compatible source-built engine.
- See android/VALIDATION.md for exact hashes, evidence, and physical-device limits.
