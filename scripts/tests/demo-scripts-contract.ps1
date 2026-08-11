[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scriptsRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $scriptsRoot
. (Join-Path $scriptsRoot 'demo-common.ps1')

$failures = New-Object System.Collections.Generic.List[string]
function Assert-Contract([bool]$Condition, [string]$Message) {
    if (-not $Condition) { $failures.Add($Message) }
}

$readonly = Get-DemoProfile 'demo-readonly'
$approval = Get-DemoProfile 'demo-approval'
Assert-Contract ($readonly.database -eq 'student_dormitory_readonly_demo') 'readonly database contract'
Assert-Contract ($readonly.redisDatabase -eq 12) 'readonly Redis DB contract'
Assert-Contract (-not $readonly.writeExecutionEnabled) 'readonly write contract'
Assert-Contract ($approval.database -eq 'student_dormitory_approval_demo') 'approval database contract'
Assert-Contract ($approval.redisDatabase -eq 13) 'approval Redis DB contract'
Assert-Contract ([bool]$approval.writeExecutionEnabled) 'approval write contract'

$compose = Get-Content -Raw (Join-Path $repositoryRoot 'compose.yml')
Assert-Contract ($compose -match '\$\{MYSQL_BIND_ADDRESS:-127\.0\.0\.1\}:\$\{MYSQL_PORT:-3306\}:3306') 'MySQL loopback bind contract'
Assert-Contract ($compose -match '\$\{REDIS_BIND_ADDRESS:-127\.0\.0\.1\}:\$\{REDIS_PORT:-6379\}:6379') 'Redis loopback bind contract'
Assert-Contract ($compose -match 'image: \$\{MYSQL_IMAGE:-mysql:8\.4\}') 'MySQL image override contract'
Assert-Contract ($compose -match 'image: \$\{REDIS_IMAGE:-redis:7\.4-alpine\}') 'Redis image override contract'
$ignore = Get-Content -Raw (Join-Path $repositoryRoot '.gitignore')
Assert-Contract ($ignore -match '(?m)^\.demo/$') '.demo ignore contract'

foreach ($name in @('demo-common.ps1', 'demo-start.ps1', 'demo-health.ps1', 'demo-stop.ps1', 'demo-reset.ps1')) {
    $path = Join-Path $scriptsRoot $name
    $tokens = $null
    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors) | Out-Null
    Assert-Contract ($errors.Count -eq 0) "$name syntax contract"
}

$startScript = Get-Content -Raw (Join-Path $scriptsRoot 'demo-start.ps1')
$promptWait = $startScript.IndexOf('Wait-DemoBootstrapPrompts')
$controlPlaneSeed = $startScript.IndexOf('Invoke-DemoControlPlaneSeed')
Assert-Contract ($promptWait -ge 0 -and $controlPlaneSeed -gt $promptWait) `
    'prompt bootstrap wait must run before control-plane seed'
Assert-Contract ($startScript -notmatch 'demo-only-.+key') 'fixed demo HMAC key must not be committed'
Assert-Contract ($startScript -match "REQUIRE_LOGIN_ORIGIN\s*=\s*'true'") `
    'demo login origin protection must remain enabled'

$keys = 1..4 | ForEach-Object { New-DemoEphemeralKey }
Assert-Contract (($keys | Sort-Object -Unique).Count -eq 4 -and
    @($keys | Where-Object { [Convert]::FromBase64String($_).Length -ge 32 }).Count -eq 4) `
    'ephemeral HMAC keys must be unique and at least 32 bytes'

if ($failures.Count -gt 0) {
    throw "演示脚本合同失败：$($failures -join '; ')"
}
[ordered]@{ status = 'PASS'; assertions = 19; modes = @('demo-readonly', 'demo-approval') } | ConvertTo-Json -Depth 4
