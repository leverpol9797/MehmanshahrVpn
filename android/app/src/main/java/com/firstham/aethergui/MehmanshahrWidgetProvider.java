package com.firstham.aethergui;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import java.security.SecureRandom;

public final class MehmanshahrWidgetProvider extends AppWidgetProvider {
    private static final String TAG = "MehmanshahrWidget";
    static final String ACTION_WIDGET_TOGGLE = "com.firstham.aethergui.WIDGET_TOGGLE";
    private static final String EXTRA_TOGGLE_TOKEN = "toggleToken";
    private static final String KEY_TOGGLE_TOKEN = "widgetToggleToken";

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (!ACTION_WIDGET_TOGGLE.equals(intent.getAction())) return;
        // The provider has to stay exported so the framework can deliver APPWIDGET_UPDATE, which
        // also let any installed app send an explicit toggle and switch the VPN on or off. The tap
        // PendingIntent is immutable and built by this app, so it can carry a secret held only in
        // this app's private state; a forged broadcast cannot reproduce it.
        if (!isTrustedToggle(context, intent)) {
            Log.w(TAG, "Rejected an untrusted widget toggle broadcast");
            return;
        }
        String state = context.getSharedPreferences("service_state", Context.MODE_PRIVATE).getString("state", "disconnected");
        if ("connected".equals(state)) {
            context.startService(new Intent(context, AetherVpnService.class).setAction(AetherVpnService.ACTION_STOP));
        } else if ("disconnected".equals(state) || "error".equals(state) || "blocked".equals(state)) {
            android.content.SharedPreferences preferences = context.getSharedPreferences("aether", Context.MODE_PRIVATE);
            if (!"manual".equals(preferences.getString("mode", "vpn")) && VpnService.prepare(context) != null) {
                Intent launch = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(MehmanshahrTileService.EXTRA_CONNECT_FROM_TILE, true);
                context.startActivity(launch);
            } else {
                VpnConnectionController.connect(context, preferences);
            }
        }
        update(context);
    }

    private static boolean isTrustedToggle(Context context, Intent intent) {
        String expected = context.getSharedPreferences("service_state", Context.MODE_PRIVATE).getString(KEY_TOGGLE_TOKEN, "");
        String presented = intent.getStringExtra(EXTRA_TOGGLE_TOKEN);
        return expected != null && !expected.isEmpty() && expected.equals(presented);
    }

    private static String toggleToken(Context context) {
        SharedPreferences store = context.getSharedPreferences("service_state", Context.MODE_PRIVATE);
        String token = store.getString(KEY_TOGGLE_TOKEN, "");
        if (token != null && !token.isEmpty()) return token;
        byte[] material = new byte[32];
        new SecureRandom().nextBytes(material);
        StringBuilder builder = new StringBuilder(material.length * 2);
        for (byte value : material) builder.append(String.format(java.util.Locale.US, "%02x", value));
        token = builder.toString();
        store.edit().putString(KEY_TOGGLE_TOKEN, token).commit();
        return token;
    }

    static void update(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName name = new ComponentName(context, MehmanshahrWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(name);
        if (ids.length == 0) return;
        String state = context.getSharedPreferences("service_state", Context.MODE_PRIVATE).getString("state", "disconnected");
        boolean active = "connected".equals(state);
        boolean transitioning = "starting".equals(state) || "smart-testing".equals(state) || "scanning".equals(state) || "securing".equals(state) || "reconnecting".equals(state) || "disconnecting".equals(state);
        android.content.SharedPreferences serviceState = context.getSharedPreferences("service_state", Context.MODE_PRIVATE);
        long ping = serviceState.getLong("ping", -1L);
        long rx = serviceState.getLong("rx", 0L);
        long tx = serviceState.getLong("tx", 0L);
        String endpoint = serviceState.getString("endpoint", "");
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_status);
        views.setImageViewResource(R.id.widget_logo, R.drawable.ic_app_mono);
        views.setTextViewText(R.id.widget_name, context.getString(R.string.app_name));
        views.setTextViewText(R.id.widget_download, active ? formatBytes(rx) : context.getString(R.string.metric_unavailable));
        views.setTextViewText(R.id.widget_ping, active && ping >= 0 ? context.getString(R.string.ping_millis, Long.toString(ping)) : context.getString(R.string.metric_unavailable));
        views.setTextViewText(R.id.widget_upload, active ? formatBytes(tx) : context.getString(R.string.metric_unavailable));
        views.setTextViewText(R.id.widget_location, active && endpoint != null && !endpoint.isEmpty()
                ? endpoint : context.getString(R.string.widget_location_unavailable));
        views.setImageViewResource(R.id.widget_status_dot, active ? R.drawable.widget_status_dot_connected : R.drawable.widget_status_dot_disconnected);
        views.setTextViewText(R.id.widget_action, context.getString(active ? R.string.widget_disconnect : "disconnecting".equals(state) ? R.string.widget_disconnecting : transitioning ? R.string.widget_connecting : R.string.widget_connect));
        views.setViewVisibility(R.id.widget_action, View.VISIBLE);
        views.setBoolean(R.id.widget_action, "setEnabled", !transitioning);
        views.setTextViewText(R.id.widget_status, context.getString(active ? R.string.widget_active : "disconnecting".equals(state) ? R.string.widget_disconnecting : transitioning ? R.string.widget_connecting : R.string.widget_disconnected));
        Intent launch = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Intent toggle = new Intent(context, MehmanshahrWidgetProvider.class).setAction(ACTION_WIDGET_TOGGLE).putExtra(EXTRA_TOGGLE_TOKEN, toggleToken(context));
        views.setOnClickPendingIntent(R.id.widget_action, PendingIntent.getBroadcast(context, 7202, toggle, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 7201, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        for (int id : ids) manager.updateAppWidget(id, views);
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) { update(context); }
    @Override public void onEnabled(Context context) { update(context); }
    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int appWidgetId, android.os.Bundle newOptions) { update(context); }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
