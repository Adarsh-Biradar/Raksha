param(
 [Parameter(Mandatory=$true)][string]$Context,
 [string]$EnvFile=(Join-Path $PSScriptRoot '..\.env')
)
$ErrorActionPreference='Stop'
$taskValues=@{}
Get-Content -LiteralPath (Resolve-Path -LiteralPath $EnvFile).Path | ForEach-Object {
 if($_ -match '^([A-Z_]+)=(.*)$'){$taskValues[$matches[1]]=$matches[2]}
}
foreach($taskKey in @('SMTP_USERNAME','SMTP_PASSWORD','SMTP_FROM')){
 if([string]::IsNullOrWhiteSpace($taskValues[$taskKey])){throw "$taskKey is required in the selected environment file."}
}
# Require existing application credentials; never replace database/login passwords.
$null=kubectl --context $Context -n raksha get secret raksha-secrets -o name
if($LASTEXITCODE -ne 0){throw 'Create the application namespace and raksha-secrets before configuring SMTP.'}
$taskData=@{}
foreach($taskKey in @('SMTP_USERNAME','SMTP_PASSWORD','SMTP_FROM')){
 $taskData[$taskKey]=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($taskValues[$taskKey]))
}
$taskSmtpSecret=@{apiVersion='v1';kind='Secret';metadata=@{name='raksha-secrets';namespace='raksha'};data=$taskData}|ConvertTo-Json -Depth 5
# The dedicated field manager owns only these three SMTP keys.
$taskSmtpSecret | kubectl --context $Context apply --server-side --field-manager=raksha-smtp-config --force-conflicts -f -
if($LASTEXITCODE -ne 0){throw 'SMTP secret update failed.'}
kubectl --context $Context -n raksha rollout restart deployment/raksha-api
if($LASTEXITCODE -ne 0){throw 'SMTP secret updated, but API restart failed.'}
Write-Output 'SMTP credentials updated. Check API rollout, then configure recipients in Email alerts.'
