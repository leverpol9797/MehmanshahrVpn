use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::path::PathBuf;
use tauri::{AppHandle, Emitter, Manager};
use tokio::io::AsyncWriteExt;
use tokio::sync::Mutex;

const RELEASE_API: &str = "https://api.github.com/repos/hamvex/AetherGUI/releases?per_page=30";
const DOWNLOAD_PREFIX: &str = "https://github.com/hamvex/AetherGUI/releases/download/";
const CHECKSUM_ASSET: &str = "SHA256SUMS.txt";

/// The publisher every downloaded installer must be signed by, pinned into the binary at
/// build time by the release pipeline (`AETHON_UPDATE_PUBLISHER`).
///
/// When it is not set the signature must still be valid and trusted — that check is never
/// optional — but no particular publisher is required, so a build produced without the
/// variable will accept any installer Windows trusts. The release workflow sets it; see
/// the signing gate for why a release without it is not approvable.
const EXPECTED_PUBLISHER: Option<&str> = option_env!("AETHON_UPDATE_PUBLISHER");

/// Resolve `candidate` and require that it really is an official release download.
///
/// A `starts_with` test on the raw string is not enough: `.../download/../../../elsewhere`
/// passes it, and the HTTP client then normalises the path and fetches something else
/// entirely. Parsing first and testing the *resolved* url closes that, and lets the
/// scheme, host, port and credentials be pinned at the same time.
fn pinned_release_url(candidate: &str) -> Result<reqwest::Url, String> {
    let prefix = reqwest::Url::parse(DOWNLOAD_PREFIX).map_err(|error| error.to_string())?;
    let url = reqwest::Url::parse(candidate)
        .map_err(|_| "The release URL could not be parsed".to_string())?;
    let pinned = url.scheme() == "https"
        && url.host_str() == prefix.host_str()
        && url.port().is_none()
        && url.username().is_empty()
        && url.password().is_none()
        && url.path().starts_with(prefix.path());
    if !pinned {
        return Err("The release URL is not an official Aethon release URL".into());
    }
    Ok(url)
}

/// Require `candidate` to be the download URL of exactly `name` inside release `tag`.
///
/// `pinned_release_url` proves the URL lives under the release-download prefix; it does not
/// prove it is the asset whose name was matched. GitHub pairs the two in its API response,
/// and the updater is not allowed to trust that pairing — an altered response could put a
/// legitimate-looking asset name next to a different path in the same release area. Binding
/// the last two path segments to the tag and filename the updater decided it wanted makes
/// the expected artifact part of the check rather than metadata taken on trust.
fn pinned_release_asset(candidate: &str, tag: &str, name: &str) -> Result<reqwest::Url, String> {
    let url = pinned_release_url(candidate)?;
    let segments: Vec<&str> = url
        .path_segments()
        .map(|segments| segments.collect())
        .unwrap_or_default();
    let tail_matches = match segments.as_slice() {
        [.., release_tag, asset_name] => *release_tag == tag && *asset_name == name,
        _ => false,
    };
    if !tail_matches {
        return Err(format!(
            "The release URL does not point at {name} in release {tag}"
        ));
    }
    Ok(url)
}

/// Require a trusted Authenticode signature from the pinned publisher, or refuse.
///
/// The SHA-256 the updater compares against travels with the installer from the same
/// server, so on its own it proves only that the transfer was intact. This is the check
/// that says who built the bytes, and it is the reason an attacker who can serve both the
/// installer and its manifest still cannot get an installer executed.
fn verify_publisher(path: &std::path::Path, handle: Option<&std::fs::File>) -> Result<(), String> {
    let signer = crate::signature::verify(path, handle)
        .map_err(|error| format!("The installer was rejected: {error}"))?;
    match EXPECTED_PUBLISHER {
        Some(expected) if !signer.is(expected) => Err(format!(
            "The installer is signed by \"{}\" but Aethon updates must be signed by \"{expected}\"",
            signer.name
        )),
        _ => Ok(()),
    }
}

/// Open `path` for reading in a way that stops it being replaced while it is trusted.
///
/// Hashing a file, checking its signature and then handing its *path* to `CreateProcess`
/// leaves a window in which anything with write access to the cache directory — which
/// lives under `%LOCALAPPDATA%` and is writable by the unprivileged user — can swap the
/// verified bytes for unverified ones. Holding a handle that permits further readers but
/// no writers and no deletion for the whole verify-then-launch sequence removes the
/// window instead of narrowing it.
fn open_for_verified_launch(path: &std::path::Path) -> Result<std::fs::File, String> {
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        use windows_sys::Win32::Storage::FileSystem::FILE_SHARE_READ;
        std::fs::OpenOptions::new()
            .read(true)
            .share_mode(FILE_SHARE_READ)
            .open(path)
            .map_err(|error| format!("The cached installer could not be opened: {error}"))
    }
    #[cfg(not(windows))]
    {
        std::fs::File::open(path)
            .map_err(|error| format!("The cached installer could not be opened: {error}"))
    }
}

/// SHA-256 of everything `file` holds, read through the handle rather than by path.
fn sha256_of(file: &mut std::fs::File) -> Result<String, String> {
    use std::io::{Read, Seek, SeekFrom};
    file.seek(SeekFrom::Start(0))
        .map_err(|error| error.to_string())?;
    let mut hasher = Sha256::new();
    let mut buffer = vec![0u8; 1 << 16];
    loop {
        let read = file.read(&mut buffer).map_err(|error| error.to_string())?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(format!("{:x}", hasher.finalize()))
}

#[derive(Default)]
pub struct UpdateState {
    downloaded: Mutex<Option<DownloadedUpdate>>,
}

struct DownloadedUpdate {
    path: PathBuf,
    sha256: String,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfo {
    pub current_version: String,
    pub latest_version: String,
    /// The release tag the installer belongs to, kept so the download step can re-derive
    /// the exact expected artifact path instead of trusting the URL it was handed.
    pub release_tag: String,
    pub release_notes: String,
    pub download_url: String,
    pub sha256: String,
    pub available: bool,
}

#[derive(Deserialize)]
struct GithubRelease {
    tag_name: String,
    body: Option<String>,
    assets: Vec<GithubAsset>,
}

#[derive(Deserialize)]
struct GithubAsset {
    name: String,
    browser_download_url: String,
    digest: Option<String>,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct UpdateProgress {
    downloaded_bytes: u64,
    total_bytes: Option<u64>,
    percent: Option<u8>,
    status: &'static str,
    speed_bytes_per_second: Option<u64>,
}

fn client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .user_agent(concat!("Aethon-Update/", env!("CARGO_PKG_VERSION")))
        .timeout(std::time::Duration::from_secs(60))
        .build()
        .map_err(|error| error.to_string())
}

fn normalized_version(value: &str) -> &str {
    value.trim().trim_start_matches(['v', 'V'])
}

fn version_parts(value: &str) -> Option<Vec<u64>> {
    normalized_version(value)
        .split('.')
        .map(str::parse::<u64>)
        .collect::<Result<Vec<_>, _>>()
        .ok()
}

pub(crate) fn is_newer_version(latest: &str, current: &str) -> bool {
    match (version_parts(latest), version_parts(current)) {
        (Some(mut latest), Some(mut current)) => {
            let length = latest.len().max(current.len());
            latest.resize(length, 0);
            current.resize(length, 0);
            latest > current
        }
        _ => false,
    }
}

async fn checksum_from_manifest(
    http: &reqwest::Client,
    release: &GithubRelease,
    installer_name: &str,
) -> Result<String, String> {
    let asset = release
        .assets
        .iter()
        .find(|asset| asset.name == CHECKSUM_ASSET)
        .ok_or("The release does not provide a SHA-256 checksum")?;
    let source = pinned_release_asset(
        &asset.browser_download_url,
        &release.tag_name,
        CHECKSUM_ASSET,
    )
    .map_err(|_| "The checksum URL is not an official Aethon release URL".to_string())?;
    let text = http
        .get(source)
        .send()
        .await
        .map_err(|error| error.to_string())?
        .error_for_status()
        .map_err(|error| error.to_string())?
        .text()
        .await
        .map_err(|error| error.to_string())?;
    text.lines()
        .filter_map(|line| {
            let mut fields = line.split_whitespace();
            Some((fields.next()?, fields.next()?.trim_start_matches('*')))
        })
        .find_map(|(hash, name)| {
            name.eq_ignore_ascii_case(installer_name)
                .then(|| hash.to_lowercase())
        })
        .filter(|hash| hash.len() == 64 && hash.chars().all(|c| c.is_ascii_hexdigit()))
        .ok_or_else(|| format!("No SHA-256 checksum was found for {installer_name}"))
}

async fn latest_update() -> Result<UpdateInfo, String> {
    let http = client()?;
    let releases = http
        .get(RELEASE_API)
        .send()
        .await
        .map_err(|error| format!("Update check failed: {error}"))?
        .error_for_status()
        .map_err(|error| format!("Update check failed: {error}"))?
        .json::<Vec<GithubRelease>>()
        .await
        .map_err(|error| format!("Invalid update metadata: {error}"))?;
    // Single-sourced from Cargo.toml so the installed version can never drift away from the
    // package version and silently offer the running build to itself as an update.
    let current = env!("CARGO_PKG_VERSION");
    let (release, latest, installer_name, asset) = releases
        .iter()
        .filter_map(|release| {
            let latest = normalized_version(&release.tag_name).to_string();
            if !is_newer_version(&latest, current) {
                return None;
            }
            let installer_name = format!("Aethon-VPN-v{latest}-Windows-x64-Installer.exe");
            let asset = release
                .assets
                .iter()
                .find(|asset| asset.name == installer_name)?;
            Some((release, latest, installer_name, asset))
        })
        .next()
        .or_else(|| {
            releases
                .iter()
                .filter_map(|release| {
                    let latest = normalized_version(&release.tag_name).to_string();
                    let installer_name = format!("Aethon-VPN-v{latest}-Windows-x64-Installer.exe");
                    let asset = release
                        .assets
                        .iter()
                        .find(|asset| asset.name == installer_name)?;
                    Some((release, latest, installer_name, asset))
                })
                .next()
        })
        .ok_or("No compatible Windows update package is available")?;
    // Pinned here as well as at download time so a rejected url can never reach the UI as
    // an offer the user is invited to accept.
    let download_url = pinned_release_asset(
        &asset.browser_download_url,
        &release.tag_name,
        &installer_name,
    )
    .map_err(|_| "The installer URL is not an official Aethon release URL".to_string())?;
    let sha256 = match asset
        .digest
        .as_deref()
        .and_then(|value| value.strip_prefix("sha256:"))
    {
        Some(hash) if hash.len() == 64 && hash.chars().all(|c| c.is_ascii_hexdigit()) => {
            hash.to_lowercase()
        }
        _ => checksum_from_manifest(&http, release, &installer_name).await?,
    };
    Ok(UpdateInfo {
        available: is_newer_version(&latest, current),
        current_version: current.into(),
        latest_version: latest,
        release_tag: release.tag_name.clone(),
        release_notes: release.body.clone().unwrap_or_default(),
        download_url: download_url.to_string(),
        sha256,
    })
}

#[tauri::command]
pub async fn check_for_update(app: AppHandle) -> Result<UpdateInfo, String> {
    let info = latest_update().await?;
    if info.available {
        if let Some(tray) = app.tray_by_id("main") {
            let _ = tray.set_tooltip(Some(format!(
                "Aethon {} update available",
                info.latest_version
            )));
        }
    }
    Ok(info)
}

#[tauri::command]
pub async fn download_update(
    app: AppHandle,
    state: tauri::State<'_, UpdateState>,
) -> Result<String, String> {
    let info = latest_update().await?;
    if !info.available {
        return Err("Aethon is already up to date".into());
    }
    let directory = app
        .path()
        .app_cache_dir()
        .map_err(|error| error.to_string())?
        .join("updates");
    tokio::fs::create_dir_all(&directory)
        .await
        .map_err(|error| error.to_string())?;
    let filename = format!("Aethon_{}_x64-setup.exe", info.latest_version);
    let final_path = directory.join(&filename);
    let partial_path = directory.join(format!("{filename}.part"));
    let http = client()?;
    // Re-checked rather than trusted from `info`: this is the value that becomes a request,
    // and it is re-bound to the artifact this updater decided it wanted — the installer for
    // `latest_version` inside `release_tag` — not merely to something under the prefix.
    let expected_asset = format!(
        "Aethon-VPN-v{}-Windows-x64-Installer.exe",
        info.latest_version
    );
    let source = pinned_release_asset(&info.download_url, &info.release_tag, &expected_asset)?;
    let response = http
        .get(source)
        .send()
        .await
        .map_err(|error| format!("Update download failed: {error}"))?
        .error_for_status()
        .map_err(|error| format!("Update download failed: {error}"))?;
    let total = response.content_length();
    let mut stream = response.bytes_stream();
    let mut file = tokio::fs::File::create(&partial_path)
        .await
        .map_err(|error| error.to_string())?;
    let mut hasher = Sha256::new();
    let mut downloaded = 0u64;
    let started = std::time::Instant::now();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|error| format!("Update download failed: {error}"))?;
        file.write_all(&chunk)
            .await
            .map_err(|error| error.to_string())?;
        hasher.update(&chunk);
        downloaded += chunk.len() as u64;
        let percent =
            total.map(|size| ((downloaded.saturating_mul(100) / size.max(1)).min(100)) as u8);
        let _ = app.emit(
            "update-progress",
            UpdateProgress {
                downloaded_bytes: downloaded,
                total_bytes: total,
                percent,
                status: "downloading",
                speed_bytes_per_second: Some(downloaded / started.elapsed().as_secs().max(1)),
            },
        );
    }
    file.flush().await.map_err(|error| error.to_string())?;
    drop(file);
    let actual = format!("{:x}", hasher.finalize());
    if actual != info.sha256 {
        let _ = tokio::fs::remove_file(&partial_path).await;
        return Err("The downloaded installer failed SHA-256 verification".into());
    }
    // Refused here, before the download is promoted out of its `.part` name, so an
    // installer that is not signed by the pinned publisher is never left somewhere that
    // looks ready to run. Checked again at install time against the handle that launches
    // it; this pass is what reports the problem while the user is still watching.
    if let Err(error) = verify_publisher(&partial_path, None) {
        let _ = tokio::fs::remove_file(&partial_path).await;
        return Err(error);
    }
    if tokio::fs::try_exists(&final_path).await.unwrap_or(false) {
        tokio::fs::remove_file(&final_path)
            .await
            .map_err(|error| error.to_string())?;
    }
    tokio::fs::rename(&partial_path, &final_path)
        .await
        .map_err(|error| error.to_string())?;
    *state.downloaded.lock().await = Some(DownloadedUpdate {
        path: final_path,
        sha256: info.sha256,
    });
    let _ = app.emit(
        "update-progress",
        UpdateProgress {
            downloaded_bytes: downloaded,
            total_bytes: total,
            percent: Some(100),
            status: "ready",
            speed_bytes_per_second: Some(downloaded / started.elapsed().as_secs().max(1)),
        },
    );
    Ok(info.latest_version)
}

#[tauri::command]
pub async fn install_update(
    app: AppHandle,
    state: tauri::State<'_, UpdateState>,
) -> Result<(), String> {
    let downloaded = state.downloaded.lock().await;
    let update = downloaded
        .as_ref()
        .ok_or("No verified update is ready to install")?;

    // Everything below happens against one handle that permits no writers and no
    // deletion, so the bytes hashed, the bytes whose signature is checked, and the bytes
    // Windows executes are provably the same bytes. Nothing here can fall back to
    // launching an installer that failed a check.
    let mut handle = open_for_verified_launch(&update.path)?;
    if sha256_of(&mut handle)? != update.sha256 {
        return Err("The cached installer failed SHA-256 verification".into());
    }
    verify_publisher(&update.path, Some(&handle))?;

    std::process::Command::new(&update.path)
        .spawn()
        .map_err(|error| format!("Could not start the installer: {error}"))?;
    drop(handle);
    app.exit(0);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn semantic_versions_compare_numerically() {
        assert!(is_newer_version("1.11.1", "1.11.0"));
        assert!(is_newer_version("2.0.0", "1.11.1"));
        assert!(is_newer_version("v2.0.0", "1.99.99"));
        assert!(!is_newer_version("1.11.1", "1.11.1"));
        assert!(!is_newer_version("invalid", "1.11.1"));
    }

    #[test]
    fn the_endpoints_this_updater_trusts_are_https_and_nothing_else() {
        for endpoint in [RELEASE_API, DOWNLOAD_PREFIX] {
            let url = reqwest::Url::parse(endpoint).expect("a pinned endpoint must parse");
            assert_eq!("https", url.scheme(), "{endpoint}");
            assert!(url.port().is_none(), "{endpoint}");
            assert!(url.username().is_empty(), "{endpoint}");
            assert!(url.password().is_none(), "{endpoint}");
        }
        assert_eq!(
            Some("api.github.com"),
            reqwest::Url::parse(RELEASE_API).unwrap().host_str()
        );
        assert_eq!(
            Some("github.com"),
            reqwest::Url::parse(DOWNLOAD_PREFIX).unwrap().host_str()
        );
    }

    #[test]
    fn a_genuine_release_asset_url_is_accepted_unchanged() {
        let asset = "https://github.com/hamvex/AetherGUI/releases/download/v2.1.1/Aethon-VPN-v2.1.1-Windows-x64-Installer.exe";
        assert_eq!(asset, pinned_release_url(asset).unwrap().as_str());
    }

    #[test]
    fn traversal_that_escapes_the_release_path_is_refused_after_resolution() {
        // The whole point of parsing: every one of these passes a `starts_with` test on
        // DOWNLOAD_PREFIX, and every one of them resolves somewhere else.
        for escape in [
            "https://github.com/hamvex/AetherGUI/releases/download/../../../../evil.exe",
            "https://github.com/hamvex/AetherGUI/releases/download/v1/../../../../../evil.exe",
            "https://github.com/hamvex/AetherGUI/releases/download/..%2f..%2f..%2f..%2fevil.exe",
        ] {
            let resolved = reqwest::Url::parse(escape).unwrap();
            let escaped = !resolved
                .path()
                .starts_with("/hamvex/AetherGUI/releases/download/");
            assert_eq!(
                escaped,
                pinned_release_url(escape).is_err(),
                "resolved to {} — accepted: {}",
                resolved.path(),
                pinned_release_url(escape).is_ok()
            );
        }
    }

    #[test]
    fn a_url_that_is_not_an_official_release_download_is_refused() {
        for rejected in [
            "http://github.com/hamvex/AetherGUI/releases/download/v2.1.1/x.exe",
            "https://github.com.evil.test/hamvex/AetherGUI/releases/download/v2.1.1/x.exe",
            "https://evil.test/hamvex/AetherGUI/releases/download/v2.1.1/x.exe",
            "https://github.com:8443/hamvex/AetherGUI/releases/download/v2.1.1/x.exe",
            "https://user:pass@github.com/hamvex/AetherGUI/releases/download/v2.1.1/x.exe",
            "https://github.com/someone-else/AetherGUI/releases/download/v2.1.1/x.exe",
            "https://github.com/hamvex/AetherGUI/archive/refs/tags/v2.1.1.zip",
            "file:///C:/Windows/System32/calc.exe",
            "not a url at all",
            "",
        ] {
            assert!(
                pinned_release_url(rejected).is_err(),
                "accepted a url it must refuse: {rejected}"
            );
        }
    }

    #[test]
    fn the_download_url_must_name_the_artifact_the_updater_chose() {
        let tag = "v2.1.1";
        let asset = "Aethon-VPN-v2.1.1-Windows-x64-Installer.exe";
        let genuine =
            format!("https://github.com/hamvex/AetherGUI/releases/download/{tag}/{asset}");
        assert_eq!(
            genuine,
            pinned_release_asset(&genuine, tag, asset).unwrap().as_str()
        );
        assert_eq!(
            "https://github.com/hamvex/AetherGUI/releases/download/v2.1.1/SHA256SUMS.txt",
            pinned_release_asset(
                "https://github.com/hamvex/AetherGUI/releases/download/v2.1.1/SHA256SUMS.txt",
                tag,
                CHECKSUM_ASSET
            )
            .unwrap()
            .as_str()
        );

        // Every one of these is under the release-download prefix, so the prefix check alone
        // lets them through. None of them is the artifact that was asked for.
        for mismatched in [
            // A different file in the same release.
            "https://github.com/hamvex/AetherGUI/releases/download/v2.1.1/something-else.exe",
            // The right filename, but attached to a different release.
            "https://github.com/hamvex/AetherGUI/releases/download/v0.1.0/Aethon-VPN-v2.1.1-Windows-x64-Installer.exe",
            // The right name buried one level deeper than a release asset can live.
            "https://github.com/hamvex/AetherGUI/releases/download/v2.1.1/nested/Aethon-VPN-v2.1.1-Windows-x64-Installer.exe",
            // The name as a query string rather than the path that is actually fetched.
            "https://github.com/hamvex/AetherGUI/releases/download/v2.1.1/other.exe?name=Aethon-VPN-v2.1.1-Windows-x64-Installer.exe",
            // The name as a fragment, likewise never sent to the server.
            "https://github.com/hamvex/AetherGUI/releases/download/v2.1.1/other.exe#Aethon-VPN-v2.1.1-Windows-x64-Installer.exe",
            // No tag segment at all.
            "https://github.com/hamvex/AetherGUI/releases/download/Aethon-VPN-v2.1.1-Windows-x64-Installer.exe",
        ] {
            assert!(
                pinned_release_asset(mismatched, tag, asset).is_err(),
                "accepted a url that is not the expected artifact: {mismatched}"
            );
        }
    }

    #[cfg(windows)]
    #[test]
    fn the_launch_handle_blocks_writers_and_deletion_but_still_allows_execution() {
        // The verify-then-launch handle is only worth holding if it does both jobs: keeps
        // the bytes from changing, and does not stop Windows starting the process.
        let source = std::env::current_exe().expect("test binary path");
        let copy = std::env::temp_dir().join(format!(
            "aethon-launch-handle-{}-{}.exe",
            std::process::id(),
            source.file_name().unwrap().to_string_lossy()
        ));
        std::fs::copy(&source, &copy).expect("copy the test binary");

        let mut handle = open_for_verified_launch(&copy).expect("open for launch");
        assert_eq!(
            sha256_of(&mut handle).unwrap(),
            {
                let mut direct = std::fs::File::open(&copy).unwrap();
                sha256_of(&mut direct).unwrap()
            },
            "hashing through the launch handle must see the same bytes"
        );

        assert!(
            std::fs::OpenOptions::new().write(true).open(&copy).is_err(),
            "a writer got in while the launch handle was held"
        );
        assert!(
            std::fs::remove_file(&copy).is_err(),
            "the verified file was deleted while the launch handle was held"
        );

        // A filter that matches no test, so the spawned copy starts, runs nothing and exits
        // rather than re-running this suite recursively.
        let mut spawned = std::process::Command::new(&copy)
            .arg("aethon_launch_handle_probe_matches_no_test")
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .expect("Windows must still be able to execute the file the handle holds open");
        let _ = spawned.wait();

        drop(handle);
        let _ = std::fs::remove_file(&copy);
    }
}
