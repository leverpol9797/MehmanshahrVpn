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

    /**
     * The chain waits for a tunnel, not for a bound port.
     *
     * onListeningSocksProxyPort fires about a second after start; onConnected fires
     * tens of seconds later, after every candidate has been tried and refused.
     * Returning on the port let the traffic gate run against a SOCKS listener with
     * no server behind it, and the gate's response to that was to restart the core
     * — Psiphon's upstream. Every in-flight handshake came back "connection
     * refused", a failure the next roll repeated:
     *
     *   failed to connect to 2YGk+CJD: ... dial tcp: connect: connection refused
     */
    @Test public void aChainedSessionWaitsForARealTunnel() throws Exception {
        String chain = read("src", "main", "java", "com", "firstham", "aethergui",
                "SiphonChain.java");
        assertTrue("the chain must track a tunnel, not only a port",
                chain.contains("tunnelReady"));
        assertTrue("and set it from the connected callback",
                chain.contains("tunnelReady = true;"));

        String service = read("src", "main", "java", "com", "firstham", "aethergui",
                "AetherVpnService.java");
        int start = service.indexOf("private boolean startSiphonChain(");
        // Bounded by siphonChain.start(), not by the method: stop() also appears in
        // the cleanup block at the top, well before the wait loop runs.
        int body = service.indexOf("siphonChain.tunnelReady()", start);
        int began = service.indexOf("siphonChain.start(", start);
        assertTrue("startSiphonChain must return on tunnelReady",
                start > 0 && began > start && body > began);
    }

    /**
     * A chain never re-rolls the core for a traffic failure.
     *
     * Re-rolling is stopAetherOnly, which closes the port Psiphon is dialling
     * through. On a chain that converts a Psiphon problem into a self-inflicted
     * one, and each roll repeats it. A bad WARP edge is the ladder's job, and the
     * ladder runs before Psiphon starts.
     */
    @Test public void aChainNeverRerollsTheCore() throws Exception {
        String source = read("src", "main", "java", "com", "firstham", "aethergui",
                "AetherVpnService.java");
        int gate = source.indexOf("private boolean validateTrafficReady(");
        int roll = source.indexOf("stopAetherOnly()", gate);
        int guard = source.indexOf("EXTRA_CHAIN_LEG, false)", gate);
        assertTrue("the guard has to come before the restart it prevents",
                gate > 0 && guard > gate && guard < roll);
    }

    /**
     * A chain routes DNS inside the bridge, because UDP cannot leave it.
     *
     * hev-socks5-tunnel has no CONNECT-only mode. UDP is enabled unconditionally
     * in lwIP, and the one key that picks the SOCKS5 command for it has two values
     * and no third:
     *
     *   udp: 'udp' -> 0x03 UDP ASSOCIATE, which Psiphon refuses
     *   udp: 'tcp' -> 0x05 BIND, which Psiphon also refuses
     *   omitted    -> 0x05, the C default rather than the README's "udp"
     *
     * So the commands cannot be configured away; the UDP has to be taken off the
     * wire. mapdns does that — hev answers DNS locally and only resolves through
     * the tunnel on a cache miss — and the TUN resolver has to point at the same
     * 198.18.0.2, or the two disagree and a working tunnel looks dead.
     */
    @Test public void aChainAnswersDnsInsideTheBridge() throws Exception {
        String source = read("src", "main", "java", "com", "firstham", "aethergui",
                "AetherVpnService.java");
        assertTrue("mapdns must be written for a chain",
                source.contains("writer.write(\"mapdns:\\n\")"));
        assertTrue("with a cache, or nothing ever resolves",
                source.contains("cache-size:"));
        assertTrue("and the TUN must name the same router address",
                source.contains("builder.addDnsServer(HEV_ROUTER_DNS)"));
        // The direct sessions keep the public resolvers: the core grants UDP
        // ASSOCIATE, so 1.1.1.1 works there and is the right answer.
        assertTrue(source.contains("builder.addDnsServer(\"1.1.1.1\")"));
    }

    /**
     * The licence gate is one switch, read where the decision is made.
     *
     * Testing a tunnel means uninstalling the previous build, and an uninstall
     * takes the stored licence with it, so every iteration otherwise means the
     * whole purchase path again before the app will connect.
     */
    @Test public void theLicenceGateHasExactlyOneSwitch() throws Exception {
        String source = read("src", "main", "java", "com", "firstham", "aethergui",
                "AuthGate.java");
        int gate = source.indexOf("public static boolean isValid(Context context)");
        int off = source.indexOf("if (DISABLED) return true;", gate);
        int verify = source.indexOf("LicenseVerifier.verify(", gate);
        assertTrue("the bypass has to be inside isValid, before any verification",
                gate > 0 && off > gate && verify > off);
        assertTrue("and it has to come from the build flag",
                source.contains("BuildConfig.LICENCE_GATE_DISABLED"));
    }

    /**
     * The TUN subnet has to hold the bridge's own address.
     *
     * The interface was 198.18.0.1/30, so the subnet was 198.18.0.0/30 — four
     * addresses, and lwIP's address fell outside it. Every packet was dropped,
     * silently, which is what MSN-GUARD's Tun2SocksManager warns about in its
     * class doc:
     *
     *   "Passing the interface address to runTun2Socks() instead of the router
     *    address makes lwIP silently drop every packet."
     *
     * Their plan is ("10.0.0.1", "10.0.0.0", 8, "10.0.0.2"): /8, so the interface,
     * the router and mapdns's 100.64.0.0/10 answers all have somewhere to live.
     */
    @Test public void theTunSubnetCanHoldTheBridge() throws Exception {
        String source = read("src", "main", "java", "com", "firstham", "aethergui",
                "AetherVpnService.java");
        assertTrue("a /30 cannot hold a point-to-point link plus the resolver",
                source.contains("HEV_TUN_PREFIX = 8"));
        assertTrue("and the router must be inside that subnet", source.contains(
                "HEV_TUN_ADDRESS = \"10.0.0.1\"") && source.contains(
                "HEV_ROUTER_DNS = \"10.0.0.2\"") && source.contains(
                "HEV_TUN_SUBNET = \"10.0.0.0\""));
        assertTrue("bypass-local excludes 10.0.0.0/8, so it has to be routed back in",
                source.contains("builder.addRoute(HEV_TUN_SUBNET, HEV_TUN_PREFIX)"));
    }

    /**
     * The traffic gate goes through the TUN on a chain.
     *
     * This app is excluded from the VPN, so a direct SOCKS dial proves the tunnel
     * and nothing about the bridge. The too-small subnet was invisible to it —
     * that is how a dead bridge published connected with a real German exit — so
     * on a chain the proof has to be a flow only the bridge can carry, and that
     * means a socket this app deliberately does not protect.
     */
    @Test public void aChainedGateTestsTheBridgeItself() throws Exception {
        String source = read("src", "main", "java", "com", "firstham", "aethergui",
                "AetherVpnService.java");
        int gate = source.indexOf("private boolean validateTrafficReady(");
        int body = source.indexOf("tunTrafficProof(request, host", gate);
        int socks = source.indexOf("socksHttpGet(socks, host", gate);
        assertTrue("a chain must take the TUN path", body > gate);
        assertTrue("and a direct session must keep the SOCKS one", socks > gate);
        assertTrue("the TUN proof must not protect its socket",
                source.contains("tunTrafficProof") && !source.contains(
                        "tunTrafficProof(Context context, String host, String path, int timeoutMs)\n"
                                + "            throws Exception {\n        try {\n            VpnService"));
    }

    /**
     * A released build enforces the licence.
     *
     * The bypass was added so that reinstalling between tests would not cost the
     * whole purchase path each time. It is a build flag, which means the safety
     * of the whole licensing system rests on a default in a Gradle file, so the
     * default is asserted here rather than trusted.
     */
    @Test public void aReleaseDoesNotShipWithTheGateOpen() throws Exception {
        String gradle = read("..", "app", "build.gradle");
        int ext = gradle.indexOf("ext.licenceGateDisabled");
        assertTrue("the default has to be explicit", ext > 0);
        int def = gradle.indexOf(": false", ext);
        assertTrue("a release must require a signed licence by default", def > ext);
        assertTrue("and the flag stays available for testing builds",
                gradle.contains("project.property('licenceGateDisabled')"));
    }

    /**
     * The protocol list is one list, offered in one order.
     *
     * psiphon moved above smart connect: a user reaching for smart connect gets
     * a single protocol that has to happen to work, while psiphon is a chain
     * that reaches where the single leg does not. Three files have to agree or
     * the UI labels the wrong transports, so the order is checked in all three.
     */
    @Test public void psiphonIsOfferedAboveSmartConnect() throws Exception {
        String expected = "{\"masque\", \"wg\", \"gool\", \"siphon\", \"smart\"}";
        String controller = read("src", "main", "java", "com", "firstham", "aethergui",
                "VpnConnectionController.java");
        assertTrue("the controller drives the default choice", controller.contains(expected));
        for (String locale : new String[] { "values", "values-en" }) {
            String arrays = read("src", "main", "res", locale, "arrays.xml");
            assertTrue(locale + " must document the same order",
                    arrays.contains("PROTOCOLS = { masque, wg, gool, siphon, smart }"));
            // The two locales name these differently, so the order is read from the
            // array items rather than matched as English text.
            int labels = arrays.indexOf("protocol_labels");
            int end = arrays.indexOf("</string-array>", labels);
            String items = arrays.substring(labels, end);
            java.util.List<String> names = new java.util.ArrayList<>();
            java.util.regex.Matcher item = java.util.regex.Pattern
                    .compile("<item>([^<]*)</item>").matcher(items);
            while (item.find()) names.add(item.group(1));
            assertEquals(locale + " must label every protocol", 5, names.size());
            // Compared by array position, not by spelling: the two locales
            // name these differently, and a test that hardcodes either string
            // only tests the string.
            int psiphon = -1;
            int smart = -1;
            String[] order = VpnConnectionController.PROTOCOLS;
            for (int i = 0; i < order.length; i++) {
                if ("siphon".equals(order[i])) psiphon = i;
                if ("smart".equals(order[i])) smart = i;
            }
            assertTrue(locale + " must label every protocol", names.size() == order.length);
            assertTrue(locale + " lists psiphon above smart connect", psiphon < smart);
            assertTrue(locale + " must name the psiphon entry", !names.get(psiphon).isEmpty());
            assertTrue(locale + " must name the smart entry", !names.get(smart).isEmpty());
            assertFalse(locale + " must not repeat a label",
                    names.get(psiphon).equals(names.get(smart)));
            assertEquals("and they must line up with the controller's order",
                    VpnConnectionController.PROTOCOLS[psiphon], "siphon");
            assertEquals(VpnConnectionController.PROTOCOLS[smart], "smart");
        }
    }

    /**
     * CI ships a signed release, not a debug build.
     *
     * Every APK from 2.4.2 to 2.5.0 was signed with a throwaway debug key, which
     * Gradle regenerates per machine, so no two of them shared a signature.
     * Android's answer to that is INSTALL_FAILED_UPDATE_INCOMPATIBLE and a
     * prompt to uninstall — which wipes the stored licence, so the user has to
     * buy again. Nothing in the build failed; it was published repeatedly.
     *
     * Three things have to hold, and each has broken on its own here: the task
     * must be a release, the signing secrets must be checked before the build
     * rather than after, and the artifact's own certificate must be inspected
     * before it is published.
     */
    @Test public void ciPublishesASignedRelease() throws Exception {
        String workflow = read("..", "..", ".github", "workflows", "build-android.yml");
        assertTrue("the build must be a release build",
                workflow.contains("./gradlew assembleRelease"));
        assertFalse("a debug build is signed with a key nobody keeps — that was the bug",
                workflow.contains("./gradlew assembleDebug"));
        assertTrue("and the output must be read from the release directory",
                workflow.contains("android/app/build/outputs/apk/release"));
        assertTrue("missing secrets must fail the build, not produce an unsigned APK",
                workflow.contains("ANDROID_KEYSTORE_BASE64")
                        && workflow.contains("exit 1"));
        assertTrue("and the published artifact's certificate has to be checked",
                workflow.contains("apksigner") && workflow.contains("CN=Android Debug"));
    }

    /**
     * A chain that finishes connecting after the user disconnected changes nothing.
     *
     * The button would stay on "connecting" after a disconnect, and pressing it
     * again would disconnect rather than connect. Only siphon did it, because
     * only siphon waits: startSiphonChain sleeps in a loop for as long as it
     * takes Psiphon to find a server, tens of seconds, while the other protocols
     * are finished in a couple.
     *
     * The Go library delivers onPsiphonConnected on its own goroutine, so it
     * arrives after stop() has run and re-sets tunnelReady — and the wait loop,
     * which only re-checks every 250ms, carries on with a chain whose runner is
     * null. The service guards its own state writes with isCurrentSession, but
     * SiphonChain owns tunnelReady and had no way to know the session was over.
     */
    @Test public void aStoppedChainIgnoresWhatArrivesAfterwards() throws Exception {
        String chain = read("src", "main", "java", "com", "firstham", "aethergui",
                "SiphonChain.java");
        assertTrue("stop has to be visible across threads",
                chain.contains("private volatile boolean stopped"));
        assertTrue("and start has to clear it, or the next connect inherits the last one's",
                chain.contains("stopped = false;"));
        // Every callback that writes state has to be gated. tunnelReady and
        // readyPort are what the wait loop reads, so an ungated one resurrects a
        // chain the user already cancelled.
        // onPsiphonConnecting was missed first time round, and it is the one that
        // writes "connecting" — the state the button got stuck on. So the list
        // here is every callback, not the ones that seemed worth guarding.
        for (String callback : new String[] {
                "onPsiphonReady(int port)", "onPsiphonConnecting()",
                "onPsiphonConnected()", "onPsiphonExiting(String reason)" }) {
            int at = chain.indexOf(callback);
            int body = chain.indexOf("{", at);
            int guard = chain.indexOf("if (stopped) return;", body);
            assertTrue(callback + " must ignore a callback that arrives after stop()",
                    at > 0 && guard > body);
        }
        // And the loop has to notice, not just the flag.
        String service = read("src", "main", "java", "com", "firstham", "aethergui",
                "AetherVpnService.java");
        int loop = service.indexOf("siphonChain.start(entries)");
        assertTrue("the wait loop must bail when the chain was stopped",
                service.indexOf("siphonChain.stopped()", loop) > loop);

        // The service side too, because the chain's flag is only one of two ways
        // a late callback gets in: a caller that forgets the guard entirely still
        // reaches updateState. Each service callback writes state and has to ask.
        int listener = service.indexOf("new SiphonChain(this, new PsiphonTunnelRunner.Listener()");
        int end = service.indexOf("siphonChain.setCountry(", listener);
        for (String callback : new String[] {
                "onPsiphonReady(int port)", "onPsiphonConnecting()",
                "onPsiphonConnected()", "onPsiphonExiting(String reason)" }) {
            int at = service.indexOf(callback, listener);
            int body = service.indexOf("{", at);
            int guard = service.indexOf("if (!isCurrentSession(request, session)) return;", body);
            assertTrue("service " + callback + " must check the session first",
                    listener < at && at < end && guard > body);
        }

        // And updateState refuses a connecting state once the session is
        // stopping, so a missed guard cannot reach the UI even so.
        int update = service.indexOf("private void updateState(String state, String message)");
        int assign = service.indexOf("currentState = state;", update);
        assertTrue("updateState must not publish a stale connecting state",
                service.indexOf("if (stopping &&", update) > update
                        && service.indexOf("return;", update) < assign);
    }

    /**
     * A disconnect has to be announced before the service dies.
     *
     * Disconnecting siphon needed two presses: the first tore the tunnel down,
     * the second is what actually moved the button. The cause is here rather than
     * in the tunnel logic — the terminal state was written with apply(), which is
     * asynchronous, and written without a broadcast, and then stopSelf() ended the
     * instance. So the activity kept the "connecting" it already had, and the
     * second press started a fresh service that handled ACTION_STOP and announced
     * the state it had been holding all along.
     */
    @Test public void aDisconnectIsAnnouncedBeforeTheServiceDies() throws Exception {
        String service = read("src", "main", "java", "com", "firstham", "aethergui",
                "AetherVpnService.java");

        int update = service.indexOf("private void updateState(String state, String message)");
        int write = service.indexOf(".putString(\"state\", currentState)", update);
        int commit = service.indexOf(".commit();", write);
        int send = service.indexOf("sendStatus(", commit);
        assertTrue("the state write must be synchronous: the instance dies right after",
                update > 0 && commit > write && commit < write + 300);
        assertTrue("and it has to be announced", send > commit);

        // onDestroy is the last writer in a disconnect, so the same applies there.
        int destroy = service.indexOf("public void onDestroy()");
        int tail = service.indexOf(".putString(\"state\", currentState)", destroy);
        assertTrue("onDestroy must commit too",
                destroy > 0 && service.indexOf(".commit();", tail) > tail);
        assertTrue("and it must broadcast — this is the press that was needed twice",
                service.indexOf("sendStatus(", service.indexOf(".commit();", tail)) > tail);
    }

    @Test public void cdnFrontingIsOptIn() {
        assertTrue(!PsiphonTunnelRunner.configJson(null, false).contains("FrontedMeekCDNScan"));
        assertTrue(PsiphonTunnelRunner.configJson(null, true).contains("FrontedMeekCDNScan"));
    }

    @Test public void addingAProtocolDoesNotRepointSmartConnect() {
        // mode=smart was stored by v2.1.1 and still has to mean Smart Connect
        // wherever the list has since put it. Resolving by name, not by index,
        // is what makes that true across reorderings.
        int smart = VpnConnectionController.normalizedProtocolIndex("smart", 0);
        assertEquals("smart", protocolAt(smart));
        // What this guards is resolution by name. It used to be "smart must not
        // be the last entry", which was a proxy for "the tail is not smart" and
        // stopped meaning anything once the list was reordered. Name resolution
        // is the property; the position is not.
        assertTrue("mode=smart must resolve to smart whatever the order is",
                "smart".equals(protocolAt(
                        VpnConnectionController.normalizedProtocolIndex("smart",
                                protocolCount() - 1))));
        // It resolves to the smart entry itself, not to whatever now precedes it.
        assertEquals("smart", protocolAt(VpnConnectionController.normalizedProtocolIndex("smart", 3)));
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

    /**
     * Mirrors VpnConnectionController.PROTOCOLS.
     *
     * Duplicated rather than read from the class, which is how this copy went
     * stale when psiphon moved above smart connect — the tests kept passing
     * against the old order while the app offered the new one. So the order is
     * asserted against the real array in psiphonIsOfferedAboveSmartConnect, and
     * anything that has to track the list reads that instead of this.
     */
    private static String[] protocols() {
        return VpnConnectionController.PROTOCOLS;
    }
}
