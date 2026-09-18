mod endpoint_cache;
mod process;
pub mod routing;
mod settings;
pub mod signature;
mod update;

use process::{emit_status, ProcessManager};
use routing::{RoutingManager, TrafficTotals};
use settings::Settings;
use std::sync::{
    atomic::{AtomicU64, Ordering},
    Arc, Mutex, OnceLock,
};
use std::time::{Instant, SystemTime, UNIX_EPOCH};
use std::{
    collections::HashSet,
    fs::{self, OpenOptions},
    io::Write,
    path::PathBuf,
};
use tauri::{
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Emitter, Manager, WindowEvent,
};
use tauri_plugin_dialog::DialogExt;

struct AppState {
    process: Arc<ProcessManager>,
    routing: Arc<RoutingManager>,
    connect_gate: tokio::sync::Mutex<()>,
    session_epoch: Arc<AtomicU64>,
}

#[derive(Clone, Default)]
pub(crate) struct AttemptTelemetry {
    pub(crate) attempt_id: String,
    pub(crate) session_generation: u64,
    pub(crate) started_monotonic_ms: u64,
}

pub(crate) fn active_attempt() -> &'static std::sync::RwLock<AttemptTelemetry> {
    static ATTEMPT: OnceLock<std::sync::RwLock<AttemptTelemetry>> = OnceLock::new();
    ATTEMPT.get_or_init(|| std::sync::RwLock::new(AttemptTelemetry::default()))
}

#[tauri::command]
async fn connect(
    app: AppHandle,
    state: tauri::State<'_, AppState>,
    settings: Settings,
    client_started_at_ms: Option<u64>,
    attempt_id: Option<String>,
) -> Result<(), String> {
    let Ok(_connect_guard) = state.connect_gate.try_lock() else {
        return Err("A connection attempt is already in progress".into());
    };
    let session_token = state.session_epoch.fetch_add(1, Ordering::SeqCst) + 1;
    let mut settings = settings;
    settings.normalize_protocol_options();
    let lan_address = if settings.allow_remote_listener {
        let address = active_lan_ipv4()
            .ok_or("Connection from LAN requires an active private IPv4 network interface")?;
        settings.socks_address = "0.0.0.0:1819".into();
        Some(address)
    } else {
        None
    };
    let backend_received_ms = unix_time_ms();
    let connect_started_ms = client_started_at_ms
        .filter(|started| backend_received_ms.saturating_sub(*started) <= 60_000)
        .unwrap_or(backend_received_ms);
    let timeline_id = attempt_id
        .filter(|value| {
            !value.is_empty()
                && value.len() <= 100
                && value.chars().all(|character| {
                    character.is_ascii_alphanumeric() || matches!(character, '-' | '_')
                })
        })
        .unwrap_or_else(|| {
            format!(
                "{}-{session_token}-{connect_started_ms}",
                std::process::id()
            )
        });
    let backend_latency_ms = backend_received_ms.saturating_sub(connect_started_ms);
    *active_attempt()
        .write()
        .unwrap_or_else(|poisoned| poisoned.into_inner()) = AttemptTelemetry {
        attempt_id: timeline_id.clone(),
        session_generation: session_token,
        started_monotonic_ms: monotonic_ms().saturating_sub(backend_latency_ms),
    };
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "connection_task_created",
        elapsed_since_ms(connect_started_ms),
    );
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "backend_connect_received",
        backend_latency_ms,
    );
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "saved_settings_loaded",
        elapsed_since_ms(connect_started_ms),
    );
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "settings_loaded",
        elapsed_since_ms(connect_started_ms),
    );
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "selected_protocol_loaded",
        elapsed_since_ms(connect_started_ms),
    );
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "server_profile_loaded",
        elapsed_since_ms(connect_started_ms),
    );
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "backend_connect_request",
        backend_received_ms.saturating_sub(connect_started_ms),
    );
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "firewall_setup_started",
        elapsed_since_ms(connect_started_ms),
    );
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "firewall_started",
        elapsed_since_ms(connect_started_ms),
    );
    if let Err(error) = configure_lan_firewall(settings.allow_remote_listener) {
        emit_timeline_failure(
            &app,
            &timeline_id,
            &settings.protocol,
            &settings.connection_mode,
            "firewall_setup_failed",
            &error,
        );
        return Err(error);
    }
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "firewall_setup_finished",
        elapsed_since_ms(connect_started_ms),
    );
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "firewall_ready",
        elapsed_since_ms(connect_started_ms),
    );
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "t0_user_connect_click",
        0,
    );
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "t1_backend_request_received",
        backend_received_ms.saturating_sub(connect_started_ms),
    );
    // A repeated Connect tap must not tear down a live tunnel or start a second core.
    // The frontend also debounces this path, but keeping the guard here protects tray/IPC callers.
    if matches!(
        state.process.connection_state().await,
        "connected" | "connecting"
    ) {
        return Ok(());
    }
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "runtime_configuration_generation_started",
        elapsed_since_ms(connect_started_ms),
    );
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "config_generation_started",
        elapsed_since_ms(connect_started_ms),
    );
    if let Err(error) = ensure_socks_address_available(&settings.socks_address).await {
        emit_timeline_failure(
            &app,
            &timeline_id,
            &settings.protocol,
            &settings.connection_mode,
            "connect_failed",
            &error,
        );
        return Err(error);
    }
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "configuration_validation_started",
        elapsed_since_ms(connect_started_ms),
    );
    let core_result = if settings.protocol == "smart" {
        start_smart_core(
            &app,
            &state.process,
            &mut settings,
            &timeline_id,
            connect_started_ms,
            &state.session_epoch,
            session_token,
        )
        .await
    } else {
        start_validated_protocol(
            &app,
            &state.process,
            &settings,
            &timeline_id,
            connect_started_ms,
            "connect_validation",
            &state.session_epoch,
            session_token,
        )
        .await
    };
    let (generation, expected_probe) = match core_result {
        Ok(result) => result,
        Err(error) => {
            emit_timeline_failure(
                &app,
                &timeline_id,
                &settings.protocol,
                &settings.connection_mode,
                "connect_failed",
                &error,
            );
            return Err(error);
        }
    };
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "configuration_validation_finished",
        elapsed_since_ms(connect_started_ms),
    );
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "config_generation_finished",
        elapsed_since_ms(connect_started_ms),
    );
    if state.process.generation().await != generation {
        let _ = state.routing.stop(&app).await;
        return Err("Connection attempt was cancelled".into());
    }
    let core_elapsed = elapsed_since_ms(connect_started_ms);
    let process_elapsed = state.process.elapsed_ms().await;
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "aether_process_started",
        core_elapsed.saturating_sub(process_elapsed),
    );
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "t2_aether_process_started",
        core_elapsed.saturating_sub(process_elapsed),
    );
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "selected_protocol_handshake",
        core_elapsed,
    );
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "socks_ready_and_https_validated",
        core_elapsed,
    );
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "Aether_socks_ready",
        core_elapsed,
    );
    // Set once the tunnel has proven it carries traffic and the remaining system
    // HTTPS + exit-IP validation is to be completed behind the published state.
    let mut validate_in_background = false;
    if settings.connection_mode == "vpn" {
        let connect_started_monotonic_ms = active_attempt()
            .read()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .started_monotonic_ms;
        emit_timeline(
            &app,
            &timeline_id,
            &settings.protocol,
            &settings.connection_mode,
            "routing_helper_initialization_started",
            elapsed_since_ms(connect_started_ms),
        );
        emit_timeline(
            &app,
            &timeline_id,
            &settings.protocol,
            &settings.connection_mode,
            "routing_helper_started",
            elapsed_since_ms(connect_started_ms),
        );
        if let Err(error) = state
            .routing
            .start(
                app.clone(),
                &settings,
                state.process.clone(),
                connect_started_ms,
                connect_started_monotonic_ms,
                timeline_id.clone(),
                session_token,
            )
            .await
        {
            let _ = state.routing.stop(&app).await;
            let _ = state.process.stop().await;
            emit_status(&app, "disconnected", None, None);
            emit_timeline_failure(
                &app,
                &timeline_id,
                &settings.protocol,
                &settings.connection_mode,
                "routing_failed",
                &error,
            );
            return Err(format!("VPN Mode could not start: {error}"));
        }
        if state.process.generation().await != generation {
            let _ = state.routing.stop(&app).await;
            return Err("Connection attempt was cancelled".into());
        }
        if settings.routing_mode != "split-include" {
            emit_timeline(
                &app,
                &timeline_id,
                &settings.protocol,
                &settings.connection_mode,
                "endpoint_validation_started",
                elapsed_since_ms(connect_started_ms),
            );
            emit_timeline(
                &app,
                &timeline_id,
                &settings.protocol,
                &settings.connection_mode,
                "t17a_basic_traffic_readiness_started",
                elapsed_since_ms(connect_started_ms),
            );
            // Step 3 of the connect sequence: confirm the tunnel actually carries
            // traffic. The full HTTPS exchange and the exit-IP comparison follow in
            // the background, after the UI has been told the tunnel is up; they are
            // not skipped, and a failure there still tears the connection down.
            if let Err(error) =
                confirm_basic_traffic_readiness(&app, &state.process, generation).await
            {
                let _ = state.routing.stop(&app).await;
                let _ = state.process.stop().await;
                emit_status(&app, "disconnected", None, None);
                emit_timeline_failure(
                    &app,
                    &timeline_id,
                    &settings.protocol,
                    &settings.connection_mode,
                    "data_plane_failed",
                    &error,
                );
                return Err(format!("Xray TUN data-plane validation failed: {error}"));
            }
            emit_timeline(
                &app,
                &timeline_id,
                &settings.protocol,
                &settings.connection_mode,
                "t17b_basic_traffic_readiness_confirmed",
                elapsed_since_ms(connect_started_ms),
            );
            validate_in_background = true;
        }
    }
    {
        let mut cache = location_cache().lock().await;
        if cache.exit_ip != expected_probe.exit_ip {
            cache.location.clear();
            cache.retry_after = None;
        }
        cache.exit_ip = expected_probe.exit_ip.clone();
    }
    // The only place a pin is ever written. The reader observed this pair when
    // the core announced it; it is remembered only now, after the same attempt
    // has carried real traffic end to end, so a pair that merely opened a
    // listener never becomes the next connect's starting point.
    //
    // The network is the one the attempt *dialled out on*, captured before the
    // core spawned. It cannot be resolved here: the tunnel is up, so the default
    // route now belongs to the Aethon TUN adapter, and a pin filed under that
    // could never be found again by a lookup made with the tunnel down.
    if let (Some(dir), Some(endpoints), Some(network)) = (
        endpoint_cache_dir(&app),
        state.process.observed_endpoints().await,
        state.process.network_at_start().await,
    ) {
        endpoint_cache::store(
            &dir,
            endpoints,
            &network,
            &settings.scan_mode,
            process::AETHER_VERSION,
        );
    }
    state.process.mark_connected().await;
    let message = if settings.connection_mode == "vpn" {
        "Aether and System-wide VPN Mode are ready"
    } else {
        "Aether SOCKS5 proxy is ready"
    };
    emit_status(&app, "connected", None, Some(message.into()));
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "ui_connected",
        elapsed_since_ms(connect_started_ms),
    );
    emit_timeline(
        &app,
        &timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "connected_event_emitted",
        elapsed_since_ms(connect_started_ms),
    );
    if validate_in_background {
        // Publishes t18/t19, then starts the health watchdog on success or tears the
        // connection down on failure. Owns the watchdog hand-off so the monitor is
        // never started against a data plane that has not been verified end to end.
        spawn_connect_data_plane_validation(
            app.clone(),
            state.process.clone(),
            state.routing.clone(),
            settings,
            generation,
            expected_probe.exit_ip,
            timeline_id.clone(),
            connect_started_ms,
            state.session_epoch.clone(),
            session_token,
        );
    } else {
        spawn_data_plane_monitor(
            app.clone(),
            state.process.clone(),
            settings,
            generation,
            expected_probe.exit_ip,
            state.session_epoch.clone(),
            session_token,
        );
    }
    if let Some(address) = lan_address {
        let _ = app.emit(
            "aether-log",
            format!("LAN SOCKS5 available at {address}:1819 (Private network profile)"),
        );
    }
    Ok(())
}

#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct LanStatus {
    enabled: bool,
    address: Option<String>,
    port: u16,
    listener: String,
}

fn active_lan_ipv4() -> Option<String> {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        use std::process::Command;
        let output = Command::new("powershell.exe")
            .args([
                "-NoProfile", "-NonInteractive", "-Command",
                "Get-NetIPAddress -AddressFamily IPv4 -AddressState Preferred | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.IPAddress -match '^(10\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[0-1])\\.)' -and $_.InterfaceAlias -notlike 'AethonTun-*' } | Select-Object -ExpandProperty IPAddress",
            ])
            .creation_flags(windows_sys::Win32::System::Threading::CREATE_NO_WINDOW)
            .output()
            .ok()?;
        let stdout = String::from_utf8_lossy(&output.stdout).into_owned();
        let mut candidates = stdout
            .lines()
            .map(str::trim)
            .filter(|value| !value.is_empty());
        candidates.next().map(str::to_string)
    }
    #[cfg(not(windows))]
    {
        None
    }
}

fn configure_lan_firewall(enabled: bool) -> Result<(), String> {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        use std::process::Command;
        let mut command = Command::new("netsh.exe");
        command.creation_flags(windows_sys::Win32::System::Threading::CREATE_NO_WINDOW);
        let args = if enabled {
            vec![
                "advfirewall",
                "firewall",
                "add",
                "rule",
                "name=Aethon LAN SOCKS5",
                "dir=in",
                "action=allow",
                "protocol=TCP",
                "localport=1819",
                "profile=private",
                "remoteip=localsubnet",
            ]
        } else {
            vec![
                "advfirewall",
                "firewall",
                "delete",
                "rule",
                "name=Aethon LAN SOCKS5",
            ]
        };
        let output = command
            .args(args)
            .output()
            .map_err(|error| format!("Could not configure Windows LAN firewall: {error}"))?;
        if enabled && !output.status.success() {
            return Err(format!(
                "Windows refused the Private-profile LAN firewall rule: {}",
                String::from_utf8_lossy(&output.stderr).trim()
            ));
        }
    }
    Ok(())
}

#[tauri::command]
fn lan_status(settings: Settings) -> LanStatus {
    let enabled = settings.allow_remote_listener;
    LanStatus {
        enabled,
        address: enabled.then(active_lan_ipv4).flatten(),
        port: 1819,
        listener: if enabled {
            "0.0.0.0:1819".into()
        } else {
            "127.0.0.1:1819".into()
        },
    }
}

async fn ensure_socks_address_available(address: &str) -> Result<(), String> {
    let listener = tokio::net::TcpListener::bind(address)
        .await
        .map_err(|error| format!("SOCKS5 address {address} is already in use: {error}"))?;
    drop(listener);
    Ok(())
}

fn spawn_data_plane_monitor(
    app: AppHandle,
    process: Arc<ProcessManager>,
    settings: Settings,
    generation: u64,
    expected_exit: String,
    session_epoch: Arc<AtomicU64>,
    session_token: u64,
) {
    if !settings.watchdog {
        return;
    }
    tauri::async_runtime::spawn(async move {
        let mut failures = 0u8;
        let mut recovering = false;
        loop {
            tokio::time::sleep(std::time::Duration::from_secs(3)).await;
            if process.generation().await != generation
                || session_epoch.load(Ordering::SeqCst) != session_token
            {
                break;
            }
            let health = if settings.connection_mode == "vpn"
                && settings.routing_mode != "split-include"
            {
                validate_watchdog_data_plane(&app, &process, generation, &settings, &expected_exit)
                    .await
            } else {
                match run_trace_probe(
                    &app,
                    &process,
                    generation,
                    &settings,
                    true,
                    "socks_https",
                    "watchdog",
                    1,
                    false,
                )
                .await
                {
                    Ok(Some(_)) => Ok(()),
                    Ok(None) => continue,
                    Err(error) => Err(error),
                }
            };
            if health.is_ok() {
                failures = 0;
                if recovering {
                    recovering = false;
                    process.mark_connected().await;
                    emit_status(
                        &app,
                        "connected",
                        None,
                        Some("The verified VPN data plane recovered".into()),
                    );
                    let _ = app.emit(
                        "aether-log",
                        format!(
                            "[watchdog] {}",
                            serde_json::json!({
                                "state": "connected",
                                "timestampMs": unix_time_ms(),
                                "generation": generation
                            })
                        ),
                    );
                }
                continue;
            }
            let error = health.unwrap_err();
            if update_health_failures(&mut failures, false) && !recovering {
                if process.generation().await != generation {
                    break;
                }
                recovering = true;
                process.mark_recovering().await;
                emit_status(
                    &app,
                    "reconnecting",
                    None,
                    Some(format!(
                        "The VPN data plane is unavailable; waiting for recovery: {error}"
                    )),
                );
                let _ = app.emit(
                    "aether-log",
                    format!(
                        "[watchdog] {}",
                        serde_json::json!({
                            "state": "reconnecting",
                            "timestampMs": unix_time_ms(),
                            "generation": generation,
                            "failures": failures,
                            "reason": error
                        })
                    ),
                );
            } else {
                let _ = app.emit(
                    "aether-log",
                    format!("Data-plane health check failed ({failures}/2): {error}"),
                );
            }
        }
    });
}

fn update_health_failures(failures: &mut u8, healthy: bool) -> bool {
    if healthy {
        *failures = 0;
    } else {
        *failures = failures.saturating_add(1);
    }
    *failures >= 2
}

fn smart_protocol_cache() -> &'static tokio::sync::Mutex<Option<String>> {
    static CACHE: OnceLock<tokio::sync::Mutex<Option<String>>> = OnceLock::new();
    CACHE.get_or_init(|| tokio::sync::Mutex::new(None))
}

fn unix_time_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .min(u64::MAX as u128) as u64
}

pub(crate) fn elapsed_since_ms(started_ms: u64) -> u64 {
    let attempt = active_attempt()
        .read()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    if attempt.started_monotonic_ms > 0 {
        monotonic_ms().saturating_sub(attempt.started_monotonic_ms)
    } else {
        unix_time_ms().saturating_sub(started_ms)
    }
}

fn monotonic_ms() -> u64 {
    #[cfg(windows)]
    {
        unsafe { windows_sys::Win32::System::SystemInformation::GetTickCount64() }
    }
    #[cfg(not(windows))]
    {
        static START: OnceLock<Instant> = OnceLock::new();
        START.get_or_init(Instant::now).elapsed().as_millis() as u64
    }
}

pub(crate) fn emit_timeline(
    app: &AppHandle,
    timeline_id: &str,
    protocol: &str,
    connection_mode: &str,
    stage: &str,
    elapsed_ms: u64,
) {
    let attempt = active_attempt()
        .read()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone();
    let line = serde_json::json!({
        "timelineId": timeline_id,
        "attemptId": attempt.attempt_id,
        "sessionGeneration": attempt.session_generation,
        "protocol": protocol,
        "connectionMode": connection_mode,
        "stage": stage,
        "timestampMs": unix_time_ms(),
        "monotonicMs": monotonic_ms(),
        "elapsedMs": elapsed_ms,
        "processId": std::process::id(),
        "result": "success",
        "errorCategory": ""
    });
    persist_pre_xray_timeline(timeline_id, &line);
    let _ = app.emit("aether-log", format!("[timeline] {line}"));
}

fn emit_timeline_failure(
    app: &AppHandle,
    timeline_id: &str,
    protocol: &str,
    connection_mode: &str,
    stage: &str,
    error: &str,
) {
    let attempt = active_attempt()
        .read()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone();
    let line = serde_json::json!({
        "timelineId": timeline_id,
        "attemptId": attempt.attempt_id,
        "sessionGeneration": attempt.session_generation,
        "protocol": protocol,
        "connectionMode": connection_mode,
        "stage": stage,
        "timestampMs": unix_time_ms(),
        "monotonicMs": monotonic_ms(),
        "elapsedMs": monotonic_ms().saturating_sub(attempt.started_monotonic_ms),
        "processId": std::process::id(),
        "result": "failure",
        "errorCategory": classify_connection_error(error),
        "error": error
    });
    persist_pre_xray_timeline(timeline_id, &line);
    let _ = app.emit("aether-log", format!("[timeline] {line}"));
}

fn persist_pre_xray_timeline(timeline_id: &str, line: &serde_json::Value) {
    static WRITE_LOCK: OnceLock<Mutex<()>> = OnceLock::new();
    let _guard = WRITE_LOCK.get_or_init(|| Mutex::new(())).lock().ok();
    let Some(local) = std::env::var_os("LOCALAPPDATA") else {
        return;
    };
    let mut path = PathBuf::from(local);
    path.push("io.github.hamvex.aether-gui");
    path.push("routing");
    if fs::create_dir_all(&path).is_err() {
        return;
    }
    path.push(format!("pre-xray-{timeline_id}.log"));
    let Ok(mut file) = OpenOptions::new().create(true).append(true).open(path) else {
        return;
    };
    let _ = writeln!(file, "{line}");
}

fn classify_connection_error(error: &str) -> &'static str {
    let value = error.to_ascii_lowercase();
    if value.contains("cancel") || value.contains("generation") {
        "cancellation_generation_race"
    } else if value.contains("already in progress") {
        "duplicate_connection_attempt"
    } else if value.contains("already running") || value.contains("port") && value.contains("use") {
        "port_collision_or_stale_process"
    } else if value.contains("socks") && (value.contains("timeout") || value.contains("open")) {
        "aether_socks_readiness"
    } else if value.contains("aether") && (value.contains("start") || value.contains("exited")) {
        "aether_startup"
    } else if value.contains("wintun") || value.contains("adapter") {
        "wintun_creation_or_readiness"
    } else if value.contains("route") {
        "route_installation_or_readiness"
    } else if value.contains("dns") {
        "dns"
    } else if value.contains("tls") {
        "tls"
    } else if value.contains("https") || value.contains("http") {
        "https_validation"
    } else if value.contains("xray") || value.contains("routing engine") {
        "xray_startup_or_readiness"
    } else if value.contains("timeout") || value.contains("timed out") {
        "timeout"
    } else {
        "unknown"
    }
}

#[allow(clippy::too_many_arguments)]
fn emit_probe_result(
    app: &AppHandle,
    probe_type: &str,
    trigger: &str,
    elapsed_ms: u64,
    retry_count: u8,
    result: &str,
    failure_reason: Option<&str>,
    error_category: Option<&str>,
) {
    let attempt_context = active_attempt()
        .read()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone();
    let _ = app.emit(
        "aether-log",
        format!(
            "[probe] {}",
            serde_json::json!({
                "probeType": probe_type,
                "triggerSource": trigger,
                "attemptId": attempt_context.attempt_id,
                "sessionGeneration": attempt_context.session_generation,
                "timestampMs": unix_time_ms(),
                "monotonicMs": monotonic_ms(),
                "processId": std::process::id(),
                "elapsedMs": elapsed_ms,
                "result": result,
                "retryCount": retry_count,
                "failureReason": failure_reason.unwrap_or(""),
                "errorCategory": error_category.unwrap_or("")
            })
        ),
    );
}

async fn load_smart_protocol(app: &AppHandle) -> Option<String> {
    if let Some(protocol) = smart_protocol_cache().lock().await.clone() {
        return Some(protocol);
    }
    let path = app
        .path()
        .app_local_data_dir()
        .ok()?
        .join("smart-winner.txt");
    let protocol = std::fs::read_to_string(path).ok()?.trim().to_string();
    if !matches!(protocol.as_str(), "gool" | "wg" | "masque") {
        return None;
    }
    *smart_protocol_cache().lock().await = Some(protocol.clone());
    Some(protocol)
}

fn store_smart_protocol(app: &AppHandle, protocol: &str) {
    let Ok(data_dir) = app.path().app_local_data_dir() else {
        return;
    };
    if std::fs::create_dir_all(&data_dir).is_ok() {
        let _ = std::fs::write(data_dir.join("smart-winner.txt"), protocol);
    }
}

async fn start_smart_core(
    app: &AppHandle,
    process: &Arc<ProcessManager>,
    settings: &mut Settings,
    timeline_id: &str,
    connect_started_ms: u64,
    session_epoch: &Arc<AtomicU64>,
    session_token: u64,
) -> Result<(u64, TraceProbe), String> {
    let cached = load_smart_protocol(app).await;
    emit_timeline(
        app,
        timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "cached_endpoint_read_finished",
        elapsed_since_ms(connect_started_ms),
    );
    emit_timeline(
        app,
        timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        if cached.is_some() {
            "cached_endpoint_validated"
        } else {
            "cached_endpoint_missing_or_invalid"
        },
        elapsed_since_ms(connect_started_ms),
    );
    let mut candidates = Vec::new();
    if let Some(protocol) = cached {
        candidates.push(protocol);
    }
    candidates.extend(["gool".into(), "wg".into(), "masque".into()]);
    let mut seen = HashSet::new();
    let mut failures = Vec::new();
    for protocol in candidates {
        if !seen.insert(protocol.clone()) {
            continue;
        }
        let mut trial = settings.clone();
        trial.protocol = protocol.clone();
        let started = Instant::now();
        match start_validated_protocol(
            app,
            process,
            &trial,
            timeline_id,
            connect_started_ms,
            "smart_candidate",
            session_epoch,
            session_token,
        )
        .await
        {
            Ok((generation, probe)) => {
                *smart_protocol_cache().lock().await = Some(protocol.clone());
                store_smart_protocol(app, &protocol);
                settings.protocol = protocol.clone();
                let _ = app.emit(
                    "aether-log",
                    format!(
                        "[smart] candidate={protocol} result=accepted score_ms={} selected=true",
                        started.elapsed().as_millis()
                    ),
                );
                return Ok((generation, probe));
            }
            Err(error) => {
                let _ = app.emit(
                    "aether-log",
                    format!(
                        "[smart] candidate={protocol} result=rejected score_ms={} reason={error}",
                        started.elapsed().as_millis()
                    ),
                );
                failures.push(format!("{protocol}: {error}"));
            }
        }
    }
    Err(format!(
        "Smart Connect could not establish a protocol: {}",
        failures.join("; ")
    ))
}

#[allow(clippy::too_many_arguments)]
async fn start_validated_protocol(
    app: &AppHandle,
    process: &Arc<ProcessManager>,
    settings: &Settings,
    timeline_id: &str,
    connect_started_ms: u64,
    trigger: &str,
    session_epoch: &Arc<AtomicU64>,
    session_token: u64,
) -> Result<(u64, TraceProbe), String> {
    emit_timeline(
        app,
        timeline_id,
        &settings.protocol,
        &settings.connection_mode,
        "endpoint_selection_started",
        elapsed_since_ms(connect_started_ms),
    );
    if settings.protocol == "smart" {
        emit_timeline(
            app,
            timeline_id,
            &settings.protocol,
            &settings.connection_mode,
            "cached_endpoint_read_started",
            elapsed_since_ms(connect_started_ms),
        );
    }
    // Both failure modes that a different endpoint can fix get the same budget:
    // an exit in the country the tunnel exists to leave, and a listener that
    // opens but carries no traffic. Before this, only the first was retried -
    // the second returned immediately, which is why a sixth of all
    // non-cancelled attempts ended at "SOCKS5 opened, but HTTPS data-plane
    // validation failed" with no second endpoint ever tried.
    let max_exit_attempts = if settings.protocol == "gool" { 3 } else { 2 };
    let mut failures = Vec::new();
    for exit_attempt in 1..=max_exit_attempts {
        if session_epoch.load(Ordering::SeqCst) != session_token {
            return Err("Connection attempt was cancelled".into());
        }
        let generation =
            start_core_with_fallback(app, process, settings, timeline_id, connect_started_ms)
                .await?;
        emit_timeline(
            app,
            timeline_id,
            &settings.protocol,
            &settings.connection_mode,
            "aether_socks_ready",
            elapsed_since_ms(connect_started_ms),
        );
        emit_timeline(
            app,
            timeline_id,
            &settings.protocol,
            &settings.connection_mode,
            "t3_aether_socks_listener_ready",
            elapsed_since_ms(connect_started_ms),
        );
        let probe = match run_trace_probe(
            app,
            process,
            generation,
            settings,
            true,
            "socks_https",
            trigger,
            3,
            true,
        )
        .await
        {
            Ok(Some(probe)) => probe,
            Ok(None) => unreachable!("blocking validation always acquires the probe slot"),
            Err(error) => {
                // The listener answered the SOCKS5 handshake and then carried
                // nothing. That is a property of the endpoint behind it, not of
                // the listener, so the recovery is to select a different one.
                if session_epoch.load(Ordering::SeqCst) != session_token {
                    return Err("Connection attempt was cancelled".into());
                }
                invalidate_endpoint_cache(
                    app,
                    "the endpoint opened a SOCKS5 listener but carried no traffic",
                );
                emit_timeline_failure(
                    app,
                    timeline_id,
                    &settings.protocol,
                    &settings.connection_mode,
                    "aether_traffic_validation_failed",
                    &error,
                );
                failures.push(format!("attempt {exit_attempt}: {error}"));
                let _ = process.stop().await;
                if exit_attempt >= max_exit_attempts {
                    return Err(format!(
                        "Aether SOCKS5 opened, but HTTPS data-plane validation failed on all {max_exit_attempts} endpoints: {}",
                        failures.join("; ")
                    ));
                }
                // Published state stays honest: not connected, and visibly
                // working on it.
                emit_status(
                    app,
                    "reconnecting",
                    None,
                    Some("The selected endpoint carried no traffic; selecting another".into()),
                );
                let _ = app.emit(
                    "aether-log",
                    format!(
                        "[recovery] endpoint carried no traffic; reselecting (attempt {} of {max_exit_attempts})",
                        exit_attempt + 1
                    ),
                );
                continue;
            }
        };
        emit_timeline(
            app,
            timeline_id,
            &settings.protocol,
            &settings.connection_mode,
            "endpoint_candidate_responded",
            elapsed_since_ms(connect_started_ms),
        );
        if settings.protocol != "gool" || probe.country != "IR" {
            emit_timeline(
                app,
                timeline_id,
                &settings.protocol,
                &settings.connection_mode,
                "endpoint_selected",
                elapsed_since_ms(connect_started_ms),
            );
            emit_timeline(
                app,
                timeline_id,
                &settings.protocol,
                &settings.connection_mode,
                "t4_aether_socks_https_usable",
                elapsed_since_ms(connect_started_ms),
            );
            return Ok((generation, probe));
        }
        failures.push(format!("attempt {exit_attempt} returned IR"));
        // A pin that lands back inside the country is as unusable as one that
        // carries nothing, and would otherwise be reused on the next connect.
        invalidate_endpoint_cache(app, "the cached endpoints exited inside the restricted country");
        let _ = app.emit(
            "aether-log",
            format!(
                "[gool] rejected_exit_country=IR attempt={exit_attempt} max_attempts={max_exit_attempts}"
            ),
        );
        let _ = process.stop().await;
        if session_epoch.load(Ordering::SeqCst) != session_token {
            return Err("Connection attempt was cancelled".into());
        }
    }
    Err(format!(
        "GOOL could not obtain a non-IR exit after {max_exit_attempts} bounded attempts: {}",
        failures.join("; ")
    ))
}

/// The readiness budget for an attempt started from a cached pin.
///
/// A scanned attempt is allowed the full `stall_timeout` because it genuinely
/// may need it. A pinned attempt may not: v1.9.0's `run_gool` never blacklists
/// a hand-pinned hop, so if the pin is dead the core retries it until something
/// outside stops it. Waiting `stall_timeout` (90 s by default) to discover that
/// would make the cache far more expensive when it is wrong than it is
/// profitable when it is right. Ten seconds is comfortably above the observed
/// p90 for a *scanned* listener, so a healthy pin is never cut off.
const PINNED_ATTEMPT_READY_SECS: u64 = 10;

async fn start_core_with_fallback(
    app: &AppHandle,
    process: &Arc<ProcessManager>,
    settings: &Settings,
    timeline_id: &str,
    connect_started_ms: u64,
) -> Result<u64, String> {
    let generation = process.start(app.clone(), settings.clone()).await?;
    emit_process_timeline(
        app,
        timeline_id,
        settings,
        "aether_process_created",
        connect_started_ms,
        process.pid().await,
    );
    let from_cache = process.started_from_cache().await;
    let primary_result = wait_for_core_socks(
        process,
        settings,
        generation,
        if from_cache {
            settings.stall_timeout.min(PINNED_ATTEMPT_READY_SECS)
        } else {
            settings.stall_timeout
        },
    )
    .await;
    if primary_result.is_ok() {
        return Ok(generation);
    }
    if process.generation().await != generation {
        return Err("Connection attempt was cancelled".into());
    }
    let primary_error = primary_result.unwrap_err();
    // A pinned attempt that never opened its listener has told us the pin is
    // dead. Drop it and scan, rather than reporting a failure the next connect
    // would repeat: the pin is the only thing that was different.
    if from_cache {
        invalidate_endpoint_cache(app, "the cached endpoints did not open a listener");
        emit_timeline(
            app,
            timeline_id,
            &settings.protocol,
            &settings.connection_mode,
            "aether_cached_endpoint_rescan",
            elapsed_since_ms(connect_started_ms),
        );
        let _ = process.stop().await;
        let rescan_generation = process
            .start_with_options(app.clone(), settings.clone(), false)
            .await?;
        emit_process_timeline(
            app,
            timeline_id,
            settings,
            "aether_process_created",
            connect_started_ms,
            process.pid().await,
        );
        return match wait_for_core_socks(
            process,
            settings,
            rescan_generation,
            settings.stall_timeout,
        )
        .await
        {
            Ok(()) => Ok(rescan_generation),
            Err(error) => {
                let _ = process.stop().await;
                Err(format!(
                    "cached endpoints failed ({primary_error}); the full scan that followed also failed ({error})"
                ))
            }
        };
    }
    if settings.protocol == "masque" && settings.masque_transport == "h3" {
        let _ = process.stop().await;
        let mut fallback = settings.clone();
        fallback.masque_transport = "h2".into();
        let _ = app.emit(
            "aether-log",
            "MASQUE HTTP/3 failed; retrying with HTTP/2 transport",
        );
        let fallback_generation = process.start(app.clone(), fallback.clone()).await?;
        emit_process_timeline(
            app,
            timeline_id,
            &fallback,
            "aether_fallback_process_created",
            connect_started_ms,
            process.pid().await,
        );
        match wait_for_core_socks(
            process,
            &fallback,
            fallback_generation,
            fallback.stall_timeout,
        )
        .await
        {
            Ok(()) => Ok(fallback_generation),
            Err(error) => {
                let _ = process.stop().await;
                Err(format!(
                    "MASQUE HTTP/3 failed ({primary_error}); HTTP/2 fallback also failed ({error})"
                ))
            }
        }
    } else {
        let _ = process.stop().await;
        Err(primary_error)
    }
}

/// Directory the endpoint cache lives in - the same local data directory the
/// core's runtime config and logs use.
fn endpoint_cache_dir(app: &AppHandle) -> Option<PathBuf> {
    app.path().app_local_data_dir().ok()
}

fn invalidate_endpoint_cache(app: &AppHandle, reason: &str) {
    if let Some(dir) = endpoint_cache_dir(app) {
        endpoint_cache::invalidate(&dir);
        let _ = app.emit("aether-log", format!("[cache] discarded: {reason}"));
    }
}

fn emit_process_timeline(
    app: &AppHandle,
    timeline_id: &str,
    settings: &Settings,
    stage: &str,
    connect_started_ms: u64,
    process_id: Option<u32>,
) {
    let attempt = active_attempt()
        .read()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone();
    let _ = app.emit(
        "aether-log",
        format!(
            "[timeline] {}",
            serde_json::json!({
                "timelineId": timeline_id,
                "attemptId": attempt.attempt_id,
                "sessionGeneration": attempt.session_generation,
                "protocol": settings.protocol,
                "connectionMode": settings.connection_mode,
                "stage": stage,
                "timestampMs": unix_time_ms(),
                "monotonicMs": monotonic_ms(),
                "elapsedMs": elapsed_since_ms(connect_started_ms),
                "processId": process_id.unwrap_or_default(),
                "result": "success",
                "errorCategory": ""
            })
        ),
    );
}

async fn wait_for_core_socks(
    process: &Arc<ProcessManager>,
    settings: &Settings,
    generation: u64,
    budget_secs: u64,
) -> Result<(), String> {
    let deadline = Instant::now() + std::time::Duration::from_secs(budget_secs);
    loop {
        if process.generation().await != generation {
            return Err("connection attempt cancelled".into());
        }
        if probe_socks(&settings.socks_address).await {
            return Ok(());
        }
        let detail = process.diagnostic_tail().await;
        let lower = detail.to_ascii_lowercase();
        if settings.protocol == "masque"
            && (lower.contains("no usable masque gateway found")
                || lower.contains("prober: no clean endpoint found"))
        {
            return Err(format!("gateway scan failed: {detail}"));
        }
        if !process.is_running().await {
            return Err(if detail.is_empty() {
                "Aether exited before opening its SOCKS5 listener".into()
            } else {
                format!("Aether exited before opening its SOCKS5 listener: {detail}")
            });
        }
        if Instant::now() >= deadline {
            return Err(if detail.is_empty() {
                format!("Aether did not open its SOCKS5 listener within {budget_secs} seconds")
            } else {
                format!(
                    "Aether did not open its SOCKS5 listener within {budget_secs} seconds: {detail}"
                )
            });
        }
        tokio::time::sleep(std::time::Duration::from_millis(150)).await;
    }
}

async fn probe_socks(address: &str) -> bool {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let attempt = async {
        let mut stream = tokio::net::TcpStream::connect(address).await?;
        stream.write_all(&[5, 1, 0]).await?;
        let mut response = [0u8; 2];
        stream.read_exact(&mut response).await?;
        Ok::<bool, std::io::Error>(response == [5, 0])
    };
    tokio::time::timeout(std::time::Duration::from_millis(300), attempt)
        .await
        .ok()
        .and_then(Result::ok)
        .unwrap_or(false)
}
#[tauri::command]
async fn disconnect(app: AppHandle, state: tauri::State<'_, AppState>) -> Result<(), String> {
    let context = active_attempt()
        .read()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone();
    if !context.attempt_id.is_empty() {
        emit_timeline(
            &app,
            &context.attempt_id,
            "",
            "",
            "disconnect_backend_received",
            elapsed_since_ms(0),
        );
    }
    state.session_epoch.fetch_add(1, Ordering::SeqCst);
    // Cancel non-essential probes immediately so they cannot hold up routing cleanup.
    state.process.cancel_background_work().await;
    if !context.attempt_id.is_empty() {
        emit_timeline(
            &app,
            &context.attempt_id,
            "",
            "",
            "routing_stop_started",
            elapsed_since_ms(0),
        );
    }
    let routing_result = state.routing.stop(&app).await;
    // Release system-wide routes and DNS before terminating the SOCKS provider. This ordering
    // keeps cleanup reachable even if the core is already unhealthy.
    let process_result = state.process.stop().await;
    if !context.attempt_id.is_empty() {
        emit_timeline(
            &app,
            &context.attempt_id,
            "",
            "",
            "Aether_stopped",
            elapsed_since_ms(0),
        );
    }
    let firewall_result = configure_lan_firewall(false);
    if !context.attempt_id.is_empty() {
        emit_timeline(
            &app,
            &context.attempt_id,
            "",
            "",
            "disconnect_complete",
            elapsed_since_ms(0),
        );
    }
    emit_status(&app, "disconnected", None, None);
    match (routing_result, process_result, firewall_result) {
        (Ok(()), Ok(()), Ok(())) => Ok(()),
        (_, _, Err(firewall)) => Err(firewall),
        (Err(routing), Ok(()), Ok(())) => Err(format!(
            "Aether stopped, but Windows network cleanup needs attention: {routing}"
        )),
        (Ok(()), Err(process), Ok(())) => Err(format!("Could not stop the Aether core: {process}")),
        (routing, process, Ok(())) => Err(format!(
            "Network cleanup reported errors: Aether={process:?}; routing={routing:?}"
        )),
    }
}
#[tauri::command]
async fn elapsed(state: tauri::State<'_, AppState>) -> Result<u64, String> {
    Ok(state.process.elapsed_secs().await)
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct ConnectionSnapshot {
    state: String,
    elapsed: u64,
}

#[tauri::command]
async fn connection_state(state: tauri::State<'_, AppState>) -> Result<ConnectionSnapshot, String> {
    let core_state = state.process.connection_state().await;
    Ok(ConnectionSnapshot {
        state: core_state.to_string(),
        elapsed: state.process.elapsed_secs().await,
    })
}
#[tauri::command]
async fn traffic_totals(state: tauri::State<'_, AppState>) -> Result<TrafficTotals, String> {
    state.routing.traffic_totals().await
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct VpnProbe {
    ping: Option<u64>,
    location: String,
    exit_ip: String,
}

#[derive(Clone)]
struct TraceProbe {
    exit_ip: String,
    latency_ms: u64,
    country: String,
}

#[derive(Debug)]
struct ProbeFailure {
    message: String,
    transient: bool,
    category: &'static str,
}

#[derive(Default)]
struct LocationCache {
    exit_ip: String,
    location: String,
    retry_after: Option<Instant>,
}

fn normalized_location(value: &serde_json::Value) -> String {
    let clean = |value: Option<&str>, limit: usize| {
        value
            .unwrap_or("")
            .trim()
            .chars()
            .filter(|character| !character.is_control())
            .take(limit)
            .collect::<String>()
    };
    let city = clean(
        value
            .get("city")
            .or_else(|| value.get("town"))
            .or_else(|| value.get("locality"))
            .and_then(|value| value.as_str()),
        80,
    );
    let country_name = clean(
        value
            .get("country_name")
            .or_else(|| value.get("country"))
            .and_then(|value| value.as_str())
            .filter(|country| country.trim().len() > 2),
        80,
    );
    let country_code = clean(
        value
            .get("country_code")
            .or_else(|| value.get("countryCode"))
            .or_else(|| value.get("country"))
            .and_then(|value| value.as_str()),
        2,
    )
    .to_ascii_uppercase();
    let country = if country_name.is_empty() {
        country_code
    } else {
        country_name
    };
    match (city.is_empty(), country.is_empty()) {
        (false, false) => format!("{city}, {country}"),
        (false, true) => city,
        (true, false) => country,
        (true, true) => String::new(),
    }
}

fn provider_matches_exit_ip(value: &serde_json::Value, expected_ip: &str) -> bool {
    value
        .get("ip")
        .or_else(|| value.get("query"))
        .and_then(|value| value.as_str())
        .is_none_or(|reported| reported.trim().eq_ignore_ascii_case(expected_ip))
}

fn emit_location_result(
    app: &AppHandle,
    provider: &str,
    elapsed_ms: u64,
    status: &str,
    result: &str,
) {
    let _ = app.emit(
        "aether-log",
        format!(
            "[location] {}",
            serde_json::json!({
                "provider": provider,
                "elapsedMs": elapsed_ms,
                "status": status,
                "result": result
            })
        ),
    );
}

async fn lookup_vpn_location(
    app: &AppHandle,
    process: &Arc<ProcessManager>,
    generation: u64,
    client: &reqwest::Client,
    exit_ip: &str,
) -> Result<String, String> {
    let providers = [
        ("ipapi.co", format!("https://ipapi.co/{exit_ip}/json/")),
        ("ipwho.is", format!("https://ipwho.is/{exit_ip}")),
        (
            "api.country.is",
            format!("https://api.country.is/{exit_ip}"),
        ),
    ];
    for (provider, url) in providers {
        let started = Instant::now();
        let response = tokio::select! {
            response = client.get(url).send() => response,
            _ = process.wait_for_generation_change(generation) => {
                return Err("Location lookup was cancelled".into());
            }
        };
        let elapsed_ms = started.elapsed().as_millis().min(u64::MAX as u128) as u64;
        let response = match response {
            Ok(response) => response,
            Err(error) => {
                emit_location_result(
                    app,
                    provider,
                    elapsed_ms,
                    "network-error",
                    &display_err(error),
                );
                continue;
            }
        };
        let status = response.status();
        if !status.is_success() {
            emit_location_result(app, provider, elapsed_ms, &status.to_string(), "http-error");
            continue;
        }
        let value = match response.json::<serde_json::Value>().await {
            Ok(value) => value,
            Err(error) => {
                emit_location_result(
                    app,
                    provider,
                    elapsed_ms,
                    "parse-error",
                    &display_err(error),
                );
                continue;
            }
        };
        if value.get("success").and_then(|value| value.as_bool()) == Some(false)
            || value.get("error").and_then(|value| value.as_bool()) == Some(true)
            || !provider_matches_exit_ip(&value, exit_ip)
        {
            emit_location_result(
                app,
                provider,
                elapsed_ms,
                "invalid",
                "provider response rejected",
            );
            continue;
        }
        let location = normalized_location(&value);
        if location.is_empty() {
            emit_location_result(app, provider, elapsed_ms, "empty", "no country or city");
            continue;
        }
        emit_location_result(app, provider, elapsed_ms, "success", &location);
        return Ok(location);
    }
    Ok(String::new())
}

fn location_cache() -> &'static tokio::sync::Mutex<LocationCache> {
    static CACHE: OnceLock<tokio::sync::Mutex<LocationCache>> = OnceLock::new();
    CACHE.get_or_init(|| tokio::sync::Mutex::new(LocationCache::default()))
}

fn data_plane_probe_lock() -> &'static tokio::sync::Mutex<()> {
    static LOCK: OnceLock<tokio::sync::Mutex<()>> = OnceLock::new();
    LOCK.get_or_init(|| tokio::sync::Mutex::new(()))
}

async fn trace_probe_once(
    settings: &Settings,
    through_socks: bool,
    connect_timeout: std::time::Duration,
    request_timeout: std::time::Duration,
) -> Result<TraceProbe, ProbeFailure> {
    let mut builder = reqwest::Client::builder()
        .connect_timeout(connect_timeout)
        .timeout(request_timeout)
        .no_proxy();
    if through_socks {
        let proxy = reqwest::Proxy::all(format!("socks5h://{}", settings.socks_address)).map_err(
            |error| ProbeFailure {
                message: reqwest_error_detail(&error),
                transient: false,
                category: "proxy_configuration",
            },
        )?;
        builder = builder.proxy(proxy);
    }
    let client = builder.build().map_err(|error| ProbeFailure {
        message: reqwest_error_detail(&error),
        transient: false,
        category: "https_client",
    })?;
    let started = Instant::now();
    let response = client
        .get("https://www.cloudflare.com/cdn-cgi/trace")
        .send()
        .await
        .map_err(|error| ProbeFailure {
            transient: error.is_timeout() || error.is_connect() || error.is_request(),
            message: reqwest_error_detail(&error),
            category: classify_reqwest_error(&error),
        })?;
    let status = response.status();
    if !status.is_success() {
        return Err(ProbeFailure {
            message: format!("HTTPS trace returned {status}"),
            transient: status.as_u16() == 408 || status.as_u16() == 429 || status.is_server_error(),
            category: "http_status",
        });
    }
    let trace = response.text().await.map_err(|error| ProbeFailure {
        transient: true,
        message: reqwest_error_detail(&error),
        category: "http_body",
    })?;
    let latency_ms = started.elapsed().as_millis().min(u64::MAX as u128) as u64;
    let (ip, country) = parse_trace_response(&trace)?;
    Ok(TraceProbe {
        exit_ip: ip,
        latency_ms,
        country,
    })
}

fn parse_trace_response(trace: &str) -> Result<(String, String), ProbeFailure> {
    let ip = trace
        .lines()
        .find_map(|line| line.strip_prefix("ip="))
        .unwrap_or("")
        .trim()
        .parse::<std::net::IpAddr>()
        .map_err(|_| ProbeFailure {
            message: "The HTTPS trace did not return a valid public exit IP".into(),
            transient: false,
            category: "http_content",
        })?
        .to_string();
    let country = trace
        .lines()
        .find_map(|line| line.strip_prefix("loc="))
        .unwrap_or("")
        .trim()
        .to_ascii_uppercase();
    Ok((ip, country))
}

fn classify_reqwest_error(error: &reqwest::Error) -> &'static str {
    let detail = reqwest_error_detail(error).to_ascii_lowercase();
    if error.is_timeout() {
        "timeout"
    } else if detail.contains("dns") || detail.contains("name resolution") {
        "dns"
    } else if detail.contains("certificate") || detail.contains("tls") {
        "tls"
    } else if error.is_connect() {
        "tcp_connect"
    } else {
        "https_request"
    }
}

fn reqwest_error_detail(error: &reqwest::Error) -> String {
    use std::error::Error as _;
    let mut details = vec![error.to_string()];
    let mut source = error.source();
    while let Some(current) = source {
        let value = current.to_string();
        if !value.is_empty() && details.last() != Some(&value) {
            details.push(value);
        }
        source = current.source();
    }
    details.join(": ")
}

fn emit_data_plane_phase(
    app: &AppHandle,
    phase: &str,
    attempt: u32,
    duration_ms: u64,
    result: &str,
    error_category: &str,
    error: &str,
) {
    let context = active_attempt()
        .read()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone();
    let _ = app.emit(
        "aether-log",
        format!(
            "[data-plane-phase] {}",
            serde_json::json!({
                "attemptId": context.attempt_id,
                "sessionGeneration": context.session_generation,
                "phase": phase,
                "attempt": attempt,
                "timestampMs": unix_time_ms(),
                "monotonicMs": monotonic_ms(),
                "elapsedMs": monotonic_ms().saturating_sub(context.started_monotonic_ms),
                "durationMs": duration_ms,
                "result": result,
                "errorCategory": error_category,
                "error": error,
                "processId": std::process::id()
            })
        ),
    );
}

async fn system_trace_probe_once(
    app: &AppHandle,
    attempt: u32,
) -> Result<TraceProbe, ProbeFailure> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio_rustls::rustls::{pki_types::ServerName, ClientConfig, RootCertStore};

    let dns_started = Instant::now();
    let addresses = tokio::time::timeout(
        std::time::Duration::from_secs(2),
        tokio::net::lookup_host(("www.cloudflare.com", 443)),
    )
    .await
    .map_err(|_| ProbeFailure {
        message: "DNS resolution timed out after 2 seconds".into(),
        transient: true,
        category: "dns_timeout",
    })?
    .map_err(|error| ProbeFailure {
        message: format!("DNS resolution failed: {error}"),
        transient: true,
        category: "dns",
    })?
    .collect::<Vec<_>>();
    let dns_ms = dns_started.elapsed().as_millis().min(u64::MAX as u128) as u64;
    emit_data_plane_phase(app, "dns", attempt, dns_ms, "success", "", "");
    let address = addresses
        .iter()
        .copied()
        .find(std::net::SocketAddr::is_ipv4)
        .or_else(|| addresses.first().copied())
        .ok_or(ProbeFailure {
            message: "DNS returned no addresses for www.cloudflare.com".into(),
            transient: true,
            category: "dns_empty",
        })?;

    let tcp_started = Instant::now();
    let stream = tokio::time::timeout(
        std::time::Duration::from_secs(2),
        tokio::net::TcpStream::connect(address),
    )
    .await
    .map_err(|_| ProbeFailure {
        message: format!("TCP connection to {address} timed out after 2 seconds"),
        transient: true,
        category: "tcp_timeout",
    })?
    .map_err(|error| ProbeFailure {
        message: format!("TCP connection to {address} failed: {error}"),
        transient: true,
        category: "tcp_connect",
    })?;
    let tcp_ms = tcp_started.elapsed().as_millis().min(u64::MAX as u128) as u64;
    emit_data_plane_phase(app, "tcp", attempt, tcp_ms, "success", "", "");

    let roots = RootCertStore::from_iter(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let config = ClientConfig::builder()
        .with_root_certificates(roots)
        .with_no_client_auth();
    let connector = tokio_rustls::TlsConnector::from(Arc::new(config));
    let server_name = ServerName::try_from("www.cloudflare.com")
        .map_err(|error| ProbeFailure {
            message: format!("Invalid TLS server name: {error}"),
            transient: false,
            category: "tls_configuration",
        })?
        .to_owned();
    let tls_started = Instant::now();
    let mut tls = tokio::time::timeout(
        std::time::Duration::from_secs(3),
        connector.connect(server_name, stream),
    )
    .await
    .map_err(|_| ProbeFailure {
        message: "TLS handshake timed out after 3 seconds".into(),
        transient: true,
        category: "tls_timeout",
    })?
    .map_err(|error| ProbeFailure {
        message: format!("TLS handshake failed: {error}"),
        transient: false,
        category: "tls",
    })?;
    let tls_ms = tls_started.elapsed().as_millis().min(u64::MAX as u128) as u64;
    emit_data_plane_phase(app, "tls", attempt, tls_ms, "success", "", "");

    let http_started = Instant::now();
    tls.write_all(b"GET /cdn-cgi/trace HTTP/1.1\r\nHost: www.cloudflare.com\r\nUser-Agent: Aethon/2.1.1\r\nAccept: text/plain\r\nConnection: close\r\n\r\n")
        .await
        .map_err(|error| ProbeFailure {
            message: format!("HTTPS request write failed: {error}"),
            transient: true,
            category: "http_write",
        })?;
    let mut response = Vec::new();
    tokio::time::timeout(
        std::time::Duration::from_secs(4),
        tls.read_to_end(&mut response),
    )
    .await
    .map_err(|_| ProbeFailure {
        message: "HTTPS response timed out after 4 seconds".into(),
        transient: true,
        category: "http_timeout",
    })?
    .map_err(|error| ProbeFailure {
        message: format!("HTTPS response read failed: {error}"),
        transient: true,
        category: "http_read",
    })?;
    let response = String::from_utf8_lossy(&response);
    let (headers, body) = response.split_once("\r\n\r\n").ok_or(ProbeFailure {
        message: "HTTPS response did not contain a complete header block".into(),
        transient: true,
        category: "http_response",
    })?;
    let status = headers.lines().next().unwrap_or_default();
    if !status.contains(" 200 ") {
        return Err(ProbeFailure {
            message: format!("HTTPS trace returned {status}"),
            transient: status.contains(" 408 ")
                || status.contains(" 429 ")
                || status.contains(" 5"),
            category: "http_status",
        });
    }
    let (exit_ip, country) = parse_trace_response(body)?;
    let http_ms = http_started.elapsed().as_millis().min(u64::MAX as u128) as u64;
    emit_data_plane_phase(app, "http", attempt, http_ms, "success", "", "");
    Ok(TraceProbe {
        exit_ip,
        latency_ms: dns_ms + tcp_ms + tls_ms + http_ms,
        country,
    })
}

#[allow(clippy::too_many_arguments)]
async fn run_trace_probe(
    app: &AppHandle,
    process: &Arc<ProcessManager>,
    generation: u64,
    settings: &Settings,
    through_socks: bool,
    probe_type: &str,
    trigger: &str,
    max_attempts: u8,
    wait_for_slot: bool,
) -> Result<Option<TraceProbe>, String> {
    let permit = if wait_for_slot {
        tokio::select! {
            permit = data_plane_probe_lock().lock() => permit,
            _ = process.wait_for_generation_change(generation) => {
                return Err("Connection validation was cancelled".into());
            }
        }
    } else if let Ok(permit) = data_plane_probe_lock().try_lock() {
        permit
    } else {
        let _ = app.emit(
            "aether-log",
            format!(
                "[probe] {}",
                serde_json::json!({
                    "probeType": probe_type,
                    "triggerSource": trigger,
                    "timestampMs": unix_time_ms(),
                    "processId": std::process::id(),
                    "result": "skipped-overlap",
                    "retryCount": 0
                })
            ),
        );
        return Ok(None);
    };
    let _permit = permit;
    let mut last_error = String::from("HTTPS trace failed");
    let connecting_probe = trigger == "connect_validation";
    let watchdog_probe = trigger.starts_with("watchdog");
    let connect_timeout = std::time::Duration::from_secs(if connecting_probe || watchdog_probe {
        2
    } else {
        4
    });
    let request_timeout = std::time::Duration::from_secs(if watchdog_probe {
        3
    } else if connecting_probe {
        4
    } else {
        8
    });
    for attempt in 0..max_attempts {
        let context = active_attempt()
            .read()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone();
        emit_timeline(
            app,
            &context.attempt_id,
            &settings.protocol,
            &settings.connection_mode,
            "health_probe_started",
            monotonic_ms().saturating_sub(context.started_monotonic_ms),
        );
        let started = Instant::now();
        let result = tokio::select! {
            result = trace_probe_once(settings, through_socks, connect_timeout, request_timeout) => result,
            _ = process.wait_for_generation_change(generation) => {
                return Err("Connection validation was cancelled".into());
            }
        };
        let elapsed_ms = started.elapsed().as_millis().min(u64::MAX as u128) as u64;
        emit_timeline(
            app,
            &context.attempt_id,
            &settings.protocol,
            &settings.connection_mode,
            "health_probe_finished",
            monotonic_ms().saturating_sub(context.started_monotonic_ms),
        );
        match result {
            Ok(probe) => {
                emit_probe_result(
                    app, probe_type, trigger, elapsed_ms, attempt, "success", None, None,
                );
                return Ok(Some(probe));
            }
            Err(error) => {
                last_error = error.message;
                emit_probe_result(
                    app,
                    probe_type,
                    trigger,
                    elapsed_ms,
                    attempt,
                    "failure",
                    Some(&last_error),
                    Some(error.category),
                );
                if !error.transient || attempt + 1 >= max_attempts {
                    break;
                }
                let delay_ms = if connecting_probe {
                    [100u64, 200, 300, 500, 500, 500, 500]
                        .get(attempt as usize)
                        .copied()
                        .unwrap_or(500)
                } else {
                    500 * u64::from(attempt + 1)
                };
                let delay = std::time::Duration::from_millis(delay_ms);
                tokio::select! {
                    _ = tokio::time::sleep(delay) => {}
                    _ = process.wait_for_generation_change(generation) => {
                        return Err("Connection validation was cancelled".into());
                    }
                }
            }
        }
    }
    Err(last_error)
}

/// Prove the system-wide tunnel carries traffic, without waiting for the whole
/// HTTPS exchange and exit-IP comparison.
///
/// This is step 3 of the connect sequence - "basic traffic readiness confirmed".
/// It resolves the probe host and completes a TCP handshake to it *through the
/// system routes*, which is what actually distinguishes a tunnel that carries
/// packets from an adapter that merely exists: the SYN leaves through the TUN,
/// crosses the core and is answered by the far end. It deliberately stops
/// short of TLS, the HTTP round trip and the exit-IP comparison, all of which
/// `spawn_connect_data_plane_validation` completes immediately afterwards.
///
/// A failure here is still fatal to the connection, exactly as before: it means
/// the data plane never came up at all, so `Connected` is never published.
async fn confirm_basic_traffic_readiness(
    app: &AppHandle,
    process: &Arc<ProcessManager>,
    generation: u64,
) -> Result<(), String> {
    let readiness_started = Instant::now();
    let deadline = readiness_started + std::time::Duration::from_secs(12);
    let mut attempt = 0u32;
    loop {
        attempt += 1;
        let probe_started = Instant::now();
        let result = tokio::select! {
        result = system_reachability_probe_once(app, attempt) => result,
        _ = process.wait_for_generation_change(generation) => {
            return Err("Connection validation was cancelled".into());
        }
           };
        let probe_ms = probe_started.elapsed().as_millis().min(u64::MAX as u128) as u64;
        let error = match result {
            Ok(()) => {
                emit_data_plane_phase(
                    app,
                    "basic_traffic_readiness",
                    attempt,
                    readiness_started.elapsed().as_millis() as u64,
                    "success",
                    "",
                    "",
                );
                return Ok(());
            }
            Err(error) => error,
        };
        emit_data_plane_phase(
            app,
            "readiness",
            attempt,
            probe_ms,
            "failure",
            error.category,
            &error.message,
        );
        if !error.transient {
            return Err(format!("{}: {}", error.category, error.message));
        }
        if Instant::now() >= deadline {
            return Err(format!(
  "{}: system data-plane did not become usable within the 12-second measured convergence budget after {attempt} attempts: {}",
         error.category, error.message
     ));
        }
        let delay = match error.category {
            "dns" | "dns_empty" | "dns_timeout" => 300,
            "tcp_connect" | "tcp_timeout" => 200,
            _ => 400,
        };
        tokio::select! {
                   _ = tokio::time::sleep(std::time::Duration::from_millis(delay)) => {}
            _ = process.wait_for_generation_change(generation) => {
         return Err("Connection validation was cancelled".into());
            }
        }
    }
}

/// DNS + TCP to the probe host over the system routes. The first two legs of
/// `system_trace_probe_once`, sharing its timeouts and error categories.
async fn system_reachability_probe_once(app: &AppHandle, attempt: u32) -> Result<(), ProbeFailure> {
    let dns_started = Instant::now();
    let addresses = tokio::time::timeout(
        std::time::Duration::from_secs(2),
        tokio::net::lookup_host(("www.cloudflare.com", 443)),
    )
    .await
    .map_err(|_| ProbeFailure {
        message: "DNS resolution timed out after 2 seconds".into(),
        transient: true,
        category: "dns_timeout",
    })?
    .map_err(|error| ProbeFailure {
        message: format!("DNS resolution failed: {error}"),
        transient: true,
        category: "dns",
    })?
    .collect::<Vec<_>>();
    emit_data_plane_phase(
        app,
        "dns",
        attempt,
        dns_started.elapsed().as_millis().min(u64::MAX as u128) as u64,
        "success",
        "",
        "",
    );
    let address = addresses
        .iter()
        .copied()
        .find(std::net::SocketAddr::is_ipv4)
        .or_else(|| addresses.first().copied())
        .ok_or(ProbeFailure {
            message: "DNS returned no addresses for www.cloudflare.com".into(),
            transient: true,
            category: "dns_empty",
        })?;
    let tcp_started = Instant::now();
    tokio::time::timeout(
        std::time::Duration::from_secs(2),
        tokio::net::TcpStream::connect(address),
    )
    .await
    .map_err(|_| ProbeFailure {
        message: format!("TCP connection to {address} timed out after 2 seconds"),
        transient: true,
        category: "tcp_timeout",
    })?
    .map_err(|error| ProbeFailure {
        message: format!("TCP connection to {address} failed: {error}"),
        transient: true,
        category: "tcp_connect",
    })?;
    emit_data_plane_phase(
        app,
        "tcp",
        attempt,
        tcp_started.elapsed().as_millis().min(u64::MAX as u128) as u64,
        "success",
        "",
        "",
    );
    Ok(())
}

/// Finish the system HTTPS exchange and the exit-IP comparison after the UI has
/// already been told the tunnel is up.
///
/// Nothing is skipped: this is the same `validate_system_data_plane` the connect
/// path used to await inline, with the same 12-second budget, the same retries
/// and the same exit-IP equality requirement. Only the moment the user sees
/// `Connected` has moved ahead of it.
///
/// The state is not left to drift if it fails. The status goes to `error` before
/// anything is torn down, the connection is then stopped, and the failure is
/// written to the timeline under the same `data_plane_failed` stage the blocking
/// path used - so a tunnel that comes up but does not carry verified system
/// traffic ends disconnected rather than sitting on a `Connected` that was never
/// true. The health watchdog is started only once this has passed, which is the
/// order the blocking path had.
#[allow(clippy::too_many_arguments)]
fn spawn_connect_data_plane_validation(
    app: AppHandle,
    process: Arc<ProcessManager>,
    routing: Arc<RoutingManager>,
    settings: Settings,
    generation: u64,
    expected_exit: String,
    timeline_id: String,
    connect_started_ms: u64,
    session_epoch: Arc<AtomicU64>,
    session_token: u64,
) {
    tauri::async_runtime::spawn(async move {
        emit_timeline(
            &app,
            &timeline_id,
            &settings.protocol,
            &settings.connection_mode,
            "t18_system_https_probe_started",
            elapsed_since_ms(connect_started_ms),
        );
        let outcome = validate_system_data_plane(
            &app,
            &process,
            generation,
            &expected_exit,
            "connect_validation",
            true,
        )
        .await;
        // A superseded attempt, or one the user has already disconnected, is not
        // this attempt's failure and must not tear anything down.
        if process.generation().await != generation
            || session_epoch.load(Ordering::SeqCst) != session_token
        {
            return;
        }
        match outcome {
            Ok(()) => {
                emit_timeline(
                    &app,
                    &timeline_id,
                    &settings.protocol,
                    &settings.connection_mode,
                    "t19_system_https_probe_succeeded",
                    elapsed_since_ms(connect_started_ms),
                );
                emit_timeline(
                    &app,
                    &timeline_id,
                    &settings.protocol,
                    &settings.connection_mode,
                    "endpoint_validation_finished",
                    elapsed_since_ms(connect_started_ms),
                );
                spawn_data_plane_monitor(
                    app.clone(),
                    process.clone(),
                    settings,
                    generation,
                    expected_exit,
                    session_epoch,
                    session_token,
                );
            }
            Err(error) => {
                emit_timeline_failure(
                    &app,
                    &timeline_id,
                    &settings.protocol,
                    &settings.connection_mode,
                    "data_plane_failed",
                    &error,
                );
                let message = format!("Xray TUN data-plane validation failed: {error}");
                // This attempt stored its pin a moment ago, on the strength of
                // the SOCKS-level probe. The system-wide plane has now failed,
                // so that pin is withdrawn rather than handed to the next
                // connect.
                invalidate_endpoint_cache(
                    &app,
                    "system-wide validation failed after the endpoints were stored",
                );
                // The UI leaves `Connected` the moment the validation fails, not
                // when the teardown it triggers finishes. Stopping the elevated
                // helper and the core takes seconds, and staying green across
                // that window would be the false Connected this path exists to
                // prevent.
                emit_status(&app, "error", None, Some(message.clone()));
                let _ = app.emit(
                    "aether-log",
                    format!(
                        "[data-plane] {}",
                        serde_json::json!({
                            "state": "error",
                            "phase": "post_connect_system_validation",
                        "timestampMs": unix_time_ms(),
                            "generation": generation,
                        "reason": error
                        })
                    ),
                );
                process.mark_unhealthy().await;
                let _ = routing.stop(&app).await;
                let _ = process.stop().await;
                // The core's own output can publish `reconnecting` or `scanning`
                // on its way down, so the terminal state is restated once the
                // teardown is over.
                emit_status(&app, "error", None, Some(message));
            }
        }
    });
}

async fn validate_system_data_plane(
    app: &AppHandle,
    process: &Arc<ProcessManager>,
    generation: u64,
    expected_exit: &str,
    trigger: &str,
    wait_for_slot: bool,
) -> Result<(), String> {
    if trigger != "connect_validation" {
        let Some(probe) = run_trace_probe(
            app,
            process,
            generation,
            &Settings::default(),
            false,
            "system_https",
            trigger,
            1,
            wait_for_slot,
        )
        .await?
        else {
            return Ok(());
        };
        return if probe.exit_ip == expected_exit {
            Ok(())
        } else {
            Err(format!(
                "system HTTPS exited at {}, but Aether SOCKS5 exited at {expected_exit}",
                probe.exit_ip
            ))
        };
    }

    let permit = tokio::select! {
        permit = data_plane_probe_lock().lock() => permit,
        _ = process.wait_for_generation_change(generation) => {
            return Err("Connection validation was cancelled".into());
        }
    };
    let _permit = permit;
    let readiness_started = Instant::now();
    let deadline = readiness_started + std::time::Duration::from_secs(12);
    let mut attempt = 0u32;
    loop {
        attempt += 1;
        let probe_started = Instant::now();
        let result = tokio::select! {
            result = system_trace_probe_once(app, attempt) => result,
            _ = process.wait_for_generation_change(generation) => {
                return Err("Connection validation was cancelled".into());
            }
        };
        let probe_ms = probe_started.elapsed().as_millis().min(u64::MAX as u128) as u64;
        let (last_error, last_category) = match result {
            Ok(probe) if probe.exit_ip == expected_exit => {
                emit_probe_result(
                    app,
                    "system_https",
                    trigger,
                    probe_ms,
                    attempt.saturating_sub(1).min(u8::MAX as u32) as u8,
                    "success",
                    None,
                    None,
                );
                emit_data_plane_phase(
                    app,
                    "first_packet_and_https",
                    attempt,
                    readiness_started.elapsed().as_millis() as u64,
                    "success",
                    "",
                    "",
                );
                return Ok(());
            }
            Ok(probe) => {
                return Err(format!(
                    "exit_ip_mismatch: system HTTPS exited at {}, but Aether SOCKS5 exited at {expected_exit}",
                    probe.exit_ip
                ));
            }
            Err(error) => {
                let last_error = error.message;
                let last_category = error.category;
                emit_probe_result(
                    app,
                    "system_https",
                    trigger,
                    probe_ms,
                    attempt.saturating_sub(1).min(u8::MAX as u32) as u8,
                    "failure",
                    Some(&last_error),
                    Some(last_category),
                );
                emit_data_plane_phase(
                    app,
                    "readiness",
                    attempt,
                    probe_ms,
                    "failure",
                    last_category,
                    &last_error,
                );
                if !error.transient {
                    return Err(format!("{last_category}: {last_error}"));
                }
                (last_error, last_category)
            }
        };
        if Instant::now() >= deadline {
            return Err(format!(
                "{last_category}: system data-plane did not become usable within the 12-second measured convergence budget after {attempt} attempts: {last_error}"
            ));
        }
        let delay = match last_category {
            "dns" | "dns_empty" | "dns_timeout" => 300,
            "tcp_connect" | "tcp_timeout" => 200,
            _ => 400,
        };
        tokio::select! {
            _ = tokio::time::sleep(std::time::Duration::from_millis(delay)) => {}
            _ = process.wait_for_generation_change(generation) => {
                return Err("Connection validation was cancelled".into());
            }
        }
    }
}

async fn validate_watchdog_data_plane(
    app: &AppHandle,
    process: &Arc<ProcessManager>,
    generation: u64,
    settings: &Settings,
    expected_exit: &str,
) -> Result<(), String> {
    let system = run_trace_probe(
        app,
        process,
        generation,
        settings,
        false,
        "system_https",
        "watchdog",
        1,
        true,
    )
    .await?
    .ok_or("The watchdog data-plane probe was skipped")?;
    if system.exit_ip == expected_exit {
        return Ok(());
    }
    let socks = run_trace_probe(
        app,
        process,
        generation,
        settings,
        true,
        "socks_https",
        "watchdog_exit_validation",
        1,
        true,
    )
    .await?
    .ok_or("The watchdog SOCKS5 comparison probe was skipped")?;
    if system.exit_ip != socks.exit_ip {
        return Err(format!(
            "system HTTPS exited at {}, but Aether SOCKS5 exited at {}",
            system.exit_ip, socks.exit_ip
        ));
    }
    Ok(())
}

#[tauri::command]
async fn vpn_probe(
    app: AppHandle,
    state: tauri::State<'_, AppState>,
    settings: Settings,
) -> Result<VpnProbe, String> {
    settings.validate()?;
    let generation = state.process.generation().await;
    if state.process.connection_state().await != "connected" {
        return Err("Aethon is not connected".into());
    }
    let probe = run_trace_probe(
        &app,
        &state.process,
        generation,
        &settings,
        true,
        "socks_https",
        "frontend_telemetry",
        1,
        false,
    )
    .await?
    .ok_or("A data-plane probe is already in flight")?;
    let client = reqwest::Client::builder()
        .proxy(
            reqwest::Proxy::all(format!("socks5h://{}", settings.socks_address))
                .map_err(display_err)?,
        )
        .connect_timeout(std::time::Duration::from_secs(3))
        .timeout(std::time::Duration::from_secs(5))
        .build()
        .map_err(display_err)?;
    let ip = probe.exit_ip;
    let mut location = String::new();
    if !ip.is_empty() {
        let now = Instant::now();
        let retry_after = {
            let cache = location_cache().lock().await;
            if cache.exit_ip == ip {
                location = cache.location.clone();
                cache.retry_after
            } else {
                None
            }
        };
        if location.is_empty() && retry_after.is_none_or(|deadline| now >= deadline) {
            location = lookup_vpn_location(&app, &state.process, generation, &client, &ip).await?;
            if state.process.generation().await != generation
                || state.process.connection_state().await != "connected"
            {
                return Err("Location lookup was cancelled".into());
            }
            let mut cache = location_cache().lock().await;
            cache.exit_ip = ip.clone();
            cache.location = location.clone();
            cache.retry_after = location
                .is_empty()
                .then(|| now + std::time::Duration::from_secs(60));
        }
    }
    Ok(VpnProbe {
        ping: Some(probe.latency_ms),
        location,
        exit_ip: ip,
    })
}

#[tauri::command]
async fn vpn_ping(
    app: AppHandle,
    state: tauri::State<'_, AppState>,
    settings: Settings,
) -> Result<Option<u64>, String> {
    settings.validate()?;
    let generation = state.process.generation().await;
    if state.process.connection_state().await != "connected" {
        return Err("Aethon is not connected".into());
    }
    let context = active_attempt()
        .read()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone();
    if !context.attempt_id.is_empty() {
        emit_timeline(
            &app,
            &context.attempt_id,
            &settings.protocol,
            &settings.connection_mode,
            "ping_probe_started",
            elapsed_since_ms(0),
        );
    }
    let result = run_trace_probe(
        &app,
        &state.process,
        generation,
        &settings,
        true,
        "socks_https",
        "frontend_ping",
        1,
        true,
    )
    .await?
    .ok_or("A data-plane probe is already in flight")?;
    {
        let mut cache = location_cache().lock().await;
        if cache.exit_ip != result.exit_ip {
            cache.location.clear();
            cache.retry_after = None;
        }
        cache.exit_ip = result.exit_ip;
    }
    if !context.attempt_id.is_empty() {
        emit_timeline(
            &app,
            &context.attempt_id,
            &settings.protocol,
            &settings.connection_mode,
            "ping_available",
            elapsed_since_ms(0),
        );
    }
    Ok(Some(result.latency_ms))
}

#[tauri::command]
async fn vpn_location(
    app: AppHandle,
    state: tauri::State<'_, AppState>,
    settings: Settings,
) -> Result<String, String> {
    settings.validate()?;
    let generation = state.process.generation().await;
    if state.process.connection_state().await != "connected" {
        return Err("Aethon is not connected".into());
    }
    let context = active_attempt()
        .read()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone();
    if !context.attempt_id.is_empty() {
        emit_timeline(
            &app,
            &context.attempt_id,
            &settings.protocol,
            &settings.connection_mode,
            "location_probe_started",
            elapsed_since_ms(0),
        );
    }
    let mut exit_ip = location_cache().lock().await.exit_ip.clone();
    if exit_ip.is_empty() {
        let probe = run_trace_probe(
            &app,
            &state.process,
            generation,
            &settings,
            true,
            "socks_https",
            "frontend_location",
            1,
            true,
        )
        .await?
        .ok_or("A data-plane probe is already in flight")?;
        exit_ip = probe.exit_ip;
        let mut cache = location_cache().lock().await;
        cache.exit_ip = exit_ip.clone();
    }
    let cached = {
        let cache = location_cache().lock().await;
        if cache.exit_ip == exit_ip {
            cache.location.clone()
        } else {
            String::new()
        }
    };
    let location = if !cached.is_empty() {
        cached
    } else {
        let client = reqwest::Client::builder()
            .connect_timeout(std::time::Duration::from_secs(2))
            .timeout(std::time::Duration::from_secs(3))
            .build()
            .map_err(display_err)?;
        let lookup = tokio::time::timeout(
            std::time::Duration::from_secs(8),
            lookup_vpn_location(&app, &state.process, generation, &client, &exit_ip),
        )
        .await
        .map_err(|_| "Location lookup timed out after 8 seconds".to_string())??;
        if state.process.generation().await != generation
            || state.process.connection_state().await != "connected"
        {
            return Err("Location lookup was cancelled".into());
        }
        let mut cache = location_cache().lock().await;
        cache.exit_ip = exit_ip;
        cache.location = lookup.clone();
        cache.retry_after = lookup
            .is_empty()
            .then(|| Instant::now() + std::time::Duration::from_secs(60));
        lookup
    };
    if !context.attempt_id.is_empty() {
        emit_timeline(
            &app,
            &context.attempt_id,
            &settings.protocol,
            &settings.connection_mode,
            "location_available",
            elapsed_since_ms(0),
        );
    }
    Ok(location)
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct DiagnosticsSnapshot {
    protocol: String,
    tun_interface: Option<String>,
    runtime_mtu: Option<u16>,
    exit_ip: String,
    ping_ms: Option<u64>,
    download_bytes: u64,
    upload_bytes: u64,
    dns: String,
    tunnel_state: String,
    reconnect_count: u32,
    last_recovery: Option<String>,
    aether_running: bool,
    aether_version: String,
    xray_running: bool,
    xray_version: &'static str,
}

#[tauri::command]
async fn network_diagnostics(
    app: AppHandle,
    state: tauri::State<'_, AppState>,
    settings: Settings,
) -> Result<DiagnosticsSnapshot, String> {
    settings.validate()?;
    let traffic = state.routing.traffic_totals().await.unwrap_or_default();
    let tunnel_state = state.process.connection_state().await.to_string();
    let (tun_interface, runtime_mtu) = state.routing.runtime_tun_info().await;
    let probe = if tunnel_state == "connected" {
        let generation = state.process.generation().await;
        run_trace_probe(
            &app,
            &state.process,
            generation,
            &settings,
            true,
            "socks_https",
            "diagnostics",
            1,
            false,
        )
        .await
        .ok()
        .flatten()
        .map(|probe| VpnProbe {
            ping: Some(probe.latency_ms),
            location: String::new(),
            exit_ip: probe.exit_ip,
        })
    } else {
        None
    };
    let data_dir = app.path().app_local_data_dir().map_err(display_err)?;
    let recovery = data_dir.join("routing").join("recovery.json");
    let xray_running = state.routing.engine_pid().await.is_some();
    let aether_version = process::verified_core_version(&app)?;
    Ok(DiagnosticsSnapshot {
        protocol: state
            .process
            .runtime_protocol()
            .await
            .unwrap_or_else(|| settings.protocol.clone()),
        tun_interface: tun_interface.clone(),
        runtime_mtu,
        exit_ip: probe
            .as_ref()
            .map(|p| p.exit_ip.clone())
            .unwrap_or_default(),
        ping_ms: probe.and_then(|p| p.ping),
        download_bytes: traffic.downloaded,
        upload_bytes: traffic.uploaded,
        dns: if settings.dns_resolvers.trim().is_empty() {
            "system".into()
        } else {
            settings.dns_resolvers.clone()
        },
        tunnel_state,
        reconnect_count: 0,
        last_recovery: recovery
            .metadata()
            .ok()
            .and_then(|m| m.modified().ok())
            .map(|t| format!("{t:?}")),
        aether_running: state.process.is_running().await,
        aether_version,
        xray_running,
        xray_version: "26.3.27",
    })
}

#[derive(serde::Serialize)]
struct InstalledApplication {
    name: String,
    path: String,
    icon: String,
}

#[tauri::command]
async fn installed_applications() -> Result<Vec<InstalledApplication>, String> {
    #[cfg(windows)]
    {
        let script = "Add-Type -AssemblyName System.Drawing;$roots=@($env:ProgramData+'\\Microsoft\\Windows\\Start Menu\\Programs',$env:APPDATA+'\\Microsoft\\Windows\\Start Menu\\Programs');$w=New-Object -ComObject WScript.Shell;Get-ChildItem $roots -Filter *.lnk -Recurse -ErrorAction SilentlyContinue|%{$s=$w.CreateShortcut($_.FullName);if($s.TargetPath -match '\\.exe$'){$b='';try{$i=[Drawing.Icon]::ExtractAssociatedIcon($s.TargetPath);if($i){$m=New-Object IO.MemoryStream;$i.ToBitmap().Save($m,[Drawing.Imaging.ImageFormat]::Png);$b=[Convert]::ToBase64String($m.ToArray());$m.Dispose();$i.Dispose()}}catch{};('{0}`t{1}`t{2}' -f $_.BaseName,$s.TargetPath,$b)}}";
        let mut command = std::process::Command::new("powershell.exe");
        command.args(["-NoProfile", "-NonInteractive", "-Command", script]);
        use std::os::windows::process::CommandExt;
        command.creation_flags(windows_sys::Win32::System::Threading::CREATE_NO_WINDOW);
        let output = command.output().map_err(display_err)?;
        let mut apps = Vec::new();
        for line in String::from_utf8_lossy(&output.stdout).lines() {
            let mut parts = line.splitn(3, '\t');
            if let (Some(name), Some(path), Some(icon)) = (parts.next(), parts.next(), parts.next())
            {
                apps.push(InstalledApplication {
                    name: name.into(),
                    path: path.into(),
                    icon: icon.into(),
                });
            }
        }
        apps.sort_by_key(|a| a.name.to_lowercase());
        apps.dedup_by(|a, b| a.path.eq_ignore_ascii_case(&b.path));
        Ok(apps)
    }
    #[cfg(not(windows))]
    Ok(Vec::new())
}
#[tauri::command]
async fn load_settings(app: AppHandle) -> Result<Settings, String> {
    let mut settings = load_settings_value(&app)?;
    settings.normalize_protocol_options();
    Ok(settings)
}
fn load_settings_value(app: &AppHandle) -> Result<Settings, String> {
    let path = settings_path(app)?;
    if !path.exists() {
        return Ok(Settings::default());
    }
    serde_json::from_str(&std::fs::read_to_string(path).map_err(display_err)?)
        .map_err(|e| format!("Saved settings are invalid: {e}"))
}
#[tauri::command]
async fn save_settings(app: AppHandle, settings: Settings) -> Result<(), String> {
    let mut settings = settings;
    settings.normalize_protocol_options();
    settings.validate()?;
    let path = settings_path(&app)?;
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(display_err)?;
    }
    std::fs::write(
        path,
        serde_json::to_vec_pretty(&settings).map_err(display_err)?,
    )
    .map_err(display_err)
}
#[tauri::command]
async fn connection_test(settings: Settings) -> Result<String, String> {
    settings.validate()?;
    let proxy = reqwest::Proxy::all(format!("socks5h://{}", settings.socks_address))
        .map_err(display_err)?;
    let client = reqwest::Client::builder()
        .proxy(proxy)
        .timeout(std::time::Duration::from_secs(20))
        .build()
        .map_err(display_err)?;
    client
        .get("https://www.cloudflare.com/cdn-cgi/trace")
        .send()
        .await
        .map_err(|e| format!("Connection test failed: {e}"))?
        .error_for_status()
        .map_err(display_err)?
        .text()
        .await
        .map_err(display_err)
}
fn tray_menu(app: &AppHandle) -> tauri::Result<Menu<tauri::Wry>> {
    let show = MenuItem::with_id(app, "show", "Show Aethon", true, None::<&str>)?;
    let connect = MenuItem::with_id(app, "connect", "Connect", true, None::<&str>)?;
    let disconnect = MenuItem::with_id(app, "disconnect", "Disconnect", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Exit", true, None::<&str>)?;
    Menu::with_items(app, &[&show, &connect, &disconnect, &quit])
}
fn settings_path(app: &AppHandle) -> Result<std::path::PathBuf, String> {
    Ok(app
        .path()
        .app_config_dir()
        .map_err(display_err)?
        .join("settings.json"))
}
fn show_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}
async fn stop_and_exit(app: AppHandle, process: Arc<ProcessManager>, routing: Arc<RoutingManager>) {
    let _ = routing.stop(&app).await;
    let _ = process.stop().await;
    app.exit(0);
}
fn display_err(e: impl std::fmt::Display) -> String {
    e.to_string()
}

#[tauri::command]
async fn repair_network(app: AppHandle) -> Result<(), String> {
    let base = app
        .path()
        .app_local_data_dir()
        .map_err(display_err)?
        .join("routing");
    let recovery = base.join("recovery.json");
    if !recovery.exists() {
        // Fall back to the machine-wide snapshot so a repair still runs when only that copy is left.
        return routing::repair_cli();
    }
    let value: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&recovery).map_err(display_err)?)
            .map_err(display_err)?;
    let session = value
        .get("sessionDir")
        .and_then(|v| v.as_str())
        .ok_or("Recovery snapshot is invalid")?;
    // Repair the session this snapshot names. Delegating to repair_cli() here read a different
    // file, so a stale or missing machine-wide copy made Repair Network report success without
    // releasing the adapter this snapshot describes.
    routing::repair_session(std::path::Path::new(session))
}
#[tauri::command]
async fn recovery_status(app: AppHandle) -> Result<bool, String> {
    let local = app
        .path()
        .app_local_data_dir()
        .map_err(display_err)?
        .join("routing")
        .join("recovery.json")
        .exists();
    Ok(local || routing::cli_recovery_exists())
}

#[tauri::command]
async fn pick_applications(app: AppHandle) -> Result<Vec<String>, String> {
    let files = app
        .dialog()
        .file()
        .add_filter("Windows applications", &["exe"])
        .blocking_pick_files()
        .unwrap_or_default();
    Ok(files
        .into_iter()
        .filter_map(|file| file.into_path().ok())
        .filter(|path| {
            path.is_absolute()
                && path
                    .extension()
                    .is_some_and(|ext| ext.eq_ignore_ascii_case("exe"))
        })
        .map(|path| path.to_string_lossy().into_owned())
        .collect())
}

pub fn run() {
    let process = Arc::new(ProcessManager::default());
    let routing = Arc::new(RoutingManager::default());
    let setup_process = process.clone();
    let setup_routing = routing.clone();
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_single_instance::init(|app, _, _| {
            show_window(app)
        }))
        .manage(AppState {
            process: process.clone(),
            routing: routing.clone(),
            connect_gate: tokio::sync::Mutex::new(()),
            session_epoch: Arc::new(AtomicU64::new(0)),
        })
        .manage(update::UpdateState::default())
        .invoke_handler(tauri::generate_handler![
            connect,
            disconnect,
            elapsed,
            connection_state,
            load_settings,
            save_settings,
            connection_test,
            traffic_totals,
            vpn_probe,
            vpn_ping,
            vpn_location,
            installed_applications,
            network_diagnostics,
            lan_status,
            repair_network,
            recovery_status,
            pick_applications,
            update::check_for_update,
            update::download_update,
            update::install_update
        ])
        .setup(move |app| {
            apply_compact_window_default(app.handle());
            // Hash the shipped executables now, while the window is still opening,
            // so Connect does not have to wait for it later.
            process::prewarm_integrity_checks(app.handle());
            let menu = tray_menu(app.handle())?;
            let tray_process = setup_process.clone();
            let tray_routing = setup_routing.clone();
            TrayIconBuilder::with_id("main")
                .icon(
                    app.default_window_icon()
                        .cloned()
                        .expect("application icon"),
                )
                .tooltip("Aethon - Disconnected")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(move |app, event| match event.id.as_ref() {
                    "show" => show_window(app),
                    "connect" => {
                        show_window(app);
                        let _ = app.emit("tray-connect", ());
                    }
                    "disconnect" => {
                        let app = app.clone();
                        let p = tray_process.clone();
                        let r = tray_routing.clone();
                        tauri::async_runtime::spawn(async move {
                            let _ = r.stop(&app).await;
                            let _ = p.stop().await;
                            emit_status(&app, "disconnected", None, None);
                        });
                    }
                    "quit" => {
                        let app = app.clone();
                        let p = tray_process.clone();
                        let r = tray_routing.clone();
                        tauri::async_runtime::spawn(stop_and_exit(app, p, r));
                    }
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if matches!(
                        event,
                        TrayIconEvent::Click {
                            button: MouseButton::Left,
                            button_state: MouseButtonState::Up,
                            ..
                        }
                    ) {
                        show_window(tray.app_handle());
                    }
                })
                .build(app)?;
            Ok(())
        })
        .on_window_event(move |window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running Aethon");
}

fn apply_compact_window_default(app: &AppHandle) {
    #[cfg(windows)]
    {
        use tauri::{LogicalSize, Size};
        use windows_sys::Win32::Foundation::RECT;
        use windows_sys::Win32::UI::WindowsAndMessaging::{SystemParametersInfoW, SPI_GETWORKAREA};
        let Some(window) = app.get_webview_window("main") else {
            return;
        };
        let Ok(scale) = window.scale_factor() else {
            return;
        };
        let Ok(inner) = window.inner_size() else {
            return;
        };
        let Ok(outer) = window.outer_size() else {
            return;
        };
        let mut work_area: RECT = unsafe { std::mem::zeroed() };
        if unsafe {
            SystemParametersInfoW(SPI_GETWORKAREA, 0, (&mut work_area as *mut RECT).cast(), 0)
        } == 0
        {
            return;
        }
        let work_height = (work_area.bottom - work_area.top).max(0) as f64;
        let non_client_height = outer.height.saturating_sub(inner.height) as f64;
        let available_client_height = (work_height - non_client_height).max(600.0 * scale);
        let target_height = ((774.0 * scale).min(available_client_height * 0.9) / scale).max(600.0);
        let _ = window.set_size(Size::Logical(LogicalSize::new(387.0, target_height)));
        let _ = window.center();
    }
}

#[cfg(test)]
mod tests {
    use super::{
        ensure_socks_address_available, normalized_location, parse_trace_response,
        provider_matches_exit_ip, update_health_failures,
    };

    #[test]
    fn data_plane_health_requires_consecutive_failures() {
        let mut failures = 0;
        assert!(!update_health_failures(&mut failures, false));
        assert!(!update_health_failures(&mut failures, true));
        assert!(!update_health_failures(&mut failures, false));
        assert!(update_health_failures(&mut failures, false));
    }

    #[tokio::test]
    async fn occupied_socks_address_is_rejected_before_startup() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap().to_string();
        let error = ensure_socks_address_available(&address).await.unwrap_err();
        assert!(error.contains("already in use"));
    }

    #[test]
    fn trace_parser_normalizes_exit_country_and_rejects_invalid_ip() {
        let (ip, country) = parse_trace_response("ip=104.28.1.2\nloc=ir\n").unwrap();
        assert_eq!(ip, "104.28.1.2");
        assert_eq!(country, "IR");
        assert!(parse_trace_response("ip=not-an-ip\nloc=DE\n").is_err());
    }

    #[test]
    fn location_normalization_prefers_city_and_country_name() {
        let value = serde_json::json!({
            "ip": "104.28.214.161",
            "city": "Frankfurt",
            "country": "Germany",
            "country_code": "DE"
        });
        assert_eq!(normalized_location(&value), "Frankfurt, Germany");
        assert!(provider_matches_exit_ip(&value, "104.28.214.161"));
        assert!(!provider_matches_exit_ip(&value, "104.28.192.178"));
    }

    #[test]
    fn location_normalization_accepts_country_code_fallback() {
        let value = serde_json::json!({"country": "DE"});
        assert_eq!(normalized_location(&value), "DE");
        assert!(provider_matches_exit_ip(&value, "104.28.214.161"));
    }
}
