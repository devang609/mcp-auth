<#
.SYNOPSIS
    Generate a locally-trusted TLS cert for the healthcare MCP demo (Windows).

.DESCRIPTION
    Produces:
      traefik\certs\local.pem      (certificate, covers mcp.local + keycloak.local)
      traefik\certs\local-key.pem  (private key)

    Uses mkcert so the cert is trusted by the OS/browser trust store (mkcert -install).
    Idempotent: skips generation if both files already exist.
#>

$ErrorActionPreference = "Stop"

# Resolve repo root (this script lives in <root>\scripts).
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir   = Split-Path -Parent $ScriptDir
$CertDir   = Join-Path $RootDir "traefik\certs"

$CertFile = Join-Path $CertDir "local.pem"
$KeyFile  = Join-Path $CertDir "local-key.pem"

$Hosts = @("mcp.local", "keycloak.local")

# Check for mkcert.
$mkcert = Get-Command mkcert -ErrorAction SilentlyContinue
if (-not $mkcert) {
    Write-Error @"
mkcert is not installed or not on PATH.

mkcert generates a locally-trusted development certificate.
Install it, then re-run this script:
  Chocolatey:  choco install mkcert
  Scoop:       scoop bucket add extras; scoop install mkcert
  Docs:        https://github.com/FiloSottile/mkcert
"@
    exit 1
}

if (-not (Test-Path $CertDir)) {
    New-Item -ItemType Directory -Path $CertDir -Force | Out-Null
}

if ((Test-Path $CertFile) -and (Test-Path $KeyFile)) {
    Write-Host "Certificates already exist - skipping generation:"
    Write-Host "  $CertFile"
    Write-Host "  $KeyFile"
    Write-Host "(Delete them and re-run to regenerate.)"
    exit 0
}

Write-Host "Installing mkcert local CA into the system trust store (mkcert -install)..."
mkcert -install

Write-Host "Generating certificate for: $($Hosts -join ', ')"
mkcert -cert-file $CertFile -key-file $KeyFile @Hosts

Write-Host ""
Write-Host "Done. Certificate written to:"
Write-Host "  $CertFile"
Write-Host "  $KeyFile"
Write-Host ""
Write-Host "Traefik references these at /certs/local.pem and /certs/local-key.pem."
