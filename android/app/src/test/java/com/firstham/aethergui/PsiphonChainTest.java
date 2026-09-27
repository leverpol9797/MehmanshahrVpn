package com.firstham.aethergui;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    @Test public void theLadderLeadsWithWireGuard() {
        // The setting is "Psiphon over WARP": on a network carrying WireGuard
        // the first rung connects on its first try with nothing else spent. The
        // rungs behind it are for the carriers that block it, not a preference
        // against it.
        String[] ladder = SiphonChain.innerLadder();
        assertEquals("wireguard", ladder[0]);
        assertEquals("gool", ladder[ladder.length - 1]);
    }

    @Test public void theTunIsEstablishedBeforePsiphonStarts() throws Exception {
        // Psiphon's NetworkMonitor reads tun0 appearing as a network change and
        // restarts the controller, so a TUN created after Psiphon starts is not
        // a race to be lost — it is a 13-second restart loop to be sat through.
        //
        // Compared as positions rather than as a matched block, because an exact
        // string pins the two lines to being adjacent and went stale the moment
        // anything was inserted between them. What matters is the order, not the
        // adjacency.
        String source = read("src", "main", "java", "com", "firstham", "aethergui",
                "AetherVpnService.java");
        int tun = source.indexOf("if (!establishVpn(request, session)) return false;");
        int chain = source.indexOf("if (!startSiphonChain(request, session)) return false;");
        assertTrue("the TUN must be established somewhere in the connect path", tun > 0);
        assertTrue("the chain must be started somewhere in the connect path", chain > 0);
        assertTrue("the TUN must be established before the chain starts", tun < chain);
        // And the flag has to be set before the ladder runs, or the inner legs
        // publish on 1819 where Psiphon's own listener is about to bind.
        int flag = source.indexOf("request.putExtra(EXTRA_CHAIN_LEG, true)");
        int ladder = source.indexOf("SiphonChain.innerLadder()");
        assertTrue("the chain flag must be set before the inner legs start", flag > 0);
        assertTrue(flag < ladder);
    }

    /**
     * A chain is recognised by an intent flag, never by the protocol name.
     *
     * The ladder hands the core an inner leg — wireguard, masque or gool — and
     * that is the only protocol the core is being asked to run. Testing the
     * protocol against "siphon" was therefore false on every rung, the core
     * published on 1819 where Psiphon's own listener binds, and the chain's
     * probe of 1820 timed out on legs that were up and validated. The field log
     * shows all three rungs doing exactly that.
     */
    @Test public void theChainIsFlaggedOnTheRequestNotInferredFromTheProtocol() throws Exception {
        String source = read("src", "main", "java", "com", "firstham", "aethergui",
                "AetherVpnService.java");
        int env = source.indexOf("env.put(\"AETHER_SOCKS\"");
        assertTrue(env > 0);
        int lineEnd = source.indexOf('\n', env);
        String decision = source.substring(env, lineEnd);
        assertTrue("AETHER_SOCKS must go through the one shared accessor",
                decision.contains("coreSocksAddress(request)"));
        assertFalse("and never on the protocol string: the ladder passes inner legs here",
                decision.contains("\"siphon\".equals(protocol)"));
    }

    /**
     * Every place that names the core's SOCKS port goes through one accessor.
     *
     * The chain overrides the port to 1820, and that override lives in an intent
     * flag rather than in any extra the caller sets. Reading the "socks" extra
     * directly — which thirteen call sites did — meant waitForSocks polled 1819
     * for the full sixty seconds while the core was already serving 1820, and
     * every rung was discarded as a failure. The log showed a leg validated at
     * 03:31:16 and the next rung starting at 03:32:00: exactly that wait.
     */
    @Test public void everySocksPortReadGoesThroughOneAccessor() throws Exception {
        String source = read("src", "main", "java", "com", "firstham", "aethergui",
                "AetherVpnService.java");
        // The accessor's own fallback legitimately names the default, so the
        // count is taken over everything outside its body rather than the whole
        // file — otherwise the one correct occurrence fails the test.
        int accessor = source.indexOf("private static String coreSocksAddress(Intent request)");
        assertTrue("the accessor must exist", accessor > 0);
        int end = source.indexOf("\n    }", accessor);
        assertTrue(end > accessor);
        String callers = source.substring(0, accessor) + source.substring(end);
        int direct = countOccurrences(callers, "value(request, \"socks\", \"127.0.0.1:1819\")");
        assertEquals("no call site may name the default port on its own", 0, direct);
        assertTrue("and the accessor must be the one that consults the chain flag",
                source.contains("EXTRA_CHAIN_LEG, false")
                        && source.contains("SiphonChain.chainSocksAddress()"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }

    @Test public void siphonIsNeverHandedToTheCore() {
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
                config.contains("\"LocalSocksProxyPort\":1819"));
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

    /**
     * The config carries every key Psiphon's Commit insists on.
     *
     * PropagationChannelId is the one it named: "psi.Start#255:
     * psiphon.(*Config).Commit#1665: propagation channel ID is missing from the
     * configuration file" — and it rejected the whole config, so nothing about
     * the chain was tested by that run.
     */
    @Test public void theConfigHasTheKeysCommitRefusesToStartWithout() {
        String config = PsiphonTunnelRunner.configJson(null, false);
        assertTrue("propagation channel", config.contains("\"PropagationChannelId\""));
        assertTrue("sponsor", config.contains("\"SponsorId\""));
        assertTrue("server entry signing key",
                config.contains("\"ServerEntrySignaturePublicKey\""));
        assertTrue("remote list signing key",
                config.contains("\"RemoteServerListSignaturePublicKey\""));
        assertTrue("exchange obfuscation key", config.contains("\"ExchangeObfuscationKey\""));
        // Empty strings, not absent: omitted, Psiphon fetches a remote server
        // list through the upstream proxy, which is one more request for a
        // carrier to block.
        assertTrue(config.contains("\"RemoteServerListURL\":\"\""));
        assertTrue(config.contains("\"TunnelProtocol\":\"\""));
    }

    @Test public void theConfigDeclaresIranSoItsTacticsAreUsed() {
        // Psiphon downloads region-specific tactics and applies them in place of
        // the defaults. Without this it runs the default protocol set against a
        // carrier that needs the specialised one.
        assertTrue(PsiphonTunnelRunner.configJson(null, false).contains("\"DeviceRegion\":\"IR\""));
    }

    /**
     * The port key is LocalSocksProxyPort, and it is the only one that is.
     *
     * "LocalSocksPort" does not exist in libgojni.so, and a key the Go config
     * does not know is ignored rather than rejected — so Psiphon bound an
     * arbitrary port (43471) while the bridge was configured for 1819. The
     * session reported connected, the location probe came back IR, and nothing
     * told either fact was wrong. A test asserting the key exists cannot catch a
     * typo in a key it never checks, so the count is over the string form.
     */
    @Test public void theSocksPortKeyIsTheOneThatExists() {
        String config = PsiphonTunnelRunner.configJson(null, false);
        assertTrue("the Go config spells it LocalSocksProxyPort",
                config.contains("\"LocalSocksProxyPort\":" + PsiphonTunnelRunner.SOCKS_PORT));
        assertFalse("LocalSocksPort is silently ignored, not rejected",
                config.contains("\"LocalSocksPort\""));
    }

    /**
     * The bridge is attached after Psiphon, not when the TUN is established.
     *
     * The TUN must exist first — Psiphon's NetworkMonitor restarts the controller
     * when tun0 appears — but the bridge must not attach until there is a port
     * that carries, or it points at the core's 1820 with nothing behind it.
     */
    @Test public void aChainedSessionDefersTheBridgeUntilPsiphonHasAPort() throws Exception {
        String source = read("src", "main", "java", "com", "firstham", "aethergui",
                "AetherVpnService.java");
        assertTrue("establishVpn must take a flag saying whether to attach",
                source.contains("boolean attachBridge"));
        assertTrue("and a chain must pass false",
                source.contains("!request.getBooleanExtra(EXTRA_CHAIN_LEG, false)"));
        int attach = source.indexOf("attachChainBridge(request, session)");
        assertTrue("the chain must attach the bridge from its own path", attach > 0);
    }

    /**
     * The chain flag is set before the TUN is established, not after.
     *
     * establishVpn reads that flag to decide whether to attach the bridge. Set
     * it afterwards and the bridge attaches at once — against 1819, the default,
     * with udp: udp — and then attachChainBridge's start is a no-op, because
     * upstream refuses to run two bridges at once. The first one survives,
     * pointing at whatever bound 1819, which is Psiphon, and a CONNECT-only
     * listener answers its UDP ASSOCIATE with 0x03 forever.
     *
     * The two failures are indistinguishable from the log: a tunnel that
     * publishes connected, resolves nothing, and scrolls warnings.
     */
    @Test public void theChainIsMarkedBeforeTheTunIsBuilt() throws Exception {
        String source = read("src", "main", "java", "com", "firstham", "aethergui",
                "AetherVpnService.java");
        int mark = source.indexOf("putExtra(EXTRA_CHAIN_LEG, true)");
        int establish = source.indexOf("if (!establishVpn(request, session)) return false;",
                mark - 4000);
        assertTrue("both calls must be in the connect path", mark > 0 && establish > 0);
        assertTrue("the flag has to be set first: establishVpn reads it to decide "
                        + "whether to attach the bridge at all",
                mark < establish);
    }

    /**
     * A chain tunnels UDP over TCP, because Psiphon's SOCKS speaks CONNECT only.
     *
     * hev's socks5.udp is a client-side choice: "udp" makes it open a SOCKS5 UDP
     * ASSOCIATE, which is command 0x03. The Aether core grants it; Psiphon does
     * not, and answered hundreds of times per minute:
     *
     *   SOCKS proxy accept error: socks5ReadCommand: SOCKS message field
     *   command was 0x03, not 0x01
     *
     * The traffic behind it was name resolution, so the visible symptom was a
     * tunnel that reported connected and resolved nothing.
     */
    @Test public void aChainNeverAsksForUdpAssociate() throws Exception {
        String source = read("src", "main", "java", "com", "firstham", "aethergui",
                "AetherVpnService.java");
        assertTrue("the mode must be chosen from the chain flag",
                source.contains("EXTRA_CHAIN_LEG, false) ? \"tcp\" : \"udp\""));
        assertTrue("and written into the hev config", source.contains("socksUdpMode(request)"));
        // The core keeps UDP: it is the only upstream that grants 0x03, and the
        // non-chained sessions have always relied on it for DNS.
        assertFalse("the bare default must not be hardcoded over the computed mode",
                source.contains("writer.write(\"  udp: 'udp'\\n\")"));
    }

    /**
     * The traffic gate and the location lookup measure the device's path, not the core's.
     *
     * On a chain the device goes tun0, bridge, Psiphon, core. A probe that dials
     * the core directly measures the WARP leg alone — and answers early, before
     * Psiphon has any server. The field log shows exactly that: traffic_ready
     * green at 4186ms total while Psiphon was still logging "no active tunnels",
     * and a location reading taken from the WARP exit rather than the exit the
     * user actually gets.
     *
     * Two accessors, because these are genuinely two questions: is the WARP leg
     * up, and does a request from the device reach the internet.
     */
    @Test public void deviceFacingProbesGoThroughTheDevicePath() throws Exception {
        String source = read("src", "main", "java", "com", "firstham", "aethergui",
                "AetherVpnService.java");
        // Each device-facing method has to name it, checked inside that method's
        // own body so a caller elsewhere cannot satisfy the assertion for it.
        // Matched on the declaration, not the name: maybeCheckTunnelHealth() is
        // also called from elsewhere, and an indexOf on the bare name can land on
        // a call site whose next "private" is hundreds of lines away.
        for (String declaration : new String[]{
                "private boolean validateTrafficReady(",
                "private void scheduleLocationLookup(",
                "private void maybeCheckTunnelHealth("}) {
            int start = source.indexOf(declaration);
            assertTrue("could not locate " + declaration, start > 0);
            int next = source.indexOf("\n    private ", start + 10);
            int body = source.indexOf("deviceSocksAddress(request)", start);
            assertTrue(declaration + " must probe the device path",
                    body > start && (next < 0 || body < next));
        }
        // And the core's own machinery must not have been redirected: the ladder,
        // the port release and AETHER_SOCKS are questions about the WARP leg.
        assertTrue("AETHER_SOCKS still names the core port",
                source.contains("env.put(\"AETHER_SOCKS\", coreSocksAddress(request))"));
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
