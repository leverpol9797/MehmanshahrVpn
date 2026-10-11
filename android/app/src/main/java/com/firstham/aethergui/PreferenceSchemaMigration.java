package com.firstham.aethergui;

import android.content.SharedPreferences;

/**
 * One-shot remap of the stored protocol index across the schema change that the MehmanshahrVpn
 * 2.5.3 -> 2.5.4 sync introduced.
 *
 * <p>The {@code protocol} preference is persisted as a STORAGE INDEX into
 * {@code VpnConnectionController.PROTOCOLS}, not as a protocol name, so the meaning of an index is
 * a contract. The 2.5.3 build shipped a five-entry list whose storage order was
 * {@code {masque, wg, gool, siphon, smart}} (siphon = "Psiphon over WARP"). 2.5.4 inherits
 * upstream's {@link ProtocolOrder} order
 * {@code 0=MASQUE 1=WireGuard 2=gool 3=Smart Connect 4=MIM 5=WG+Psiphon 6=gool+Psiphon ...}.
 *
 * <p>Indices 0-2 mean the same thing in both, but 3 and 4 do not: an install that had Smart Connect
 * stored at index 4 would silently start on MIM after the upgrade, and one that had siphon at
 * index 3 would silently start on Smart Connect. Both are silent — the tunnel still connects, just
 * to something the user did not pick — which is exactly the kind of change that must not happen
 * quietly. Hence this migration, run once per install before any preference is read.
 *
 * <p>{@code 3 -> 6} is deliberate rather than a fallback: index 3 used to mean "Psiphon carried
 * over the gool/WARP transport", and index 6 is upstream's {@code gool+Psiphon}, the same shape of
 * chain. Index 4 was Smart Connect and is Smart Connect again at index 3.
 *
 * <p>Nothing is overwritten when the marker is present, so a later Reset Defaults or a second
 * upgrade cannot re-run this against an already-current schema.
 */
final class PreferenceSchemaMigration {

    /** Preference that tells us whether {@link #migrate} has already run for this install. */
    private static final String MARKER_KEY = "protocol_schema_migrated";

    /** The schema this class migrates FROM, recorded so the migration is self-describing. */
    private static final String FROM_SCHEMA = "2.5.3";

    /** The schema this class migrates TO. */
    private static final String TO_SCHEMA = "2.5.4";

    private static final int OLD_SIPHON_INDEX = 3;
    private static final int OLD_SMART_INDEX = 4;

    /** gool + Psiphon under {@link ProtocolOrder} — the successor to standalone siphon. */
    private static final int NEW_GOOL_PSIPHON_INDEX = 6;
    private static final int NEW_SMART_INDEX = ProtocolOrder.SMART;

    /**
     * Remap the stored protocol index once. Safe to call on every launch: after the first run the
     * marker short-circuits, so the cost is one preference read.
     *
     * @return true when this call performed the migration, false when it was already done or there
     *         was nothing stored to remap.
     */
    static boolean migrate(SharedPreferences preferences) {
        if (preferences.getBoolean(MARKER_KEY, false)) return false;
        if (!preferences.contains("protocol")) {
            // Fresh install: nothing was ever stored, so there is no index with the old meaning.
            // Still mark it, otherwise every launch would re-check for a key that will never come.
            markDone(preferences);
            return false;
        }
        int stored = preferences.getInt("protocol", ConnectionDefaults.PROTOCOL_INDEX);
        int mapped = remap(stored);
        SharedPreferences.Editor editor = preferences.edit();
        if (mapped != stored) editor.putInt("protocol", mapped);
        markDone(editor);
        editor.apply();
        return mapped != stored;
    }

    /**
     * The mapping itself, kept separate so it can be asserted directly by a unit test without
     * going through SharedPreferences.
     */
    static int remap(int oldIndex) {
        if (oldIndex == OLD_SIPHON_INDEX) return NEW_GOOL_PSIPHON_INDEX;
        if (oldIndex == OLD_SMART_INDEX) return NEW_SMART_INDEX;
        // 0, 1, 2 are unchanged, and anything above the old five-entry range could only have been
        // written by a schema that is already current, so it passes through untouched.
        return oldIndex;
    }

    private static void markDone(SharedPreferences preferences) {
        preferences.edit().putBoolean(MARKER_KEY, true)
                .putString("protocol_schema", TO_SCHEMA).apply();
    }

    private static void markDone(SharedPreferences.Editor editor) {
        editor.putBoolean(MARKER_KEY, true).putString("protocol_schema", TO_SCHEMA);
    }

    /** Exposed for the migration unit test. */
    static String fromSchema() { return FROM_SCHEMA; }

    static String toSchema() { return TO_SCHEMA; }

    private PreferenceSchemaMigration() { }
}