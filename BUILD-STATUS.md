# Build status — Sari VPN 2.2.1

NOT COMPILED. The editing sandbox has no Android SDK, Gradle, Go or network access.
Verified statically only: XML well-formedness, bracket balance in edited Kotlin files,
resource/binding references, no remaining OpenVPN/AIDL/Advanced references.

Authoritative check: GitHub Actions workflow `.github/workflows/build.yml` (Actions tab -> Run workflow),
which produces the `SariVPN-MultiEngine-APK` artifact.

Not verified: that the Xray bridge compiles against the pinned xray-core, that the Blogger servers
accept connections right now, real internet access through any server, and Tor (external only).
