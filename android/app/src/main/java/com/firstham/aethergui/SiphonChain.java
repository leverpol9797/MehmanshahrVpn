package com.firstham.aethergui;

import java.util.List;

/**
 * Runs the WARP-then-Psiphon chain for {@link AetherVpnService}.
 *
 * The ordering is the whole design and everything else follows from it:
 *
 * <ol>
 *   <li>the Aether core comes up as a plain WARP transport and publishes SOCKS
 *       on {@link PsiphonTunnelRunner#CHAIN_SOCKS_PORT} — it never takes the
 *       TUN, because the hev bridge owns that;</li>
 *   <li>Psiphon starts with that SOCKS as its upstream proxy;</li>
 *   <li>only once Psiphon reports its own SOCKS listener does the hev bridge
 *       attach, so device traffic has somewhere to go that is not a dead end.</li>
 * </ol>
 *
 * Doing step 3 before step 2 would send every packet into a tunnel that is not
 * listening yet, and doing step 2 before step 1 would make Psiphon dial
 * straight past the WARP leg into a network where its servers are null-routed.
 */
final class SiphonChain {

    /**
     * The WARP transports tried in order until one carries Psiphon.
     *
     * WireGuard leads because that is the leg this setting names: the chain is
     * "Psiphon over WARP", and on a network that carries WireGuard the first
     * rung connects on its first try with nothing else spent.
     *
     * The rest are there because which transport a carrier allows is a property
     * of the carrier, not of the user. A blocked WireGuard is normal on Iranian
     * SIMs, and a setting that then simply failed would be the wrong answer for
     * them, so masque comes next — it has transports of its own and an endpoint
     * cache, so a repeat connect is fast. gool is last: it is WARP-on-WARP, so
     * it stacks two tunnels under Psiphon for three in total, and is only worth
     * reaching when the single-layer transports are shut.
     *
     * The core parses only "masque", "wireguard" and "gool" and sends every other
     * string to masque without a word, so a chained session has to name a real
     * inner leg. Every rung here is one the core knows.
     */
    private static final String[] INNER_LADDER = { "wireguard", "masque", "gool" };

    /** How long each inner leg gets to publish its SOCKS listener. */
    private static final long INNER_LEG_TIMEOUT_MS = 45_000L;

    /** The full ladder, for the retry that walks it. */
    static String[] innerLadder() {
        return INNER_LADDER.clone();
    }

    /** How long to give a chosen country before falling back to every country. */
    private static final long COUNTRY_ATTEMPT_MS = 45_000L;

    private final AetherVpnService service;
    private final PsiphonTunnelRunner.Listener listener;
    private PsiphonTunnelRunner runner;

    /**
     * Set by stop(), cleared by start(). The Go library delivers its callbacks on
     * its own goroutine, so a tunnel that finishes connecting after the user has
     * disconnected still arrives — and it arrives second.
     *
     * That is why disconnecting a chain used to leave the button reading
     * "connecting": stop() cleared tunnelReady, then onPsiphonConnected set it
     * back a moment later, and runConnection — still inside its wait loop,
     * because the loop only re-checks the session every 250ms — carried on as if
     * nothing had happened. The service side guards its own state writes with
     * isCurrentSession, but this class owns tunnelReady and had no way to know
     * the session was over.
     */
    private volatile boolean stopped;

    /** The preferred country, or null for auto. Set once per session. */
    private String country;
    private boolean cdnFronting;
    private boolean countryAttemptDone;
    private long countryAttemptStartedAt;
    private volatile int readyPort;

    /**
     * Whether Psiphon has an actual server tunnel, as opposed to an open port.
     *
     * These are minutes apart, not milliseconds. onListeningSocksProxyPort fires
     * as soon as the library binds 1819 — within about a second of start — while
     * onConnected only fires once a server has accepted a connection, which on a
     * restrictive carrier is tens of seconds and involves hundreds of rejected
     * candidates. Gating on the port meant gating on the half of that has no
     * network to it, so the gate failed, the re-roll restarted the core, and
     * that took Psiphon's upstream away mid-handshake:
     *
     *   failed to connect to 2YGk+CJD: ... dial tcp: connect: connection refused
     */
    private volatile boolean tunnelReady;
    private volatile boolean connected;

    SiphonChain(AetherVpnService service, PsiphonTunnelRunner.Listener listener) {
        this.service = service;
        this.listener = listener;
    }

    /** The user's chosen exit country, or null to let Psiphon choose. */
    void setCountry(String code) {
        country = PsiphonRegions.isCode(code) ? code : null;
    }

    void setCdnFronting(boolean enabled) {
        cdnFronting = enabled;
    }

    String country() {
        return country;
    }

    boolean cdnFronting() {
        return cdnFronting;
    }

    /** The port the hev bridge should attach to, or 0 while Psiphon is not up. */
    int readyPort() {
        return readyPort;
    }

    /** True once Psiphon reports a connected server, not merely a bound port. */
    boolean tunnelReady() {
        return tunnelReady;
    }

    boolean isConnected() {
        return connected;
    }

    /** The SOCKS address the core should publish for this session. */
    static String chainSocksAddress() {
        return "127.0.0.1:" + PsiphonTunnelRunner.CHAIN_SOCKS_PORT;
    }

    /**
     * The address the device's own traffic arrives at.
     *
     * Not the core's, and the difference is the whole point of the chain. Device
     * traffic goes tun0, then the bridge, then Psiphon, then the core. A probe
     * that dials the core directly measures the WARP leg alone, so on a chain it
     * answers a question about half the tunnel — and answers it early, before
     * Psiphon has a server at all. That is how the traffic gate went green at
     * 4186ms while Psiphon was still reporting "no active tunnels", and why the
     * location shown was the WARP exit rather than the one the user gets.
     */
    static String deviceSocksAddress() {
        return "127.0.0.1:" + PsiphonTunnelRunner.SOCKS_PORT;
    }

    /** The port the inner leg listens on. */
    static final int CHAIN_PORT = PsiphonTunnelRunner.CHAIN_SOCKS_PORT;

    /**
     * Start Psiphon over the core's SOCKS listener.
     *
     * @param serverEntries hex-encoded embedded server list, or empty
     */
    void start(String serverEntries) {
        stop();
        countryAttemptDone = false;
        stopped = false;
        readyPort = 0;
        tunnelReady = false;
        connected = false;
        runner = new PsiphonTunnelRunner(service, new PsiphonTunnelRunner.Listener() {
            @Override public void onPsiphonReady(int port) {
                if (stopped) return;
                readyPort = port;
                listener.onPsiphonReady(port);
            }

            @Override public void onPsiphonConnecting() {
                if (country != null) {
                    countryAttemptStartedAt = System.currentTimeMillis();
                    service.sendLog("Psiphon trying " + PsiphonRegions.name(country)
                            + " first, then any country that connects");
                }
                listener.onPsiphonConnecting();
            }

            @Override public void onPsiphonConnected() {
                if (stopped) return;
                tunnelReady = true;
                connected = true;
                listener.onPsiphonConnected();
            }

            @Override public void onPsiphonExiting(String reason) {
                if (stopped) return;
                connected = false;
                listener.onPsiphonExiting(reason);
            }

            @Override public void onPsiphonLog(String line) {
                service.sendLog(line);
            }

            @Override public void onPsiphonRegions(List<String> regions) {
                listener.onPsiphonRegions(regions);
            }
        });
        runner.start(serverEntries, country, cdnFronting);
    }

    /**
     * Drop the country filter when a chosen country will not come up.
     *
     * EgressRegion is a hard filter, so a country with no reachable server
     * cannot connect at all — and a chosen country that cannot connect is
     * exactly when the user's other countries matter most. The second attempt
     * has no filter, so Psiphon takes whatever it can reach rather than ending
     * with no tunnel.
     *
     * @return true when a retry was started
     */
    boolean retryWithoutCountry(String serverEntries) {
        if (countryAttemptDone || country == null) return false;
        countryAttemptDone = true;
        service.sendLog("Preferred country " + PsiphonRegions.name(country)
                + " did not come up; retrying without a country filter");
        start(serverEntries);
        return true;
    }

    /** Whether the country attempt has used up its budget without connecting. */
    boolean countryAttemptExpired() {
        if (country == null || countryAttemptDone || connected) return false;
        if (countryAttemptStartedAt == 0L) return false;
        return System.currentTimeMillis() - countryAttemptStartedAt >= COUNTRY_ATTEMPT_MS;
    }

    /** True once stop() has run, until start() runs again. */
    boolean stopped() { return stopped; }

    void stop() {
        stopped = true;
        if (runner != null) {
            runner.stop();
            runner = null;
        }
        readyPort = 0;
        tunnelReady = false;
        connected = false;
        countryAttemptStartedAt = 0L;
    }
}
