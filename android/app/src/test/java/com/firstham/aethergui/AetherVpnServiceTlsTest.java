package com.firstham.aethergui;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateExpiredException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Hostname verification for the TLS the VPN service speaks over its own SOCKS tunnel.
 *
 * <p>These run against a real {@link SSLServerSocket} on loopback, with a throwaway CA
 * and three leaf certificates minted by {@code keytool} when the class loads. Nothing
 * binary is committed to the repository and nothing reaches the network: the client
 * trusts only the CA created here, and the platform trust store is left untouched.
 *
 * <p>{@link #aCertificateForAnotherHostIsRejected()} is the regression test for the
 * defect. {@link #withoutEndpointIdentificationTheWrongCertificateIsAccepted()} is its
 * control — it exercises the same server with the hardening removed and shows the
 * handshake then succeeds, which is what the service used to do.
 */
public final class AetherVpnServiceTlsTest {
    private static final String PASSWORD = "aethon-unit-test";
    private static final char[] PASSWORD_CHARS = PASSWORD.toCharArray();
    private static final int TIMEOUT_MS = 15_000;

    private static Path workDir;
    private static SSLSocketFactory clientFactory;

    @BeforeClass public static void mintCertificates() throws Exception {
        workDir = Files.createTempDirectory("aethon-tls-test");
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=Aethon Unit Test CA", "-ext", "bc:c", "-validity", "36500",
                "-keystore", "ca.p12", "-storetype", "PKCS12", "-storepass", PASSWORD);
        keytool("-exportcert", "-alias", "ca", "-keystore", "ca.p12", "-storepass", PASSWORD,
                "-rfc", "-file", "ca.crt");

        // Valid, issued for localhost, which is the host these tests then ask for.
        mintLeaf("right", "CN=localhost", "dns:localhost", "-validity", "36500");
        // Valid and CA-signed, but issued for a different name entirely.
        mintLeaf("wrong", "CN=wrong.invalid", "dns:wrong.invalid", "-validity", "36500");
        // Issued for localhost, but its validity window closed long ago.
        mintLeaf("expired", "CN=localhost", "dns:localhost", "-startdate", "-400d", "-validity", "1");

        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, PASSWORD_CHARS);
        trust.setCertificateEntry("ca", loadKeyStore("right.p12").getCertificate("ca"));
        TrustManagerFactory trustManagers =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, trustManagers.getTrustManagers(), null);
        clientFactory = client.getSocketFactory();
    }

    @AfterClass public static void deleteCertificates() throws Exception {
        if (workDir == null) return;
        try (Stream<Path> paths = Files.walk(workDir)) {
            List<Path> deepestFirst = paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (Path path : deepestFirst) Files.deleteIfExists(path);
        }
    }

    @Test public void endpointIdentificationAndSniAreSetForADnsName() {
        SSLParameters parameters =
                AetherVpnService.httpsIdentityParameters(new SSLParameters(), "api.ipify.org");
        assertEquals("HTTPS", parameters.getEndpointIdentificationAlgorithm());
        assertEquals(1, sniCount(parameters));
        assertEquals("api.ipify.org", ((SNIHostName) parameters.getServerNames().get(0)).getAsciiName());
    }

    @Test public void anIpLiteralStillGetsEndpointIdentificationJustNoSni() {
        for (String literal : new String[]{"104.16.132.229", "1.1.1.1", "2606:4700::6810:85e5", "::1"}) {
            assertTrue(literal, AetherVpnService.isIpLiteral(literal));
            SSLParameters parameters =
                    AetherVpnService.httpsIdentityParameters(new SSLParameters(), literal);
            assertEquals("HTTPS", parameters.getEndpointIdentificationAlgorithm());
            assertEquals("SNI must not carry an IP literal: " + literal, 0, sniCount(parameters));
        }
        // The hosts this service actually contacts are domain names and must keep SNI.
        for (String name : new String[]{"www.cloudflare.com", "api.ipify.org", "ipapi.co",
                "ipwho.is", "api.country.is"}) {
            assertFalse(name, AetherVpnService.isIpLiteral(name));
            assertEquals(name, 1,
                    sniCount(AetherVpnService.httpsIdentityParameters(new SSLParameters(), name)));
        }
    }

    /** {@link SSLParameters#getServerNames()} is null, not empty, when nothing was set. */
    private static int sniCount(SSLParameters parameters) {
        return parameters.getServerNames() == null ? 0 : parameters.getServerNames().size();
    }

    @Test(timeout = 120_000) public void aCertificateForTheRequestedHostIsAccepted() throws Exception {
        TlsServer server = new TlsServer("right.p12");
        try {
            SSLSocket ssl = connect(server, "localhost");
            try {
                assertEquals("HTTPS", ssl.getSSLParameters().getEndpointIdentificationAlgorithm());
                assertEquals("CN=localhost", ssl.getSession().getPeerPrincipal().getName());
                assertEquals("hello", read(ssl));
            } finally { ssl.close(); }
        } finally { server.close(); }
    }

    @Test(timeout = 120_000) public void aCertificateForAnotherHostIsRejected() throws Exception {
        TlsServer server = new TlsServer("wrong.p12");
        try {
            SSLSocket ssl = null;
            try {
                ssl = connect(server, "localhost");
                fail("a certificate issued for wrong.invalid was accepted for localhost");
            } catch (IOException rejected) {
                String trace = describe(rejected);
                assertTrue("expected a hostname mismatch, got: " + trace,
                        trace.contains("localhost") || trace.contains("wrong.invalid"));
            } finally { if (ssl != null) ssl.close(); }
        } finally { server.close(); }
    }

    @Test(timeout = 120_000) public void anExpiredCertificateIsRejected() throws Exception {
        TlsServer server = new TlsServer("expired.p12");
        try {
            SSLSocket ssl = null;
            try {
                ssl = connect(server, "localhost");
                fail("an expired certificate was accepted");
            } catch (IOException rejected) {
                String trace = describe(rejected);
                assertTrue("expected an expiry failure, got: " + trace,
                        hasCause(rejected, CertificateExpiredException.class)
                                || trace.contains("expired") || trace.contains("validity check failed"));
            } finally { if (ssl != null) ssl.close(); }
        } finally { server.close(); }
    }

    /**
     * The defect, reproduced. Same server, same wrong certificate, but the socket is
     * built the way the service built it before this fix: chain validation on, hostname
     * checking off. The handshake succeeds, which is exactly the hole that was closed.
     */
    @Test(timeout = 120_000) public void withoutEndpointIdentificationTheWrongCertificateIsAccepted() throws Exception {
        TlsServer server = new TlsServer("wrong.p12");
        try {
            Socket plain = plainSocket(server);
            SSLSocket ssl = (SSLSocket) clientFactory.createSocket(plain, "localhost", server.port(), true);
            try {
                assertNull("the platform default must not already be checking hostnames — "
                                + "if it does, this control no longer proves anything",
                        ssl.getSSLParameters().getEndpointIdentificationAlgorithm());
                ssl.setSoTimeout(TIMEOUT_MS);
                ssl.startHandshake();
                assertEquals("CN=wrong.invalid", ssl.getSession().getPeerPrincipal().getName());
            } finally { ssl.close(); }
        } finally { server.close(); }
    }

    // --- fixture -----------------------------------------------------------------

    private SSLSocket connect(TlsServer server, String host) throws IOException {
        Socket plain = plainSocket(server);
        try {
            return AetherVpnService.verifiedTlsSocket(clientFactory, plain, host, server.port(), TIMEOUT_MS);
        } catch (IOException failure) {
            closeQuietly(plain);
            throw failure;
        } catch (RuntimeException failure) {
            closeQuietly(plain);
            throw failure;
        }
    }

    private static Socket plainSocket(TlsServer server) throws IOException {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), TIMEOUT_MS);
        plain.setSoTimeout(TIMEOUT_MS);
        return plain;
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }

    private static String read(SSLSocket ssl) throws IOException {
        byte[] buffer = new byte[64];
        int read = ssl.getInputStream().read(buffer);
        return read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.US_ASCII);
    }

    private static String describe(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            text.append(cause.getClass().getName()).append(": ").append(cause.getMessage()).append(" | ");
        }
        return text.toString();
    }

    private static boolean hasCause(Throwable error, Class<?> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) return true;
        }
        return false;
    }

    /** A one-shot TLS server on loopback presenting the certificate in {@code keystore}. */
    private static final class TlsServer {
        private final SSLServerSocket socket;
        private final Thread thread;
        private final CountDownLatch listening = new CountDownLatch(1);

        TlsServer(String keystore) throws Exception {
            KeyManagerFactory keyManagers =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(loadKeyStore(keystore), PASSWORD_CHARS);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers.getKeyManagers(), null, null);
            socket = (SSLServerSocket) context.getServerSocketFactory()
                    .createServerSocket(0, 1, InetAddress.getLoopbackAddress());
            socket.setSoTimeout(TIMEOUT_MS);
            thread = new Thread(new Runnable() {
                @Override public void run() { serveOnce(); }
            }, "tls-test-server");
            thread.setDaemon(true);
            thread.start();
            assertTrue("server did not start listening", listening.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }

        int port() { return socket.getLocalPort(); }

        private void serveOnce() {
            listening.countDown();
            Socket accepted = null;
            try {
                accepted = socket.accept();
                accepted.setSoTimeout(TIMEOUT_MS);
                OutputStream output = accepted.getOutputStream();
                output.write("hello".getBytes(StandardCharsets.US_ASCII));
                output.flush();
            } catch (IOException expectedWhenTheClientRefusesUs) {
                // A client that rejects the certificate sends a fatal alert and hangs up.
            } finally {
                if (accepted != null) closeQuietly(accepted);
            }
        }

        void close() throws Exception {
            socket.close();
            thread.join(TIMEOUT_MS);
        }
    }

    private static KeyStore loadKeyStore(String name) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        InputStream input = Files.newInputStream(workDir.resolve(name));
        try {
            store.load(input, PASSWORD_CHARS);
        } finally { input.close(); }
        return store;
    }

    private static void mintLeaf(String alias, String dname, String san, String... validity) throws Exception {
        keytool("-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                "-dname", dname, "-validity", "36500",
                "-keystore", alias + ".p12", "-storetype", "PKCS12", "-storepass", PASSWORD);
        keytool("-certreq", "-alias", alias, "-keystore", alias + ".p12", "-storepass", PASSWORD,
                "-file", alias + ".csr");
        List<String> sign = new ArrayList<String>(Arrays.asList("-gencert", "-alias", "ca",
                "-keystore", "ca.p12", "-storepass", PASSWORD, "-infile", alias + ".csr",
                "-outfile", alias + ".crt", "-ext", "san=" + san, "-rfc"));
        sign.addAll(Arrays.asList(validity));
        keytool(sign.toArray(new String[0]));
        keytool("-importcert", "-alias", "ca", "-keystore", alias + ".p12", "-storepass", PASSWORD,
                "-file", "ca.crt", "-noprompt");
        Files.write(workDir.resolve(alias + "-chain.crt"), concat(alias + ".crt", "ca.crt"));
        keytool("-importcert", "-alias", alias, "-keystore", alias + ".p12", "-storepass", PASSWORD,
                "-file", alias + "-chain.crt", "-noprompt");
    }

    private static byte[] concat(String first, String second) throws IOException {
        byte[] a = Files.readAllBytes(workDir.resolve(first));
        byte[] b = Files.readAllBytes(workDir.resolve(second));
        byte[] joined = new byte[a.length + b.length];
        System.arraycopy(a, 0, joined, 0, a.length);
        System.arraycopy(b, 0, joined, a.length, b.length);
        return joined;
    }

    private static void keytool(String... arguments) throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        File tool = new File(System.getProperty("java.home"), "bin/keytool" + (windows ? ".exe" : ""));
        assertTrue("keytool is required to mint the test certificates: " + tool, tool.isFile());
        List<String> command = new ArrayList<String>();
        command.add(tool.getAbsolutePath());
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile()).redirectErrorStream(true).start();
        String output = drain(process.getInputStream());
        assertTrue("keytool timed out: " + command, process.waitFor(120, TimeUnit.SECONDS));
        assertEquals("keytool failed: " + command + "\n" + output, 0, process.exitValue());
    }

    private static String drain(InputStream input) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) > 0) collected.write(buffer, 0, read);
        return new String(collected.toByteArray(), StandardCharsets.UTF_8);
    }
}
