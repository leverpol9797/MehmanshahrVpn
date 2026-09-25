package com.firstham.aethergui;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * Holds the active license and answers one question: may this build connect?
 *
 * Storage note — the license is kept in plain SharedPreferences, deliberately.
 * The signature already guarantees nobody can alter it, so encrypting the blob
 * would only hide a value the user owns anyway, at the cost of a fragile
 * keystore dependency. What matters is that it lives in its own prefs file so
 * the existing "backup settings" export does not sweep it up: a licence that
 * rides along in a settings file is a licence users hand each other.
 */
public final class AuthGate {

    private static final String PREFS = "license";
    private static final String KEY_LICENSE = "license_json";
    private static final String KEY_SUBJECT = "subject";
    private static final String KEY_EXPIRES = "expires_at";
    private static final String KEY_TIER = "tier";

    private static final SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private AuthGate() { }

    /** The stored license text, or null when there is none. */
    public static String stored(Context context) {
        String v = prefs(context).getString(KEY_LICENSE, null);
        return TextUtils.isEmpty(v) ? null : v;
    }

    /**
     * Validate a license the user pasted, and remember it if it holds.
     *
     * @return the verifier result, so the caller can show the precise reason —
     *         "expired" and "bad signature" are different problems for a user
     *         and should not both read as "wrong".
     */
    public static LicenseVerifier.Result submit(Context context, String licenseJson) {
        LicenseVerifier.Result result = LicenseVerifier.verify(licenseJson);
        if (result.isValid()) {
            prefs(context).edit()
                    .putString(KEY_LICENSE, licenseJson.trim())
                    .putString(KEY_SUBJECT, result.subject)
                    .putLong(KEY_EXPIRES, result.expiresAt)
                    .putString(KEY_TIER, result.tier)
                    .apply();
        }
        return result;
    }

    /**
     * The gate itself. Called before the tunnel is built, not just by the UI —
     * the quick-settings tile and the home-screen widget both reach the service
     * without ever opening an activity, so a check anywhere else leaves the
     * tunnel startable.
     */
    public static boolean isValid(Context context) {
        String stored = stored(context);
        return stored != null && LicenseVerifier.verify(stored).isValid();
    }

    /** Re-check the stored license and drop it if it has lapsed. */
    public static LicenseVerifier.Result revalidate(Context context) {
        String stored = stored(context);
        if (stored == null) return LicenseVerifier.Result.none();
        LicenseVerifier.Result result = LicenseVerifier.verify(stored);
        if (!result.isValid()) clear(context);
        return result;
    }

    /** Unix expiry of the stored license, or 0 when there is none. */
    public static long expiresAt(Context context) {
        return prefs(context).getLong(KEY_EXPIRES, 0L);
    }

    public static String subject(Context context) {
        return prefs(context).getString(KEY_SUBJECT, null);
    }

    public static void clear(Context context) {
        prefs(context).edit().clear().apply();
    }
}
