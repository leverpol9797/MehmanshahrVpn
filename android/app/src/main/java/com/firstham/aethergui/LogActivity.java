package com.firstham.aethergui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * A window onto what the core actually said.
 *
 * <p>The service reads the core's output and broadcasts it as {@code ACTION_LOG}. This is the
 * end of that broadcast, and the reason the log can be copied in one tap: whoever hits a
 * transport that will not come up can paste the actual error instead of describing the symptom.
 *
 * <p>The lines are rendered through {@link LogTerminal}, which classifies them and filters by
 * level. Two things are kept from the previous version of this screen: the service's stored
 * buffer is read on open so a screen opened mid-session starts with history, and Clear also tells
 * the service to drop its copy rather than only emptying the view.
 */
public final class LogActivity extends AppCompatActivity {

    private LogTerminal terminal;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String lines = intent.getStringExtra("lines");
            if (lines == null || lines.trim().isEmpty()) return;
            for (String line : lines.split("\n")) terminal.append(line);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_log);
        // Built from the include subtree, not from the Activity root: an id declared inside an
        // <include> is not reliably reachable from the parent view, and a null view here
        // would crash the screen on open rather than fail the build.
        terminal = new LogTerminal(findViewById(R.id.logTerminalHost));

        View host = findViewById(R.id.logTerminalHost);
        host.findViewById(R.id.logCopyButton).setOnClickListener(v -> copy());
        host.findViewById(R.id.logClearButton).setOnClickListener(v -> clear());
        findViewById(R.id.logClose).setOnClickListener(v -> finish());
        host.findViewById(R.id.logDisconnectButton).setOnClickListener(v ->
                VpnConnectionController.disconnect(this));

        // Whatever the service collected before this screen opened.
        String existing = getSharedPreferences("service_state", Context.MODE_PRIVATE)
                .getString("logs", "");
        if (existing != null && !existing.isEmpty())
            for (String line : existing.split("\n")) terminal.append(line);
    }

    @Override protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(AetherVpnService.ACTION_LOG);
            filter.addCategory(Intent.CATEGORY_DEFAULT);
            ContextCompat.registerReceiver(this, receiver, filter, INTERNAL_PERMISSION,
                    null, ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
    }

    @Override protected void onStop() {
        super.onStop();
        if (receiverRegistered) {
            try { unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
            receiverRegistered = false;
        }
    }

    private void copy() {
        String text = terminal.asText();
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.log_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        // The clipboard is the only export here; writing a file to shared storage needs a
        // permission that a read-only screen has no reason to hold.
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, R.string.log_copy_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("aether-log", text));
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show();
    }

    private void clear() {
        terminal.clear();
        try {
            startService(new Intent(this, AetherVpnService.class)
                    .setAction(AetherVpnService.ACTION_CLEAR_LOGS));
        } catch (IllegalStateException ignored) {
            // Nothing to clear if the service is not running; the view is empty either way.
        }
    }
}