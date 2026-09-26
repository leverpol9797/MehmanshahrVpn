package com.firstham.aethergui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

/**
 * A window onto what the core actually said.
 *
 * The service has always read the core's output and broadcast it as
 * ACTION_LOG — and nothing has ever received that broadcast. The lines went
 * into logcat and into a preference, which is a report nobody asked for: when a
 * transport failed there was nowhere to look, so the failure could only be
 * described from the outside as "it does not work".
 *
 * This activity is the missing end of that broadcast, and the reason it can be
 * copied to the clipboard in one tap: the next person to hit a transport that
 * will not come up can paste the actual error instead of guessing at it.
 */
public final class LogActivity extends android.app.Activity {

    private final StringBuilder text = new StringBuilder();
    private TextView view;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String lines = intent.getStringExtra("lines");
            if (lines == null || lines.trim().isEmpty()) return;
            if (text.length() > 0) text.append('\n');
            text.append(lines);
            render();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_log);

        view = findViewById(R.id.log_text);
        findViewById(R.id.log_copy).setOnClickListener(v -> copy());
        findViewById(R.id.log_clear).setOnClickListener(v -> clear());
        findViewById(R.id.log_close).setOnClickListener(v -> finish());

        // Whatever the service already collected before this screen opened.
        // Same file the service writes through its own stateStore.
        String existing = getSharedPreferences("service_state", Context.MODE_PRIVATE)
                .getString("logs", "");
        if (existing != null) text.append(existing);
        render();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(AetherVpnService.ACTION_LOG);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    @Override protected void onStop() {
        super.onStop();
        try { unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
    }

    private void render() {
        if (view == null) return;
        view.setText(text.length() == 0 ? getString(R.string.log_empty) : text.toString());
    }

    private void copy() {
        if (text.length() == 0) return;
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                getString(R.string.log_title), text.toString()));
        android.widget.Toast.makeText(this, R.string.log_copied, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void clear() {
        text.setLength(0);
        send(new Intent(AetherVpnService.ACTION_CLEAR_LOGS).setPackage(getPackageName()));
        render();
    }

    private void send(Intent intent) {
        try { startService(intent); } catch (IllegalStateException ignored) { }
    }
}
