package com.firstham.aethergui;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

import ca.psiphon.PsiphonTunnel;

/**
 * The Psiphon half of the WARP-then-Psiphon chain.
 *
 * The shape of it, and why each piece is where it is:
 *
 * <pre>
 *   device TUN --hev-- SOCKS 1819 -- Psiphon -- socks5://127.0.0.1:1820 -- Aether
 * </pre>
 *
 * Psiphon runs in SOCKS mode and never takes the TUN itself, so the same hev
 * bridge that already carries ordinary traffic carries this too. The Aether
 * core moves off its usual 1819 to {@link #CHAIN_SOCKS_PORT} and publishes
 * SOCKS there without a tun_fd — which the core already does, since this app
 * always bridges rather than passing the TUN down.
 *
 * That ordering is the whole point. The WARP leg has to be up and carrying
 * before Psiphon dials through it, because on a hostile carrier the Psiphon
 * servers are null-routed: dialling first means waiting out a timeout per
 * candidate. With the WARP leg underneath, the dial leaves over the tunnel and
 * Psiphon's own fronted protocols have something to ride.
 *
 * Because the WARP leg is underneath, the exit IP is Psiphon's server and not
 * Cloudflare's — which is what makes the user's chosen country the one that
 * actually shows up on a site.
 */
public final class PsiphonTunnelRunner {

    /**
     * Where the Aether core listens while it is the inner leg.
     *
     * 1819 belongs to Psiphon's own SOCKS listener, so the core moves up. Both
     * numbers are hardcoded rather than configurable: nothing else may bind
     * them, and a setting here could only be set to a value that breaks things.
     */
    public static final int CHAIN_SOCKS_PORT = 1820;

    public static final int SOCKS_PORT = 1819;

    /** The part of the chain this class reports back to. */
    public interface Listener {
        /** Psiphon has a usable SOCKS listener on {@code port}. */
        void onPsiphonReady(int port);

        /** Psiphon is trying; usually a short attempt for the chosen country. */
        void onPsiphonConnecting();

        /** The tunnel is up. */
        void onPsiphonConnected();

        /** Psiphon is done — it either failed or was stopped. */
        void onPsiphonExiting(String reason);

        /** A diagnostic line for the connection log. */
        void onPsiphonLog(String line);

        /** The live set of countries Psiphon says it can reach. */
        void onPsiphonRegions(List<String> regions);
    }

    private final Context context;
    private final Listener listener;

    private PsiphonTunnel tunnel;
    private volatile int socksPort;
    private volatile boolean connected;
    private String config = "{}";

    public PsiphonTunnelRunner(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public int socksPort() {
        return socksPort;
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * Start Psiphon, chained through the Aether core's SOCKS listener.
     *
     * @param serverEntries the embedded server list, hex-encoded, or empty
     * @param country       the country to try first, or null to let Psiphon choose
     * @param cdnFronting   restrict the tunnel to the FRONTED-MEEK-CDN family
     */
    public void start(String serverEntries, String country, boolean cdnFronting) {
        try {
            config = configJson(country, cdnFronting);
            Host host = new Host();
            PsiphonTunnel created = PsiphonTunnel.newPsiphonTunnel(host);
            // SOCKS mode always: this app owns the TUN and bridges it with hev.
            // Letting Psiphon take the fd would put two owners on one interface.
            created.setVpnMode(false);
            tunnel = created;
            listener.onPsiphonLog("Psiphon tunnel starting"
                    + (country == null ? "" : " in " + PsiphonRegions.name(country)));
            created.startTunneling(serverEntries == null ? "" : serverEntries);
        } catch (Exception error) {
            listener.onPsiphonExiting(describe(error));
        }
    }

    /**
     * The config Psiphon is handed, as its own JSON.
     *
     * Two things are load-bearing:
     *
     *  - {@code UpstreamProxyURL} is what makes this a chain. Without it Psiphon
     *    dials the internet directly and the WARP leg is doing nothing at all.
     *  - {@code LocalSocksPort} is fixed, because the hev bridge is started
     *    against this number before Psiphon is free to choose one.
     *
     * The one consequence worth knowing: a SOCKS5 upstream cannot carry a UDP
     * associate, so Psiphon stops attempting QUIC-OSSH entirely and uses TCP
     * protocols only. That is Psiphon's own rule rather than a defect here, and
     * it is why the fronted and direct rungs still work.
     */
    static String configJson(String country, boolean cdnFronting) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        json.append("\"UpstreamProxyURL\":\"socks5://127.0.0.1:").append(CHAIN_SOCKS_PORT).append('"');
        json.append(",\"LocalSocksPort\":").append(SOCKS_PORT);
        json.append(",\"EmitDiagnosticNotices\":true");
        json.append(",\"UseIndistinguishableTLS\":true");
        if (country != null) {
            // A hard filter: only that country's servers are candidates. Used
            // for one attempt and then dropped, so a country with no reachable
            // server falls back to the whole list instead of failing outright.
            json.append(",\"EgressRegion\":\"").append(country).append('"');
        }
        if (cdnFronting) {
            json.append(",\"FrontedMeekCDNScanUseBuiltInSpec\":true");
        }
        json.append('}');
        return json.toString();
    }

    /**
     * The config for the second attempt, with the country filter dropped.
     *
     * Kept as a separate string because the filter is what makes a chosen
     * country retryable rather than merely reported as failed: the retry goes
     * out with no EgressRegion, so Psiphon is free to come out wherever it can
     * actually connect. Sending the same country again would just fail in the
     * same place.
     */
    static String retryConfigJson(boolean cdnFronting) {
        return configJson(null, cdnFronting);
    }

    public void stop() {
        final PsiphonTunnel running = tunnel;
        tunnel = null;
        connected = false;
        socksPort = 0;
        if (running == null) return;
        // Blocks until the Go controller has fully unwound, so this must not run
        // on the main thread: teardown happens there, and a stop that waits can
        // trip the ANR watchdog.
        Thread stopper = new Thread(new Runnable() {
            @Override public void run() {
                try { running.stop(); } catch (Exception ignored) { }
            }
        }, "psiphon-stop");
        stopper.setDaemon(true);
        stopper.start();
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    /**
     * The callbacks Psiphon makes on its way up.
     *
     * Every one is gated on the current tunnel, because a stop does not cancel
     * callbacks that are already in flight: the Go controller can call back
     * after stop() returns, and letting those through would publish a stale
     * port onto whatever tunnel is live now.
     */
    private final class Host implements PsiphonTunnel.HostService {
        @Override public Context getContext() {
            return context;
        }

        @Override public String getPsiphonConfig() {
            return config;
        }

        @Override public void onConnecting() {
            listener.onPsiphonConnecting();
        }

        @Override public void onConnected() {
            connected = true;
            listener.onPsiphonConnected();
        }

        @Override public void onListeningSocksProxyPort(int port) {
            if (tunnel == null) return;
            socksPort = port;
            listener.onPsiphonReady(port);
        }

        @Override public void onAvailableEgressRegions(List<String> regions) {
            if (regions == null) return;
            List<String> copy = new ArrayList<String>(regions);
            PsiphonRegions.remember(context, copy);
            listener.onPsiphonRegions(copy);
        }

        @Override public void onConnectedServerRegion(String region) {
            if (region == null || region.isEmpty()) return;
            listener.onPsiphonLog("Egress region: " + PsiphonRegions.label(region));
        }

        @Override public void onClientRegion(String region) {
            // The country the client is in, not where it comes out. Logged
            // because on an Iranian carrier the two being equal is the thing
            // that explains a country that is not changing.
            if (region == null || region.isEmpty()) return;
            listener.onPsiphonLog("Client region: " + region);
        }

        @Override public void onExiting() {
            listener.onPsiphonExiting("Psiphon exited");
        }

        @Override public void onUpstreamProxyError(String message) {
            // The WARP leg went away underneath Psiphon. This is the one that
            // matters on the chained path: it means the inner leg died, not that
            // Psiphon picked a bad server.
            listener.onPsiphonLog("Upstream proxy error: " + message);
        }

        @Override public void onDiagnosticMessage(String message) {
            if (message == null) return;
            listener.onPsiphonLog("[Psiphon] " + message);
        }

        @Override public void onBytesTransferred(long sent, long received) {
            // Counted only; the UI reads the same numbers from the core.
        }

        @Override public void onHomepage(String url) {
        }
    }
}
