package com.firstham.aethergui;

final class ConnectionDefaults {
    /**
     * Index 1 of {@code R.array.protocol_labels} is WireGuard. Gool was the
     * fresh-install default, but it is a research client's own transport and a
     * paid app that ships it as the default is just a front for the free one.
     */
    static final int PROTOCOL_INDEX = 1;
    /**
     * Fresh-install default; an existing preference value always wins at the call site. Index 0 of
     * {@code R.array.scan_labels} is Balanced, matching {@code VpnConnectionController.SCANS}.
     * Turbo is unchanged and still selectable - only which mode a new install starts on moved.
     */
    static final int SCAN_INDEX = 0;
    static final String PROTOCOL = "wireguard";
    static final String SCAN = "balanced";
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

    private ConnectionDefaults() { }
}
