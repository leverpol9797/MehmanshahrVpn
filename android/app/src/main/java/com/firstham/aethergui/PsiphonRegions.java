package com.firstham.aethergui;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The egress countries a user may ask Psiphon for, and the names to show them.
 *
 * Psiphon's own {@code EgressRegion} key is a hard filter, not a weighting: set
 * it and every server outside that country leaves the candidate pool. That is
 * why the country is used for one short attempt in front of the ladder and then
 * dropped — see {@link SiphonChain}. Pinning it for the whole session would
 * break the path that works on the worst domestic operator, where only a
 * handful of the bundled servers advertise fronted dialing and they are all in
 * the US and the UK.
 *
 * Two sources, unioned:
 *
 *  - {@link #BUNDLED} is what the embedded server list advertises, so the picker
 *    is populated on a fresh install before any tunnel has come up.
 *  - Psiphon reports the live set through {@code onAvailableEgressRegions} on
 *    every successful handshake. That is authoritative — the embedded list ages
 *    — so it is cached and merged on top.
 *
 * A code with no name still works: it shows as the bare code, so a country
 * Psiphon adds later is selectable without an app update.
 */
public final class PsiphonRegions {

    /** Where the last onAvailableEgressRegions report is cached, comma-separated. */
    public static final String AVAILABLE_PREF = "psiphon_available_regions";

    /** The stored preference: a country code, or {@link #AUTO}. */
    public static final String PREF = "psiphon_egress_region";

    public static final String AUTO = "auto";

    private static final String STORE = "aether";

    /**
     * Countries the embedded server list carries, with the server counts as of
     * writing. The counts drift with every refresh, but they are the honest
     * predictor of whether a country will connect, and they are why the list is
     * ordered by name rather than by size: a user picking a country cares which
     * country it is, not how many servers it has.
     */
    private static final Map<String, String> BUNDLED = new LinkedHashMap<String, String>();

    private static final Map<String, Integer> BUNDLED_COUNT = new LinkedHashMap<String, Integer>();

    /** Names for countries Psiphon can report that the bundled list omits. */
    private static final Map<String, String> EXTRA_NAMES = new LinkedHashMap<String, String>();

    private static void bundled(String code, String name, int count) {
        BUNDLED.put(code, name);
        BUNDLED_COUNT.put(code, Integer.valueOf(count));
    }

    private static void extra(String code, String name) {
        EXTRA_NAMES.put(code, name);
    }

    static {
        bundled("AT", "Austria", 4);
        bundled("AU", "Australia", 8);
        bundled("BE", "Belgium", 3);
        bundled("CA", "Canada", 65);
        bundled("CH", "Switzerland", 5);
        bundled("CZ", "Czechia", 4);
        bundled("DE", "Germany", 60);
        bundled("DK", "Denmark", 7);
        bundled("ES", "Spain", 10);
        bundled("FI", "Finland", 7);
        bundled("FR", "France", 26);
        bundled("GB", "United Kingdom", 31);
        bundled("ID", "Indonesia", 3);
        bundled("IE", "Ireland", 3);
        bundled("IN", "India", 11);
        bundled("IT", "Italy", 9);
        bundled("JP", "Japan", 13);
        bundled("NL", "Netherlands", 38);
        bundled("NO", "Norway", 5);
        bundled("PL", "Poland", 18);
        bundled("RO", "Romania", 1);
        bundled("RS", "Serbia", 5);
        bundled("SE", "Sweden", 17);
        bundled("SG", "Singapore", 12);
        bundled("US", "United States", 65);

        extra("AE", "United Arab Emirates");
        extra("AR", "Argentina");
        extra("BG", "Bulgaria");
        extra("BR", "Brazil");
        extra("CL", "Chile");
        extra("EE", "Estonia");
        extra("GR", "Greece");
        extra("HK", "Hong Kong");
        extra("HU", "Hungary");
        extra("IL", "Israel");
        extra("IS", "Iceland");
        extra("KR", "South Korea");
        extra("LT", "Lithuania");
        extra("LV", "Latvia");
        extra("MD", "Moldova");
        extra("MX", "Mexico");
        extra("MY", "Malaysia");
        extra("NZ", "New Zealand");
        extra("PT", "Portugal");
        extra("SK", "Slovakia");
        extra("TR", "Türkiye");
        extra("TW", "Taiwan");
        extra("UA", "Ukraine");
        extra("ZA", "South Africa");
    }

    private PsiphonRegions() {
    }

    /** Human name for a two-letter code, or the code itself when unknown. */
    public static String name(String code) {
        String key = clean(code);
        String label = BUNDLED.get(key);
        if (label == null) label = EXTRA_NAMES.get(key);
        return label == null ? key : label;
    }

    /**
     * How many servers the bundled list advertises for a country, or 0 when
     * unknown.
     *
     * A country only Psiphon has reported has no bundled count, which is not a
     * problem — it means the count is unknown, so the picker says that rather
     * than claiming zero servers.
     */
    public static int bundledCount(String code) {
        Integer n = BUNDLED_COUNT.get(clean(code));
        return n == null ? 0 : n.intValue();
    }

    /** Flag + name, e.g. Germany with its flag. */
    public static String label(String code) {
        return flag(clean(code)) + " " + name(code);
    }

    private static String flag(String code) {
        if (code.length() != 2) return "";
        int high = code.charAt(0) - 'A' + 0x1F1E6;
        int low = code.charAt(1) - 'A' + 0x1F1E6;
        return new String(Character.toChars(high)) + new String(Character.toChars(low));
    }

    /**
     * Selectable countries, ordered by name.
     *
     * The bundled set is always included even if a cached report is narrower: a
     * report is a snapshot of one moment on one network, and dropping a country
     * the embedded list still carries would remove a working choice.
     */
    public static List<String> options(Context context) {
        List<String> codes = new ArrayList<String>(BUNDLED.keySet());
        for (String code : reported(context)) {
            if (!codes.contains(code)) codes.add(code);
        }
        Collections.sort(codes, new java.util.Comparator<String>() {
            @Override public int compare(String left, String right) {
                return name(left).compareTo(name(right));
            }
        });
        return codes;
    }

    private static List<String> reported(Context context) {
        List<String> out = new ArrayList<String>();
        String cached = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .getString(AVAILABLE_PREF, "");
        if (cached == null || cached.isEmpty()) return out;
        for (String part : cached.split(",")) {
            String code = clean(part);
            if (isCode(code)) out.add(code);
        }
        return out;
    }

    /** Cache Psiphon's live report so the picker reflects what the network offers. */
    public static void remember(Context context, List<String> codes) {
        if (codes == null || codes.isEmpty()) return;
        List<String> kept = new ArrayList<String>();
        for (String code : codes) {
            String value = clean(code);
            if (isCode(value) && !kept.contains(value)) kept.add(value);
        }
        if (kept.isEmpty()) return;
        Collections.sort(kept);
        StringBuilder joined = new StringBuilder();
        for (String code : kept) {
            if (joined.length() > 0) joined.append(',');
            joined.append(code);
        }
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
                .putString(AVAILABLE_PREF, joined.toString()).apply();
    }

    /**
     * The country the user asked for, or null for "let Psiphon choose".
     *
     * An unrecognised value is treated as auto rather than sent to Psiphon,
     * because EgressRegion is a hard filter: a typo would silently produce a
     * tunnel from a country the user did not ask for, or no tunnel at all.
     */
    public static String preferred(Context context) {
        String value = clean(context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .getString(PREF, AUTO));
        if (value.isEmpty() || AUTO.equals(value)) return null;
        return isCode(value) ? value : null;
    }

    /** Store the user's choice. {@code null} clears it back to auto. */
    public static void setPreferred(Context context, String code) {
        String value = clean(code);
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
                .putString(PREF, isCode(value) ? value : AUTO).apply();
    }

    /** The label for whatever is currently stored, including the auto case. */
    public static String preferredLabel(Context context) {
        String code = preferred(context);
        return code == null ? context.getString(R.string.psiphon_auto) : label(code);
    }

    /** Exactly two ASCII letters — anything else is not an egress region. */
    public static boolean isCode(String code) {
        if (code == null || code.length() != 2) return false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c < 'A' || c > 'Z') return false;
        }
        return true;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.US);
    }
}
