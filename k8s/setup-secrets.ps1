param(
  [Parameter(Mandatory=$true)][string]$Context,
  [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env')
)
$ErrorActionPreference = 'Stop'
$taskEnvPath = (Resolve-Path -LiteralPath $EnvFile).Path
$taskEntries = @{}
Get-Content -LiteralPath $taskEnvPath | ForEach-Object {
  if ($_ -match '^([A-Z_]+)=(.*)$') { $taskEntries[$matches[1]] = $matches[2] }
}
if ([string]::IsNullOrWhiteSpace($taskEntries['DATABASE_PASSWORD'])) { throw 'DATABASE_PASSWORD is required.' }
if ([string]::IsNullOrWhiteSpace($taskEntries['DEMO_PASSWORD']) -or $taskEntries['DEMO_PASSWORD'].Length -lt 16) { throw 'DEMO_PASSWORD must contain at least 16 characters.' }
# Construct JSON in memory so passwords never appear in command arguments or files.
$taskSecret = @{
  apiVersion = 'v1'
  kind = 'Secret'
  metadata = @{ name = 'raksha-secrets'; namespace = 'raksha' }
  type = 'Opaque'
  data = @{
    DATABASE_PASSWORD = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($taskEntries['DATABASE_PASSWORD']))
    DEMO_PASSWORD = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($taskEntries['DEMO_PASSWORD']))
  }
} | ConvertTo-Json -Depth 5
$taskSecret | kubectl --context $Context apply -f -
if ($LASTEXITCODE -ne 0) { throw 'Secret creation failed. Check your context and create the raksha namespace first.' }
Write-Output 'Raksha application secrets configured.'
