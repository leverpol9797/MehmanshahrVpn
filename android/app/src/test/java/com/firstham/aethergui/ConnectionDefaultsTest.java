package com.firstham.aethergui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ConnectionDefaultsTest {
    @Test public void freshInstallUsesBalancedWireGuardAutomaticMtuAndHttp2() {
        // Index 1 is WireGuard. Gool is the upstream research client's own transport, and a
        // paid app shipping it as the default is a front for the free one. gool stays in the
        // selector and remains fully selectable.
        assertEquals(1, ConnectionDefaults.PROTOCOL_INDEX);
        assertEquals("wireguard", ConnectionDefaults.PROTOCOL);
        // Balanced is the fresh-install scan mode. Index 0 must stay aligned with R.array.scan_labels,
        // whose first entry is Balanced, and with VpnConnectionController.SCANS. Turbo is untouched:
        // it remains in both arrays and is still selectable, it is simply no longer the default.
        assertEquals(0, ConnectionDefaults.SCAN_INDEX);
        assertEquals("balanced", ConnectionDefaults.SCAN);
        assertEquals(2, ConnectionDefaults.OBFUSCATION_INDEX);
        assertEquals("balanced", ConnectionDefaults.OBFUSCATION);
        // MASQUE defaults to HTTP/2. Index 1 must stay aligned with R.array.transport_labels, whose
        // second entry is HTTP/2; the array order is deliberately unchanged so stored indices keep
        // their meaning across the upgrade.
        assertEquals(1, ConnectionDefaults.TRANSPORT_INDEX);
        assertEquals("h2", ConnectionDefaults.TRANSPORT);
        assertEquals("automatic", ConnectionDefaults.MTU_MODE);
        // The default must survive normalisation, or the connect intent would silently say "manual".
        assertEquals("automatic", VpnConnectionController.normalizedMtuMode(ConnectionDefaults.MTU_MODE));
    }

    @Test public void activeStatesCanBeStoppedFromTile() {
        assertTrue(VpnConnectionController.canDisconnect("connected"));
        assertTrue(VpnConnectionController.canDisconnect("reconnecting"));
        assertTrue(VpnConnectionController.canDisconnect("smart-testing"));
        assertFalse(VpnConnectionController.canDisconnect("disconnected"));
        assertFalse(VpnConnectionController.canDisconnect("error"));
    }

    @Test public void autoConnectOnlyStartsAnIdleLaunch() {
        assertTrue(VpnConnectionController.shouldAutoConnect(true, "disconnected"));
        assertTrue(VpnConnectionController.shouldAutoConnect(true, "error"));
        assertFalse(VpnConnectionController.shouldAutoConnect(false, "disconnected"));
        assertFalse(VpnConnectionController.shouldAutoConnect(true, "checking"));
        assertFalse(VpnConnectionController.shouldAutoConnect(true, "starting"));
        assertFalse(VpnConnectionController.shouldAutoConnect(true, "connected"));
    }

    @Test public void legacySmartModeMigratesToSmartProtocol() {
        assertEquals("vpn", VpnConnectionController.normalizedMode("smart"));
        assertEquals(3, VpnConnectionController.normalizedProtocolIndex("smart", 2));
        assertEquals("manual", VpnConnectionController.normalizedMode("manual"));
        assertEquals(1, VpnConnectionController.normalizedProtocolIndex("vpn", 1));
    }
}
