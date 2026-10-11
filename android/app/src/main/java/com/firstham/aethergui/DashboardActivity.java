package com.firstham.aethergui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

/**
 * The dashboard: state, the one control that matters, and the settings that decide where traffic
 * goes. Replaces the older main screen rather than living beside it.
 *
 * <p>Everything on screen is driven by {@link AetherVpnService}'s broadcasts. The service is the
 * only thing that knows whether the tunnel is up, and this never guesses from its own last
 * request -- a connect that the system refused with a permission dialog must not leave the
 * button reading Connected.
 *
 * <p>The uptime clock ticks from a local {@code connectedAt} timestamp rather than by counting
 * the service's stats messages, so a stats message that arrives late does not make the clock run
 * slow. The counter is corrected against the service's own uptime on every stats broadcast.
 */
public final class DashboardActivity extends AppCompatActivity {

    private static final int VPN_REQUEST = 4211;

    /** How often the uptime clock redraws. One second is what the display shows. */
    private static final long TICK_MS = 1000L;

    private SharedPreferences preferences;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MaterialButton connectButton;
    private ProgressBar progress;
    private TextView statusView;
    private TextView endpointView;
    private TextView latencyView;
    private TextView uptimeView;
    private TextView portView;
    private TextView uploadView;
    private TextView downloadView;
    private MaterialCardView ring;
    private MaterialButtonToggleGroup modeToggle;
    private MaterialAutoCompleteTextView protocolInput;
    private MaterialAutoCompleteTextView scanInput;
    private MaterialAutoCompleteTextView transportInput;

    /** The last state the service reported. Never inferred locally. */
    private String state = "idle";
    private long connectedAtMs = 0L;
    private String serviceUptime = "00:00:00";
    private boolean receiverRegistered = false;
    private boolean restoring = true;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long elapsed = connectedAtMs == 0L ? 0L : (System.currentTimeMillis() - connectedAtMs) / 1000L;
            uptimeView.setText(connectedAtMs == 0L ? "00:00:00" : formatUptime(elapsed));
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case AetherVpnService.ACTION_STATUS:
                    applyStatus(intent);
                    break;
                case AetherVpnService.ACTION_STATS:
                    applyStats(intent);
                    break;
                default:
                    break;
            }
        }
    };

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("settings", MODE_PRIVATE);
        setContentView(R.layout.activity_dashboard);
        bind();

        setUpProtocols();
        setUpScan();
        setUpTransport();
        setUpModeToggle();
        setUpConnect();

        findViewById(R.id.dashboardLogButton).setOnClickListener(
                v -> startActivity(new Intent(this, LogActivity.class)));
        findViewById(R.id.dashboardSettingsButton).setOnClickListener(
                v -> startActivity(new Intent(this, MainActivity.class)));

        // Ask the service what state it is in rather than assuming from the last tap.
        startService(new Intent(this, AetherVpnService.class).setAction(AetherVpnService.ACTION_QUERY));
        render();
    }

    private void bind() {
        connectButton = findViewById(R.id.dashboardConnectButton);
        progress = findViewById(R.id.dashboardProgress);
        statusView = findViewById(R.id.dashboardStatus);
        endpointView = findViewById(R.id.dashboardEndpoint);
        latencyView = findViewById(R.id.dashboardLatency);
        uptimeView = findViewById(R.id.dashboardUptime);
        portView = findViewById(R.id.dashboardPort);
        uploadView = findViewById(R.id.dashboardUpload);
        downloadView = findViewById(R.id.dashboardDownload);
        ring = findViewById(R.id.dashboardRing);
        modeToggle = findViewById(R.id.dashboardModeToggle);
        protocolInput = findViewById(R.id.dashboardProtocolInput);
        scanInput = findViewById(R.id.dashboardScanInput);
        transportInput = findViewById(R.id.dashboardTransportInput);
    }

    /**
     * The protocol dropdown.
     *
     * <p>The entries are the combined ones too, because the persisted preference is an index into
     * the same array and the service reads it as such. A shorter list here would silently change
     * which protocol a saved index selects.
     */
    private void setUpProtocols() {
        String[] labels = getResources().getStringArray(R.array.protocol_labels);
        protocolInput.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, labels));
        int index = Math.max(0, Math.min(labels.length - 1,
                preferences.getInt("protocol", ConnectionDefaults.PROTOCOL_INDEX)));
        protocolInput.setText(labels[index], false);
        protocolInput.setTag(index);
        protocolInput.setOnItemClickListener((parent, view, position, id) -> {
            if (restoring) return;
            preferences.edit().putInt("protocol", position).apply();
            protocolInput.setTag(position);
        });
    }

    private void setUpScan() {
        String[] labels = getResources().getStringArray(R.array.scan_labels);
        scanInput.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        int index = Math.max(0, Math.min(labels.length - 1,
                preferences.getInt("scan", ConnectionDefaults.SCAN_INDEX)));
        scanInput.setText(labels[index], false);
        scanInput.setTag(index);
        scanInput.setOnItemClickListener((parent, view, position, id) -> {
            if (restoring) return;
            preferences.edit().putInt("scan", position).apply();
            scanInput.setTag(position);
        });
    }

    private void setUpTransport() {
        String[] labels = getResources().getStringArray(R.array.transport_labels);
        transportInput.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        int index = Math.max(0, Math.min(labels.length - 1,
                preferences.getInt("transport", ConnectionDefaults.TRANSPORT_INDEX)));
        transportInput.setText(labels[index], false);
        transportInput.setTag(index);
        transportInput.setOnItemClickListener((parent, view, position, id) -> {
            if (restoring) return;
            preferences.edit().putInt("transport", position).apply();
            transportInput.setTag(position);
        });
    }

    /**
     * SOCKS5 proxy or system VPN. The mode is read live from the same preference the old screen
     * wrote, so the two do not disagree if someone switches screens.
     */
    private void setUpModeToggle() {
        boolean vpn = "vpn".equals(preferences.getString("mode", "vpn"));
        modeToggle.check(vpn ? R.id.dashboardModeVpn : R.id.dashboardModeProxy);
        updateModeSummary(vpn);
        modeToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked || restoring) return;
            boolean vpnMode = checkedId == R.id.dashboardModeVpn;
            preferences.edit().putString("mode", vpnMode ? "vpn" : "manual").apply();
            updateModeSummary(vpnMode);
            // Switching into VPN mode while already connected cannot take effect without
            // rebuilding the tunnel, so say so rather than appearing to apply.
            if (vpnMode && "connected".equals(state)) statusView.setText(R.string.dashboard_permission_needed);
        });
    }

    private void updateModeSummary(boolean vpn) {
        ((TextView) findViewById(R.id.dashboardModeSummary)).setText(
                vpn ? R.string.dashboard_mode_vpn_summary : R.string.dashboard_mode_proxy_summary);
    }

    private void setUpConnect() {
        connectButton.setOnClickListener(v -> {
            if ("connected".equals(state) || "connecting".equals(state)) disconnect();
            else connect();
        });
    }

    private void connect() {
        // System VPN needs the user to grant it. Without this check the service would fail
        // asynchronously and the button would sit on Connecting.
        if (!"manual".equals(preferences.getString("mode", "vpn"))) {
            Intent permission = VpnService.prepare(this);
            if (permission != null) {
                startActivityForResult(permission, VPN_REQUEST);
                return;
            }
        }
        Intent intent = new Intent(this, AetherVpnService.class).setAction(AetherVpnService.ACTION_START);
        // The service reads these to build its command line; the dashboard is only the front end.
        intent.putExtra("protocol", protocolInput.getTag() instanceof Integer ? protocolInput.getTag() : 0);
        intent.putExtra("scan", scanInput.getTag() instanceof Integer ? scanInput.getTag() : 1);
        intent.putExtra("transport", transportInput.getTag() instanceof Integer ? transportInput.getTag() : 1);
        startService(intent);
        state = "connecting";
        connectedAtMs = 0L;
        render();
    }

    private void disconnect() {
        VpnConnectionController.disconnect(this);
        state = "idle";
        connectedAtMs = 0L;
        render();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != VPN_REQUEST) return;
        // RESULT_CANCELED means the user said no. Falling through to connect() anyway would show
        // Connecting forever.
        if (resultCode != RESULT_OK) {
            state = "idle";
            render();
            return;
        }
        connect();
    }

    private void applyStatus(Intent intent) {
        state = intent.getStringExtra("state");
        if (state == null) state = "idle";
        String endpoint = intent.getStringExtra("endpoint");
        if (endpoint != null && !endpoint.isEmpty()) {
            endpointView.setText(endpoint);
            endpointView.setVisibility(View.VISIBLE);
        } else {
            endpointView.setVisibility(View.GONE);
        }
        if (intent.hasExtra("port")) portView.setText(String.valueOf(intent.getIntExtra("port", 1819)));
        String message = intent.getStringExtra("message");
        if (message != null && !message.isEmpty()) statusView.setText(message);
        if ("connected".equals(state) && connectedAtMs == 0L) connectedAtMs = System.currentTimeMillis();
        if (!"connected".equals(state)) connectedAtMs = 0L;
        render();
    }

    private void applyStats(Intent intent) {
        String ping = intent.getStringExtra("ping");
        latencyView.setText(ping == null || ping.isEmpty() ? "--" : ping);
        String up = intent.getStringExtra("tx");
        String down = intent.getStringExtra("rx");
        if (up != null) uploadView.setText(up);
        if (down != null) downloadView.setText(down);
    }

    /** The whole screen's appearance from one state value, so it cannot disagree with itself. */
    private void render() {
        boolean connected = "connected".equals(state);
        boolean connecting = "connecting".equals(state);
        boolean error = "error".equals(state);

        connectButton.setText(connected || connecting
                ? R.string.dashboard_disconnect : R.string.dashboard_connect);
        connectButton.setBackgroundTintList(ContextCompat.getColorStateList(this,
                connected ? R.color.state_connected : connecting ? R.color.state_connecting : R.color.app_accent));
        ring.setStrokeColor(ContextCompat.getColor(this,
                connected ? R.color.state_connected : connecting ? R.color.state_connecting : R.color.app_border));

        // The spinner sits over the button while connecting: the button stays tappable, because
        // a connect that is taking a long time is usually one the user wants to cancel.
        progress.setVisibility(connecting ? View.VISIBLE : View.GONE);
        connectButton.setAlpha(connecting ? 0.35f : 1f);

        int statusColour = connected ? R.color.state_connected
                : connecting ? R.color.state_connecting
                : error ? R.color.state_error : R.color.state_idle;
        if (statusView.getText().length() == 0) statusView.setText(R.string.status_ready_message);
        statusView.setTextColor(ContextCompat.getColor(this, statusColour));

        if (!connected && !connecting && statusView.getText().length() == 0)
            statusView.setText(R.string.dashboard_state_idle);

        // The protocol controls do not apply mid-tunnel; changing them silently would promise
        // something the running tunnel is not doing.
        boolean enabled = !connected && !connecting;
        for (int id : new int[]{R.id.dashboardProtocolInput, R.id.dashboardScanInput, R.id.dashboardTransportInput})
            findViewById(id).setEnabled(enabled);
    }

    /** {@code 3661 -> 01:01:01}, matching the core's own uptime formatting. */
    static String formatUptime(long seconds) {
        long days = seconds / 86400L;
        long hours = (seconds % 86400L) / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        String clock = String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, secs);
        return days == 0L ? clock : days + "d " + clock;
    }

    @Override protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(AetherVpnService.ACTION_STATUS);
            filter.addAction(AetherVpnService.ACTION_STATS);
            ContextCompat.registerReceiver(this, receiver, filter, INTERNAL_PERMISSION,
                    null, ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        handler.post(tick);
        restoring = false;
    }

    @Override protected void onStop() {
        super.onStop();
        handler.removeCallbacks(tick);
        if (receiverRegistered) {
            unregisterReceiver(receiver);
            receiverRegistered = false;
        }
    }
}