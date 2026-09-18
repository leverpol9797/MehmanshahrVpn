#![cfg_attr(test, allow(clippy::field_reassign_with_default))]

use serde::{Deserialize, Serialize};
use std::{collections::HashMap, net::SocketAddr, path::Path};

/// Transport encapsulation ceilings for the TUN MTU.
pub const MASQUE_MTU_CAP: u16 = 1400;
pub const WIREGUARD_MTU_CAP: u16 = 1420;
pub const NESTED_WIREGUARD_MTU_CAP: u16 = 1360;
pub const MIN_TUN_MTU: u16 = 1280;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    pub language: String,
    pub appearance: String,
    pub orb_style: String,
    #[serde(default = "default_automatic_updates")]
    pub automatic_updates: bool,
    pub connection_mode: String,
    pub routing_mode: String,
    pub dns_leak_protection: bool,
    pub ipv6_behavior: String,
    pub kill_switch: bool,
    pub tun_mtu: u16,
    pub split_applications: Vec<String>,
    pub route_exclusions: Vec<String>,
    pub protocol: String,
    pub scan_mode: String,
    pub log_level: String,
    pub ip_mode: String,
    pub obfuscation: String,
    pub masque_transport: String,
    pub socks_address: String,
    pub allow_remote_listener: bool,
    pub peer: String,
    pub wg_keepalive: u16,
    pub stall_timeout: u64,
    pub watchdog: bool,
    pub config_path: String,
    pub wg_config_path: String,
    pub masque_config_path: String,
    pub quick_reconnect: bool,
    pub auto_connect_at_start: bool,
    pub dns_resolvers: String,
    pub route_block: Vec<String>,
    pub route_direct: Vec<String>,
    pub routes_file: String,
    #[serde(default)]
    pub psiphon_chain_enabled: bool,
    #[serde(default)]
    pub psiphon_region: String,
    #[serde(default)]
    pub psiphon_local_port: u16,
}

fn default_automatic_updates() -> bool {
    true
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            language: "en".into(),
            appearance: "system".into(),
            orb_style: "living-mercury".into(),
            automatic_updates: true,
            connection_mode: "vpn".into(),
            routing_mode: "bypass-local".into(),
            dns_leak_protection: true,
            ipv6_behavior: "tunnel".into(),
            kill_switch: false,
            tun_mtu: 1500,
            split_applications: Vec::new(),
            route_exclusions: Vec::new(),
            protocol: "gool".into(),
            scan_mode: "balanced".into(),
            log_level: "info".into(),
            ip_mode: "v4".into(),
            obfuscation: "balanced".into(),
            masque_transport: "h3".into(),
            socks_address: "127.0.0.1:1819".into(),
            allow_remote_listener: false,
            peer: String::new(),
            wg_keepalive: 5,
            stall_timeout: 90,
            watchdog: true,
            config_path: String::new(),
            wg_config_path: String::new(),
            masque_config_path: String::new(),
            quick_reconnect: true,
            auto_connect_at_start: false,
            dns_resolvers: String::new(),
            route_block: Vec::new(),
            route_direct: Vec::new(),
            routes_file: String::new(),
            psiphon_chain_enabled: false,
            psiphon_region: String::new(),
            psiphon_local_port: 0,
        }
    }
}

impl Settings {
    /// Largest safe TUN MTU for the selected transport. MASQUE adds QUIC/HTTP datagram framing,
    /// WireGuard adds its own 60-byte header, and `gool` nests WireGuard inside WireGuard, so a
    /// 1500-byte TUN would force every packet to fragment on the way out. Mirrors
    /// `AetherVpnService.effectiveMtu` on Android so both platforms agree.
    pub fn effective_tun_mtu(&self) -> u16 {
        Self::cap_tun_mtu(&self.protocol, self.tun_mtu)
    }

    pub fn cap_tun_mtu(protocol: &str, configured: u16) -> u16 {
        let transport_cap = match protocol {
            "gool" | "smart" => NESTED_WIREGUARD_MTU_CAP,
            "wg" => WIREGUARD_MTU_CAP,
            _ => MASQUE_MTU_CAP,
        };
        configured.min(transport_cap).max(MIN_TUN_MTU)
    }

    /// True only when the Aether core actually carries IPv6 upstream. When it does not, IPv6 has
    /// to be rejected rather than forwarded into an IPv4-only tunnel.
    pub fn ipv6_upstream(&self) -> bool {
        self.ipv6_behavior == "tunnel" && matches!(self.ip_mode.as_str(), "v6" | "both")
    }

    /// Migrate protocol-specific values from older Windows UI versions before validation.
    pub fn normalize_protocol_options(&mut self) {
        if !["firewall", "gfw", "balanced", "aggressive", "off"]
            .contains(&self.obfuscation.as_str())
        {
            self.obfuscation = "balanced".into();
        }
        if !["h3", "h2"].contains(&self.masque_transport.as_str()) {
            self.masque_transport = "h3".into();
        }
    }

    pub fn validate(&self) -> Result<(), String> {
        one_of("connection mode", &self.connection_mode, &["vpn", "manual"])?;
        one_of("orb style", &self.orb_style, &["classic", "living-mercury"])?;
        one_of(
            "routing mode",
            &self.routing_mode,
            &["full", "bypass-local", "split-include", "split-exclude"],
        )?;
        one_of("IPv6 behavior", &self.ipv6_behavior, &["tunnel", "block"])?;
        if !(1280..=9000).contains(&self.tun_mtu) {
            return Err("TUN MTU must be between 1280 and 9000".into());
        }
        for path in &self.split_applications {
            let path = Path::new(path);
            if !path.is_absolute()
                || path
                    .extension()
                    .and_then(|v| v.to_str())
                    .map(|v| !v.eq_ignore_ascii_case("exe"))
                    .unwrap_or(true)
            {
                return Err("Split-tunnel applications must be absolute .exe paths".into());
            }
        }
        for cidr in &self.route_exclusions {
            if cidr.contains(['\0', ' ', ';', '&', '|']) || !cidr.contains('/') {
                return Err("Route exclusions must be CIDR addresses".into());
            }
        }
        one_of(
            "protocol",
            &self.protocol,
            &["smart", "masque", "wg", "gool"],
        )?;
        one_of(
            "scan mode",
            &self.scan_mode,
            &["turbo", "balanced", "thorough", "stealth", "ironclad"],
        )?;
        one_of(
            "log level",
            &self.log_level,
            &["error", "warn", "info", "debug", "trace"],
        )?;
        one_of("IP mode", &self.ip_mode, &["v4", "v6", "both"])?;
        one_of("MASQUE transport", &self.masque_transport, &["h3", "h2"])?;
        one_of(
            "obfuscation profile",
            &self.obfuscation,
            &["firewall", "gfw", "balanced", "aggressive", "off"],
        )?;
        let listen: SocketAddr = self.socks_address.parse().map_err(|_| {
            "SOCKS5 address must be an IP address and port, for example 127.0.0.1:1819".to_string()
        })?;
        if !listen.ip().is_loopback() && !self.allow_remote_listener {
            return Err(
                "A non-local SOCKS5 listener requires explicit risk acknowledgement".into(),
            );
        }
        if !self.peer.trim().is_empty() {
            self.peer
                .trim()
                .parse::<SocketAddr>()
                .map_err(|_| "Custom endpoint must be an IP address and port".to_string())?;
        }
        if !(1..=65535).contains(&self.wg_keepalive) {
            return Err("WireGuard keepalive must be between 1 and 65535 seconds".into());
        }
        if !(10..=3600).contains(&self.stall_timeout) {
            return Err("Stall timeout must be between 10 and 3600 seconds".into());
        }
        for (label, value) in [
            ("configuration", &self.config_path),
            ("WireGuard configuration", &self.wg_config_path),
            ("MASQUE configuration", &self.masque_config_path),
            ("routing rules", &self.routes_file),
        ] {
            if !value.trim().is_empty()
                && (Path::new(value).file_name().is_none() || value.contains('\0'))
            {
                return Err(format!("Invalid {label} file path"));
            }
        }
        if self.dns_resolvers.split(',').any(|resolver| {
            let value = resolver.trim();
            !value.is_empty() && value.parse::<std::net::IpAddr>().is_err()
        }) {
            return Err("DNS resolvers must be comma-separated IP addresses".into());
        }
        for (label, rules) in [
            ("blocked", &self.route_block),
            ("direct", &self.route_direct),
        ] {
            if rules
                .iter()
                .any(|rule| rule.trim().is_empty() || rule.contains(['\0', ';', '&', '|']))
            {
                return Err(format!("Invalid {label} routing rule"));
            }
        }
        // Psiphon settings remain deserializable for backward compatibility, but the
        // suspended production build deliberately ignores them and never starts Psiphon.
        Ok(())
    }

    pub fn environment(&self, default_config: &Path) -> Result<HashMap<String, String>, String> {
        self.validate()?;
        let mut env = HashMap::from([
            ("AETHER_PROTOCOL".into(), self.protocol.clone()),
            ("AETHER_SCAN".into(), self.scan_mode.clone()),
            ("AETHER_LOG_LEVEL".into(), self.log_level.clone()),
            ("AETHER_IP".into(), self.ip_mode.clone()),
            ("AETHER_NOIZE".into(), self.obfuscation.clone()),
            ("AETHER_SOCKS".into(), self.socks_address.clone()),
            (
                "AETHER_QUICK_RECONNECT".into(),
                if self.quick_reconnect { "1" } else { "0" }.into(),
            ),
            (
                "AETHER_CONFIG".into(),
                if self.config_path.trim().is_empty() {
                    default_config.to_string_lossy().into_owned()
                } else {
                    self.config_path.clone()
                },
            ),
        ]);
        if !self.peer.trim().is_empty() {
            env.insert(
                if self.protocol == "masque" {
                    "AETHER_PEER".into()
                } else {
                    "AETHER_WG_PEER".into()
                },
                self.peer.trim().into(),
            );
        }
        if self.protocol == "masque" {
            env.insert(
                "AETHER_MASQUE_HTTP2".into(),
                if self.masque_transport == "h2" {
                    "1"
                } else {
                    "0"
                }
                .into(),
            );
        } else {
            env.insert("AETHER_WG_KEEPALIVE".into(), self.wg_keepalive.to_string());
        }
        if !self.wg_config_path.trim().is_empty() {
            env.insert("AETHER_WG_CONFIG".into(), self.wg_config_path.clone());
        }
        if !self.masque_config_path.trim().is_empty() {
            env.insert(
                "AETHER_MASQUE_CONFIG".into(),
                self.masque_config_path.clone(),
            );
        }
        if !self.dns_resolvers.trim().is_empty() {
            env.insert("AETHER_DNS".into(), self.dns_resolvers.trim().into());
        }
        if !self.route_block.is_empty() {
            env.insert("AETHER_ROUTE_BLOCK".into(), self.route_block.join(","));
        }
        if !self.route_direct.is_empty() {
            env.insert("AETHER_ROUTE_DIRECT".into(), self.route_direct.join(","));
        }
        if !self.routes_file.trim().is_empty() {
            env.insert("AETHER_ROUTES_FILE".into(), self.routes_file.clone());
        }
        Ok(env)
    }
}

fn one_of(label: &str, value: &str, options: &[&str]) -> Result<(), String> {
    options
        .contains(&value)
        .then_some(())
        .ok_or_else(|| format!("Unsupported {label}: {value}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn defaults_map_to_documented_environment() {
        let settings = Settings::default();
        assert_eq!(settings.scan_mode, "balanced");
        assert_eq!(settings.obfuscation, "balanced");
        let env = settings
            .environment(Path::new("C:/data/aether.toml"))
            .unwrap();
        assert_eq!(env["AETHER_PROTOCOL"], "gool");
        assert_eq!(env["AETHER_SOCKS"], "127.0.0.1:1819");
    }

    #[test]
    fn saved_scan_and_obfuscation_values_override_new_user_defaults() {
        let saved: Settings =
            serde_json::from_str(r#"{"scanMode":"thorough","obfuscation":"firewall"}"#).unwrap();
        assert_eq!(saved.scan_mode, "thorough");
        assert_eq!(saved.obfuscation, "firewall");
    }
    #[test]
    fn android_obfuscation_profiles_are_supported_for_all_protocols() {
        let mut s = Settings::default();
        s.protocol = "wg".into();
        assert!(s.validate().is_ok());
        s.obfuscation = "firewall".into();
        assert!(s.validate().is_ok());
        for profile in ["gfw", "balanced", "aggressive", "off"] {
            s.obfuscation = profile.into();
            assert!(s.validate().is_ok(), "profile {profile} should be accepted");
        }
    }
    #[test]
    fn masque_legacy_obfuscation_is_migrated_without_touching_scan_mode() {
        let mut settings = Settings::default();
        settings.protocol = "masque".into();
        settings.obfuscation = "balanced".into();
        settings.scan_mode = "ironclad".into();
        settings.masque_transport = "invalid".into();
        settings.normalize_protocol_options();
        assert_eq!(settings.obfuscation, "balanced");
        assert_eq!(settings.masque_transport, "h3");
        assert_eq!(settings.scan_mode, "ironclad");
        assert!(settings.validate().is_ok());
    }
    #[test]
    fn tun_mtu_never_exceeds_transport_encapsulation_limits() {
        let mut settings = Settings::default();
        settings.tun_mtu = 1500;
        settings.protocol = "gool".into();
        assert_eq!(settings.effective_tun_mtu(), 1360);
        settings.protocol = "wg".into();
        assert_eq!(settings.effective_tun_mtu(), 1420);
        settings.protocol = "masque".into();
        assert_eq!(settings.effective_tun_mtu(), 1400);
        // A safe user value is preserved, and the IPv6 minimum is still the floor.
        settings.tun_mtu = 1320;
        assert_eq!(settings.effective_tun_mtu(), 1320);
        settings.tun_mtu = 1280;
        settings.protocol = "gool".into();
        assert_eq!(settings.effective_tun_mtu(), 1280);
        assert_eq!(Settings::cap_tun_mtu("gool", 1281), 1281);
    }

    #[test]
    fn ipv6_upstream_requires_both_tunnelling_and_an_ipv6_core() {
        let mut settings = Settings::default();
        settings.ipv6_behavior = "tunnel".into();
        settings.ip_mode = "v4".into();
        assert!(!settings.ipv6_upstream());
        settings.ip_mode = "v6".into();
        assert!(settings.ipv6_upstream());
        settings.ip_mode = "both".into();
        assert!(settings.ipv6_upstream());
        settings.ipv6_behavior = "block".into();
        assert!(!settings.ipv6_upstream());
    }

    #[test]
    fn non_loopback_listener_is_rejected() {
        let mut s = Settings::default();
        s.socks_address = "0.0.0.0:1819".into();
        assert!(s.validate().unwrap_err().contains("acknowledgement"));
        s.allow_remote_listener = true;
        assert!(s.validate().is_ok());
    }
    #[test]
    fn invalid_values_are_rejected() {
        let mut s = Settings::default();
        s.peer = "example.com:443".into();
        assert!(s.validate().is_err());
        s.peer.clear();
        s.socks_address = "127.0.0.1:70000".into();
        assert!(s.validate().is_err());
    }
    #[test]
    fn settings_round_trip_without_secrets() {
        let settings = Settings::default();
        let json = serde_json::to_string(&settings).unwrap();
        assert_eq!(serde_json::from_str::<Settings>(&json).unwrap(), settings);
        assert!(!json.contains("private_key"));
    }

    #[test]
    fn update_preference_is_persisted_but_not_forwarded_to_core() {
        let mut settings = Settings::default();
        settings.automatic_updates = false;
        let json = serde_json::to_string(&settings).unwrap();
        assert!(json.contains("\"automaticUpdates\":false"));
        let env = settings.environment(Path::new("aether.toml")).unwrap();
        assert!(!env.contains_key("AETHER_AUTOMATIC_UPDATES"));
    }

    #[test]
    fn wireguard_and_h2_use_exact_core_names() {
        let mut settings = Settings::default();
        settings.protocol = "masque".into();
        settings.obfuscation = "firewall".into();
        settings.masque_transport = "h2".into();
        let h2 = settings.environment(Path::new("aether.toml")).unwrap();
        assert_eq!(h2["AETHER_MASQUE_HTTP2"], "1");
        settings.protocol = "wg".into();
        settings.obfuscation = "balanced".into();
        settings.peer = "162.159.192.1:2408".into();
        let wg = settings.environment(Path::new("aether.toml")).unwrap();
        assert_eq!(wg["AETHER_WG_PEER"], "162.159.192.1:2408");
        assert_eq!(wg["AETHER_WG_KEEPALIVE"], "5");
    }
}
