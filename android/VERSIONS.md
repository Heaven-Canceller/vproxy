# Android release versions

## Android v1: initial usable port

- Source commit: `dd17c1613587d9946b8a168a866ff9aac6bd596d`.
- Source tag: `android-v1`.
- APK versionName: `1.16.7-android.1`; versionCode: `1160701`.
- ARM64 artifact: `vproxy-1.16.7-android.1-arm64-v8a.apk`.
- Bytes: `25092947`.
- SHA-256: `ab9cc4188f4c418ee8125bc98480625c53d2aff276981a5da5c29b510fe391af`.
- Package: `io.github.heavencanceller.vproxy`.
- Development certificate SHA-256:
  `faced6f4dff58d2fed6ae416fb3ed2564d7d5d3d7d5b192b8d4ed24edb258b76`.
- Scope: import, selection, TCP timing, configuration export, Mihomo VPN, logs.
- Evidence: [v1 validation](VALIDATION.md).

This baseline remains v1 even though upstream's application version is 1.16.7.
The Android milestone name and upstream release number are separate concepts.
Do not rename/rebuild this artifact in place and then reuse its recorded hash.

## Android v2: desktop-feature migration

- Development branch: `codex/android-v2`.
- Status: scope and architecture assessment; no v2 APK has been produced.
- Working objective: migrate desktop business features with Android-specific
  integration, tracked by [.agent/android-v2-plan.md](../.agent/android-v2-plan.md).
- Release identity: retain package ID and signing identity, and use a higher
  versionCode so v2 can update v1 without uninstalling it.
- User data migration and actual feature-level runtime verification are required.

v2 must have its own validation report and immutable artifact checksum. The
existing `VALIDATION.md` describes v1 and must not be mistaken for v2 evidence.
