# Build status — Sari VPN 2.2.3

## Verified in this environment
- XML well-formedness of all resources and the manifest.
- Bracket balance of all Kotlin sources.
- View-binding ids used by MainActivity/ServerAdapter exist in their layouts.
- Every R.drawable / R.color reference exists.
- Every TunnelState.Phase used exists in the enum.

## NOT verified here (no Android SDK, Gradle, Go, Java compiler, or internet access)
- Kotlin compilation and the Gradle build.
- gomobile build of xray-go (requires Go 1.26 and the pinned xray-core).
- That Xray's "tun" inbound accepts the fd passed via env vars in the pinned core version.
- That the Blogger servers accept connections, and internet access through any server.
- Notification traffic numbers and the power-button appearance on a device.

## Defects found by source review
1. (Critical, fixed) The Xray AAR was added with `implementation(files("libs/xray.aar"))`. Gradle does not unpack an AAR that way, so its classes and native .so were not packaged; the app would always fail with "Xray native library is missing". Fixed with a flatDir repository in settings.gradle.kts and `implementation(name = "xray", ext = "aar")`.
2. (Fixed in 2.2.2) Notification permission missing on Android 13+; no DNS/routing in the Xray config; notification without traffic data; locale-dependent digits.

## Still unresolved
- Tor is external only (opens the separate Tor VPN app).
- Protected feed source: a URL inside the APK is not secret.

Authoritative check: `.github/workflows/build.yml` (Actions -> Run workflow). It builds the AAR and produces `SariVPN-MultiEngine-APK`.
