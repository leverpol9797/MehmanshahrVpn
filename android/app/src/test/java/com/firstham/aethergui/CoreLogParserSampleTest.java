package com.firstham.aethergui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Runs the parser over log lines captured from a real Aether run rather than over ones written to
 * suit the assertions. The sample is checked in beside the tests; it is trimmed and anonymised
 * but the byte prefixes are untouched, because the wrapper format is the part that breaks first
 * when the core changes its logger settings.
 */
public final class CoreLogParserSampleTest {

    private static List<String> sample() throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream("src/test/resources/aether-log-sample.txt"), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) if (!line.trim().isEmpty()) lines.add(line);
        }
        return lines;
    }

    @Test public void theCheckedInSampleIsPresentAndSubstantial() throws Exception {
        List<String> lines = sample();
        assertTrue("the sample should hold a full startup, found " + lines.size(), lines.size() >= 25);
    }

    @Test public void everySampleLineParsesWithoutFallingBack() throws Exception {
        int unrecognised = 0;
        for (String raw : sample()) {
            CoreLogParser.Line parsed = CoreLogParser.parse(raw);
            if (parsed.text.isEmpty() && !raw.trim().isEmpty()) unrecognised++;
        }
        assertEquals("lines were dropped by the parser", 0, unrecognised);
    }

    @Test public void aStartupEndsWithBothListenersUpAndAPortThatIsParseable() throws Exception {
        java.util.regex.Matcher socks = null;
        java.util.regex.Matcher http = null;
        java.util.regex.Matcher stats = null;
        boolean sawProgress = false;
        for (String raw : sample()) {
            CoreLogParser.Line line = CoreLogParser.parse(raw);
            if (CoreLogParser.LISTENING.matcher(line.text).find()) {
                if (socks == null && line.text.startsWith("socks5")) socks = CoreLogParser.LISTENING.matcher(line.text);
                if (http == null && line.text.startsWith("http proxy")) http = CoreLogParser.LISTENING.matcher(line.text);
            }
            if (CoreLogParser.STATS.matcher(line.text).find()) stats = CoreLogParser.STATS.matcher(line.text);
            if (line.level == CoreLogParser.Level.PROGRESS) sawProgress = true;
        }
        assertTrue("the sample must show the core working before it connects", sawProgress);
        assertTrue("socks5 listener not found in the sample", socks != null);
        assertEquals("127.0.0.1:1819", socks.group(3));
        assertTrue("the http twin is what non-SOCKS clients use", http != null);
        assertTrue("stats reporter not found", stats != null);
        assertTrue("uptime must be hh:mm:ss or carry a day prefix",
                stats.group(3).matches("([0-9]{2}:){2}[0-9]{2}|[0-9]+d [0-9]{2}:[0-9]{2}:[0-9]{2}"));
    }
}