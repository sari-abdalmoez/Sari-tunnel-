# Build status

**Version 2.2.0 — source modified; APK not built in the editing environment.**

The editing sandbox has no Android SDK, Gradle, Go or network access, so the Kotlin code could not be compiled here and nothing was run on a device. What was checked statically: XML well-formedness, bracket balance, that every view-binding id and R.* resource referenced from Kotlin exists, and that no code references the removed classes.

The authoritative check is the GitHub Actions run:

1. Push to `main` (or Actions -> *Build Sari VPN APK* -> Run workflow).
2. The workflow installs Java 17, Go (version from `xray-go/go.mod`), Android SDK 35 + NDK 26.1.10909125, builds `app/libs/xray.aar` with `scripts/build-xray-aar.sh`, then runs `gradle :app:assembleDebug -x buildXrayAar` (Gradle 8.13).
3. Download the `SariVPN-MultiEngine-APK` artifact (kept 14 days).

Not verifiable without that run / a phone: that the Xray Go bridge still compiles against the pinned xray-core version, that the Blogger servers currently accept connections, and real-world speed. If the workflow fails, the log shows the exact line.

## 2.2.0 changes
- New Home / Location / Settings UI; VLESS feed limited to the Blogger post; 24-hour cached refresh; latency testing and grouped/sorted server list; in-tunnel traffic verification before showing "Connected".
- OpenVPN and Tor screens moved to `OpenVpnActivity` (Settings -> OpenVPN & Tor engines), behaviour unchanged.
- Removed: `VlessServerCatalogActivity`, `AutoServerSelector` (superseded).
- Restored `.github/workflows/build.yml`.
