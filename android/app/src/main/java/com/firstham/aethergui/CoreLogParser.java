package com.firstham.aethergui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Aether's log stream and turns it into something a UI can render.
 *
 * <p>The core is a Rust binary using {@code env_logger} with the default format, so every line
 * looks like:
 *
 * <pre>
 * [2026-09-28T04:52:06.385Z INFO  aether] [+] socks5 server listening on 127.0.0.1:1819
 * </pre>
 *
 * and the meaningful part is the marker the core puts at the head of its own message. There are
 * five, and they mean different things:
 *
 * <ul>
 *   <li>{@code [+]}. Success. The tunnel is up, or a step of it completed.</li>
 *   <li>{@code [-]}. Failure. The step did not happen.</li>
 *   <li>{@code [*]}. Progress. Still working; the line is not an outcome.</li>
 *   <li>{@code [!]}. Warning. The core kept going without something optional.</li>
 *   <li>{@code [=]}. Periodic statistics: bytes up, bytes down, uptime.</li>
 * </ul>
 *
 * <p>Levels are taken from both the {@code env_logger} level and the marker, because they do not
 * agree: the core logs an outcome failure with {@code log::error!} but emits a warning marker for
 * things it recovered from, and reports routine progress at {@code info}. Filtering has to feel
 * right to the person reading, so the marker wins where there is one.
 *
 * <p>Nothing here throws. A log line that does not match every pattern is still shown, at the
 * fallback level, because dropping a line the user cannot see is worse than showing one they
 * cannot classify.
 */
final class CoreLogParser {

    enum Level {
        INFO, PROGRESS, SUCCESS, WARN, ERROR, META;

        /** Whether a line at this level belongs on screen when only this chip is enabled. */
        boolean matches(Level filter) {
            return this == filter;
        }
    }

    /** One parsed line. {@code level} drives the colour; {@code meta} drives the stats row. */
    static final class Line {
        final Level level;
        final String text;
        final String timestamp;

        Line(Level level, String text, String timestamp) {
            this.level = level;
            this.text = text;
            this.timestamp = timestamp;
        }
    }

    /**
     * env_logger with {@code format_timestamp_millis}, whose default output is:
     * {@code [2026-09-28T04:52:06.385Z INFO  aether] message}. The level is padded to five
     * characters, so the pattern allows one or more spaces after it.
     */
    private static final Pattern LINE = Pattern.compile(
            "^\\[(\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z)\\s+(\\w+)\\s+([\\w:]+)\\]\\s*(.*)$");

    /** The marker the core puts at the start of its own message, e.g. {@code [+] ...}. */
    private static final Pattern MARKER = Pattern.compile("^\\[([+\\-*!=])\\]\\s*(.*)$");

    /** {@code [+] socks5 server listening on 127.0.0.1:1819} and the http twin. */
    static final Pattern LISTENING = Pattern.compile(
            "(socks5|http proxy) (server )?listening on ([^\\s]+)");

    /** {@code [=] up 12.3 MB down 45.6 MB uptime 01:01:01}, the core's stats reporter. */
    static final Pattern STATS = Pattern.compile(
            "up (.+?) down (.+?) uptime (\\S+)");

    /** {@code [+] the tunnel is up}, and the various "connected" wordings across transports. */
    private static final Pattern TUNNEL_UP = Pattern.compile(
            "(tunnel is up|connected|connection established|all set|warp=on)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Lines that mark the core as finished rather than in progress. The core has no signal
     * handling of its own, so this is what tells the UI the process is winding down.
     */
    private static final Pattern FINISHED = Pattern.compile(
            "(shutting down|goodbye|stopped|terminated)", Pattern.CASE_INSENSITIVE);

    private CoreLogParser() {
    }

    /**
     * Classifies one raw line.
     *
     * @param raw a line as read from the process, without its newline
     * @return the parsed line; never null, even for input that matches nothing
     */
    static Line parse(String raw) {
        String line = raw == null ? "" : raw.strip();
        if (line.isEmpty()) return new Line(Level.META, "", "");

        String timestamp = "";
        String body = line;
        Matcher outer = LINE.matcher(line);
        if (outer.matches()) {
            timestamp = outer.group(1);
            body = outer.group(4);
        }

        // A line with no marker of its own is the framework's chatter (profile summary, fd
        // limits). It still belongs on screen, but it is not an outcome, so it stays INFO.
        Matcher marker = MARKER.matcher(body);
        if (!marker.matches()) return new Line(Level.INFO, body, timestamp);

        String kind = marker.group(1);
        String message = marker.group(2);

        Level level;
        switch (kind) {
            case "+": level = Level.SUCCESS; break;
            case "-": level = Level.ERROR; break;
            case "*": level = Level.PROGRESS; break;
            case "!": level = Level.WARN; break;
            case "=": level = Level.META; break;
            default:  level = Level.INFO; break;
        }
        return new Line(level, message, timestamp);
    }

    /**
     * Whether the parsed line announces that traffic is being carried. Used to flip the dashboard
     * to Connected without waiting for the service to report back, so the button reacts the
     * instant the core says so rather than on the next IPC round trip.
     */
    static boolean indicatesConnected(Line line) {
        return TUNNEL_UP.matcher(line.text).find();
    }

    /** Whether the line announces the core is done, so the UI should stop waiting for output. */
    static boolean indicatesFinished(Line line) {
        return FINISHED.matcher(line.text).find();
    }
}