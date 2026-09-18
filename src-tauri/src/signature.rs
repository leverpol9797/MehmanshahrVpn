//! Authenticode verification for executables Aethon is about to run.
//!
//! The update path already checks a SHA-256 against the release manifest, but both the
//! installer and the hash it is compared to come from the same place, so a hash match
//! only proves the download was not corrupted in transit — it says nothing about who
//! produced the bytes. This module supplies the independent check: `WinVerifyTrust`
//! against the machine's own trust policy, plus the signer's name so the caller can
//! require a specific publisher.
//!
//! Every path here fails closed. An unsigned file, a modified file, an untrusted or
//! expired chain, a revoked certificate, and a revocation status that cannot be
//! established are all refusals, not warnings.

#[cfg(windows)]
use std::os::windows::io::AsRawHandle;
use std::path::Path;

/// What a valid signature turned out to be.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Signer {
    /// The signing certificate's display name, e.g. `Example Publishing Ltd`.
    pub name: String,
}

impl Signer {
    /// Whether this signature was produced by `expected`, compared the way Windows
    /// displays certificate names: case-insensitively, ignoring surrounding space.
    pub fn is(&self, expected: &str) -> bool {
        self.name.trim().eq_ignore_ascii_case(expected.trim())
    }
}

#[cfg(windows)]
fn trust_error(status: i32) -> String {
    // The values WinVerifyTrust actually returns for a file that must not be run.
    let reason = match status as u32 {
        0x800B_0100 => "it is not signed",
        0x800B_0101 => "its signing certificate has expired",
        0x800B_0004 => "the signature is not trusted for this purpose",
        0x800B_0109 => "its certificate chain ends in an untrusted root",
        0x800B_010C => "its signing certificate has been revoked",
        0x800B_0111 => "its signature is explicitly distrusted on this system",
        0x8009_6010 => "the file has been modified since it was signed",
        0x800B_010E | 0x8009_2013 => {
            "the revocation status of its certificate could not be checked"
        }
        0x8009_6002 => "the signing provider is not recognised",
        _ => "the signature could not be verified",
    };
    format!("The signature was refused because {reason} (0x{status:08X})")
}

/// Verify `path` against the machine's Authenticode trust policy and return its signer.
///
/// Verification reads through `handle` when one is supplied, so the bytes checked are the
/// bytes the caller already holds open rather than whatever the path resolves to a moment
/// later. Callers that are about to execute the file should always pass its handle.
#[cfg(windows)]
pub fn verify(path: &Path, handle: Option<&std::fs::File>) -> Result<Signer, String> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Security::WinTrust::{
        WinVerifyTrust, WINTRUST_ACTION_GENERIC_VERIFY_V2, WINTRUST_DATA, WINTRUST_DATA_0,
        WINTRUST_FILE_INFO, WTD_CHOICE_FILE, WTD_REVOCATION_CHECK_CHAIN, WTD_REVOKE_WHOLECHAIN,
        WTD_STATEACTION_CLOSE, WTD_STATEACTION_VERIFY, WTD_UI_NONE,
    };

    let wide: Vec<u16> = path
        .as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect();
    let mut file = WINTRUST_FILE_INFO {
        cbStruct: std::mem::size_of::<WINTRUST_FILE_INFO>() as u32,
        pcwszFilePath: wide.as_ptr(),
        hFile: handle.map_or(std::ptr::null_mut(), |file| file.as_raw_handle()),
        pgKnownSubject: std::ptr::null_mut(),
    };
    let mut data = WINTRUST_DATA {
        cbStruct: std::mem::size_of::<WINTRUST_DATA>() as u32,
        pPolicyCallbackData: std::ptr::null_mut(),
        pSIPClientData: std::ptr::null_mut(),
        dwUIChoice: WTD_UI_NONE,
        fdwRevocationChecks: WTD_REVOKE_WHOLECHAIN,
        dwUnionChoice: WTD_CHOICE_FILE,
        Anonymous: WINTRUST_DATA_0 {
            pFile: &mut file as *mut _,
        },
        dwStateAction: WTD_STATEACTION_VERIFY,
        hWVTStateData: std::ptr::null_mut(),
        pwszURLReference: std::ptr::null_mut(),
        dwProvFlags: WTD_REVOCATION_CHECK_CHAIN,
        dwUIContext: 0,
        pSignatureSettings: std::ptr::null_mut(),
    };
    let mut action = WINTRUST_ACTION_GENERIC_VERIFY_V2;

    // SAFETY: `data` and `action` outlive the call, `file` outlives `data`, and the state
    // handle the verify call allocates is released by the paired CLOSE below on every
    // path — including the error paths, which is why the result is held rather than
    // returned straight away.
    unsafe {
        let status = WinVerifyTrust(
            std::ptr::null_mut(),
            &mut action,
            &mut data as *mut _ as *mut core::ffi::c_void,
        );
        let outcome = if status == 0 {
            signer_from_state(data.hWVTStateData)
        } else {
            Err(trust_error(status))
        };
        data.dwStateAction = WTD_STATEACTION_CLOSE;
        WinVerifyTrust(
            std::ptr::null_mut(),
            &mut action,
            &mut data as *mut _ as *mut core::ffi::c_void,
        );
        outcome
    }
}

/// Pull the signing certificate's display name out of a verified trust state.
///
/// # Safety
/// `state` must be the `hWVTStateData` of a `WinVerifyTrust` call that returned success
/// and has not yet been closed.
#[cfg(windows)]
unsafe fn signer_from_state(
    state: windows_sys::Win32::Foundation::HANDLE,
) -> Result<Signer, String> {
    use windows_sys::Win32::Security::Cryptography::{
        CertGetNameStringW, CERT_NAME_SIMPLE_DISPLAY_TYPE,
    };
    use windows_sys::Win32::Security::WinTrust::{
        WTHelperGetProvCertFromChain, WTHelperGetProvSignerFromChain, WTHelperProvDataFromStateData,
    };

    let provider = WTHelperProvDataFromStateData(state);
    if provider.is_null() {
        return Err("The signature verified but its provider data was unavailable".into());
    }
    let signer = WTHelperGetProvSignerFromChain(provider, 0, 0, 0);
    if signer.is_null() {
        return Err("The signature verified but carried no signer".into());
    }
    let certificate = WTHelperGetProvCertFromChain(signer, 0);
    if certificate.is_null() || (*certificate).pCert.is_null() {
        return Err("The signature verified but carried no signing certificate".into());
    }
    let context = (*certificate).pCert;
    let length = CertGetNameStringW(
        context,
        CERT_NAME_SIMPLE_DISPLAY_TYPE,
        0,
        std::ptr::null(),
        std::ptr::null_mut(),
        0,
    );
    if length <= 1 {
        return Err("The signing certificate has no subject name".into());
    }
    let mut buffer = vec![0u16; length as usize];
    let written = CertGetNameStringW(
        context,
        CERT_NAME_SIMPLE_DISPLAY_TYPE,
        0,
        std::ptr::null(),
        buffer.as_mut_ptr(),
        length,
    );
    if written <= 1 {
        return Err("The signing certificate's subject name could not be read".into());
    }
    let name = String::from_utf16_lossy(&buffer[..(written as usize) - 1]);
    Ok(Signer { name })
}

#[cfg(not(windows))]
pub fn verify(path: &Path, _handle: Option<&std::fs::File>) -> Result<Signer, String> {
    let _ = path;
    Err("Authenticode verification is only available on Windows".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_signer_matches_its_own_name_however_it_is_cased_or_padded() {
        let signer = Signer {
            name: "WireGuard LLC".into(),
        };
        assert!(signer.is("WireGuard LLC"));
        assert!(signer.is("wireguard llc"));
        assert!(signer.is("  WireGuard LLC  "));
        assert!(!signer.is("WireGuard"));
        assert!(!signer.is("Wireguard LLC Inc"));
        assert!(!signer.is(""));
    }

    #[cfg(windows)]
    #[test]
    fn the_shipped_wintun_is_signed_by_wireguard() {
        // wintun.dll is the one binary Aethon ships whose publisher is independent of
        // this project, which makes it the honest positive control for this module.
        let wintun = std::path::Path::new("binaries/wintun.dll");
        if !wintun.is_file() {
            panic!("binaries/wintun.dll is missing; fetch the sidecars before running tests");
        }
        let signer = verify(wintun, None).expect("wintun.dll must verify");
        assert!(
            signer.is("WireGuard LLC"),
            "unexpected wintun.dll publisher: {}",
            signer.name
        );
    }

    #[cfg(windows)]
    #[test]
    fn an_unsigned_file_is_refused_and_says_so() {
        let unsigned =
            std::env::temp_dir().join(format!("aethon-unsigned-{}.exe", std::process::id()));
        std::fs::copy(std::env::current_exe().unwrap(), &unsigned).unwrap();
        // Truncating the copy is not needed: the test binary itself is unsigned. Copying
        // it keeps the assertion about a file this test owns rather than about the runner.
        let error = verify(&unsigned, None).expect_err("an unsigned file must be refused");
        let _ = std::fs::remove_file(&unsigned);
        assert!(
            error.contains("not signed"),
            "expected an unsigned-file refusal, got: {error}"
        );
    }

    #[cfg(windows)]
    #[test]
    fn a_file_that_does_not_exist_is_refused_rather_than_trusted() {
        let missing = std::env::temp_dir().join("aethon-does-not-exist-9f3a1c.exe");
        assert!(verify(&missing, None).is_err());
    }
}
