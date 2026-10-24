# Dependency license checklist

- Xray-core: MPL-2.0 — https://github.com/XTLS/Xray-core/blob/main/LICENSE
- WireGuard Tunnel library: Apache-2.0 — https://git.zx2c4.com/wireguard-android/plain/COPYING
- OpenVPN for Android external AIDL `remoteExample`: Apache-2.0 — https://github.com/schwabe/ics-openvpn/blob/master/doc/README.txt
- OpenVPN for Android engine: not packaged in this project; its separately installed app uses GPL-2.0.
- AndroidX, Material Components, Kotlin, OkHttp, Jsoup and Gradle plugins: retain their upstream licenses and transitive notices.

Before publishing an APK, bundle the license texts and third-party notices required by each selected version and review transitive Go module license obligations for the pinned Xray-core graph.
