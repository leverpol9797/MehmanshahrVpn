$ErrorActionPreference = "Stop"
$commit = "38148cd835e07d688dbb6b30ae24ad2fd0e5d847"
$sourceUrl = "https://github.com/Psiphon-Labs/psiphon-tunnel-core.git"
$expected = "fc52730ba75425c20125b621ed9889b221d26e82631603501e49f1ce85bd039b"
$root = Join-Path ([System.IO.Path]::GetTempPath()) "aethon-psiphon-$([guid]::NewGuid())"
$source = Join-Path $root "psiphon-tunnel-core"
$output = Join-Path $root "psiphon-tunnel-core-x86_64.exe"
$destination = Join-Path $PSScriptRoot "..\src-tauri\binaries\psiphon-tunnel-core-x86_64-pc-windows-msvc.exe"

try {
  if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw "git is required to fetch Psiphon source." }
  if (-not (Get-Command go -ErrorAction SilentlyContinue)) { throw "Go 1.26 or newer is required to build Psiphon." }
  New-Item -ItemType Directory -Force $root | Out-Null
  git init $source | Out-Null
  git -C $source remote add origin $sourceUrl
  git -C $source fetch --depth 1 origin $commit | Out-Null
  git -C $source checkout --detach FETCH_HEAD | Out-Null
  $actualCommit = (git -C $source rev-parse HEAD).Trim()
  if ($actualCommit -ne $commit) { throw "Psiphon source commit mismatch. Expected $commit, got $actualCommit." }
  $goVersion = (go version).Trim()
  if ($goVersion -notmatch 'go1\.(2[6-9]|[3-9][0-9])') { throw "Unsupported Go toolchain: $goVersion. Psiphon requires Go 1.26+." }
  Push-Location (Join-Path $source "ConsoleClient")
  $env:GOOS = "windows"
  $env:GOARCH = "amd64"
  $env:CGO_ENABLED = "0"
  $env:GOTOOLCHAIN = "local"
  go build -mod=vendor -trimpath -ldflags "-s -w" -o $output .
  Pop-Location

  $bytes = [IO.File]::ReadAllBytes($output)
  $peOffset = [BitConverter]::ToInt32($bytes, 0x3c)
  $machine = [BitConverter]::ToUInt16($bytes, $peOffset + 4)
  if ($machine -ne 0x8664) { throw ("Psiphon build is not AMD64 PE (machine 0x{0:X4})." -f $machine) }
  $actual = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($actual -ne $expected) { throw "Psiphon reproducible-build checksum mismatch. Expected $expected, got $actual." }
  New-Item -ItemType Directory -Force (Split-Path $destination) | Out-Null
  Copy-Item -LiteralPath $output -Destination $destination -Force
  Copy-Item -LiteralPath (Join-Path $source "LICENSE") -Destination (Join-Path $PSScriptRoot "..\third-party\psiphon-LICENSE.txt") -Force
  Write-Host "Built and verified Psiphon ConsoleClient AMD64 from $commit with $goVersion"
  Write-Host "SHA256=$actual"
}
finally {
  Pop-Location -ErrorAction SilentlyContinue
  if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}
