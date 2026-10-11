package com.firstham.aethergui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The 2.5.3 -> 2.5.4 protocol-index migration. These assertions pin the storage contract: a
 * silent misread here hands a user a tunnel they did not pick, so the two indices that changed
 * meaning are asserted individually rather than through a round trip.
 */
public class PreferenceSchemaMigrationTest {

    @Test
    public void lowIndicesMeanTheSameThingAndAreLeftAlone() {
        // MASQUE, WireGuard and gool sit at 0-2 in both the 2.5.3 five-entry order and upstream's
        // ProtocolOrder, so an upgrade must not touch them.
        assertEquals(0, PreferenceSchemaMigration.remap(0));
        assertEquals(1, PreferenceSchemaMigration.remap(1));
        assertEquals(2, PreferenceSchemaMigration.remap(2));
    }

    @Test
    public void legacySiphonBecomesGoolOverPsiphon() {
        // 2.5.3 index 3 was "siphon" (Psiphon over WARP). Upstream has no standalone siphon; index
        // 6 is gool+Psiphon, the same chain shape, so the user keeps a Psiphon-over-gool tunnel
        // instead of silently landing on Smart Connect.
        assertEquals(6, PreferenceSchemaMigration.remap(3));
    }

    @Test
    public void legacySmartConnectKeepsSmartConnect() {
        // 2.5.3 index 4 was Smart Connect. Under upstream's order that same index is MIM, so
        // without this remap an upgrading user would silently switch privacy modes.
        assertEquals(ProtocolOrder.SMART, PreferenceSchemaMigration.remap(4));
        assertEquals(3, PreferenceSchemaMigration.remap(4));
    }

    @Test
    public void currentSchemaIndicesPassThroughUntouched() {
        // An install that already wrote the current schema must not be re-mapped.
        assertEquals(5, PreferenceSchemaMigration.remap(5));
        assertEquals(8, PreferenceSchemaMigration.remap(8));
        assertEquals(10, PreferenceSchemaMigration.remap(10));
    }

    @Test
    public void defaultProtocolIsPlainWireGuard() {
        // The product decision this release carries: a paid client must not open on one of the
        // Aether project's own transports by default.
        assertEquals(1, ConnectionDefaults.PROTOCOL_INDEX);
        assertEquals("wg", ConnectionDefaults.PROTOCOL);
        // The default must be the plain WireGuard slot in upstream's storage order.
        assertEquals(ProtocolOrder.WIREGUARD, ConnectionDefaults.PROTOCOL_INDEX);
    }

    @Test
    public void migrationIsSelfDescribing() {
        assertEquals("2.5.3", PreferenceSchemaMigration.fromSchema());
        assertEquals("2.5.4", PreferenceSchemaMigration.toSchema());
    }
}