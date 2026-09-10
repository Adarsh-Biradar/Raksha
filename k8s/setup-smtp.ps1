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
# Require existing application config; never replace database/login passwords.
$null=kubectl --context $Context -n raksha get configmap raksha-config -o name
if($LASTEXITCODE -ne 0){throw 'Create the application namespace and raksha-config before configuring SMTP.'}
$taskData=@{}
foreach($taskKey in @('SMTP_USERNAME','SMTP_PASSWORD','SMTP_FROM')){
 $taskData[$taskKey]=$taskValues[$taskKey]
}
$taskSmtpConfig=@{apiVersion='v1';kind='ConfigMap';metadata=@{name='raksha-config';namespace='raksha'};data=$taskData}|ConvertTo-Json -Depth 5
# The dedicated field manager owns only these three SMTP keys.
$taskSmtpConfig | kubectl --context $Context apply --server-side --field-manager=raksha-smtp-config --force-conflicts -f -
if($LASTEXITCODE -ne 0){throw 'SMTP config update failed.'}
kubectl --context $Context -n raksha rollout restart deployment/raksha-api
if($LASTEXITCODE -ne 0){throw 'SMTP config updated, but API restart failed.'}
Write-Output 'SMTP credentials updated (in plain text in the raksha-config ConfigMap). Check API rollout, then configure recipients in Email alerts.'
