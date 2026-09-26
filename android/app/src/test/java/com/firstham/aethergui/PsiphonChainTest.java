package com.firstham.aethergui;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Psiphon chain's contract with the core, and the one thing that would
 * quietly break it.
 *
 * The chain is WARP underneath and Psiphon on top, which means the two have to
 * hold different ports: the core publishes SOCKS on 1820 for Psiphon to dial
 * through, while Psiphon's own SOCKS listener stays on 1819 because that is
 * the number the hev bridge and the LAN proxy are already built against. Both
 * are hardcoded, so both are pinned here — a setting for either could only be
 * set to a value that breaks things.
 *
 * The index test is here for a specific regression: adding a protocol used to
 * repoint every legacy Smart Connect user at it, because "smart" was found as
 * the last entry rather than by name.
 */
public final class PsiphonChainTest {

    @Test public void theTwoLegsHoldDifferentPorts() {
        assertEquals("Psiphon keeps 1819 — the hev bridge and LAN proxy are built on it",
                1819, PsiphonTunnelRunner.SOCKS_PORT);
        assertEquals("the core moves up so it is not fighting Psiphon for 1819",
                1820, PsiphonTunnelRunner.CHAIN_SOCKS_PORT);
    }

    @Test public void theLadderStartsWithMasqueNotWireGuard() {
        // MSN-GUARD's measured order, and putting wireguard first cost the whole
        // budget on a protocol Iranian carriers usually block — the field log
        // showed a MASQUE identity being provisioned only after the fallback had
        // already been given up on. masque has its own fallback and endpoint
        // cache, so it is the rung most likely to be carrying on a repeat.
        String[] ladder = SiphonChain.innerLadder();
        assertEquals("masque", ladder[0]);
        assertEquals("gool", ladder[ladder.length - 1]);
    }

    @Test public void theTunIsEstablishedBeforePsiphonStarts() {
        // Psiphon's NetworkMonitor reads tun0 appearing as a network change and
        // restarts the controller, so a TUN created after Psiphon starts is not
        // a race to be lost — it is a 13-second restart loop to be sat through.
        // The order in the connect path is the only thing holding this, so it is
        // pinned here rather than trusted to a comment.
        String source = read("src", "main", "java", "com", "firstham", "aethergui",
                "AetherVpnService.java");
        int tun = source.indexOf("if (!establishVpn(request, session)) return false;\n"
                + "              if (!startSiphonChain(request, session)) return false;");
        assertTrue("the TUN must be established before the chain starts", tun > 0);
    }

    @Test public void siphonIsNeverHandedToTheCore() {
        // The core's Protocol::parse ends in `_ => Protocol::Masque`, so an
        // unknown string is accepted silently and becomes MASQUE. Passing
        // "siphon" produced a MASQUE tunnel on 1820 that the outer half was
        // never going to match, and the field log showed exactly that: a masque
        // identity provisioned, a masque gateway hunt, and no SOCKS listener.
        assertEquals("wireguard", AetherVpnService.innerLegProtocol("siphon"));
    }

    @Test public void everyInnerLegIsOneTheCoreActuallyHas() {
        // The ladder is walked until a leg publishes a listener, so a rung the
        // core does not know is not a fallback — it is a silent MASQUE retry.
        String[] ladder = SiphonChain.innerLadder();
        assertTrue("the ladder must not be empty", ladder.length > 0);
        for (String rung : ladder) {
            assertTrue("the core does not know \"" + rung + "\"",
                    "wireguard".equals(rung) || "masque".equals(rung) || "gool".equals(rung));
        }
    }

    @Test public void nonChainedProtocolsArePassedThroughUntouched() {
        // Second-guessing the core's defaults here would give it two places to
        // decide what an unknown protocol means instead of one.
        assertEquals("gool", AetherVpnService.innerLegProtocol("gool"));
        assertEquals("wireguard", AetherVpnService.innerLegProtocol("wireguard"));
        assertEquals("masque", AetherVpnService.innerLegProtocol("masque"));
        assertEquals("something-new", AetherVpnService.innerLegProtocol("something-new"));
    }

    @Test public void theChainPortIsTheInnerLegPort() {
        assertEquals(PsiphonTunnelRunner.CHAIN_SOCKS_PORT, SiphonChain.CHAIN_PORT);
    }

    @Test public void theChainIsActuallyAChain() {
        String config = PsiphonTunnelRunner.configJson("DE", false);
        assertTrue("without UpstreamProxyURL Psiphon dials the internet directly "
                        + "and the WARP leg does nothing at all",
                config.contains("\"UpstreamProxyURL\":\"socks5://127.0.0.1:1820\""));
        assertTrue("the SOCKS port must be fixed, not chosen by Psiphon",
                config.contains("\"LocalSocksPort\":1819"));
    }

    @Test public void theChosenCountryIsAHardFilter() {
        assertTrue(PsiphonTunnelRunner.configJson("NL", false).contains("\"EgressRegion\":\"NL\""));
    }

    @Test public void autoIsNoFilterRatherThanAnEmptyOne() {
        assertTrue("an empty EgressRegion is a filter that matches nothing, not a cleared one",
                !PsiphonTunnelRunner.configJson(null, false).contains("EgressRegion"));
    }

    @Test public void theRetryDropsTheCountryRatherThanRepeatingIt() {
        // Re-sending the same country fails in the same place. The retry exists
        // to let Psiphon come out wherever it can actually connect.
        String retry = PsiphonTunnelRunner.retryConfigJson(false);
        assertTrue("the retry must not re-pin the country", !retry.contains("EgressRegion"));
        assertTrue("but it must still be chained", retry.contains("UpstreamProxyURL"));
    }

    @Test public void cdnFrontingIsOptIn() {
        assertTrue(!PsiphonTunnelRunner.configJson(null, false).contains("FrontedMeekCDNScan"));
        assertTrue(PsiphonTunnelRunner.configJson(null, true).contains("FrontedMeekCDNScan"));
    }

    @Test public void addingAProtocolDoesNotRepointSmartConnect() {
        // mode=smart was stored by v2.1.1 and still has to mean Smart Connect
        // now that a protocol sits after it in the list.
        int smart = VpnConnectionController.normalizedProtocolIndex("smart", 0);
        assertEquals("smart", protocolAt(smart));
        assertTrue("and it must not be the last entry any more", smart != protocolCount() - 1);
    }

    @Test public void anExplicitChoiceIsLeftAlone() {
        assertEquals(4, VpnConnectionController.normalizedProtocolIndex("vpn", 4));
        assertEquals(0, VpnConnectionController.normalizedProtocolIndex("vpn", 0));
    }

    @Test public void everyProtocolLabelStillLinesUpWithItsName() throws Exception {
        // The selector is index-driven, so a label list and a protocol list of
        // different lengths drift by one and a different transport starts.
        String arrays = read("src", "main", "res", "values", "arrays.xml");
        assertTrue("PROTOCOLS must list siphon", arrays.contains("siphon"));
        assertTrue("and a label for it", arrays.contains("سایفون"));
    }

    @Test public void aCountryIsOnlyAcceptedWhenItIsOne() {
        assertTrue(PsiphonRegions.isCode("DE"));
        assertTrue(!PsiphonRegions.isCode("de"));
        assertTrue(!PsiphonRegions.isCode("DEU"));
        assertTrue(!PsiphonRegions.isCode(""));
        assertTrue(!PsiphonRegions.isCode(null));
    }

    @Test public void unknownCountriesStillGetAnEntry() {
        // A country Psiphon adds later must be selectable without an app update,
        // so an unmapped code shows as itself rather than being dropped.
        assertEquals("ZZ", PsiphonRegions.name("ZZ"));
        assertTrue(PsiphonRegions.name("DE").length() > 0);
    }

    @Test public void bundledCountsAreOnlyClaimsWeCanBack() {
        // A country the embedded list does not carry has no count, and the
        // picker says so rather than claiming zero servers.
        assertTrue(PsiphonRegions.bundledCount("DE") > 0);
        assertEquals(0, PsiphonRegions.bundledCount("ZZ"));
    }

    private static String read(String first, String... rest) throws Exception {
        Path path = Paths.get(first, rest);
        assertTrue("not found: " + path.toAbsolutePath(), Files.exists(path));
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String protocolAt(int index) {
        String[] all = protocols();
        return index >= 0 && index < all.length ? all[index] : "?";
    }

    private static int protocolCount() {
        return protocols().length;
    }

    /** Mirrors VpnConnectionController.PROTOCOLS. */
    private static String[] protocols() {
        return new String[] { "masque", "wg", "gool", "smart", "siphon" };
    }
}
