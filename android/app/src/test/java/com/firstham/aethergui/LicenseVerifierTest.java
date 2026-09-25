package com.firstham.aethergui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Cross-language test for the license contract.
 *
 * {@link #TEST_PUBLIC_KEY} and {@link #TEST_LICENSE} below are not invented
 * here — they were produced by the issuing service:
 *
 *     node scripts/gen-testvector.js   →   aether-lic/test-vectors.json
 *
 * which signs with Ed25519 in WebCrypto. If this file verifies that vector, the
 * Java verifier and the JS issuer agree byte for byte. If they ever drift, this
 * test fails — which is the point. A verifier tested only against itself agrees
 * with nothing.
 *
 * The key here is a disposable test key. The real one lives in
 * {@link LicenseVerifier#PUBLIC_KEY_B64} and comes from the issuing service.
 */
public class LicenseVerifierTest {

    private static final String TEST_PUBLIC_KEY =
            "uIZgkQUfh0l1SDMCgrvaKeBxFsb6Cb/tQ+Z5sKOEPUg=";

    private static final String TEST_LICENSE =
            "{\"v\":1,\"sub\":\"u_testvector0001\",\"device\":\"\",\"nbf\":1790361823,"
            + "\"exp\":1798137823,\"tier\":\"user\","
            + "\"sig\":\"xc6/mlkkPvAnRslBENV7u2OzQ+4dy329s+4B+ZLm0XhCLkp8buiW3dP7CtFk03wY3LRyp6ecLI1CUpjN6wh7Aw==\"}";

    private static final long NBF = 1790361823L;
    private static final long EXP = 1798137823L;

    /** A different, equally real key — the "attacker does not have this" case. */
    private static final String WRONG_KEY =
            "SXdv2qcLq0Ny1LZ9VYqxCBZ3T3Q1M0WYlPGkZ0jVWZg=";

    private static LicenseVerifier.Result verify(String json) {
        return LicenseVerifier.verify(json, TEST_PUBLIC_KEY, NBF + 3600);
    }

    /* ── the happy path ────────────────────────────────────────────────────── */

    @Test public void aLicenseSignedByTheIssuerVerifies() {
        LicenseVerifier.Result r = verify(TEST_LICENSE);
        assertEquals(LicenseVerifier.Status.VALID, r.status);
        assertTrue(r.isValid());
        assertEquals("u_testvector0001", r.subject);
        assertEquals(EXP, r.expiresAt);
        assertEquals("user", r.tier);
    }

    @Test public void theSignedMessageMatchesTheAgreedLayout() throws Exception {
        JSONObject o = new JSONObject(TEST_LICENSE);
        String expected = "v1|u_testvector0001||" + NBF + "|" + EXP + "|user";
        String actual = new String(LicenseVerifier.canonical(
                o.getString("sub"), o.getString("device"), o.getLong("nbf"),
                o.getLong("exp"), o.getString("tier")), StandardCharsets.UTF_8);
        assertEquals(expected, actual);
    }

    /* ── the point of the whole thing ──────────────────────────────────────── */

    @Test public void aLicenseDoesNotVerifyUnderAnUnrelatedKey() {
        LicenseVerifier.Result r =
                LicenseVerifier.verify(TEST_LICENSE, WRONG_KEY, NBF + 3600);
        assertEquals(LicenseVerifier.Status.BAD_SIGNATURE, r.status);
        assertFalse(r.isValid());
    }

    @Test public void aHandWrittenLicenseIsRejected() {
        String forged = "{\"v\":1,\"sub\":\"u_attacker\",\"device\":\"\",\"nbf\":"
                + NBF + ",\"exp\":" + (NBF + 999999) + ",\"tier\":\"user\",\"sig\":\"AAAA\"}";
        assertEquals(LicenseVerifier.Status.BAD_SIGNATURE, verify(forged).status);
    }

    @Test public void alteringAnySignedFieldBreaksTheSignature() throws Exception {
        String[] fields = {"sub", "exp", "tier", "device", "nbf"};
        for (String field : fields) {
            JSONObject o = new JSONObject(TEST_LICENSE);
            switch (field) {
                case "sub":    o.put("sub", "u_someone_else"); break;
                case "tier":   o.put("tier", "admin"); break;
                case "device": o.put("device", "stolen"); break;
                case "exp":    o.put("exp", EXP + 86_400_000L); break;
                case "nbf":    o.put("nbf", NBF - 86_400_000L); break;
                default: throw new IllegalStateException(field);
            }
            assertEquals("tampering with " + field + " must be caught",
                    LicenseVerifier.Status.BAD_SIGNATURE, verify(o.toString()).status);
        }
    }

    /* ── the validity window ───────────────────────────────────────────────── */

    @Test public void anExpiredLicenseIsSignedButUnusable() {
        LicenseVerifier.Result r =
                LicenseVerifier.verify(TEST_LICENSE, TEST_PUBLIC_KEY, EXP + 1);
        assertEquals(LicenseVerifier.Status.EXPIRED, r.status);
        assertTrue(r.isExpired());
        assertFalse(r.isValid());
        // The signature still holds, so the subject survives for the UI to show
        assertEquals("u_testvector0001", r.subject);
    }

    @Test public void aLicenseIsValidUpToTheInstantBeforeExpiry() {
        assertTrue(LicenseVerifier.verify(TEST_LICENSE, TEST_PUBLIC_KEY, EXP - 1).isValid());
    }

    @Test public void aLicenseIsNotYetValidBeforeItsWindowOpens() {
        LicenseVerifier.Result r =
                LicenseVerifier.verify(TEST_LICENSE, TEST_PUBLIC_KEY, NBF - 1);
        assertEquals(LicenseVerifier.Status.NOT_YET_VALID, r.status);
    }

    /* ── rubbish input ─────────────────────────────────────────────────────── */

    @Test public void malformedInputIsRejectedWithoutThrowing() {
        String[] junk = {
                null, "", "   ", "not json at all", "[]", "{}",
                "{\"v\":1}", "{\"v\":2,\"sub\":\"a\",\"nbf\":1,\"exp\":2,\"tier\":\"user\",\"sig\":\"AA==\"}",
                "{\"v\":1,\"sub\":\"a\",\"nbf\":\"soon\",\"exp\":2,\"tier\":\"user\",\"sig\":\"AA==\"}",
                "{\"v\":1,\"sub\":\"a\",\"device\":\"\",\"nbf\":1,\"exp\":2,\"tier\":\"user\",\"sig\":\"###\"}",
        };
        for (String s : junk) {
            LicenseVerifier.Result r = LicenseVerifier.verify(s, TEST_PUBLIC_KEY, NBF);
            assertFalse("should not accept: " + s, r.isValid());
        }
    }

    @Test public void aSignatureOfTheWrongLengthIsRejected() throws Exception {
        JSONObject o = new JSONObject(TEST_LICENSE);
        o.put("sig", "AAAA");   // 3 bytes instead of 64
        assertEquals(LicenseVerifier.Status.BAD_SIGNATURE, verify(o.toString()).status);
    }

    @Test public void aKeyOfTheWrongLengthIsRejected() {
        assertEquals(LicenseVerifier.Status.BAD_SIGNATURE,
                LicenseVerifier.verify(TEST_LICENSE, "AAAA", NBF + 1).status);
    }

    @Test public void leadingAndTrailingWhitespaceIsTolerated() {
        assertTrue(LicenseVerifier.verify("  \n" + TEST_LICENSE + "\n ",
                TEST_PUBLIC_KEY, NBF + 1).isValid());
    }

    /* ── build sanity ──────────────────────────────────────────────────────── */

    @Test public void aPlaceholderKeyIsRecognisedAsUnconfigured() {
        // A build shipped with the placeholder must say so, rather than rejecting
        // every license a real user pastes. Tested against literal values so the
        // assertion still holds once a real key is pasted in.
        assertFalse(LicenseVerifier.isKeyConfigured(null));
        assertFalse(LicenseVerifier.isKeyConfigured(""));
        assertFalse(LicenseVerifier.isKeyConfigured("REPLACE_WITH_PUBLIC_KEY_FROM_SELFTEST"));
        assertFalse(LicenseVerifier.isKeyConfigured("not base64 at all !!!"));
        assertFalse(LicenseVerifier.isKeyConfigured("AAAA"));          // 3 bytes, not 32
        assertTrue(LicenseVerifier.isKeyConfigured(TEST_PUBLIC_KEY));
    }
}
