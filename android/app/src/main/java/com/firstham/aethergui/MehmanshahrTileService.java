package com.firstham.aethergui;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.annotation.SuppressLint;

import androidx.core.content.ContextCompat;

public final class MehmanshahrTileService extends TileService {
    public static final String EXTRA_CONNECT_FROM_TILE = "connect_from_tile";

    /**
     * Redraws the tile whenever the service publishes a new state.
     *
     * <p>This exists because {@link #requestUpdate(Context)} is not sufficient on its own.
     * {@code requestListeningState} asks the system to put the tile into the listening state, which
     * is what triggers {@link #onStartListening()}. When the shade is already open the tile is
     * <em>already</em> listening, so that request changes nothing and {@code onStartListening} is
     * never called again. With the redraw living only in {@code onStartListening}, the tile then kept
     * whatever it last rendered for as long as the user held the shade open.
     *
     * <p>That was reproduced on the device: with the shade open and the tile reading
     * "MehmanshahrVPN, Connected", a tap tore the tunnel down - the log recorded
     * {@code disconnect command_received=6ms} and {@code teardown=158ms}, and tun0 was gone - yet the
     * tile still read "Connected" on every subsequent dump. The shade shows VPN state exactly while
     * the user is watching it change, so this is the one window where staleness matters most.
     */
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateTile();
        }
    };
    private boolean receiverRegistered;

    @Override public void onStartListening() {
        super.onStartListening();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(AetherVpnService.ACTION_STATUS);
            ContextCompat.registerReceiver(this, stateReceiver, filter,
                    AetherVpnService.INTERNAL_PERMISSION, null, ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        updateTile();
    }

    @Override public void onStopListening() {
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver);
            receiverRegistered = false;
        }
        super.onStopListening();
    }

    @Override public void onClick() {
        super.onClick();
        String state = resolvedState();
        if (VpnConnectionController.canDisconnect(state)) {
            VpnConnectionController.disconnect(this);
            // Disconnecting is not instantaneous, and until the service publishes "disconnecting" the
            // tile would still be showing the old active state under the user's finger.
            updateTile();
            return;
        }
        android.content.SharedPreferences preferences = getSharedPreferences("aether", MODE_PRIVATE);
        if (!"manual".equals(preferences.getString("mode", "vpn")) && VpnService.prepare(this) != null) {
            openPermissionScreen();
            return;
        }
        VpnConnectionController.connect(this, preferences);
        updateTile();
    }

    /**
     * The state the tile renders. PROMPT phase 7 requires the real VPN state rather than a cached
     * one, so three sources are combined in order of authority:
     *
     * <ol>
     *   <li>the running service's live in-process state, which cannot be stale;</li>
     *   <li>the persisted state, for when the service process is not alive;</li>
     *   <li>Android's own view of whether a VPN transport exists, which vetoes a persisted
     *       "connected" left behind by a process kill and would otherwise show an ACTIVE tile for a
     *       tunnel that no longer exists.</li>
     * </ol>
     */
    private String resolvedState() {
        String live = AetherVpnService.liveState();
        if (!live.isEmpty()) return live;
        String persisted = getSharedPreferences("service_state", MODE_PRIVATE).getString("state", "disconnected");
        String healed = reconciledState(persisted, deviceVpnActive());
        if (!healed.equals(persisted)) {
            getSharedPreferences("service_state", MODE_PRIVATE).edit().putString("state", healed).apply();
        }
        return healed;
    }

    /**
     * @param persisted   last state written by the service
     * @param vpnActive   whether Android currently reports a VPN transport
     * @return the state to display; a stale active-looking state with no VPN transport heals to
     *         "disconnected" so the tile never claims protection that does not exist
     */
    static String reconciledState(String persisted, boolean vpnActive) {
        String state = persisted == null ? "disconnected" : persisted;
        if (vpnActive) return state;
        if ("connected".equals(state) || "blocked".equals(state)) return "disconnected";
        // A transitional state without a live service means the process died mid-connect.
        if (AetherVpnService.isConnectingState(state) || "disconnecting".equals(state)) return "disconnected";
        return state;
    }

    private boolean deviceVpnActive() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager == null) return false;
        Network[] networks = manager.getAllNetworks();
        if (networks == null) return false;
        for (Network network : networks) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    /** Tile state for a published connection state. Kept pure so it can be unit tested. */
    static int tileState(String state) {
        // "blocked" means the interface is still up with traffic fail-closed, so Android considers
        // the VPN active and the tile has to stay togglable for the user to release it. Marking it
        // UNAVAILABLE, as this used to, left the only way out inside the app.
        if ("connected".equals(state) || "blocked".equals(state)) return Tile.STATE_ACTIVE;
        return Tile.STATE_INACTIVE;
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        String state = resolvedState();
        boolean permissionMissing = !"manual".equals(getSharedPreferences("aether", MODE_PRIVATE).getString("mode", "vpn"))
                && VpnService.prepare(this) != null && !"connected".equals(state) && !"blocked".equals(state);
        int resolved = permissionMissing ? Tile.STATE_UNAVAILABLE : tileState(state);
        tile.setState(resolved);
        if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(getString(subtitleFor(state, permissionMissing)));
        tile.setLabel(getString(R.string.tile_name));
        tile.updateTile();
    }

    private static int subtitleFor(String state, boolean permissionMissing) {
        if (permissionMissing) return R.string.tile_unavailable;
        if ("connected".equals(state)) return R.string.tile_connected;
        if ("blocked".equals(state)) return R.string.tile_blocked;
        if ("error".equals(state)) return R.string.tile_error;
        if (AetherVpnService.isConnectingState(state) || "disconnecting".equals(state)) return R.string.tile_connecting;
        return R.string.tile_disconnected;
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private void openPermissionScreen() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_CONNECT_FROM_TILE, true);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 7, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            startActivityAndCollapse(pendingIntent);
        } else {
            startActivityAndCollapse(intent);
        }
    }

    static void requestUpdate(Context context) {
        requestListeningState(context, new ComponentName(context, MehmanshahrTileService.class));
    }
}
