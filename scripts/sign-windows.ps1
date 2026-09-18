# Authenticode signing for every Windows PE Aethon ships.
#
# Called once per file, either directly by the release workflow or by the Tauri bundler
# through `bundle.windows.signCommand` in tauri.signing.conf.json, which is how the
# application binary gets signed before it is packed into the installers.
#
# There is no unsigned fallback. If the credentials are absent or the resulting signature
# does not verify, this fails and takes the build with it. A release that cannot be signed
# must not be produced, rather than produced and quietly shipped unsigned.

[CmdletBinding()]
param(
  [Parameter(Mandatory = $true, Position = 0, ValueFromRemainingArguments = $true)]
  [string[]]$Path
)

$ErrorActionPreference = "Stop"

function Find-SignTool {
  if ($env:AETHON_SIGNTOOL) {
    if (-not (Test-Path -LiteralPath $env:AETHON_SIGNTOOL -PathType Leaf)) {
      throw "AETHON_SIGNTOOL is set to $($env:AETHON_SIGNTOOL) but no such file exists."
    }
    return $env:AETHON_SIGNTOOL
  }
  $roots = @("${env:ProgramFiles(x86)}\Windows Kits\10\bin", "$env:ProgramFiles\Windows Kits\10\bin") |
    Where-Object { $_ -and (Test-Path -LiteralPath $_) }
  # Newest SDK first: older signtool builds predate the Azure dlib interface.
  $candidate = Get-ChildItem -LiteralPath $roots -Filter signtool.exe -File -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match "\\x64\\" } |
    Sort-Object -Property FullName -Descending |
    Select-Object -First 1 -ExpandProperty FullName
  if (-not $candidate) {
    throw "signtool.exe was not found. Install the Windows SDK signing tools or set AETHON_SIGNTOOL."
  }
  return $candidate
}

function Get-SigningMethod {
  if ($env:AETHON_SIGN_METHOD) { return $env:AETHON_SIGN_METHOD.Trim().ToLowerInvariant() }
  if ($env:AZURE_CLIENT_ID -and $env:AETHON_SIGN_ACCOUNT) { return "azure-trusted-signing" }
  if ($env:WINDOWS_CERT_PFX_BASE64) { return "pfx" }
  throw @"
No Windows code-signing credentials are present, so nothing can be signed.

Supply exactly one of these credential sets as repository secrets:

  Azure Trusted Signing (recommended: no private key ever reaches the runner)
    AZURE_TENANT_ID, AZURE_CLIENT_ID, AZURE_CLIENT_SECRET,
    AETHON_SIGN_ENDPOINT, AETHON_SIGN_ACCOUNT, AETHON_SIGN_CERT_PROFILE

  OV/EV code-signing certificate as a PKCS#12 blob
    WINDOWS_CERT_PFX_BASE64, WINDOWS_CERT_PASSWORD

In both cases also set WINDOWS_CERT_SUBJECT to the exact certificate subject name, so the
signature is checked against the publisher this project expects rather than against
whatever key happened to be available.
"@
}

function Get-TimestampUrl {
  if ($env:AETHON_SIGN_TIMESTAMP_URL) { return $env:AETHON_SIGN_TIMESTAMP_URL }
  # RFC 3161, SHA-256. A signature without a timestamp stops validating the day the
  # certificate expires, which would strand every already-installed copy.
  return "http://timestamp.digicert.com"
}

function Invoke-SignTool {
  param([string]$Tool, [string[]]$Arguments, [string]$What)
  & $Tool @Arguments
  if ($LASTEXITCODE -ne 0) { throw "$What failed with exit code $LASTEXITCODE." }
}

$signtool = Find-SignTool
$method = Get-SigningMethod
$timestamp = Get-TimestampUrl
$pfx = $null

try {
  switch ($method) {
    "azure-trusted-signing" {
      foreach ($name in @("AZURE_TENANT_ID", "AZURE_CLIENT_ID", "AZURE_CLIENT_SECRET",
          "AETHON_SIGN_ENDPOINT", "AETHON_SIGN_ACCOUNT", "AETHON_SIGN_CERT_PROFILE")) {
        if (-not (Get-Item "env:$name" -ErrorAction SilentlyContinue)) {
          throw "Azure Trusted Signing is selected but $name is not set."
        }
      }
      $dlib = $env:AETHON_SIGN_DLIB
      if (-not $dlib) {
        $dlib = Get-ChildItem -Path @("$env:ProgramFiles\Microsoft SDKs", "$env:USERPROFILE\.nuget\packages") `
          -Filter "Azure.CodeSigning.Dlib.dll" -File -Recurse -ErrorAction SilentlyContinue |
          Sort-Object -Property FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
      }
      if (-not $dlib) {
        throw "Azure.CodeSigning.Dlib.dll was not found. Install the Trusted Signing client tools or set AETHON_SIGN_DLIB."
      }
      $metadata = Join-Path ([System.IO.Path]::GetTempPath()) "aethon-trusted-signing.json"
      @{
        Endpoint               = $env:AETHON_SIGN_ENDPOINT
        CodeSigningAccountName = $env:AETHON_SIGN_ACCOUNT
        CertificateProfileName = $env:AETHON_SIGN_CERT_PROFILE
      } | ConvertTo-Json | Set-Content -LiteralPath $metadata -Encoding utf8
      $signArguments = @("sign", "/v", "/fd", "sha256", "/td", "sha256", "/tr", $timestamp,
        "/dlib", $dlib, "/dmdf", $metadata)
    }
    "pfx" {
      if (-not $env:WINDOWS_CERT_PASSWORD) {
        throw "WINDOWS_CERT_PFX_BASE64 is set but WINDOWS_CERT_PASSWORD is not."
      }
      $pfx = Join-Path ([System.IO.Path]::GetTempPath()) "aethon-signing-$([guid]::NewGuid()).pfx"
      [IO.File]::WriteAllBytes($pfx, [Convert]::FromBase64String($env:WINDOWS_CERT_PFX_BASE64))
      $signArguments = @("sign", "/v", "/fd", "sha256", "/td", "sha256", "/tr", $timestamp,
        "/f", $pfx, "/p", $env:WINDOWS_CERT_PASSWORD)
    }
    default { throw "AETHON_SIGN_METHOD=$method is not a signing method this script knows." }
  }

  foreach ($file in $Path) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Nothing to sign at $file." }
    $resolved = (Resolve-Path -LiteralPath $file).Path
    Invoke-SignTool $signtool ($signArguments + @($resolved)) "Signing $resolved"
    # Verified straight away, against the same trust policy Windows will apply on the user's
    # machine, so a signature that would not be honoured there fails the build here.
    Invoke-SignTool $signtool @("verify", "/pa", "/all", "/tw", "/v", $resolved) "Verifying $resolved"
    Write-Host "Signed and verified $resolved"
  }
}
finally {
  if ($pfx -and (Test-Path -LiteralPath $pfx)) { Remove-Item -LiteralPath $pfx -Force }
}
