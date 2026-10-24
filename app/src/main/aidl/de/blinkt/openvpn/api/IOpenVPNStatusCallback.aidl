/*
 * API declaration based on the Apache-2.0-licensed ics-openvpn remoteExample project.
 * Copyright (c) Arne Schwabe and contributors. See THIRD-PARTY-NOTICES.md.
 */

package de.blinkt.openvpn.api;
oneway interface IOpenVPNStatusCallback {
    void newStatus(String uuid, String state, String message, String level);
}
