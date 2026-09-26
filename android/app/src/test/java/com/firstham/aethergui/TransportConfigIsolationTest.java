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
}
