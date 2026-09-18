package com.firstham.aethergui;

import android.content.Context;
import java.io.File;
import java.io.FileWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class NetworkDiagnostics {
    private NetworkDiagnostics() { }

    static void run(Context context, String transport) {
        final int sent = 5; int received = 0; List<Long> samples = new ArrayList<>();
        for (int i = 0; i < sent; i++) { long started = System.nanoTime(); try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("1.1.1.1", 443), 3500); received++; samples.add((System.nanoTime() - started) / 1_000_000L); } catch (Exception ignored) { } }
        long min = samples.isEmpty() ? -1 : Collections.min(samples); long max = samples.isEmpty() ? -1 : Collections.max(samples); double avg = samples.isEmpty() ? -1 : samples.stream().mapToLong(Long::longValue).average().orElse(-1); double jitter = 0; for (int i = 1; i < samples.size(); i++) jitter += Math.abs(samples.get(i) - samples.get(i - 1)); if (samples.size() > 1) jitter /= samples.size() - 1;
        String report = String.format(Locale.US, "transport=%s packets_sent=%d packets_received=%d lost=%d loss_percent=%.1f min_ping_ms=%d avg_ping_ms=%.1f max_ping_ms=%d jitter_ms=%.1f reconnects=%d overhead=internal_socket_probe_only%n", transport, sent, received, sent - received, 100.0 * (sent - received) / sent, min, avg, max, jitter, 0);
        try { File file = new File(context.getFilesDir(), "network-diagnostics.log"); try (FileWriter writer = new FileWriter(file, true)) { writer.append(report); } } catch (Exception ignored) { }
    }
}
