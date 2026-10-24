# Third-party notices

Sari VPN integrates or communicates with the following third-party software. Check the linked upstream sources for the complete license texts and all dependent-library notices before publishing a binary.

## Xray-core — MPL-2.0

The embedded VLESS protocol engine is built from `github.com/xtls/xray-core` (pinned in `xray-go/go.mod`) and linked into the app as an Android AAR. Upstream project and license: https://github.com/XTLS/Xray-core and https://github.com/XTLS/Xray-core/blob/main/LICENSE. The Xray source used by this project can be reproduced using the pinned Go module and `scripts/build-xray-aar.sh`.

## WireGuard Tunnel — Apache-2.0

The in-app WireGuard engine uses `com.wireguard.android:tunnel:1.0.20260102`. Upstream source and license: https://git.zx2c4.com/wireguard-android/ and https://git.zx2c4.com/wireguard-android/plain/COPYING. The library ships native dependencies; retain the notices and comply with their respective licenses.

## OpenVPN for Android external API

The AIDL declarations under `app/src/main/aidl/de/blinkt/openvpn/api/` are based on the `remoteExample` API declarations from https://github.com/schwabe/ics-openvpn. The upstream documents `remoteExample` as Apache-2.0 licensed: https://github.com/schwabe/ics-openvpn/blob/master/doc/README.txt. Copyright attribution: Arne Schwabe and contributors.

Sari VPN does **not** bundle the OpenVPN for Android engine. It communicates with the separately installed app through the documented external API. The companion app retains its own GPL license and notices; this project's AIDL use does not copy its engine implementation.

## AndroidX, Material Components, Kotlin, OkHttp, Jsoup, and Gradle plugins

These dependencies retain their own licenses. Consult their artifact metadata and notices as part of the release compliance review.
