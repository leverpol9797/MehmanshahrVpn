package com.firstham.aethergui;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.app.StatusBarManager;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import android.widget.Toast;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.firstham.aethergui.databinding.ActivityMainBinding;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import org.json.JSONObject;

public final class MainActivity extends AppCompatActivity {
    private static final int VPN_REQUEST = 41;
    private static final int NOTIFICATION_REQUEST = 42;
    private static final int APPS_REQUEST = 43;
    private static final int EXPORT_REQUEST = 44;
    private static final int IMPORT_REQUEST = 45;
    private static final String INTERNAL_PERMISSION = "com.mehmanshahr.vpn.permission.INTERNAL";
    /** Taps closer together than this are swallowed so a burst cannot restart the tunnel. */
    private static final long CONNECT_DEBOUNCE_MS = 900L;
    /** How long the UI may show "Checking" before it gives up on the service answering. */
    private static final long STATE_RESOLVE_TIMEOUT_MS = 2_500L;
    private ActivityMainBinding binding;
    private SharedPreferences preferences;
    private String state = "disconnected";
    private String page = "connect";
    private boolean receiverRegistered;
    private boolean autoConnectPending;
    private String endpoint = "";
    private String selectedProtocol = "";
    private boolean smartSelected;
    private long lastConnectActionAt;
    private final Handler updateHandler = new Handler(Looper.getMainLooper());
    private final Runnable stateResolveTimeout = new Runnable() {
        @Override public void run() {
            if (binding == null || !"checking".equals(state)) return;
            renderState("disconnected", getString(R.string.status_ready_message));
        }
    };
    private final Runnable connectButtonRelease = new Runnable() {
        @Override public void run() {
            if (binding == null) return;
            binding.connectButton.setEnabled(!"disconnecting".equals(state) && !"checking".equals(state));
        }
    };
    private final Runnable updateProgressPoll = new Runnable() {
        @Override public void run() {
            if (binding == null) return;
            renderUpdateState();
            if ("downloading".equals(getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE).getString("status", ""))) updateHandler.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AetherVpnService.ACTION_STATUS.equals(intent.getAction())) {
                endpoint = safe(intent.getStringExtra("endpoint"));
                selectedProtocol = safe(intent.getStringExtra("selectedProtocol"));
                smartSelected = intent.getBooleanExtra("smartSelected", false);
                renderState(intent.getStringExtra("state"), intent.getStringExtra("message"));
                resolveAutoConnectAtStart();
            }
            else if (AetherVpnService.ACTION_STATS.equals(intent.getAction())) renderStats(intent);
            else if (UpdateConfig.ACTION_STATE.equals(intent.getAction())) renderUpdateState();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        preferences = getSharedPreferences("aether", MODE_PRIVATE);
        migrateLegacySmartSelection();
        String language = normalizedLanguage(preferences.getString("language", "en"));
        preferences.edit().putString("language", language).apply();
        applyLanguage(language, false);
        AppCompatDelegate.setDefaultNightMode(themeMode(preferences.getInt("theme", 0)));
        super.onCreate(savedInstanceState);
        if (!language.equals(activeResourceLanguage())) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language));
            recreate();
            return;
        }
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applyLayoutDirection(language);
        binding.getRoot().setAlpha(0f);
        binding.getRoot().setTranslationY(18f);
        binding.getRoot().post(() -> binding.getRoot().animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(new android.view.animation.DecelerateInterpolator()).start());
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        int toolbarHeight = binding.toolbar.getLayoutParams().height;
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            binding.mainContent.setPadding(bars.left, 0, bars.right, 0);
            binding.toolbar.setPadding(binding.toolbar.getPaddingLeft(), bars.top, binding.toolbar.getPaddingRight(), binding.toolbar.getPaddingBottom());
            android.view.ViewGroup.LayoutParams params = binding.toolbar.getLayoutParams();
            params.height = toolbarHeight + bars.top;
            binding.toolbar.setLayoutParams(params);
            binding.pageContainer.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });
        setupDropdowns();
        restoreSettings();
        setupNavigation();
        binding.toolbar.setTitle("");
        binding.toolbarTitle.setText(R.string.app_name);
        setupActions();
        requestNotificationPermission();
        AppUpdateManager.initialize(this);
        AppUpdateManager.checkNow(this, new AppUpdateManager.Listener() {
            @Override public void onComplete() { renderUpdateState(); }
            @Override public void onError(Throwable error) { renderUpdateState(); }
        });
        binding.statusVersion.setText(getString(R.string.version_format, BuildConfig.VERSION_NAME));
        binding.currentVersionValue.setText(BuildConfig.VERSION_NAME);
        binding.autoDownloadSwitch.setChecked(getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE).getBoolean(UpdateConfig.KEY_AUTO_DOWNLOAD, false));
        renderUpdateState();
        renderState(initialState(), getString(R.string.status_ready_message));
        autoConnectPending = savedInstanceState == null && preferences.getBoolean("autoConnectAtStart", false);
        if (getIntent().getBooleanExtra(MehmanshahrTileService.EXTRA_CONNECT_FROM_TILE, false)) {
            autoConnectPending = false;
            getIntent().removeExtra(MehmanshahrTileService.EXTRA_CONNECT_FROM_TILE);
            binding.root.post(this::connect);
        }
    }

    private void setupDropdowns() {
        setAdapter(binding.protocolInput, R.array.protocol_labels);
        setAdapter(binding.scanInput, R.array.scan_labels);
        setAdapter(binding.transportInput, R.array.transport_labels);
        setAdapter(binding.ipInput, R.array.ip_labels);
        setAdapter(binding.obfuscationInput, R.array.obfuscation_labels);
        setAdapter(binding.themeInput, R.array.theme_labels);
        setAdapter(binding.languageInput, R.array.language_labels);
        binding.protocolInput.setOnItemClickListener((p, v, position, id) -> { binding.protocolInput.setTag(position); updateModeUi(); saveSettings(); });
        binding.scanInput.setOnItemClickListener((p, v, position, id) -> { binding.scanInput.setTag(position); saveSettings(); });
        binding.transportInput.setOnItemClickListener((p, v, position, id) -> { binding.transportInput.setTag(position); saveSettings(); });
        binding.ipInput.setOnItemClickListener((p, v, position, id) -> { binding.ipInput.setTag(position); saveSettings(); });
        binding.obfuscationInput.setOnItemClickListener((p, v, position, id) -> { binding.obfuscationInput.setTag(position); saveSettings(); });
        binding.themeInput.setOnItemClickListener((p, v, position, id) -> { binding.themeInput.setTag(position); saveSettings(); applyTheme(position); });
        binding.languageInput.setOnItemClickListener((p, v, position, id) -> { preferences.edit().putString("language", position == 1 ? "fa" : "en").apply(); applyLanguage(position == 1 ? "fa" : "en", true); });
    }

    private void setAdapter(MaterialAutoCompleteTextView view, int arrayId) {
        view.setAdapter(new DropdownAdapter(this, getResources().getStringArray(arrayId)));
    }

    private static final class DropdownAdapter extends ArrayAdapter<String> {
        private final List<String> options;
        private final Filter fullListFilter = new Filter() {
            @Override protected FilterResults performFiltering(CharSequence constraint) {
                FilterResults results = new FilterResults();
                results.values = new ArrayList<>(options);
                results.count = options.size();
                return results;
            }

            @Override protected void publishResults(CharSequence constraint, FilterResults results) {
                clear();
                if (results.values instanceof List<?>) {
                    for (Object value : (List<?>) results.values) add(String.valueOf(value));
                }
                notifyDataSetChanged();
            }
        };

        DropdownAdapter(Context context, String[] values) {
            super(context, R.layout.item_dropdown, new ArrayList<>(java.util.Arrays.asList(values)));
            options = Collections.unmodifiableList(new ArrayList<>(java.util.Arrays.asList(values)));
        }

        @Override public Filter getFilter() { return fullListFilter; }
    }

    private void setupNavigation() {
        binding.toolbar.setNavigationOnClickListener(v -> binding.root.openDrawer(GravityCompat.START));
        binding.toolbar.setOnMenuItemClickListener(item -> false);
        binding.navigationView.setNavigationItemSelectedListener(item -> { selectPage(item); binding.root.closeDrawer(GravityCompat.START); return true; });
        binding.navigationView.setCheckedItem(R.id.nav_connect);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (binding.root.isDrawerOpen(GravityCompat.START)) {
                    binding.root.closeDrawer(GravityCompat.START);
                } else if (!"connect".equals(page)) {
                    showPage("connect");
                }
            }
        });
    }

    private void selectPage(MenuItem item) {
        int id = item.getItemId();
        showPage(id == R.id.nav_configurations ? "configurations" : id == R.id.nav_settings ? "settings" : id == R.id.nav_about ? "about" : "connect");
    }

    private void showPage(String destination) {
        page = destination;
        binding.homePage.setVisibility("connect".equals(page) ? View.VISIBLE : View.GONE);
        binding.configurationsPage.setVisibility("configurations".equals(page) ? View.VISIBLE : View.GONE);
        binding.settingsPage.setVisibility("settings".equals(page) ? View.VISIBLE : View.GONE);
        binding.aboutPage.setVisibility("about".equals(page) ? View.VISIBLE : View.GONE);
        int checked = "configurations".equals(page) ? R.id.nav_configurations : "settings".equals(page) ? R.id.nav_settings : "about".equals(page) ? R.id.nav_about : R.id.nav_connect;
        binding.navigationView.setCheckedItem(checked);
        int title = "configurations".equals(page) ? R.string.configurations_title : "settings".equals(page) ? R.string.settings_title : "about".equals(page) ? R.string.about : R.string.app_name;
        binding.toolbar.setTitle("");
        binding.toolbarTitle.setText(title);
        View visible = "configurations".equals(page) ? binding.configurationsPage : "settings".equals(page) ? binding.settingsPage : "about".equals(page) ? binding.aboutPage : binding.homePage;
        if (ValueAnimator.areAnimatorsEnabled()) {
            visible.setAlpha(0f);
            visible.setTranslationY(12f);
            visible.animate().alpha(1f).translationY(0f).setDuration(220).start();
        }
    }

    private void setupActions() {
        binding.connectButton.setOnClickListener(this::onConnectTapped);
         binding.modeGroup.addOnButtonCheckedListener((group, checkedId, checked) -> { if (!checked) return; preferences.edit().putString("mode", checkedId == R.id.proxy_mode_button ? "manual" : "vpn").apply(); updateModeUi(); });
        binding.splitSwitch.setOnCheckedChangeListener((button, checked) -> { binding.splitContainer.setVisibility(checked ? View.VISIBLE : View.GONE); saveSettings(); });
        binding.routingGroup.setOnCheckedChangeListener((group, checkedId) -> { saveSettings(); updateSelectedCount(); });
        binding.chooseAppsButton.setOnClickListener(v -> openAppSelection());
        binding.advancedToggle.setOnClickListener(v -> { boolean show = binding.advancedContainer.getVisibility() != View.VISIBLE; binding.advancedContainer.setVisibility(show ? View.VISIBLE : View.GONE); binding.advancedToggle.setText(show ? R.string.hide_advanced : R.string.show_advanced); });
        binding.resetButton.setOnClickListener(v -> resetDefaults());
        binding.checkUpdatesButton.setOnClickListener(v -> checkForUpdates());
        binding.downloadUpdateButton.setOnClickListener(v -> { String status = getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE).getString("status", ""); if ("ready_install".equals(status)) sendBroadcast(new Intent(this, AppUpdateReceiver.class).setAction(UpdateConfig.ACTION_INSTALL)); else Toast.makeText(this, AppUpdateManager.startDownload(this, false) ? R.string.update_download_started : R.string.update_download_failed, Toast.LENGTH_SHORT).show(); });
        binding.autoDownloadSwitch.setOnCheckedChangeListener((button, checked) -> { getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE).edit().putBoolean(UpdateConfig.KEY_AUTO_DOWNLOAD, checked).apply(); AppUpdateManager.setAutomaticChecks(this, checked); if (checked) checkForUpdates(); });
        binding.autoConnectSwitch.setOnCheckedChangeListener((button, checked) -> preferences.edit().putBoolean("autoConnectAtStart", checked).apply());
        binding.mtuModeGroup.addOnButtonCheckedListener((group, checkedId, checked) -> {
            if (!checked) return;
            boolean automatic = checkedId == R.id.mtu_automatic_button;
            binding.mtuLayout.setVisibility(automatic ? View.GONE : View.VISIBLE);
            binding.mtuSummary.setText(automatic ? R.string.mtu_automatic_summary : R.string.mtu_manual_summary);
            saveSettings();
        });
        binding.lanSwitch.setOnCheckedChangeListener((button, checked) -> { preferences.edit().putBoolean("lanEnabled", checked).apply(); saveSettings(); if ("connected".equals(state)) startService(new Intent(this, AetherVpnService.class).setAction(AetherVpnService.ACTION_SET_LAN).putExtra("enabled", checked).putExtra("port", 18190)); updateLanLabel(); });
        binding.copyLanAddressButton.setOnClickListener(v -> copyLanValue(false));
        binding.copyLanPortButton.setOnClickListener(v -> copyLanValue(true));
        binding.exportSettingsButton.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").putExtra(Intent.EXTRA_TITLE, "mehmanshahrvpn-settings-backup.json"), EXPORT_REQUEST));
        binding.importSettingsButton.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE), IMPORT_REQUEST));
        binding.notificationSettingsButton.setOnClickListener(v -> openNotificationSettings());
        binding.addTileButton.setOnClickListener(v -> requestQuickSettingsTile());
        // Telegram card removed
    }

    private void restoreSettings() {
        migrateLegacySmartSelection();
        String mode = preferences.getString("mode", "vpn");
        binding.modeGroup.check("manual".equals(mode) ? R.id.proxy_mode_button : R.id.vpn_mode_button);
        setSelection(binding.protocolInput, "protocol", ConnectionDefaults.PROTOCOL_INDEX, R.array.protocol_labels);
        setSelection(binding.scanInput, "scan", ConnectionDefaults.SCAN_INDEX, R.array.scan_labels);
        setSelection(binding.transportInput, "transport", ConnectionDefaults.TRANSPORT_INDEX, R.array.transport_labels);
        setSelection(binding.ipInput, "ip", 0, R.array.ip_labels);
        setSelection(binding.obfuscationInput, "obfuscation", ConnectionDefaults.OBFUSCATION_INDEX, R.array.obfuscation_labels);
        setSelection(binding.themeInput, "theme", 0, R.array.theme_labels);
        setSelection(binding.languageInput, "fa".equals(preferences.getString("language", "en")) ? 1 : 0, R.array.language_labels);
        binding.socksInput.setText(preferences.getString("socks", getString(R.string.default_socks_address)));
        binding.peerInput.setText(preferences.getString("peer", "")); binding.mtuInput.setText(preferences.getString("mtu", getString(R.string.default_mtu)));
        boolean automaticMtu = "automatic".equals(VpnConnectionController.normalizedMtuMode(preferences.getString("mtuMode", ConnectionDefaults.MTU_MODE)));
        binding.mtuModeGroup.check(automaticMtu ? R.id.mtu_automatic_button : R.id.mtu_manual_button);
        binding.mtuLayout.setVisibility(automaticMtu ? View.GONE : View.VISIBLE);
        binding.mtuSummary.setText(automaticMtu ? R.string.mtu_automatic_summary : R.string.mtu_manual_summary);
        binding.dnsSwitch.setChecked(preferences.getBoolean("dnsLeak", true)); binding.killswitchSwitch.setChecked(preferences.getBoolean("killSwitch", false)); binding.reconnectSwitch.setChecked(preferences.getBoolean("quickReconnect", true)); binding.autoConnectSwitch.setChecked(preferences.getBoolean("autoConnectAtStart", false)); binding.lanSwitch.setChecked(preferences.getBoolean("lanEnabled", false));
        boolean split = preferences.getInt("routing", 0) >= 2; binding.splitSwitch.setChecked(split); binding.splitContainer.setVisibility(split ? View.VISIBLE : View.GONE); binding.routingGroup.check(preferences.getInt("routing", 2) == 3 ? R.id.exclude_apps_radio : R.id.include_apps_radio); updateModeUi(); updateSelectedCount();
    }

    private void setSelection(MaterialAutoCompleteTextView view, String key, int fallback, int arrayId) { setSelection(view, preferences.getInt(key, fallback), arrayId); }
    private void setSelection(MaterialAutoCompleteTextView view, int index, int arrayId) { String[] values = getResources().getStringArray(arrayId); index = Math.max(0, Math.min(values.length - 1, index)); view.setText(values[index], false); view.setTag(index); }

    private void updateModeUi() {
        String mode = preferences.getString("mode", "vpn");
        boolean smart = selectedIndex(binding.protocolInput) == 3;
        binding.modeSummary.setText(smart ? R.string.smart_mode_summary : R.string.status_ready_message);
        // SOCKS5 proxy mode uses the same core configuration as Device VPN mode. Keep Protocol
        // and Scan Mode visible so users can choose gool/WARP-in-WARP and Balanced without losing
        // their selections when switching modes. MASQUE transport remains device-VPN specific.
        binding.protocolLayout.setVisibility(View.VISIBLE);
        binding.scanLayout.setVisibility(View.VISIBLE);
        binding.transportLayout.setVisibility("manual".equals(mode) || smart || selectedIndex(binding.protocolInput) != 0 ? View.GONE : View.VISIBLE);
    }

    private void migrateLegacySmartSelection() {
        if (!"smart".equals(preferences.getString("mode", "vpn"))) return;
        preferences.edit().putString("mode", "vpn").putInt("protocol", 3).apply();
    }

    /**
     * Every tap used to reach the service, and each ACTION_START tore the tunnel down and rebuilt
     * it. The control is now latched for the debounce window while the service commits, and the
     * service itself ignores a start that matches the running configuration.
     */
    private void onConnectTapped(View view) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        long now = SystemClock.elapsedRealtime();
        if (now - lastConnectActionAt < CONNECT_DEBOUNCE_MS) return;
        lastConnectActionAt = now;
        binding.connectButton.setEnabled(false);
        updateHandler.removeCallbacks(connectButtonRelease);
        updateHandler.postDelayed(connectButtonRelease, CONNECT_DEBOUNCE_MS);
        if (shouldDisconnect()) disconnect(); else connect();
    }

    private void connect() {
        if (!validSocks(text(binding.socksInput))) { binding.socksInput.setError(getString(R.string.invalid_socks)); return; }
        if (!"automatic".equals(VpnConnectionController.normalizedMtuMode(preferences.getString("mtuMode", ConnectionDefaults.MTU_MODE))) && !validMtu(text(binding.mtuInput))) { binding.mtuInput.setError(getString(R.string.invalid_mtu)); return; }
        if (binding.splitSwitch.isChecked() && selectedPackages().isEmpty() && binding.routingGroup.getCheckedRadioButtonId() == R.id.include_apps_radio) { Toast.makeText(this, R.string.split_include_empty, Toast.LENGTH_LONG).show(); return; }
        saveSettings();
        if (!"manual".equals(preferences.getString("mode", "vpn"))) { Intent permission = VpnService.prepare(this); if (permission != null) { startActivityForResult(permission, VPN_REQUEST); return; } }
        VpnConnectionController.connect(this, preferences);
    }

    private void disconnect() { VpnConnectionController.disconnect(this); }

    private void resolveAutoConnectAtStart() {
        if (!autoConnectPending || "checking".equals(state)) return;
        autoConnectPending = false;
        if (VpnConnectionController.shouldAutoConnect(preferences.getBoolean("autoConnectAtStart", false), state)) {
            binding.root.post(this::connect);
        }
    }

    private void openAppSelection() {
        String key = binding.routingGroup.getCheckedRadioButtonId() == R.id.exclude_apps_radio ? "splitExcludeApps" : "splitIncludeApps";
        startActivityForResult(new Intent(this, AppSelectionActivity.class).putExtra(AppSelectionActivity.EXTRA_PACKAGES, preferences.getString(key, "")), APPS_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) { super.onActivityResult(requestCode, resultCode, data); if (requestCode == VPN_REQUEST) { if (resultCode == RESULT_OK) VpnConnectionController.connect(this, preferences); else Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show(); } else if (requestCode == APPS_REQUEST) { if (data != null && data.getBooleanExtra(AppSelectionActivity.EXTRA_RETURN_HOME, false)) showPage("connect"); else if (resultCode == RESULT_OK && data != null) { String key = binding.routingGroup.getCheckedRadioButtonId() == R.id.exclude_apps_radio ? "splitExcludeApps" : "splitIncludeApps"; preferences.edit().putString(key, data.getStringExtra(AppSelectionActivity.EXTRA_PACKAGES)).apply(); updateSelectedCount(); saveSettings(); } } else if (resultCode == RESULT_OK && data != null && requestCode == EXPORT_REQUEST) handleBackup(requestCode, data.getData()); else if (resultCode == RESULT_OK && data != null && requestCode == IMPORT_REQUEST) new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.restore_settings).setMessage(R.string.restore_confirmation).setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.restore_settings, (dialog, which) -> handleBackup(requestCode, data.getData())).show(); }

    private void handleBackup(int requestCode, Uri uri) {
        try {
            if (requestCode == EXPORT_REQUEST) {
                JSONObject out = new JSONObject(); out.put("format", "MehmanshahrVpn Backup v1").put("schema", 1).put("backupVersion", 1);
        for (String key : new String[]{"mode","protocol","scan","transport","ip","obfuscation","theme","language","routing","splitIncludeApps","splitExcludeApps","splitApps","splitEnabled","dnsLeak","killSwitch","quickReconnect","autoConnectAtStart","mtuMode","mtu","lanEnabled","lanPort"}) { Object value = preferences.getAll().get(key); if (value != null) out.put(key, value); }
                out.put("splitEnabled", preferences.getInt("routing", 0) >= 2);
                out.put("automaticUpdates", getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE).getBoolean(UpdateConfig.KEY_AUTO_DOWNLOAD, false));
                try (OutputStream stream = getContentResolver().openOutputStream(uri)) { if (stream == null) throw new IllegalStateException(); stream.write(out.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                Toast.makeText(this, R.string.backup_exported, Toast.LENGTH_SHORT).show();
            } else {
                StringBuilder json = new StringBuilder(); try (BufferedReader reader = new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(uri), java.nio.charset.StandardCharsets.UTF_8))) { String line; while ((line = reader.readLine()) != null) json.append(line); }
                JSONObject input = new JSONObject(json.toString());
                if (input.optInt("schema", input.optInt("backupVersion", -1)) != 1) throw new IllegalArgumentException("Unsupported backup schema");
                SharedPreferences.Editor edit = preferences.edit();
                Set<String> allowed = new LinkedHashSet<>(java.util.Arrays.asList("mode","protocol","scan","transport","ip","obfuscation","theme","language","routing","splitIncludeApps","splitExcludeApps","splitApps","splitEnabled","dnsLeak","killSwitch","quickReconnect","autoConnectAtStart","mtuMode","mtu","lanEnabled","lanPort"));
                org.json.JSONArray names = input.names();
                Set<String> booleans = new LinkedHashSet<>(java.util.Arrays.asList("dnsLeak","killSwitch","quickReconnect","autoConnectAtStart","lanEnabled","splitEnabled"));
                Set<String> integers = new LinkedHashSet<>(java.util.Arrays.asList("protocol","scan","transport","ip","obfuscation","theme","routing","lanPort"));
                for (int i = 0; names != null && i < names.length(); i++) { String key = names.getString(i); if (!allowed.contains(key)) continue; Object value = input.get(key); if (booleans.contains(key)) edit.putBoolean(key, input.getBoolean(key)); else if (integers.contains(key)) edit.putInt(key, input.getInt(key)); else if (value instanceof String) edit.putString(key, (String)value); }
                if (input.has("splitEnabled") && !input.optBoolean("splitEnabled", false)) edit.putInt("routing", 0);
                edit.apply();
                SharedPreferences updatePreferences = getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE);
                if (input.has("automaticUpdates")) {
                    boolean automatic = input.getBoolean("automaticUpdates");
                    updatePreferences.edit().putBoolean(UpdateConfig.KEY_AUTO_DOWNLOAD, automatic).apply();
                    AppUpdateManager.setAutomaticChecks(this, automatic);
                    binding.autoDownloadSwitch.setChecked(automatic);
                }
                restoreSettings(); applyTheme(preferences.getInt("theme", 0)); applyLanguage(preferences.getString("language", "en"), true); Toast.makeText(this, R.string.backup_restored, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception error) { Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_LONG).show(); }
    }

    /**
     * The service owns the real connection state and only answers ACTION_QUERY asynchronously from
     * onStart(). Rendering a hard "Disconnected" first reported a live tunnel as down, so the last
     * published state is replayed instead and anything still unresolved shows as "Checking".
     */
    private String initialState() {
        SharedPreferences service = getSharedPreferences("service_state", MODE_PRIVATE);
        String saved = service.getString("state", "");
        if (saved != null && ("connected".equals(saved) || "error".equals(saved) || "blocked".equals(saved)
                || AetherVpnService.isConnectingState(saved))) {
            return saved;
        }
        return service.getBoolean("desiredConnected", false) ? "checking" : "disconnected";
    }

    private void renderState(String newState, String message) {
        if (binding == null) return;
        state = newState == null ? "disconnected" : newState;
        boolean connected = "connected".equals(state);
        boolean checking = "checking".equals(state);
        boolean transitioning = "starting".equals(state) || "smart-testing".equals(state) || "scanning".equals(state) || "securing".equals(state) || "reconnecting".equals(state) || "disconnecting".equals(state);
        updateHandler.removeCallbacks(stateResolveTimeout);
        if (checking) updateHandler.postDelayed(stateResolveTimeout, STATE_RESOLVE_TIMEOUT_MS);
        binding.connectButton.setEnabled(!"disconnecting".equals(state) && !checking
                && SystemClock.elapsedRealtime() - lastConnectActionAt >= CONNECT_DEBOUNCE_MS);
        String orbLabel = connected ? getString(R.string.disconnect) : checking ? getString(R.string.status_checking) : transitioning ? ("disconnecting".equals(state) ? getString(R.string.disconnecting) : getString(R.string.connecting)) : getString(R.string.connect);
        binding.connectButton.setConnectionState(state, orbLabel);
        binding.connectButton.setContentDescription(orbLabel);
        if (connected && smartSelected && !selectedProtocol.isEmpty()) binding.connectionStatus.setText(getString(R.string.status_connected_via_smart, protocolLabel(selectedProtocol)));
        else binding.connectionStatus.setText(connected ? R.string.status_connected : checking ? R.string.status_checking : transitioning ? ("disconnecting".equals(state) ? R.string.status_disconnecting : R.string.status_connecting) : ("error".equals(state) || "blocked".equals(state) ? R.string.status_error : R.string.status_disconnected));
        binding.statusDot.setBackgroundResource(connected ? R.drawable.status_dot_connected : transitioning || checking ? R.drawable.status_dot_connecting : R.drawable.status_dot);
        binding.progress.setVisibility(View.GONE);
        if (connected) {
            binding.connectionMessage.setVisibility(View.GONE);
            binding.connectionInfo.setVisibility(View.VISIBLE);
            binding.locationValue.setText(endpoint == null || endpoint.isEmpty() ? getString(R.string.connection_location_unavailable) : endpoint);
        }
        else if ((transitioning && !"disconnecting".equals(state)) || checking) { binding.connectionMessage.setVisibility(View.GONE); binding.connectionInfo.setVisibility(View.VISIBLE); }
        else { boolean showError = "error".equals(state) || "blocked".equals(state); binding.connectionMessage.setText(message == null ? getString(R.string.status_error) : message); binding.connectionMessage.setVisibility(showError ? View.VISIBLE : View.GONE); binding.connectionInfo.setVisibility(View.GONE); }
        // "checking" is a UI-only placeholder; persisting it would outlive the resolve and be
        // replayed as a real state on the next launch.
        if (!checking) preferences.edit().putString("state", state).putString("message", message == null ? "" : message).apply();
        if (!connected) resetStats();
        updateLanLabel();
    }

    private void updateLanLabel() {
        if (binding == null) return;
        boolean enabled = preferences.getBoolean("lanEnabled", false);
        SharedPreferences service = getSharedPreferences("service_state", MODE_PRIVATE);
        String address = service.getString("lanAddress", "");
        int port = service.getInt("lanPort", 0);
        boolean active = "connected".equals(state) && !address.isEmpty() && port > 0;
        binding.lanProtocolValue.setVisibility(active ? View.VISIBLE : View.GONE);
        binding.lanCopyActions.setVisibility(active ? View.VISIBLE : View.GONE);
        // The LAN listener now demands SOCKS5 credentials, so the card has to show the session pair
        // or the shared proxy is unusable.
        String user = service.getString("lanUsername", "");
        String secret = service.getString("lanPassword", "");
        binding.lanProtocolValue.setText(user.isEmpty() || secret.isEmpty()
                ? getString(R.string.lan_protocol)
                : getString(R.string.lan_credentials, user, secret));
        if (!enabled) {
            binding.lanAddressValue.setText(R.string.lan_disabled);
        } else if (active) {
            binding.lanAddressValue.setText(getString(R.string.lan_address, address, port));
        } else {
            binding.lanAddressValue.setText(R.string.lan_pending);
        }
    }

    private void copyLanValue(boolean portOnly) {
        SharedPreferences service = getSharedPreferences("service_state", MODE_PRIVATE);
        String address = service.getString("lanAddress", "");
        int port = service.getInt("lanPort", 0);
        if (address.isEmpty() || port <= 0 || !"connected".equals(state)) return;
        String user = service.getString("lanUsername", "");
        String secret = service.getString("lanPassword", "");
        // Copying the bare address is no longer enough to configure a client, so the address action
        // yields a complete authenticated socks5:// URI instead.
        String value = portOnly ? String.valueOf(port)
                : user.isEmpty() || secret.isEmpty() ? address
                : "socks5://" + user + ":" + secret + "@" + address + ":" + port;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(portOnly ? "LAN port" : "LAN address", value));
        Toast.makeText(this, portOnly ? R.string.copy_port : R.string.copy_address, Toast.LENGTH_SHORT).show();
    }

    private boolean shouldDisconnect() { return "connected".equals(state) || "starting".equals(state) || "smart-testing".equals(state) || "scanning".equals(state) || "securing".equals(state) || "reconnecting".equals(state) || "disconnecting".equals(state); }

    private void renderStats(Intent intent) {
        if (binding == null) return;
        long tx = Math.max(0, intent.getLongExtra("tx", 0));
        long rx = Math.max(0, intent.getLongExtra("rx", 0));
        animateMetric(binding.uploadValue, formatTraffic(tx));
        animateMetric(binding.downloadValue, formatTraffic(rx));
        long ping = intent.getLongExtra("ping", -1);
        binding.pingValue.setText(getString(R.string.ping_value, ping >= 0 ? getString(R.string.ping_millis, Long.toString(ping)) : getString(R.string.metric_unavailable)));
    }

    private void animateMetric(TextView view, String value) {
        if (value.equals(view.getTag())) return;
        view.setTag(value);
        view.animate().cancel();
        view.setAlpha(0.45f);
        view.setScaleX(.96f);
        view.setScaleY(.96f);
        view.setText(value);
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start();
    }

    private String formatTraffic(long bytes) {
        if (bytes < 1024L * 1024L) return getString(R.string.traffic_kilobytes, bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return getString(R.string.traffic_megabytes, bytes / (1024.0 * 1024.0));
        return getString(R.string.traffic_gigabytes, bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private void resetStats() {
        binding.uploadValue.setText(R.string.metric_unavailable);
        binding.downloadValue.setText(R.string.metric_unavailable);
        binding.pingValue.setText(getString(R.string.ping_value, getString(R.string.metric_unavailable)));
        binding.locationValue.setText(R.string.connection_location_unavailable);
    }

    private void saveSettings() {
        int routing = binding.splitSwitch.isChecked() ? (binding.routingGroup.getCheckedRadioButtonId() == R.id.exclude_apps_radio ? 3 : 2) : 0;
        String include = preferences.getString("splitIncludeApps", ""); String exclude = preferences.getString("splitExcludeApps", "");
        String mtuMode = binding.mtuModeGroup.getCheckedButtonId() == R.id.mtu_automatic_button ? "automatic" : "manual";
        String mtu = text(binding.mtuInput);
        if (!validMtu(mtu)) mtu = Integer.toString(VpnConnectionController.parseMtu(mtu));
        preferences.edit().putInt("protocol", selectedIndex(binding.protocolInput)).putInt("scan", selectedIndex(binding.scanInput)).putInt("transport", selectedIndex(binding.transportInput)).putInt("ip", selectedIndex(binding.ipInput)).putInt("obfuscation", selectedIndex(binding.obfuscationInput)).putInt("theme", selectedIndex(binding.themeInput)).putInt("routing", routing).putString("splitApps", routing == 3 ? exclude : include).putString("socks", text(binding.socksInput)).putString("peer", text(binding.peerInput)).putString("mtuMode", mtuMode).putString("mtu", mtu).putBoolean("dnsLeak", binding.dnsSwitch.isChecked()).putBoolean("killSwitch", binding.killswitchSwitch.isChecked()).putBoolean("quickReconnect", binding.reconnectSwitch.isChecked()).putBoolean("autoConnectAtStart", binding.autoConnectSwitch.isChecked()).putBoolean("lanEnabled", binding.lanSwitch.isChecked()).apply();
    }

    private Set<String> selectedPackages() { Set<String> result = new LinkedHashSet<>(); String key = binding.routingGroup.getCheckedRadioButtonId() == R.id.exclude_apps_radio ? "splitExcludeApps" : "splitIncludeApps"; AppSelectionActivity.parsePackages(preferences.getString(key, ""), result); return result; }
    private void updateSelectedCount() { if (binding == null) return; binding.selectedAppsCount.setText(getResources().getQuantityString(R.plurals.app_picker_selected_count, selectedPackages().size(), selectedPackages().size())); }
    private void resetDefaults() { String language = preferences.getString("language", "en"); preferences.edit().clear().putString("language", language).putInt("theme", 0).apply(); restoreSettings(); saveSettings(); applyTheme(0); }

    private void checkForUpdates() { SharedPreferences updates = getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE); updates.edit().putString("status", "checking").apply(); renderUpdateState(); binding.checkUpdatesButton.setEnabled(false); AppUpdateManager.checkNow(this, new AppUpdateManager.Listener() { @Override public void onComplete() { binding.checkUpdatesButton.setEnabled(true); renderUpdateState(); } @Override public void onError(Throwable error) { binding.checkUpdatesButton.setEnabled(true); renderUpdateState(); String detail = error == null ? "" : error.getMessage(); Toast.makeText(MainActivity.this, detail == null || detail.isEmpty() ? getString(R.string.update_failed) : getString(R.string.update_failed) + ": " + detail, Toast.LENGTH_LONG).show(); } }, true); }
    private void renderUpdateState() { if (binding == null) return; SharedPreferences updates = getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE); String latest = updates.getString(UpdateConfig.KEY_LATEST_VERSION, ""); String status = updates.getString("status", ""); binding.latestVersionValue.setText(latest.isEmpty() ? getString(R.string.not_checked) : latest); int id = "up_to_date".equals(status) ? R.string.update_up_to_date : "available".equals(status) ? R.string.update_available : "downloading".equals(status) ? R.string.update_downloading : "ready_install".equals(status) ? R.string.update_ready_install : "checking".equals(status) ? R.string.update_checking : "download_failed".equals(status) ? R.string.update_download_failed : "verification_failed".equals(status) ? R.string.update_verification_failed : "failed".equals(status) ? R.string.update_failed : R.string.not_checked; binding.updateStatusValue.setText(id); String notes = updates.getString(UpdateConfig.KEY_RELEASE_NOTES, ""); binding.releaseNotesValue.setText(notes); binding.releaseNotesValue.setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE); boolean downloading = "downloading".equals(status); int progress = downloading ? AppUpdateManager.downloadProgress(this) : -1; binding.updateProgress.setVisibility(downloading ? View.VISIBLE : View.GONE); binding.updateProgress.setIndeterminate(downloading && progress <= 0); if (progress > 0) binding.updateProgress.setProgress(progress); boolean action = "available".equals(status) || "download_failed".equals(status) || "verification_failed".equals(status) || "ready_install".equals(status); binding.downloadUpdateButton.setVisibility(action ? View.VISIBLE : View.GONE); binding.downloadUpdateButton.setText("ready_install".equals(status) ? R.string.install_update : R.string.download_update); }

    private void applyTheme(int choice) { preferences.edit().putInt("theme", choice).apply(); AppCompatDelegate.setDefaultNightMode(themeMode(choice)); }
    private static int themeMode(int choice) { return choice == 1 ? AppCompatDelegate.MODE_NIGHT_NO : choice == 2 ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM; }
    private static String normalizedLanguage(String language) { return "fa".equalsIgnoreCase(language) ? "fa" : "en"; }
    private String activeResourceLanguage() {
        if (Build.VERSION.SDK_INT >= 24) return normalizedLanguage(getResources().getConfiguration().getLocales().get(0).getLanguage());
        return normalizedLanguage(getResources().getConfiguration().locale.getLanguage());
    }
    private void applyLayoutDirection(String language) {
        int direction = "fa".equals(normalizedLanguage(language)) ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;
        if (binding != null) binding.getRoot().setLayoutDirection(direction);
        getWindow().getDecorView().setLayoutDirection(direction);
    }
    private void applyLanguage(String language, boolean recreate) {
        String selected = normalizedLanguage(language);
        preferences.edit().putString("language", selected).apply();
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(selected));
        applyLayoutDirection(selected);
        if (recreate) recreate();
    }
    private void requestNotificationPermission() { if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, NOTIFICATION_REQUEST); }
    private void openNotificationSettings() {
        try { startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); }
        catch (Exception ignored) { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
    }
    private void requestQuickSettingsTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            StatusBarManager manager = getSystemService(StatusBarManager.class);
            manager.requestAddTileService(new ComponentName(this, MehmanshahrTileService.class), getString(R.string.tile_name), Icon.createWithResource(this, R.drawable.ic_app_mono), getMainExecutor(), result -> Toast.makeText(this, R.string.tile_add_requested, Toast.LENGTH_SHORT).show());
            return;
        }
        try { startActivity(new Intent("android.settings.QUICK_SETTINGS_SETTINGS")); }
        catch (Exception ignored) { Toast.makeText(this, R.string.tile_add_manual, Toast.LENGTH_LONG).show(); }
    }
    private int selectedIndex(MaterialAutoCompleteTextView view) { Object tag = view.getTag(); return tag instanceof Integer ? (Integer) tag : 0; }
    private String protocolLabel(String protocol) { if ("wg".equals(protocol)) return "WireGuard"; if ("gool".equals(protocol)) return "gool / WARP-in-WARP"; if ("masque".equals(protocol)) return "MASQUE"; return protocol; }
    private static String safe(String value) { return value == null ? "" : value; }
    private String text(com.google.android.material.textfield.TextInputEditText view) { return view.getText() == null ? "" : view.getText().toString().trim(); }
    private boolean validSocks(String value) { int split = value.lastIndexOf(':'); if (split <= 0) return false; try { int port = Integer.parseInt(value.substring(split + 1)); return port > 0 && port <= 65535; } catch (Exception ignored) { return false; } }
    private boolean validMtu(String value) { try { int mtu = Integer.parseInt(value); return mtu >= VpnConnectionController.MIN_MTU && mtu <= VpnConnectionController.MAX_MTU; } catch (Exception ignored) { return false; } }

    @Override protected void onStart() { super.onStart(); if (binding == null) return; if (!receiverRegistered) { IntentFilter filter = new IntentFilter(); filter.addAction(AetherVpnService.ACTION_STATUS); filter.addAction(AetherVpnService.ACTION_STATS); filter.addAction(UpdateConfig.ACTION_STATE); ContextCompat.registerReceiver(this, receiver, filter, INTERNAL_PERMISSION, null, ContextCompat.RECEIVER_NOT_EXPORTED); receiverRegistered = true; } startService(new Intent(this, AetherVpnService.class).setAction(AetherVpnService.ACTION_QUERY)); updateHandler.removeCallbacks(updateProgressPoll); updateHandler.post(updateProgressPoll); }
    @Override protected void onStop() { updateHandler.removeCallbacks(updateProgressPoll); updateHandler.removeCallbacks(stateResolveTimeout); updateHandler.removeCallbacks(connectButtonRelease); if (receiverRegistered) { unregisterReceiver(receiver); receiverRegistered = false; } super.onStop(); }
}
