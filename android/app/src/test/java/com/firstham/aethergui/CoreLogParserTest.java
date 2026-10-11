package com.firstham.aethergui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The parser is the only thing standing between a Rust log stream and a screen full of text, so
 * these use lines taken verbatim from the core's own source rather than invented ones.
 */
public final class CoreLogParserTest {

    private static CoreLogParser.Level levelOf(String raw) {
        return CoreLogParser.parse(raw).level;
    }

    @Test public void markersMapToTheLevelsTheyMeanInTheCore() {
        // The five markers, as the core emits them in src/*.rs.
        assertEquals(CoreLogParser.Level.SUCCESS, levelOf("[2026-09-28T04:52:06.385Z INFO  aether] [+] socks5 server listening on 127.0.0.1:1819"));
        assertEquals(CoreLogParser.Level.ERROR, levelOf("[2026-09-28T04:52:06.385Z ERROR aether] [-] no working gateway was found"));
        assertEquals(CoreLogParser.Level.PROGRESS, levelOf("[2026-09-28T04:52:06.385Z INFO  aether] [*] hunting for a working MASQUE gateway (deep connect-ip + dat..."));
        assertEquals(CoreLogParser.Level.WARN, levelOf("[2026-09-28T04:52:06.385Z WARN  aether] [!] could not enable warp for the masque device"));
        assertEquals(CoreLogParser.Level.META, levelOf("[2026-09-28T04:52:06.385Z INFO  aether] [=] up 12.3 MB down 45.6 MB uptime 01:01:01"));
    }

    @Test public void theMarkerWinsOverTheFrameworkLevel() {
        // The core logs routine progress at info and a recovered-from warning at warn, so the
        // marker is what the colour should follow.
        assertEquals(CoreLogParser.Level.ERROR, levelOf("[x] ERROR aether] [-] tls options rejected"));
        assertEquals(CoreLogParser.Level.SUCCESS, levelOf("[x] INFO  aether] [+] the tunnel is up"));
    }

    @Test public void theTimestampAndMessageAreSeparatedFromTheWrapper() {
        CoreLogParser.Line parsed = CoreLogParser.parse(
                "[2026-09-28T04:52:06.385Z INFO  aether] [*] establishing outer MASQUE tunnel to 162.159.192.1:2408...");
        assertEquals("2026-09-28T04:52:06.385Z", parsed.timestamp);
        assertEquals("[*] establishing outer MASQUE tunnel to 162.159.192.1:2408...".substring(4), parsed.text);
    }

    @Test public void aLineWithNoMarkerIsShownAsInformationRatherThanDropped() {
        // sysprofile::log_summary() has no marker. Losing it would hide the machine's
        // capabilities when someone is trying to work out why a gateway failed.
        CoreLogParser.Line parsed = CoreLogParser.parse(
                "[2026-09-28T04:52:06.385Z INFO  aether] cpu: 8 cores, 16000 MiB ram");
        assertEquals(CoreLogParser.Level.INFO, parsed.level);
        assertTrue(parsed.text.contains("8 cores"));
    }

    @Test public void inputThatMatchesNothingIsStillRendered() {
        // env_logger writes to stderr, and so does anything the process or the OS adds. A line
        // that is not in the expected shape has to survive rather than vanish.
        for (String raw : new String[]{"", "   ", "a bare line", "[", "[2026-13-99] broken", "[+]"}) {
            CoreLogParser.Line parsed = CoreLogParser.parse(raw);
            assertFalse("line should never be null: " + raw, parsed == null);
        }
        assertEquals("", CoreLogParser.parse("").text);
        assertEquals("a bare line", CoreLogParser.parse("a bare line").text);
    }

    @Test public void nullIsHandledBecauseTheStreamCanYieldIt() {
        assertEquals("", CoreLogParser.parse(null).text);
    }

    @Test public void theListeningLineIsRecognisedSoTheDashboardCanShowThePort() {
        java.util.regex.Matcher socks = CoreLogParser.LISTENING.matcher(
                CoreLogParser.parse("[2026-09-28T04:52:06.385Z INFO  aether] [+] socks5 server listening on 127.0.0.1:1819").text);
        assertTrue(socks.find());
        assertEquals("socks5", socks.group(1));
        assertEquals("127.0.0.1:1819", socks.group(3));

        java.util.regex.Matcher http = CoreLogParser.LISTENING.matcher(
                CoreLogParser.parse("[2026-09-28T04:52:06.385Z INFO  aether] [+] http proxy listening on 127.0.0.1:1820").text);
        assertTrue(http.find());
        assertEquals("http proxy", http.group(1));
    }

    @Test public void theStatsLineIsSplitIntoItsThreeFields() {
        // The core's reporter, from src/stats.rs: "[=] up {} down {} uptime {}".
        java.util.regex.Matcher stats = CoreLogParser.STATS.matcher(CoreLogParser.parse(
                "[2026-09-28T04:52:06.385Z INFO  aether] [=] up 12.3 MB down 45.6 MB uptime 01:01:01").text);
        assertTrue(stats.find());
        assertEquals("12.3 MB", stats.group(1));
        assertEquals("45.6 MB", stats.group(2));
        assertEquals("01:01:01", stats.group(3));
    }

    @Test public void connectedIsDetectedFromTheCoreWordings() {
        // Not one fixed string: the transports each announce themselves differently.
        String[] connected = {
                "[2026-09-28T04:52:06.385Z INFO  aether] [+] the tunnel is up",
                "[2026-09-28T04:52:06.385Z INFO  aether] [+] connected to 162.159.192.1:2408",
                "[2026-09-28T04:52:06.385Z INFO  aether] [+] connection established"};
        for (String raw : connected)
            assertTrue(raw, CoreLogParser.indicatesConnected(CoreLogParser.parse(raw)));
        // Progress lines must not be mistaken for an outcome, or the dashboard would say
        // Connected while the core is still hunting for a gateway.
        assertFalse(CoreLogParser.indicatesConnected(CoreLogParser.parse(
                "[2026-09-28T04:52:06.385Z INFO  aether] [*] establishing outer MASQUE tunnel to 162.159.192.1:2408...")));
    }

    @Test public void shutdownIsDetectedSoTheUiStopsWaitingForOutput() {
        assertTrue(CoreLogParser.indicatesFinished(CoreLogParser.parse(
                "[2026-09-28T04:52:06.385Z INFO  aether] [+] shutting down")));
    }
}