param([switch]$InitializeOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$envPath = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envPath)) {
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $names = @('POSTGRES_PASSWORD', 'TRANSPORT_DB_PASSWORD', 'FLEET_DB_PASSWORD',
            'ANALYTICS_DB_PASSWORD', 'TRACKING_DB_PASSWORD', 'KEYCLOAK_DB_PASSWORD',
            'RABBITMQ_PASSWORD', 'KEYCLOAK_ADMIN_PASSWORD')
        $lines = @('# Generated local credentials. Do not commit this file.')
        foreach ($name in $names) {
            $bytes = New-Object byte[] 32
            $random.GetBytes($bytes)
            $secret = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
            $lines += "$name=$secret"
        }
        $stream = [IO.File]::Open($envPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
        try {
            $content = [Text.Encoding]::UTF8.GetBytes(($lines -join "`n") + "`n")
            $stream.Write($content, 0, $content.Length)
        } finally { $stream.Dispose() }
        Write-Host 'Created .env with independent random development credentials.'
    } finally { $random.Dispose() }
}
if ($InitializeOnly) { return }
Push-Location $projectRoot
try {
    & docker compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose configuration is invalid.' }
    & docker compose up -d --build --wait --wait-timeout 300
    if ($LASTEXITCODE -ne 0) { throw 'Startup failed. Inspect docker compose ps and docker compose logs.' }
    Write-Host 'Backend ready. Start the React frontend separately; see README.md.'
} finally { Pop-Location }
