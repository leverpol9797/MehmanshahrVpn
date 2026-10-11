package com.firstham.aethergui;

final class ConnectionDefaults {
    /**
     * Fresh-install / Reset-Defaults protocol. PRODUCT POLICY (MehmanshahrVpn): the default is
     * plain WireGuard, not a combined privacy chain. gool is the Aether project's own
     * transport; shipping one of its combined modes as the default of a paid client would make
     * that client read as a front for the free one. Every combined mode stays fully selectable --
     * this only decides what a fresh install and Reset Defaults start on.
     * The value is the STORAGE index into {@code VpnConnectionController.PROTOCOLS}, where index
     * 1 is "wg" (plain WireGuard). An existing user's saved protocol is never overwritten by
     * an upgrade; {@code PreferenceSchemaMigration} remaps a stored index once, at first run.
     */
    static final int PROTOCOL_INDEX = 1;
    static final String PROTOCOL = "wg";
    /**
     * Missing/reset preference default. Keep the array order stable: saved choices always win.
     * Index 1 is Turbo in both {@code R.array.scan_labels} and the controller's scan mapping.
     */
    static final int SCAN_INDEX = 1;
    static final String SCAN = "turbo";
    static final int OBFUSCATION_INDEX = 2;
    static final String OBFUSCATION = "balanced";
    static final String SMART_PROTOCOL = "smart";
    /**
     * MASQUE transport default. Index 1 of {@code R.array.transport_labels} is HTTP/2; the array
     * order is deliberately left alone so a stored index keeps meaning across the upgrade that
     * moved the default off HTTP/3. HTTP/3 stays fully supported and is used whenever the user
     * selects it - runtime evidence in security-evidence/phase15 showed HTTP/3 gateway discovery
     * failing on networks where HTTP/2 reaches the same edges, so it is no longer the default.
     */
    static final int TRANSPORT_INDEX = 1;
    static final String TRANSPORT = "h2";
    /** Automatic MTU is the fresh-install default; a stored "manual" value always wins. */
    static final String MTU_MODE = "automatic";

    static String normalizedScanMode(String requested) {
        if (requested == null) return SCAN;
        String mode = requested.trim().toLowerCase(java.util.Locale.ROOT);
        return "stealth".equals(mode) || "quiet".equals(mode) || "proven".equals(mode) ? "verified" : mode;
    }

    private ConnectionDefaults() { }
}
