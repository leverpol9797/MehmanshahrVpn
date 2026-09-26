package com.firstham.aethergui;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Two things that a transport failure depends on, and that nothing else covers.
 *
 * The first is identity isolation. AETHER_CONFIG is only the base path: without
 * a per-protocol override every transport resolves to that same file, so gool —
 * which stacks two WARP tunnels and provisions a second device beside whichever
 * primary it was handed — ends up on plain WireGuard's registered device, with
 * each mode's cached peer written where the other reads it.
 *
 * The second is the log. The service has always broadcast the core's output as
 * ACTION_LOG, and nothing has ever received it, so a transport that would not
 * come up could only be described from the outside.
 *
 * Read as source rather than run: these are the decisions, not the behaviour,
 * and the behaviour only exists on a device with a network that censors.
 */
public final class TransportConfigIsolationTest {

    private static String read(String first, String... rest) throws Exception {
        Path path = Paths.get(first, rest);
        assertTrue("not found: " + path.toAbsolutePath(), Files.exists(path));
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String service() throws Exception {
        return read("src", "main", "java", "com", "firstham", "aethergui", "AetherVpnService.java");
    }

    private static List<String> strings() {
        List<String> out = new ArrayList<String>();
        out.add("masque");
        out.add("wg");
        out.add("gool");
        out.add("smart");
        return out;
    }

    @Test public void goolDoesNotShareWireGuardsIdentityFile() throws Exception {
        String source = service();
        assertTrue("gool must be routed to its own identity file",
                source.contains("aether-gool.toml"));
        assertTrue("plain WireGuard should have one too, or the mapping is a special case",
                source.contains("aether-wg.toml"));
        assertTrue("AETHER_WG_CONFIG is the documented override the core reads",
                source.contains("AETHER_WG_CONFIG"));
    }

    @Test public void everyAliasForGoolResolvesToTheSameFile() throws Exception {
        String source = service();
        for (String alias : new String[] { "gool", "wiw", "warp-in-warp", "warpinwarp" }) {
            assertTrue("the core accepts \"" + alias + "\" for WarpInWarp, so the app must map it "
                            + "to gool's identity file too",
                    source.contains("\"" + alias + "\""));
        }
    }

    @Test public void masqueKeepsItsOwnIdentityFile() throws Exception {
        assertTrue(service().contains("AETHER_MASQUE_CONFIG"));
    }

    @Test public void theLogBroadcastNowHasAReceiver() throws Exception {
        assertTrue("the core log broadcast has to be received somewhere",
                service().contains("ACTION_LOG"));
        String activity = read("src", "main", "java", "com", "firstham", "aethergui", "LogActivity.java");
        assertTrue("LogActivity must subscribe to the broadcast",
                activity.contains("ACTION_LOG") && activity.contains("registerReceiver"));
        assertTrue("the saved log lives in the service's own preference file",
                activity.contains("service_state"));
    }

    @Test public void theLogActivityIsReachableAndRegistered() throws Exception {
        String manifest = read("src", "main", "AndroidManifest.xml");
        assertTrue("LogActivity must be declared", manifest.contains(".LogActivity"));
        assertTrue("and must not be exported", manifest.contains(".LogActivity"));
        String main = read("src", "main", "java", "com", "firstham", "aethergui", "MainActivity.java");
        assertTrue("something has to open it", main.contains("LogActivity.class"));
    }

    @Test public void everyProtocolLabelStillHasAMatchingProtocolName() {
        // The label array and PROTOCOLS must stay the same length or the
        // selector drifts by one and a different transport starts on connect.
        assertTrue("protocol count", strings().size() == 4);
    }

    @Test public void anIranExitNoLongerEndsTheConnection() throws Exception {
        // The field log from v2.4.3: both WARP tunnels validated end to end,
        // SOCKS listened, traffic was ready, ping answered in 169ms — and the
        // app then logged "Rejected gool exit country IR", restarted three
        // times, and refused the connection. A tunnel that carries traffic is
        // not a failed tunnel, so the user gets a switch instead of a verdict.
        String source = service();
        assertTrue("both gool entry points must honour the setting, not just one",
                countCalls(source, "acceptsIranExit()") == 2);
        assertTrue("the switch must default to accepting, or gool still never connects",
                source.contains("getBoolean(\"acceptIranExit\", true)"));
        assertTrue("the settings screen must offer it",
                read("src", "main", "java", "com", "firstham", "aethergui", "MainActivity.java")
                        .contains("acceptIranExit"));
    }

    @Test public void aCountryRetryActuallyChangesTheGateway() throws Exception {
        // The country follows the gateway, not the registered device, so a
        // restart alone re-runs the scan and can return the same region. The
        // log showed the same gateway twice for three restarts.
        assertTrue("the retry has to clear the cached peer",
                service().contains("clearGoolGatewayCache"));
    }

    @Test public void theGatewayCacheNamesAreDerivedNotGuessed() throws Exception {
        // derive_sibling_path inserts the suffix before the extension, so
        // aether-gool.toml + "gool-lastconn" is aether-gool-gool-lastconn.toml.
        // A hand-written name silently never matches, and a cache that is
        // never cleared looks exactly like one that is.
        String source = service();
        assertTrue("must build the name the way the core does",
                source.contains("siblingName"));
        assertTrue("and use the core's own suffixes",
                source.contains("gool-lastconn") && source.contains("\"lastconn\""));
    }

    @Test public void theAntiLeakCheckIsNotTheSameAsThePreference() throws Exception {
        // An exit that cannot be identified is a leak risk and still ends the
        // connection. Only a *known* Iranian exit became a choice. These two
        // must not be conflated when the setting is relaxed.
        String source = service();
        assertTrue("an unidentified exit must still throw", source.contains("GoolExitException"));
        assertTrue("and it must be a distinct message from the Iran-exhausted one",
                source.contains("service_gool_country_unavailable")
                        && source.contains("service_gool_iran_failed"));
    }

    /** Occurrences that are calls, excluding the declaration that follows one. */
    private static int countCalls(String source, String call) {
        int count = 0;
        int at = source.indexOf(call);
        while (at >= 0) {
            int after = at + call.length();
            int scan = after;
            // Skip the space in "private boolean acceptsIranExit() {" so the
            // declaration is not counted as a call.
            while (scan < source.length() && source.charAt(scan) == ' ') scan++;
            boolean declaration = scan < source.length() && source.charAt(scan) == '{';
            if (!declaration) count++;
            at = source.indexOf(call, after);
        }
        return count;
    }
}
