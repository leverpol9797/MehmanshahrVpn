package com.firstham.aethergui;

import android.content.Context;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * A stable identifier for this phone, used to bind a license to it.
 *
 * The value is derived from {@code Settings.Secure.ANDROID_ID}, which Android
 * scopes to the (app signing key, user, device) triple. In practice that means
 * it survives an app update and a clear-data, and differs on a different
 * handset — which is exactly the property a per-device licence needs.
 *
 * A licence is signed over this value, so it cannot be edited after the fact;
 * the check below only has to confirm the phone asking matches the one that
 * was issued to.
 *
 * The identifier is hashed and formatted rather than used raw, so the raw
 * ANDROID_ID never appears in a licence, a database row, or a chat message.
 */
final class DeviceId {

    /** Prefix that distinguishes a device code from a phone number. */
    private static final String PREFIX = "MSV";

    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private static final String PREFS = "license";
    private static final String KEY = "device_id";

    private DeviceId() {}

    /**
     * The device code, computed once and cached.
     *
     * Cached so that clearing app data does not silently invalidate a licence
     * the customer already paid for: the code shown to them before the reset is
     * the one that keeps working.
     */
    static String get(Context context) {
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String cached = prefs.getString(KEY, null);
        if (cached != null && !cached.isEmpty()) return cached;

        String derived = derive(context);
        prefs.edit().putString(KEY, derived).apply();
        return derived;
    }

    private static String derive(Context context) {
        String androidId = null;
        try {
            androidId = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception ignored) {
            // Some devices refuse to expose it; fall through to the package name.
        }
        String material = (androidId == null || androidId.isEmpty())
                ? context.getPackageName()
                : androidId;

        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated on every Android release; if it is somehow
            // absent the package name is still a stable, if weak, fallback.
            digest = material.getBytes(StandardCharsets.UTF_8);
        }
        return format(digest);
    }

    /** {@code MSV-XXXX-XXXX} from the first 40 bits of the digest. */
    private static String format(byte[] digest) {
        StringBuilder sb = new StringBuilder(PREFIX).append('-');
        // 8 base32 characters cover 40 bits, which is more than enough to make a
        // collision between two handsets irrelevant at this scale.
        for (int i = 0; i < 8; i++) {
            if (i == 4) sb.append('-');
            int b = digest[i % digest.length] & 0xFF;
            sb.append(ALPHABET[b % ALPHABET.length]);
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    /**
     * True when {@code licenseDevice} may be used on this phone.
     *
     * An empty value means the licence was issued before device binding existed
     * and is accepted; anything else must match exactly. Comparison is
     * case-insensitive because the code is displayed in upper case but may be
     * re-typed by hand in any case.
     */
    static boolean matches(Context context, String licenseDevice) {
        if (licenseDevice == null) return true;
        String d = licenseDevice.trim();
        if (d.isEmpty()) return true;   // unbound licence from an earlier era
        return d.equalsIgnoreCase(get(context));
    }
}
