package com.firstham.aethergui;

import android.util.Log;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class LanProxyServer {
    private static final String TAG = "MehmanshahrLanProxy";
    /** Upper bound on simultaneously relayed LAN clients; each one costs two threads. */
    private static final int MAX_CLIENTS = 24;
    /** Idle relay timeout. Long enough for keep-alive sessions, short enough to reap dead peers. */
    private static final int RELAY_TIMEOUT_MS = 300_000;
    /** A LAN client that does not finish the SOCKS5 handshake in this window is dropped. */
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    private static final int UPSTREAM_CONNECT_TIMEOUT_MS = 15_000;
    private static final byte SOCKS_VERSION = 0x05;
    private static final byte AUTH_USERNAME_PASSWORD = 0x02;
    private static final byte AUTH_NONE = 0x00;
    private static final byte AUTH_UNACCEPTABLE = (byte) 0xff;
    private static final byte AUTH_SUBNEGOTIATION_VERSION = 0x01;
    private static final byte AUTH_SUCCESS = 0x00;
    private static final byte AUTH_FAILURE = 0x01;

    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final AtomicInteger activeClients = new AtomicInteger();
    private final SecureRandom random = new SecureRandom();
    private volatile ServerSocket server;
    private volatile String address = "";
    private volatile int port;
    private volatile String upstreamHost = "127.0.0.1";
    private volatile int upstreamPort = 1819;
    private volatile String username = "";
    private volatile String password = "";

    synchronized boolean start(int requestedPort) {
        return start(requestedPort, "127.0.0.1:1819");
    }

    synchronized boolean start(int requestedPort, String upstream) {
        if (server != null) return true;
        try {
            InetAddress local = localAddress();
            if (local == null || !local.isSiteLocalAddress()) return false;
            int separator = upstream == null ? -1 : upstream.lastIndexOf(':');
            if (separator <= 0) return false;
            String host = upstream.substring(0, separator).trim();
            int targetPort = Integer.parseInt(upstream.substring(separator + 1).trim());
            if (host.isEmpty() || targetPort < 1 || targetPort > 65535) return false;
            ServerSocket socket = new ServerSocket(requestedPort, 16, local);
            upstreamHost = host; upstreamPort = targetPort;
            // Fresh credentials per session so a leaked pair cannot be replayed against a later one.
            username = "mshvpn-" + randomHex(4);
            password = randomHex(16);
            server = socket; address = local.getHostAddress(); port = socket.getLocalPort();
            workers.execute(() -> acceptLoop(socket));
            return true;
        } catch (Exception error) { Log.w(TAG, "LAN proxy could not start", error); return false; }
    }

    synchronized void stop() {
        ServerSocket socket = server;
        server = null;
        address = "";
        port = 0;
        username = "";
        password = "";
        if (socket != null) try { socket.close(); } catch (Exception ignored) { }
        for (Socket client : clients) try { client.close(); } catch (Exception ignored) { }
        clients.clear();
        activeClients.set(0);
    }
    String address() { return address; }
    int port() { return port; }
    String username() { return username; }
    String password() { return password; }
    boolean matchesAddress() { try { InetAddress current = localAddress(); return current != null && current.getHostAddress().equals(address); } catch (Exception ignored) { return false; } }

    private void acceptLoop(ServerSocket socket) {
        while (server == socket) {
            Socket client = null;
            try {
                client = socket.accept();
                if (!client.getInetAddress().isSiteLocalAddress()) { closeQuietly(client); continue; }
                if (activeClients.get() >= MAX_CLIENTS) {
                    Log.w(TAG, "LAN proxy refused a client; " + MAX_CLIENTS + " connection limit reached");
                    closeQuietly(client);
                    continue;
                }
                activeClients.incrementAndGet();
                clients.add(client);
                final Socket accepted = client;
                workers.execute(() -> relay(accepted));
            } catch (Exception error) {
                closeQuietly(client);
                if (server == socket) Log.w(TAG, "LAN proxy stopped", error);
            }
        }
    }

    private void relay(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            client.setTcpNoDelay(true);
            if (!authenticate(client)) return;
            upstream = new Socket();
            upstream.setTcpNoDelay(true);
            upstream.connect(new java.net.InetSocketAddress(upstreamHost, upstreamPort), UPSTREAM_CONNECT_TIMEOUT_MS);
            upstream.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            // The Aether core listens without authentication on loopback, so this hop negotiates
            // "no auth" on the client's behalf. Everything after it is the client's own request.
            if (!greetUpstream(upstream)) return;
            client.setSoTimeout(RELAY_TIMEOUT_MS);
            upstream.setSoTimeout(RELAY_TIMEOUT_MS);
            Socket peer = upstream;
            Thread upload = new Thread(() -> copy(client, peer), "lan-upload");
            Thread download = new Thread(() -> copy(peer, client), "lan-download");
            upload.start();
            download.start();
            upload.join();
            download.join();
        } catch (Exception error) {
            Log.d(TAG, "LAN client closed", error);
        } finally {
            closeQuietly(upstream);
            closeQuietly(client);
            clients.remove(client);
            activeClients.decrementAndGet();
        }
    }

    /**
     * RFC 1928 method selection followed by RFC 1929 username/password verification. Before this
     * existed any device on the same WiFi could use the tunnel simply by pointing at the port.
     */
    private boolean authenticate(Socket client) throws IOException {
        DataInputStream input = new DataInputStream(client.getInputStream());
        OutputStream output = client.getOutputStream();
        if (input.readByte() != SOCKS_VERSION) return false;
        int methodCount = input.readUnsignedByte();
        if (methodCount <= 0) return false;
        byte[] methods = new byte[methodCount];
        input.readFully(methods);
        boolean offered = false;
        for (byte method : methods) if (method == AUTH_USERNAME_PASSWORD) { offered = true; break; }
        if (!offered) {
            output.write(new byte[]{SOCKS_VERSION, AUTH_UNACCEPTABLE});
            output.flush();
            Log.w(TAG, "LAN client rejected: it does not support SOCKS5 username/password auth");
            return false;
        }
        output.write(new byte[]{SOCKS_VERSION, AUTH_USERNAME_PASSWORD});
        output.flush();
        if (input.readByte() != AUTH_SUBNEGOTIATION_VERSION) return false;
        byte[] user = new byte[input.readUnsignedByte()];
        input.readFully(user);
        byte[] secret = new byte[input.readUnsignedByte()];
        input.readFully(secret);
        boolean allowed = constantTimeEquals(new String(user, StandardCharsets.UTF_8), username)
                && constantTimeEquals(new String(secret, StandardCharsets.UTF_8), password);
        output.write(new byte[]{AUTH_SUBNEGOTIATION_VERSION, allowed ? AUTH_SUCCESS : AUTH_FAILURE});
        output.flush();
        if (!allowed) Log.w(TAG, "LAN client rejected: invalid SOCKS5 credentials");
        return allowed;
    }

    private static boolean greetUpstream(Socket upstream) throws IOException {
        upstream.getOutputStream().write(new byte[]{SOCKS_VERSION, 0x01, AUTH_NONE});
        upstream.getOutputStream().flush();
        DataInputStream reply = new DataInputStream(upstream.getInputStream());
        return reply.readByte() == SOCKS_VERSION && reply.readByte() == AUTH_NONE;
    }

    private static boolean constantTimeEquals(String candidate, String expected) {
        if (expected == null || expected.isEmpty()) return false;
        byte[] left = candidate.getBytes(StandardCharsets.UTF_8);
        byte[] right = expected.getBytes(StandardCharsets.UTF_8);
        int difference = left.length ^ right.length;
        for (int i = 0; i < left.length; i++) difference |= left[i] ^ right[i % Math.max(1, right.length)];
        return difference == 0;
    }

    private String randomHex(int bytes) {
        byte[] material = new byte[bytes];
        random.nextBytes(material);
        StringBuilder builder = new StringBuilder(bytes * 2);
        for (byte value : material) builder.append(String.format(Locale.US, "%02x", value));
        return builder.toString();
    }

    private static void closeQuietly(Socket socket) { if (socket != null) try { socket.close(); } catch (Exception ignored) { } }

    private static void copy(Socket from, Socket to) { try { InputStream input = from.getInputStream(); OutputStream output = to.getOutputStream(); byte[] buffer = new byte[16 * 1024]; int count; while ((count = input.read(buffer)) >= 0) { output.write(buffer, 0, count); output.flush(); } } catch (Exception ignored) { } finally { closeQuietly(from); closeQuietly(to); } }
    private static InetAddress localAddress() throws Exception {
        InetAddress fallback = null;
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) continue;
            String name = nic.getName().toLowerCase(java.util.Locale.US);
            if (name.startsWith("tun") || name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")) continue;
            for (InetAddress candidate : Collections.list(nic.getInetAddresses())) {
                if (!(candidate instanceof Inet4Address) || !candidate.isSiteLocalAddress()) continue;
                if (name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("eth")) return candidate;
                if (fallback == null) fallback = candidate;
            }
        }
        return fallback;
    }
}
