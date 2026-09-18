use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, OpenOptions},
    io::Write,
    net::{IpAddr, Ipv4Addr},
    path::{Path, PathBuf},
    sync::Arc,
    time::{Duration, Instant},
};
use tauri::{AppHandle, Emitter, Manager};
use tokio::{
    io::{AsyncBufReadExt, BufReader},
    process::{Child, Command},
    sync::Mutex,
    time::sleep,
};

pub const PSIPHON_SOURCE_COMMIT: &str = "38148cd835e07d688dbb6b30ae24ad2fd0e5d847";
pub const PSIPHON_SOURCE_URL: &str = "https://github.com/Psiphon-Labs/psiphon-tunnel-core";
// Reproducible ConsoleClient build from the pinned source commit with Go 1.26.2,
// GOOS=windows GOARCH=amd64 CGO_ENABLED=0, -trimpath, and -s -w.
pub const PSIPHON_BINARY_SHA256: &str =
    "fc52730ba75425c20125b621ed9889b221d26e82631603501e49f1ce85bd039b";
const AETHER_SOCKS_PORT: u16 = 1819;
const DEFAULT_REGION: &str = "";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub enum PsiphonState {
    #[default]
    Disconnected,
    Starting,
    Connecting,
    Connected,
    Failed,
    Disconnecting,
}

impl PsiphonState {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Disconnected => "disconnected",
            Self::Starting => "starting",
            Self::Connecting => "connecting",
            Self::Connected => "connected",
            Self::Failed => "failed",
            Self::Disconnecting => "disconnecting",
        }
    }

    pub fn can_transition(from: &Self, to: &Self) -> bool {
        matches!(
            (from, to),
            (Self::Disconnected, Self::Starting)
                | (Self::Starting, Self::Connecting)
                | (Self::Connecting, Self::Connected)
                | (Self::Connecting, Self::Failed)
                | (Self::Connected, Self::Disconnecting)
                | (Self::Failed, Self::Starting)
                | (Self::Starting, Self::Disconnecting)
                | (Self::Connecting, Self::Disconnecting)
                | (Self::Disconnecting, Self::Disconnected)
                | (Self::Failed, Self::Disconnected)
                | (Self::Connected, Self::Disconnected)
                | (Self::Disconnected, Self::Disconnected)
        )
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "PascalCase")]
pub struct PsiphonConfig {
    pub propagation_channel_id: String,
    pub sponsor_id: String,
    pub local_socks_proxy_port: u16,
    pub egress_region: String,
    // Serialized as the official Psiphon `UpstreamProxyURL` field.
    pub upstream_proxy_url: String,
}

impl PsiphonConfig {
    pub fn validate(&self, aether_port: u16) -> Result<(), String> {
        if self.propagation_channel_id.trim().is_empty() || self.sponsor_id.trim().is_empty() {
            return Err("Psiphon credentials are unavailable; configure AETHON_PSIPHON_PROPAGATION_CHANNEL_ID and AETHON_PSIPHON_SPONSOR_ID".into());
        }
        if self.propagation_channel_id.len() > 128
            || self.sponsor_id.len() > 128
            || !self
                .propagation_channel_id
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_'))
            || !self
                .sponsor_id
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_'))
        {
            return Err("Psiphon credentials contain unsupported characters".into());
        }
        if !(1024..=65535).contains(&self.local_socks_proxy_port) {
            return Err("Psiphon local SOCKS port must be between 1024 and 65535".into());
        }
        if self.local_socks_proxy_port == aether_port {
            return Err("Psiphon local SOCKS port must differ from Aether port 1819".into());
        }
        if self.egress_region.len() > 32
            || !self
                .egress_region
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_'))
        {
            return Err("Psiphon region contains unsupported characters".into());
        }
        let expected = format!("socks5://127.0.0.1:{aether_port}");
        if self.upstream_proxy_url != expected {
            return Err(format!("Psiphon upstream must be {expected}"));
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PsiphonSnapshot {
    pub state: String,
    pub local_port: Option<u16>,
    pub available_regions: Vec<String>,
    pub diagnostic: Option<String>,
}

#[derive(Default)]
pub struct PsiphonManager {
    child: Mutex<Option<Child>>,
    config_path: Mutex<Option<PathBuf>>,
    state: Mutex<PsiphonState>,
    local_port: Mutex<Option<u16>>,
    available_regions: Mutex<Vec<String>>,
    diagnostic: Mutex<Option<String>>,
    tunnels_ready: Mutex<bool>,
}

impl PsiphonManager {
    pub async fn snapshot(&self) -> PsiphonSnapshot {
        PsiphonSnapshot {
            state: self.state.lock().await.as_str().into(),
            local_port: *self.local_port.lock().await,
            available_regions: self.available_regions.lock().await.clone(),
            diagnostic: self.diagnostic.lock().await.clone(),
        }
    }

    async fn state(&self) -> PsiphonState {
        self.state.lock().await.clone()
    }

    pub async fn start(
        self: &Arc<Self>,
        app: &AppHandle,
        requested_port: u16,
        region: &str,
    ) -> Result<u16, String> {
        self.stop().await?;
        self.set_state(PsiphonState::Starting, None).await;
        let result = self.start_inner(app, requested_port, region).await;
        if let Err(error) = &result {
            self.set_state(PsiphonState::Failed, Some(error.clone()))
                .await;
        }
        result
    }

    async fn start_inner(
        self: &Arc<Self>,
        app: &AppHandle,
        requested_port: u16,
        region: &str,
    ) -> Result<u16, String> {
        let credentials = credentials_from_environment()?;
        let port = allocate_port(requested_port, AETHER_SOCKS_PORT)?;
        let config = PsiphonConfig {
            propagation_channel_id: credentials.0,
            sponsor_id: credentials.1,
            local_socks_proxy_port: port,
            egress_region: if region.trim().is_empty() {
                DEFAULT_REGION.into()
            } else {
                region.trim().into()
            },
            upstream_proxy_url: format!("socks5://127.0.0.1:{AETHER_SOCKS_PORT}"),
        };
        config.validate(AETHER_SOCKS_PORT)?;
        let binary = resolve_bundled_binary(app)?;
        verify_binary_hash(&binary)?;
        let runtime_dir = app
            .path()
            .app_local_data_dir()
            .map_err(display_err)?
            .join("psiphon");
        fs::create_dir_all(&runtime_dir).map_err(display_err)?;
        let config_path = runtime_dir.join("client.config.json");
        write_restrictive_json(&config_path, &config)?;
        let mut command = Command::new(&binary);
        command
            .arg("-config")
            .arg(&config_path)
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .kill_on_drop(true);
        #[cfg(windows)]
        {
            command.creation_flags(windows_sys::Win32::System::Threading::CREATE_NO_WINDOW);
        }
        let mut child = command
            .spawn()
            .map_err(|e| format!("Could not start verified Psiphon binary: {e}"))?;
        let stdout = child
            .stdout
            .take()
            .ok_or("Could not capture Psiphon output")?;
        let stderr = child
            .stderr
            .take()
            .ok_or("Could not capture Psiphon errors")?;
        *self.child.lock().await = Some(child);
        *self.config_path.lock().await = Some(config_path);
        *self.local_port.lock().await = Some(port);
        self.available_regions.lock().await.clear();
        *self.tunnels_ready.lock().await = false;
        self.set_state(PsiphonState::Connecting, None).await;
        spawn_notice_reader(app.clone(), self.clone(), stdout, false);
        spawn_notice_reader(app.clone(), self.clone(), stderr, true);
        let deadline = Instant::now() + Duration::from_secs(60);
        loop {
            if !self.is_running().await {
                return Err("Psiphon exited before establishing a tunnel".into());
            }
            if self.notice_ready().await && probe_socks(port).await {
                if verify_proxy_request(port).await {
                    self.set_state(PsiphonState::Connected, None).await;
                    let monitor = self.clone();
                    let monitor_app = app.clone();
                    tauri::async_runtime::spawn(async move {
                        loop {
                            sleep(Duration::from_secs(1)).await;
                            if monitor.state().await == PsiphonState::Disconnected {
                                break;
                            }
                            if !monitor.is_running().await {
                                monitor
                                    .set_state(
                                        PsiphonState::Failed,
                                        Some("Psiphon exited after tunnel establishment".into()),
                                    )
                                    .await;
                                let _ = monitor_app.emit(
                                    "aether-status",
                                    serde_json::json!({
                                        "state": "psiphon_failed",
                                        "endpoint": null,
                                        "message": "Psiphon exited after tunnel establishment"
                                    }),
                                );
                                break;
                            }
                        }
                    });
                    return Ok(port);
                }
                return Err(
                    "Psiphon SOCKS listener is available but HTTPS validation failed".into(),
                );
            }
            if Instant::now() >= deadline {
                return Err(
                    "Psiphon did not report a tunnel and ready SOCKS listener within 60 seconds"
                        .into(),
                );
            }
            sleep(Duration::from_millis(250)).await;
        }
    }

    pub async fn stop(&self) -> Result<(), String> {
        let had_child = self.child.lock().await.is_some();
        if had_child {
            self.set_state(PsiphonState::Disconnecting, None).await;
        }
        if let Some(mut child) = self.child.lock().await.take() {
            if child.try_wait().map_err(display_err)?.is_none() {
                let _ = child.kill().await;
            }
            let _ = child.wait().await;
        }
        if let Some(path) = self.config_path.lock().await.take() {
            let _ = fs::remove_file(path);
        }
        *self.local_port.lock().await = None;
        self.available_regions.lock().await.clear();
        *self.tunnels_ready.lock().await = false;
        *self.diagnostic.lock().await = None;
        self.set_state(PsiphonState::Disconnected, None).await;
        Ok(())
    }

    pub async fn is_running(&self) -> bool {
        self.child
            .lock()
            .await
            .as_mut()
            .is_some_and(|child| child.try_wait().ok().flatten().is_none())
    }

    async fn notice_ready(&self) -> bool {
        self.local_port.lock().await.is_some() && *self.tunnels_ready.lock().await
    }

    async fn set_state(&self, state: PsiphonState, diagnostic: Option<String>) {
        let mut current = self.state.lock().await;
        if !PsiphonState::can_transition(&current, &state) {
            *self.diagnostic.lock().await = Some(format!(
                "Invalid Psiphon state transition {} -> {}",
                current.as_str(),
                state.as_str()
            ));
            return;
        }
        *current = state;
        drop(current);
        if diagnostic.is_some() {
            *self.diagnostic.lock().await = diagnostic;
        }
    }

    async fn record_notice(&self, app: &AppHandle, line: &str) {
        let parsed = serde_json::from_str::<serde_json::Value>(line).ok();
        let notice_type = parsed
            .as_ref()
            .and_then(|v| v.get("noticeType"))
            .and_then(|v| v.as_str())
            .unwrap_or("");
        if notice_type == "ListeningSocksProxyPort" {
            if let Some(port) = parsed
                .as_ref()
                .and_then(|v| v.get("data"))
                .and_then(|v| v.get("port"))
                .and_then(|v| v.as_u64())
                .and_then(|v| u16::try_from(v).ok())
            {
                *self.local_port.lock().await = Some(port);
            }
        } else if notice_type == "Tunnels" {
            let count = parsed
                .as_ref()
                .and_then(|v| v.get("data"))
                .and_then(|v| v.get("count"))
                .and_then(|v| v.as_u64())
                .unwrap_or(0);
            if count >= 1 {
                *self.tunnels_ready.lock().await = true;
                let mut diagnostic = self.diagnostic.lock().await;
                let existing = diagnostic.take().unwrap_or_default();
                *diagnostic = Some(format!("{existing} Tunnels count={count}"));
            }
        } else if notice_type == "AvailableEgressRegions" {
            if let Some(regions) = parsed
                .as_ref()
                .and_then(|v| v.get("data"))
                .and_then(|v| v.as_array())
            {
                let mut values = self.available_regions.lock().await;
                values.clear();
                values.extend(
                    regions
                        .iter()
                        .filter_map(|v| v.as_str())
                        .filter_map(sanitize_region),
                );
                values.sort();
                values.dedup();
            }
        }
        let _ = app.emit("aether-log", format!("[Psiphon] {line}"));
    }
}

fn credentials_from_environment() -> Result<(String, String), String> {
    let channel = std::env::var("AETHON_PSIPHON_PROPAGATION_CHANNEL_ID").unwrap_or_default();
    let sponsor = std::env::var("AETHON_PSIPHON_SPONSOR_ID").unwrap_or_default();
    if channel.trim().is_empty() || sponsor.trim().is_empty() {
        return Err("Psiphon is unavailable: credentials are not configured".into());
    }
    Ok((channel, sponsor))
}

fn resolve_bundled_binary(app: &AppHandle) -> Result<PathBuf, String> {
    let resource = app.path().resource_dir().map_err(display_err)?;
    let candidates = [
        resource.join("psiphon-tunnel-core-x86_64-pc-windows-msvc.exe"),
        resource.join("binaries/psiphon-tunnel-core-x86_64-pc-windows-msvc.exe"),
    ];
    candidates
        .into_iter()
        .find(|path| path.is_file())
        .ok_or_else(|| format!("Psiphon is unavailable: no bundled verified x86_64 sidecar exists for source commit {PSIPHON_SOURCE_COMMIT}"))
}

fn verify_binary_hash(path: &Path) -> Result<(), String> {
    if PSIPHON_BINARY_SHA256.len() != 64 {
        return Err(format!("Psiphon is unavailable: no reviewed x86_64 binary SHA-256 pin exists ({PSIPHON_SOURCE_URL})"));
    }
    let data = fs::read(path).map_err(display_err)?;
    let actual = format!("{:x}", Sha256::digest(data));
    if !actual.eq_ignore_ascii_case(PSIPHON_BINARY_SHA256) {
        return Err(format!(
            "Psiphon checksum mismatch: expected {PSIPHON_BINARY_SHA256}, got {actual}"
        ));
    }
    Ok(())
}

fn write_restrictive_json(path: &Path, config: &PsiphonConfig) -> Result<(), String> {
    let bytes = serde_json::to_vec_pretty(config).map_err(display_err)?;
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)
        .map_err(display_err)?;
    file.write_all(&bytes).map_err(display_err)?;
    file.flush().map_err(display_err)
}

fn allocate_port(requested: u16, aether_port: u16) -> Result<u16, String> {
    if requested != 0 {
        if requested == aether_port {
            return Err("Psiphon local SOCKS port collides with Aether port 1819".into());
        }
        if !(1024..=65535).contains(&requested) {
            return Err("Psiphon local SOCKS port must be between 1024 and 65535".into());
        }
        let listener = std::net::TcpListener::bind((IpAddr::V4(Ipv4Addr::LOCALHOST), requested))
            .map_err(|_| format!("Psiphon local SOCKS port {requested} is already in use"))?;
        drop(listener);
        return Ok(requested);
    }
    let listener =
        std::net::TcpListener::bind((IpAddr::V4(Ipv4Addr::LOCALHOST), 0)).map_err(display_err)?;
    let port = listener.local_addr().map_err(display_err)?.port();
    if port == aether_port {
        return Err("Dynamically allocated Psiphon port collided with Aether port".into());
    }
    Ok(port)
}

fn sanitize_region(value: &str) -> Option<String> {
    let value = value.trim();
    if value.is_empty()
        || value.len() > 32
        || !value
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_'))
    {
        None
    } else {
        Some(value.to_string())
    }
}

fn spawn_notice_reader<R>(app: AppHandle, manager: Arc<PsiphonManager>, reader: R, stderr: bool)
where
    R: tokio::io::AsyncRead + Unpin + Send + 'static,
{
    tauri::async_runtime::spawn(async move {
        let mut lines = BufReader::new(reader).lines();
        while let Ok(Some(line)) = lines.next_line().await {
            if stderr {
                *manager.diagnostic.lock().await = Some(line.clone());
                let _ = app.emit("aether-log", format!("[Psiphon stderr] {line}"));
            } else {
                manager.record_notice(&app, &line).await;
            }
        }
    });
}

async fn probe_socks(port: u16) -> bool {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let attempt = async {
        let mut stream = tokio::net::TcpStream::connect((Ipv4Addr::LOCALHOST, port)).await?;
        stream.write_all(&[5, 1, 0]).await?;
        let mut response = [0_u8; 2];
        stream.read_exact(&mut response).await?;
        Ok::<bool, std::io::Error>(response == [5, 0])
    };
    tokio::time::timeout(Duration::from_millis(500), attempt)
        .await
        .ok()
        .and_then(Result::ok)
        .unwrap_or(false)
}

async fn verify_proxy_request(port: u16) -> bool {
    let Ok(proxy) = reqwest::Proxy::all(format!("socks5h://127.0.0.1:{port}")) else {
        return false;
    };
    let Ok(client) = reqwest::Client::builder()
        .proxy(proxy)
        .timeout(Duration::from_secs(15))
        .build()
    else {
        return false;
    };
    client
        .get("https://www.cloudflare.com/cdn-cgi/trace")
        .send()
        .await
        .is_ok_and(|response| response.status().is_success())
}

fn display_err(error: impl std::fmt::Display) -> String {
    error.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn config() -> PsiphonConfig {
        PsiphonConfig {
            propagation_channel_id: "channel".into(),
            sponsor_id: "sponsor".into(),
            local_socks_proxy_port: 20991,
            egress_region: String::new(),
            upstream_proxy_url: "socks5://127.0.0.1:1819".into(),
        }
    }

    #[test]
    fn config_rejects_aether_port_collision() {
        let mut value = config();
        value.local_socks_proxy_port = 1819;
        assert!(value.validate(1819).is_err());
    }

    #[test]
    fn config_rejects_non_aether_upstream() {
        let mut value = config();
        value.upstream_proxy_url = "socks5://127.0.0.1:20991".into();
        assert!(value.validate(1819).is_err());
    }

    #[test]
    fn config_accepts_auto_region() {
        assert!(config().validate(1819).is_ok());
    }

    #[test]
    fn region_sanitization_is_conservative() {
        assert_eq!(sanitize_region("US"), Some("US".into()));
        assert!(sanitize_region("US West").is_none());
    }

    #[test]
    fn missing_binary_pin_fails_closed() {
        assert!(verify_binary_hash(Path::new("missing.exe")).is_err());
    }

    #[test]
    fn state_machine_rejects_invalid_transitions() {
        assert!(PsiphonState::can_transition(
            &PsiphonState::Disconnected,
            &PsiphonState::Starting
        ));
        assert!(PsiphonState::can_transition(
            &PsiphonState::Connecting,
            &PsiphonState::Failed
        ));
        assert!(!PsiphonState::can_transition(
            &PsiphonState::Disconnected,
            &PsiphonState::Connected
        ));
        assert!(!PsiphonState::can_transition(
            &PsiphonState::Connected,
            &PsiphonState::Starting
        ));
    }
}
