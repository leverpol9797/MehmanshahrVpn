import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { translations } from '../src/i18n.js';

const read = path => readFile(new URL(path, import.meta.url), 'utf8');

test('Windows UI uses the compact Android-parity navigation and Home layout', async () => {
  const [html, css, config, app, mercury] = await Promise.all([
    read('../src/index.html'), read('../src/styles.css'), read('../src-tauri/tauri.conf.json'), read('../src/app.js'), read('../src/mercury-orb.js')
  ]);
  assert.equal((html.match(/class="nav-item/g) || []).length, 5);
  for (const view of ['connect', 'configurations', 'settings', 'diagnostics', 'about']) assert.match(html, new RegExp(`id="view-${view}"`));
  assert.match(html, /id="diagAetherVersion"/);
  assert.match(app, /d\.aetherVersion/);
  assert.doesNotMatch(html, /large graph/i);
  for (const id of ['connectionOrb', 'uploadTraffic', 'downloadTraffic', 'pingValue', 'locationValue']) assert.match(html, new RegExp(`id="${id}"`));
  assert.ok(html.indexOf('id="downloadTraffic"') < html.indexOf('id="pingValue"'));
  assert.ok(html.indexOf('id="pingValue"') < html.indexOf('id="uploadTraffic"'));
  assert.doesNotMatch(html, /id="exitIpValue"/);
  assert.match(html, /data-i18n="traffic\.location"[^>]*>Location</);
  assert.doesNotMatch(html, /Upload Speed|Download Speed|How should Aethon connect\?/);
  assert.match(css, /\.connection-orb/);
  assert.match(html, /id="mercuryCanvas"/);
  assert.match(app, /createMercuryOrb/);
  assert.match(mercury, /getContext\('webgl2'/);
  assert.match(mercury, /requestAnimationFrame/);
  assert.match(mercury, /webglcontextlost/);
  assert.match(mercury, /prefers-reduced-motion/);
  const tauri = JSON.parse(config);
  assert.equal(tauri.app.windows[0].width, 387);
  assert.equal(tauri.app.windows[0].height, 774);
  assert.equal(tauri.app.windows[0].center, true);
  assert.ok(tauri.bundle.externalBin.includes('binaries/xray'));
  assert.ok(!tauri.bundle.externalBin.includes('binaries/sing-box'));
  assert.ok(tauri.bundle.resources.some(value => value.includes('xray-LICENSE')));
  assert.ok(!tauri.bundle.resources.some(value => value.includes('sing-box-LICENSE')));
});

test('Configurations preserve protocols, Smart Connect, recovery, and split tunneling', async () => {
  const [html, app, settings, routing] = await Promise.all([
    read('../src/index.html'), read('../src/app.js'), read('../src-tauri/src/settings.rs'), read('../src-tauri/src/routing.rs')
  ]);
  for (const id of ['connectionMode', 'protocol', 'scanMode', 'obfuscation', 'transport', 'peer', 'quickReconnect', 'autoConnectAtStart', 'allowRemoteListener', 'lanAddressValue', 'dnsLeakProtection', 'killSwitch', 'splitEnabled', 'splitMode', 'appSearch', 'selectAll', 'clearAll', 'applyApps']) assert.match(html, new RegExp(`id="${id}"`));
  for (const value of ['masque', 'wg', 'gool', 'turbo', 'balanced', 'thorough', 'stealth', 'ironclad', 'firewall', 'gfw', 'aggressive', 'off']) assert.match(app + html, new RegExp(`['"]${value}['"]`));
  assert.match(settings, /\["vpn", "manual"\]/);
  assert.match(settings, /\["smart", "masque", "wg", "gool"\]/);
  assert.match(app + html, /autoConnectAtStart/);
  assert.match(settings, /AETHER_MASQUE_HTTP2/);
  assert.match(routing, /split_applications/);
  assert.match(routing, /split_applications/);
});

test('Psiphon remains preserved for development but is absent from production surfaces', async () => {
  const [html, app, settings, psiphon, config, sidecar] = await Promise.all([
    read('../src/index.html'), read('../src/app.js'), read('../src-tauri/src/settings.rs'), read('../src-tauri/src/psiphon.rs'), read('../src-tauri/tauri.conf.json'), read('../scripts/prepare-sidecar.mjs')
  ]);
  for (const id of ['psiphonControls', 'psiphonChainEnabled', 'psiphonRegion', 'psiphonLocalPort', 'retryPsiphon']) assert.doesNotMatch(html, new RegExp(`id="${id}"`));
  assert.doesNotMatch(app, /psiphon/i);
  assert.match(settings, /psiphon_chain_enabled: bool/);
  assert.match(settings, /psiphon_region: String/);
  assert.match(settings, /psiphon_local_port: u16/);
  assert.match(psiphon, /UpstreamProxyURL/);
  assert.match(psiphon, /ListeningSocksProxyPort/);
  assert.match(psiphon, /verify_proxy_request/);
  const tauri = JSON.parse(config);
  assert.ok(!tauri.bundle.externalBin.some(value => value.toLowerCase().includes('psiphon')));
  assert.ok(!tauri.bundle.resources.some(value => value.toLowerCase().includes('psiphon')));
  assert.doesNotMatch(sidecar, /psiphon/i);
});

test('new Windows users receive the System and Living Mercury defaults', async () => {
  const [app, settings] = await Promise.all([read('../src/app.js'), read('../src-tauri/src/settings.rs')]);
  assert.match(app, /appearance:'system'/);
  assert.match(app, /orbStyle:'living-mercury'/);
  assert.match(settings, /appearance: "system"/);
  assert.match(settings, /orb_style: "living-mercury"/);
  assert.match(app, /scanMode:'balanced'/);
  assert.match(settings, /scan_mode: "balanced"/);
  assert.match(app, /settings=\{\.\.\.defaults,\.\.\.saved\}/);
});

test('Living Mercury is an independent persisted orb style with state-driven fallback', async () => {
  const [html, app, settings, mercury, css] = await Promise.all([
    read('../src/index.html'), read('../src/app.js'), read('../src-tauri/src/settings.rs'), read('../src/mercury-orb.js'), read('../src/styles.css')
  ]);
  assert.match(html, /id="orbStyle"/);
  assert.match(html, /value="classic"/);
  assert.match(html, /value="living-mercury"/);
  assert.match(app, /orbStyle/);
  assert.match(app, /applyOrbStyle/);
  assert.match(app, /mercuryOrb\?\.setState\(next\)/);
  assert.match(app, /mercuryOrb\?\.setTheme/);
  assert.match(settings, /pub orb_style: String/);
  assert.match(settings, /orb_style: "living-mercury"/);
  assert.match(settings, /\["classic", "living-mercury"\]/);
  assert.match(mercury, /stateTargets/);
  assert.match(mercury, /reconnecting:/);
  assert.match(mercury, /setFallback/);
  assert.match(mercury, /canvas\.hidden = true/);
  assert.match(mercury, /lobeDirection/);
  assert.match(mercury, /domain|warp/i);
  assert.match(mercury, /createSphere\(subdivisions = 3\)/);
  assert.match(mercury, /cross\(tangentPoint - displaced, bitangentPoint - displaced\)/);
  assert.match(mercury, /requestAnimationFrame/);
  assert.match(mercury, /document\.hidden/);
  assert.match(mercury, /frameInterval = 1000 \/ 30/);
  assert.ok(html.indexOf('id="mercuryCanvas"') < html.indexOf('id="connectionOrb"'));
  assert.match(html, /id="mercuryVisual" class="mercury-visual"/);
  assert.match(app, /mercuryVisual.*classList\.toggle\('active'/);
  assert.match(css, /\.mercury-visual\{[^}]*top:50%[^}]*width:292px[^}]*height:292px/);
  assert.match(css, /\.connection-orb\.living-mercury\{[^}]*overflow:visible[^}]*clip-path:none/);
  assert.match(css, /\.connection-orb\.living-mercury #orbLabel\{[^}]*font-size:13px/);
  assert.match(css, /\.connection-orb\.living-mercury small\{display:none\}/);
  assert.match(css, /\.mercury-visual::before\{[^}]*inset:27px[^}]*border-radius:24%/);
  assert.match(css, /body\[data-theme=light\] \.mercury-visual\.active::before/);
  assert.match(css, /body\[data-theme=dark\] \.mercury-visual\.active::before/);
  assert.match(css, /body\[data-theme=light\] \.connection-orb\.living-mercury/);
  assert.match(mercury, /uniform float uMacro/);
  assert.match(mercury, /anisotropy/);
  assert.match(mercury, /pinchBand/);
  assert.match(mercury, /bendAxis/);
  assert.match(mercury, /stretchAxis/);
  assert.doesNotMatch(mercury, /macroLobes/);
  assert.match(mercury, /gl\.uniform1f\(uniforms\.uScale, 0\.94\)/);
  assert.match(mercury, /uDark > 0\.5/);
  assert.match(mercury, /uMacro, current\.macro/);
  assert.match(mercury, /function projection\(aspect\).*halfHeight = 1\.72/);
});

test('Translations preserve intentional empty status messages', async () => {
  const i18n = await readFile('src/i18n.js', 'utf8');
  assert.match(i18n, /Object\.hasOwn\(selected,key\)/);
  assert.match(i18n, /'connection\.connected':''/);
});

test('English and Persian translations are complete and RTL-aware', async () => {
  const [html, css, i18n] = await Promise.all([read('../src/index.html'), read('../src/styles.css'), read('../src/i18n.js')]);
  const keys = [...html.matchAll(/data-i18n(?:-placeholder|-tooltip|-aria)?="([^"]+)"/g)].map(match => match[1]);
  assert.ok(keys.length > 35);
  for (const language of ['en', 'fa']) for (const key of new Set(keys)) assert.ok(translations[language][key], `Missing ${language} translation: ${key}`);
  assert.deepEqual(Object.keys(translations), ['en', 'fa']);
  assert.match(html, /id="language"/);
  assert.match(i18n, /document\.documentElement\.dir=language==='fa'\?'rtl':'ltr'/);
  assert.match(css, /\[dir=rtl\]/);
});

test('traffic, exit location, and connection state use existing backend managers', async () => {
  const [app, lib, process, routing] = await Promise.all([
    read('../src/app.js'), read('../src-tauri/src/lib.rs'), read('../src-tauri/src/process.rs'), read('../src-tauri/src/routing.rs')
  ]);
  assert.match(app, /invoke\('traffic_totals'\)/);
  assert.match(app, /invoke\('vpn_ping'/);
  assert.match(app, /invoke\('vpn_location'/);
  assert.match(app, /ping_probe_started/);
  assert.match(app, /location_probe_started/);
  assert.match(app, /Intl\.DisplayNames/);
  assert.match(app, /invoke\('connection_state'\)/);
  assert.match(app, /KB'.*MB'.*GB'/s);
  assert.match(lib, /socks5h:\/\//);
  assert.match(lib, /cloudflare\.com\/cdn-cgi\/trace/);
  assert.doesNotMatch(lib, /curl\.exe/);
  assert.match(lib, /data_plane_probe_lock/);
  assert.match(lib, /connect_timeout\(connect_timeout\)/);
  assert.match(lib, /Duration::from_secs\(3\)/);
  assert.match(lib, /mark_recovering/);
  assert.match(lib, /triggerSource/);
  assert.match(lib, /ipwho\.is/);
  assert.match(lib, /ipapi\.co/);
  assert.match(lib, /api\.country\.is/);
  assert.match(lib, /wait_for_generation_change\(generation\)/);
  assert.match(app, /telemetryGeneration/);
  assert.match(process, /connection_state/);
  assert.match(routing, /GetIfEntry2/);
  assert.match(routing, /GetIfTable2/);
  assert.match(routing, /InOctets/);
  assert.match(routing, /OutOctets/);
  assert.doesNotMatch(routing, /Get-NetAdapterStatistics/);
  assert.match(routing, /saturating_sub\(base\.uploaded\)/);
  assert.match(routing, /saturating_sub\(base\.downloaded\)/);
  assert.match(app, /xrayRunning/);
});

test('connection commands are single-flight and stale events cannot override disconnecting', async () => {
  const [app, lib] = await Promise.all([read('../src/app.js'), read('../src-tauri/src/lib.rs')]);
  assert.match(app, /if\(operation\|\|!\['disconnected','error'\]\.includes\(state\)\)return/);
  assert.match(app, /if\(operation==='disconnect'\|\|state==='disconnecting'\|\|state==='disconnected'\)return/);
  assert.match(app, /operation==='disconnect'&&e\.payload\.state!=='disconnected'/);
  assert.match(app, /setState\('disconnecting'\)/);
  assert.match(app, /next==='reconnecting'\)\{stopTelemetry\(\)/);
  assert.match(app, /connectedTimelineRecorded/);
  assert.match(app, /snapshot\.state==='disconnected'\)\{desiredConnected=false;setState\('disconnected'\)/);
  assert.doesNotMatch(app, /e\.payload\.state==='connected'\)\{reconnectAttempts=0;setState\('connected'\)/);
  assert.match(lib, /let routing_result = state\.routing\.stop[\s\S]*let process_result = state\.process\.stop/);
  assert.match(lib, /probe_socks/);
  assert.match(lib, /session_epoch/);
  assert.match(app, /probeInFlight/);
  assert.match(app, /setInterval\(ping,15000\)/);
  assert.match(app, /setInterval\(location,30000\)/);
  assert.match(lib, /cancel_background_work/);
});

test('Telegram control uses a bundled vector logo while preserving the channel action', async () => {
  const [html, app] = await Promise.all([read('../src/index.html'), read('../src/app.js')]);
  assert.match(html, /class="telegram-icon"/);
  assert.match(html, /<svg[^>]+viewBox="0 0 24 24"/);
  assert.match(app, /tg:\/\/resolve\?domain=hamvex/);
});

test('Connect IPC is dispatched before non-blocking settings persistence with attempt telemetry', async () => {
  const app = await read('../src/app.js');
  assert.match(app, /activeAttemptId=`ui-\$\{clientStartedAtMs\}-\$\{\+\+connectAttemptSequence\}`/);
  assert.match(app, /frontendPhase\('backend_connect_ipc_start'\);const backendConnect=invoke\('connect'/);
  assert.match(app, /const settingsSave=invoke\('save_settings'/);
  assert.ok(app.indexOf("invoke('connect'") < app.indexOf("invoke('save_settings'", app.indexOf('async function connect()')));
  assert.match(app, /settings_save_failure','failure','settings_persistence'/);
  assert.doesNotMatch(app, /await invoke\('save_settings',\{settings\}\);if\(version!==operationVersion\)return/);
});

test('early Connect is queued until settings and backend state initialization complete', async () => {
  const app = await read('../src/app.js');
  assert.match(app, /initialized=false,pendingConnect=false/);
  assert.match(app, /async function connect\(\)\{if\(!initialized\)\{pendingConnect=true;return\}/);
  assert.ok(app.indexOf('renderSettings(defaults);') < app.indexOf("$('connectionOrb').onclick"));
  assert.match(app, /initialized=true;if\(pendingConnect&&state==='disconnected'\)/);
});

test('failed automatic reconnects continue after the connect operation clears', async () => {
  const app = await read('../src/app.js');
  assert.match(app, /retryAfterFailure=desiredConnected&&settings\.quickReconnect/);
  assert.match(app, /operation=null;if\(retryAfterFailure\)scheduleReconnect\(\)/);
});

test('connected Home state has no protection subtitle', async () => {
  const [app, i18n, css] = await Promise.all([read('../src/app.js'), read('../src/i18n.js'), read('../src/styles.css')]);
  assert.doesNotMatch(app + i18n, /Your connection is protected\./);
  assert.match(i18n, /'connection\.connected':''/);
  assert.match(css, /\.status-message:empty\{display:none\}/);
  assert.match(css, /\.connection-orb\.connected small\{display:none\}/);
});

test('MASQUE and Smart Connect reuse the Aether process manager', async () => {
  const [lib, settings] = await Promise.all([read('../src-tauri/src/lib.rs'), read('../src-tauri/src/settings.rs')]);
  assert.match(lib, /start_core_with_fallback/);
  assert.match(lib, /MASQUE HTTP\/3 failed; retrying with HTTP\/2/);
  assert.match(lib, /no usable masque gateway found/);
  assert.match(lib, /prober: no clean endpoint found/);
  assert.match(lib, /candidates\.extend\(\["gool"\.into\(\), "wg"\.into\(\), "masque"\.into\(\)\]\)/);
  assert.match(lib, /\[smart\] candidate=/);
  assert.match(lib, /smart_protocol_cache/);
  assert.match(lib, /smart-winner\.txt/);
  assert.match(lib, /rejected_exit_country=IR/);
  assert.match(settings, /masque_transport/);
  assert.match(settings, /AETHER_MASQUE_HTTP2/);
});

test('updates remain centralized, verified, silent on startup, and manually available', async () => {
  const [html, app, lib, update] = await Promise.all([
    read('../src/index.html'), read('../src/app.js'), read('../src-tauri/src/lib.rs'), read('../src-tauri/src/update.rs')
  ]);
  for (const id of ['automaticUpdates', 'checkUpdates', 'updateAction', 'updateProgress']) assert.match(html, new RegExp(`id="${id}"`));
  assert.match(update, /Sha256/);
  assert.match(lib, /update::check_for_update/);
  assert.match(app, /if\(manual\)\$\('updateStatus'\)\.textContent=t\('updates\.checking'\)/);
  assert.match(app, /12\*60\*60\*1000/);
});

test('About, Telegram, and opener permissions are complete', async () => {
  const [html, app, capability] = await Promise.all([read('../src/index.html'), read('../src/app.js'), read('../src-tauri/capabilities/default.json')]);
  assert.match(html, /CluvexStudio\/Aether/);
  assert.match(html, /hamvex\/AetherGUI/);
  assert.match(app, /tg:\/\/resolve\?domain=hamvex/);
  assert.match(app, /https:\/\/t\.me\/hamvex/);
  assert.doesNotMatch(html, /(?:src|href)="https?:/);
  const opener = JSON.parse(capability).permissions.find(item => item.identifier === 'opener:allow-open-url');
  for (const url of ['tg://resolve?domain=hamvex', 'https://t.me/hamvex', 'https://github.com/CluvexStudio/Aether', 'https://github.com/hamvex/AetherGUI']) assert.ok(opener.allow.some(item => item.url === url));
});

test('application metadata and visible release version agree across every declaration site', async () => {
  const [pkg, tauri, cargo, html, app, gradle, strings] = await Promise.all([
    read('../package.json'), read('../src-tauri/tauri.conf.json'), read('../src-tauri/Cargo.toml'), read('../src/index.html'), read('../src/app.js'),
    read('../android/app/build.gradle'), read('../android/app/src/main/res/values/strings.xml')
  ]);
  // package.json is the single source of truth; every other site has to match it so a release
  // bump can never leave one platform advertising a stale version.
  const version = JSON.parse(pkg).version;
  assert.match(version, /^\d+\.\d+\.\d+$/);
  const literal = version.replace(/\./g, '\\.');
  assert.equal(JSON.parse(tauri).version, version);
  assert.match(cargo, new RegExp(`version = "${literal}"`));
  assert.match(html, new RegExp(`v${literal}`));
  assert.match(app, new RegExp(`'${literal}'`));
  assert.match(gradle, new RegExp(`versionName '${literal}'`));
  assert.match(strings, new RegExp(`name="app_version"[^>]*>v${literal}<`));
  assert.doesNotMatch(html + app, /1\.11\.[12]/);
});

test('release version comparison is single-sourced and ordered', async () => {
  const update = await read('../src-tauri/src/update.rs');
  // The installed version must come from the crate version, not a literal that can drift.
  assert.match(update, /let current = env!\("CARGO_PKG_VERSION"\)/);
  assert.doesNotMatch(update, /let current = "/);
  assert.match(update, /is_newer_version\("1\.11\.1", "1\.11\.0"\)/);
  assert.match(update, /is_newer_version\("2\.0\.0", "1\.11\.1"\)/);
});

test('Windows VPN lifecycle retains elevation, recovery, TUN readiness, and clean shutdown', async () => {
  const [routing, process, main, hooks] = await Promise.all([
    read('../src-tauri/src/routing.rs'), read('../src-tauri/src/process.rs'), read('../src-tauri/src/main.rs'), read('../src-tauri/windows/hooks.nsh')
  ]);
  for (const pattern of [/ShellExecuteW/, /CreateMutexW/, /wait_for_tun_ready/, /CREATE_NO_WINDOW/, /previous_session_dir/, /SessionChild/]) assert.match(routing, pattern);
  assert.match(routing, /Duration::from_secs\(10\)/);
  assert.match(routing, /socks_failures < 3/);
  assert.match(routing, /timeline\.log/);
  assert.match(process, /generation/);
  assert.match(process, /kill_on_drop/);
  assert.match(main, /--repair-network/);
  assert.match(hooks, /--repair-network/);
  assert.match(hooks, /\$SMPROGRAMS\\Aethon\.lnk/);
  assert.match(routing, /No Aethon network state was found to repair/);
});

test('Android reference retains VPNService, RTL, and application picker', async () => {
  const [manifest, activity, service, picker, gradle] = await Promise.all([
    read('../android/app/src/main/AndroidManifest.xml'),
    read('../android/app/src/main/java/com/firstham/aethergui/MainActivity.java'),
    read('../android/app/src/main/java/com/firstham/aethergui/AetherVpnService.java'),
    read('../android/app/src/main/java/com/firstham/aethergui/AppSelectionActivity.java'),
    read('../android/app/build.gradle')
  ]);
  assert.match(manifest, /android\.permission\.BIND_VPN_SERVICE/);
  assert.match(manifest, /supportsRtl="true"/);
  assert.match(activity, /AppCompatDelegate\.setDefaultNightMode/);
  assert.match(service, /TProxyStartService/);
  assert.match(service, /addAllowedApplication/);
  assert.match(service, /addDisallowedApplication/);
  assert.match(picker, /loadIcon/);
  assert.match(gradle, /versionCode \d+/);
  assert.match(manifest + activity + service, /supportsRtl|LocaleListCompat/);
});
