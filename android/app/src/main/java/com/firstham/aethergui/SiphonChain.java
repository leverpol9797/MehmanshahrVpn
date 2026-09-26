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

    /** How long to give a chosen country before falling back to every country. */
    private static final long COUNTRY_ATTEMPT_MS = 45_000L;

    private final AetherVpnService service;
    private final PsiphonTunnelRunner.Listener listener;
    private PsiphonTunnelRunner runner;

    /** The preferred country, or null for auto. Set once per session. */
    private String country;
    private boolean cdnFronting;
    private boolean countryAttemptDone;
    private long countryAttemptStartedAt;
    private volatile int readyPort;
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

    boolean isConnected() {
        return connected;
    }

    /** The SOCKS address the core should publish for this session. */
    static String chainSocksAddress() {
        return "127.0.0.1:" + PsiphonTunnelRunner.CHAIN_SOCKS_PORT;
    }

    /**
     * Start Psiphon over the core's SOCKS listener.
     *
     * @param serverEntries hex-encoded embedded server list, or empty
     */
    void start(String serverEntries) {
        stop();
        countryAttemptDone = false;
        readyPort = 0;
        connected = false;
        runner = new PsiphonTunnelRunner(service, new PsiphonTunnelRunner.Listener() {
            @Override public void onPsiphonReady(int port) {
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
                connected = true;
                listener.onPsiphonConnected();
            }

            @Override public void onPsiphonExiting(String reason) {
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

    void stop() {
        if (runner != null) {
            runner.stop();
            runner = null;
        }
        readyPort = 0;
        connected = false;
        countryAttemptStartedAt = 0L;
    }
}
