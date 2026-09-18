# The Windows signing gate.
#
# Every PE file in a release must carry a valid, timestamped Authenticode signature from a
# certificate that chains to a trusted root and whose subject matches the publisher this
# project expects. This script decides that, and its exit code is what the release workflow
# uses to allow or block publication.
#
# It reports rather than repairs. Nothing here signs anything, changes a trust store, or
# relaxes a check to get a pass: a build whose artifacts are unsigned is meant to fail here.
#
# Exit codes:  0 every file passed   1 at least one file failed

[CmdletBinding()]
param(
  # Files to check. Directories are searched for .exe, .dll and .msi.
  [Parameter(Mandatory = $true, Position = 0, ValueFromRemainingArguments = $true)]
  [string[]]$Path,
  # Required certificate subject, e.g. "CN=Example Ltd, O=Example Ltd, C=GB".
  # Defaults to $env:WINDOWS_CERT_SUBJECT. Without it, publisher identity is reported but
  # not enforced, which is weaker - the workflow always passes it for a release.
  [string]$ExpectedSubject = $env:WINDOWS_CERT_SUBJECT,
  # Emit "name=value" lines for $GITHUB_OUTPUT.
  [string]$GithubOutput = $env:GITHUB_OUTPUT
)

$ErrorActionPreference = "Stop"
$signable = @(".exe", ".dll", ".msi", ".sys", ".cat", ".ps1")

function Resolve-Targets {
  param([string[]]$Inputs)
  $files = New-Object System.Collections.Generic.List[string]
  foreach ($item in $Inputs) {
    if (-not (Test-Path -LiteralPath $item)) { throw "There is nothing to check at $item." }
    $resolved = Get-Item -LiteralPath $item
    if ($resolved.PSIsContainer) {
      Get-ChildItem -LiteralPath $resolved.FullName -File -Recurse |
        Where-Object { $signable -contains $_.Extension.ToLowerInvariant() } |
        ForEach-Object { $files.Add($_.FullName) }
    }
    elseif ($signable -contains $resolved.Extension.ToLowerInvariant()) {
      $files.Add($resolved.FullName)
    }
    else {
      # Named explicitly but not a PE container: say so rather than silently pass it.
      Write-Host "SKIP     $($resolved.FullName) (not an Authenticode-signable file)"
    }
  }
  return $files
}

function Test-Signature {
  param([string]$File)

  $problems = New-Object System.Collections.Generic.List[string]
  $signature = Get-AuthenticodeSignature -LiteralPath $File

  if ($signature.Status -ne "Valid") {
    $detail = if ($signature.StatusMessage) { $signature.StatusMessage } else { "no detail reported" }
    $problems.Add("signature status is $($signature.Status): $detail")
  }
  if (-not $signature.SignerCertificate) {
    $problems.Add("the file carries no signing certificate")
    return [pscustomobject]@{ File = $File; Problems = $problems; Subject = ""; Thumbprint = "" }
  }

  $certificate = $signature.SignerCertificate
  $subject = $certificate.Subject
  $thumbprint = $certificate.Thumbprint

  # A self-signed certificate satisfies "is it signed" and satisfies nothing a user cares
  # about. It is refused explicitly, because if one were ever installed into this machine's
  # trusted roots the chain check below would otherwise accept it.
  if ($certificate.Subject -eq $certificate.Issuer) {
    $problems.Add("the certificate is self-signed ($subject)")
  }

  $chain = New-Object System.Security.Cryptography.X509Certificates.X509Chain
  $chain.ChainPolicy.RevocationMode = "Online"
  # Roots are trusted by presence in the store, not by revocation status, and asking for
  # the root's CRL usually just produces an offline-revocation error.
  $chain.ChainPolicy.RevocationFlag = "ExcludeRoot"
  $chain.ChainPolicy.ApplicationPolicy.Add(
    (New-Object System.Security.Cryptography.Oid "1.3.6.1.5.5.7.3.3")) | Out-Null
  # Built as of a moment inside the leaf's validity window, not "now". Authenticode already
  # decides expiry correctly, using the RFC 3161 countersignature this script insists on, so
  # re-deciding it here against the wall clock would reject exactly the artifacts that were
  # signed properly and then outlived their certificate - which is the normal end state of
  # every correctly signed release. What this build is for is the part Authenticode's status
  # code does not spell out: whether the chain reaches a real issuing CA and a trusted root.
  $chain.ChainPolicy.VerificationTime = $certificate.NotAfter.AddMinutes(-1)
  $chain.Build($certificate) | Out-Null
  # Time validity is delegated as described above; everything else is a real chain defect,
  # including revocation that cannot be established.
  $timeStatuses = @("NoError", "NotTimeValid", "CtlNotTimeValid", "NotTimeNested")
  $chainProblems = @($chain.ChainStatus |
    Where-Object { $timeStatuses -notcontains $_.Status.ToString() })
  if ($chainProblems.Count) {
    $statuses = ($chainProblems | ForEach-Object { $_.Status }) -join ", "
    $problems.Add("the certificate chain is not acceptable: $statuses")
  }
  if ($chain.ChainElements.Count -lt 2) {
    $problems.Add("the certificate chains to itself rather than to an issuing CA")
  }

  if (-not $signature.TimeStamperCertificate) {
    # Without a countersignature the whole install base stops validating when the signing
    # certificate expires, so this is a failure and not a note.
    $problems.Add("the signature is not RFC 3161 timestamped")
  }

  $digest = $certificate.SignatureAlgorithm.FriendlyName
  if ($digest -and $digest -match "md5|sha1") {
    $problems.Add("the signature algorithm is $digest, which is not acceptable for release")
  }

  if ($ExpectedSubject -and $subject.Trim() -ne $ExpectedSubject.Trim()) {
    $problems.Add("signed by `"$subject`" but this release must be signed by `"$ExpectedSubject`"")
  }

  return [pscustomobject]@{
    File       = $File
    Problems   = $problems
    Subject    = $subject
    Thumbprint = $thumbprint
    Algorithm  = $digest
    NotAfter   = $certificate.NotAfter
    TimeStamp  = $signature.TimeStamperCertificate
  }
}

$targets = Resolve-Targets -Inputs $Path
if ($targets.Count -eq 0) { throw "No signable files were found in: $($Path -join ', ')" }

Write-Host "Windows signing gate - $($targets.Count) file(s)"
if ($ExpectedSubject) { Write-Host "Required publisher: $ExpectedSubject" }
else { Write-Host "Required publisher: (not pinned - WINDOWS_CERT_SUBJECT is unset)" }
Write-Host ""

$failed = New-Object System.Collections.Generic.List[object]
$subjects = New-Object System.Collections.Generic.HashSet[string]
foreach ($file in $targets) {
  $result = Test-Signature -File $file
  if ($result.Problems.Count -eq 0) {
    $subjects.Add($result.Subject) | Out-Null
    Write-Host "PASS     $file"
    Write-Host "         subject    $($result.Subject)"
    Write-Host "         thumbprint $($result.Thumbprint)"
    Write-Host "         algorithm  $($result.Algorithm)   expires $($result.NotAfter.ToString('yyyy-MM-dd'))"
  }
  else {
    $failed.Add($result)
    Write-Host "FAIL     $file"
    foreach ($problem in $result.Problems) { Write-Host "         - $problem" }
  }
}

Write-Host ""
if ($failed.Count -gt 0) {
  Write-Host "RELEASE BLOCKED - $($failed.Count) of $($targets.Count) file(s) are not acceptably signed:"
  foreach ($result in $failed) {
    Write-Host "  $($result.File)"
    foreach ($problem in $result.Problems) { Write-Host "    - $problem" }
  }
  Write-Host ""
  Write-Host "Required to clear this gate: an OV or EV Windows code-signing certificate from a"
  Write-Host "CA in the Microsoft Trusted Root Program, or an Azure Trusted Signing certificate"
  Write-Host "profile. Signing must happen on the release runner, inside the build that produces"
  Write-Host "the artifacts, so the bytes that are signed are the bytes that are published."
  if ($GithubOutput) { "signed=false" | Add-Content -LiteralPath $GithubOutput }
  exit 1
}

Write-Host "All $($targets.Count) file(s) are signed, timestamped, and chain to a trusted root."
if ($GithubOutput) {
  "signed=true" | Add-Content -LiteralPath $GithubOutput
  if ($subjects.Count -eq 1) {
    "publisher=$($subjects | Select-Object -First 1)" | Add-Content -LiteralPath $GithubOutput
  }
}
exit 0
