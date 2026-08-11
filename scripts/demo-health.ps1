[CmdletBinding()]
param(
    [int]$WaitSeconds = 30,
    [switch]$Json
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'demo-common.ps1')

$state = Read-DemoState
if ($state.status -ne 'running') { throw "演示状态不是 running：$($state.status)" }
$envValues = Read-DemoEnv
$expectedWrite = [bool]$state.writeExecutionEnabled
$deadline = (Get-Date).AddSeconds($WaitSeconds)
$lastError = $null

while ((Get-Date) -lt $deadline) {
    try {
        foreach ($entry in @($state.processes.backend, $state.processes.frontend)) {
            if ($null -eq (Get-DemoOwnedProcess $entry)) { throw "$($entry.name) 进程未运行。" }
        }
        foreach ($tcp in @(
            @{ name = 'MySQL'; port = [int]$state.ports.mysql },
            @{ name = 'Redis'; port = [int]$state.ports.redis },
            @{ name = '后端'; port = [int]$state.ports.backend },
            @{ name = '前端'; port = [int]$state.ports.frontend }
        )) {
            if (-not (Test-DemoTcpPort -Port $tcp.port -TimeoutMilliseconds 1000)) {
                throw "$($tcp.name) 未监听 127.0.0.1:$($tcp.port)。"
            }
        }

        $backend = [string]$state.urls.backend
        $frontend = [string]$state.urls.frontend
        $health = Invoke-WebRequest -UseBasicParsing -Uri "$backend/api/health" -TimeoutSec 10
        $front = Invoke-WebRequest -UseBasicParsing -Uri "$frontend/" -TimeoutSec 10
        $entry = Invoke-WebRequest -UseBasicParsing -Uri "$frontend/src/main.ts" -TimeoutSec 10
        $loginBody = @{
            username = Get-DemoRequiredValue $envValues 'BOOTSTRAP_ADMIN_USERNAME'
            password = Get-DemoRequiredValue $envValues 'BOOTSTRAP_ADMIN_PASSWORD'
        } | ConvertTo-Json
        $loginHeaders = @{ Origin = $frontend; Referer = "$frontend/" }
        $login = Invoke-WebRequest -UseBasicParsing -Uri "$backend/api/auth/login" -Method Post `
            -ContentType 'application/json' -Headers $loginHeaders -Body $loginBody `
            -SessionVariable session -TimeoutSec 15
        $me = Invoke-WebRequest -UseBasicParsing -Uri "$backend/api/auth/me" -WebSession $session -TimeoutSec 15
        $readiness = Invoke-WebRequest -UseBasicParsing -Uri "$backend/api/ai/operations/readiness" `
            -WebSession $session -TimeoutSec 15
        $dormitories = Invoke-WebRequest -UseBasicParsing -Uri "$backend/api/dormitories?page=1&size=1" `
            -WebSession $session -TimeoutSec 15

        $readinessJson = $readiness.Content | ConvertFrom-Json
        if ($readinessJson.data.providerAlias -ne 'fake') { throw 'AI provider 不是 fake。' }
        if ([bool]$readinessJson.data.writeExecutionEnabled -ne $expectedWrite) {
            throw 'AI 写执行状态与演示模式不一致。'
        }
        foreach ($control in @('audit', 'prompt', 'toolCatalog', 'budget')) {
            if ($readinessJson.data.controls.$control -ne 'READY') {
                throw "AI readiness 控制项未就绪：$control=$($readinessJson.data.controls.$control)"
            }
        }
        $roleCode = 'UNKNOWN'
        if ($me.Content -match '"roleCode"\s*:\s*"([^"]+)"') { $roleCode = $Matches[1] }
        $result = [ordered]@{
            status = 'PASS'
            mode = $state.mode
            frontend = [ordered]@{ status = $front.StatusCode; entryStatus = $entry.StatusCode; url = $frontend }
            backend = [ordered]@{ status = $health.StatusCode; url = $backend }
            login = [ordered]@{ status = $login.StatusCode; sessionStatus = $me.StatusCode; role = $roleCode }
            businessRead = [ordered]@{ status = $dormitories.StatusCode }
            ai = [ordered]@{
                status = $readiness.StatusCode
                provider = $readinessJson.data.providerAlias
                writeExecutionEnabled = [bool]$readinessJson.data.writeExecutionEnabled
                controls = $readinessJson.data.controls
            }
            isolation = [ordered]@{
                database = $state.database
                redisDatabase = $state.redis.database
                redisPrefix = $state.redis.prefix
            }
            checkedAt = (Get-Date).ToString('o')
        }
        if ($Json) { $result | ConvertTo-Json -Depth 10 } else { $result | ConvertTo-Json -Depth 10 }
        return
    } catch {
        $lastError = $_.Exception.Message
        Start-Sleep -Seconds 2
    }
}
throw "演示健康检查失败：$lastError"
