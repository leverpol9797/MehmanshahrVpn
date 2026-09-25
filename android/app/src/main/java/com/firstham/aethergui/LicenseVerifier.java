package com.firstham.aethergui;

import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Verifies licenses signed offline by the issuing service.
 *
 * ── THE CONTRACT ────────────────────────────────────────────────────────────
 * The issuer signs exactly one string, built from these fields in this order,
 * joined with '|', no whitespace, UTF-8:
 *
 *     v1|<sub>|<device>|<nbf>|<exp>|<tier>
 *
 * The license arrives as JSON, but the JSON is only the envelope. Signing JSON
 * would be a bug: key order is not stable, so signer and verifier can disagree
 * on a perfectly valid license.
 *
 * Changing anything on this page means changing the issuer's
 * {@code src/license.js} to match. The cross-language test
 * {@code LicenseVerifierContractTest} exists to catch it when you forget.
 * ───────────────────────────────────────────────────────────────────────────
 */
public final class LicenseVerifier {

    /**
     * Public key, standard base64 of the raw 32 Ed25519 bytes.
     *
     * Public by design — it grants nothing on its own. The matching private key
     * never leaves the issuing service, which is the only reason a signature here
     * means anything.
     */
    static final String PUBLIC_KEY_B64 = "znfzefP1LSZCIlXFhTFHa1v3cK5YiiT0U2qE6mlmswE=";

    private LicenseVerifier() { }

    /* ── result ────────────────────────────────────────────────────────────── */

    public enum Status {
        /** Signature verified and the license is inside its validity window. */
        VALID,
        /** Not a license, or the signature does not match. */
        BAD_SIGNATURE,
        /** Signed correctly, but the validity window has closed. */
        EXPIRED,
        /** Signed correctly, but nbf is in the future — usually a wrong device clock. */
        NOT_YET_VALID,
        /** Well-formed JSON, but not something this verifier understands. */
        MALFORMED
    }

    public static final class Result {
        public final Status status;
        /** User id the license was minted for; null unless VALID or EXPIRED. */
        public final String subject;
        public final long expiresAt;
        public final String tier;

        private Result(Status status, String subject, long expiresAt, String tier) {
            this.status = status;
            this.subject = subject;
            this.expiresAt = expiresAt;
            this.tier = tier;
        }

        public boolean isValid() { return status == Status.VALID; }

        /** True when the signature was good but the license is too old to use. */
        public boolean isExpired() { return status == Status.EXPIRED; }

        /** No license on file. */
        public static Result none() {
            return new Result(Status.MALFORMED, null, 0L, null);
        }
    }

    /* ── the signed bytes ──────────────────────────────────────────────────── */

    /**
     * The one function that must stay identical to the issuer's
     * {@code canonical()} in src/license.js.
     */
    static byte[] canonical(String subject, String device, long nbf, long exp, String tier) {
        String message = "v1|" + subject + "|" + device + "|" + nbf + "|" + exp + "|" + tier;
        return message.getBytes(StandardCharsets.UTF_8);
    }

    /* ── verification ──────────────────────────────────────────────────────── */

    /**
     * Pull the license object out of whatever the user pasted.
     *
     * The license arrives as a chat message, and nobody selects four lines out of
     * a sentence and gets the brackets exactly right. So the whole message is
     * accepted and the JSON object is taken from it.
     *
     * Brace counting is string-aware: a "{" inside the base64 signature or a
     * quoted value must not be mistaken for the end of the object, which is
     * what a naive first-{ to last-} slice would do.
     */
    static String extractJson(String pasted) {
        if (pasted == null) return "";
        String s = pasted.trim();
        if (s.startsWith("{") && s.endsWith("}")) return s;

        int depth = 0, start = -1;
        boolean inString = false, escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) return s.substring(start, i + 1);
            }
        }
        return s;
    }

    public static Result verify(String licenseJson) {
        return verify(licenseJson, PUBLIC_KEY_B64, System.currentTimeMillis() / 1000L);
    }

    /**
     * Overload with the key and clock injected, so tests do not need to mutate
     * {@link #PUBLIC_KEY_B64} or wait a century for an expiry.
     */
    public static Result verify(String licenseJson, String publicKeyB64, long nowUnix) {
        if (licenseJson == null || licenseJson.isEmpty()) {
            return new Result(Status.MALFORMED, null, 0L, null);
        }

        JSONObject o;
        try {
            o = new JSONObject(extractJson(licenseJson));
        } catch (Exception e) {
            return new Result(Status.MALFORMED, null, 0L, null);
        }

        try {
            if (o.getInt("v") != 1) return new Result(Status.MALFORMED, null, 0L, null);

            String subject = o.getString("sub");
            String device = o.optString("device", "");
            long nbf = o.getLong("nbf");
            long exp = o.getLong("exp");
            String tier = o.getString("tier");
            String sigB64 = o.getString("sig");

            // Check the window before spending time on the curve. An expired
            // license still reports EXPIRED rather than BAD_SIGNATURE, because
            // the signature is what tells us the expiry is genuine.
            boolean tooEarly = nowUnix < nbf;
            boolean tooLate = nowUnix >= exp;

            if (!signatureHolds(subject, device, nbf, exp, tier, sigB64, publicKeyB64)) {
                return new Result(Status.BAD_SIGNATURE, null, 0L, null);
            }

            if (tooEarly) return new Result(Status.NOT_YET_VALID, subject, exp, tier);
            if (tooLate) return new Result(Status.EXPIRED, subject, exp, tier);
            return new Result(Status.VALID, subject, exp, tier);

        } catch (Exception e) {
            return new Result(Status.MALFORMED, null, 0L, null);
        }
    }

    private static boolean signatureHolds(String subject, String device, long nbf, long exp,
                                          String tier, String sigB64, String publicKeyB64) {
        try {
            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyB64);
            if (publicKeyBytes.length != 32) return false;

            byte[] signature = Base64.getDecoder().decode(sigB64);
            if (signature.length != 64) return false;

            EdDSAPublicKeySpec spec = new EdDSAPublicKeySpec(
                    publicKeyBytes, EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519));
            EdDSAPublicKey key = new EdDSAPublicKey(spec);

            // EdDSAEngine extends java.security.Signature, so the key goes in
            // through initVerify and the message through verifyOneShot. There is
            // no verify(data, sig, key) overload — that is the API of other
            // Ed25519 libraries, not this one.
            EdDSAEngine engine = new EdDSAEngine(MessageDigest.getInstance("SHA-512"));
            engine.initVerify(key);
            return engine.verifyOneShot(canonical(subject, device, nbf, exp, tier), signature);

        } catch (Exception e) {
            return false;
        }
    }

    /* ── key handling ──────────────────────────────────────────────────────── */

    /**
     * True when the compiled-in public key is a usable Ed25519 key.
     *
     * Called from the login screen so a build shipped with the placeholder still
     * installed shows an honest message instead of rejecting every license.
     */
    public static boolean isKeyConfigured() {
        return isKeyConfigured(PUBLIC_KEY_B64);
    }

    /**
     * Key-shaped check, parameterised so it can be tested without depending on
     * which key the current build happens to carry.
     */
    static boolean isKeyConfigured(String keyB64) {
        if (keyB64 == null
                || keyB64.isEmpty()
                || keyB64.startsWith("REPLACE_")) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(keyB64).length == 32;
        } catch (Exception e) {
            return false;
        }
    }
}
