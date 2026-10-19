$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
if (Test-Path .env) { Write-Host "Ya existe .env. Se conserva su configuración."; exit 0 }
function New-Secret {
  $bytes = New-Object byte[] 24
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
  return ([BitConverter]::ToString($bytes)).Replace("-", "").ToLower()
}
$lines = @("DATABASE_PASSWORD=$(New-Secret)", "PAYMENTS_DATABASE_PASSWORD=$(New-Secret)", "ADMIN_EMAIL=admin@example.test", "ADMIN_PASSWORD=$(New-Secret)", "DEMO_DATA=true", "DEMO_PASSWORD=$(New-Secret)", "PAYMENT_API_KEY=$(New-Secret)", "PAYMENT_WEBHOOK_TOKEN=$(New-Secret)")
[IO.File]::WriteAllLines((Join-Path (Get-Location) ".env"), $lines)
Write-Host "Configuración creada en .env. Allí están tus contraseñas. Ejecutá: docker compose up --build"
