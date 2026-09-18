& (Join-Path $PSScriptRoot "fetch-xray.ps1")
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
