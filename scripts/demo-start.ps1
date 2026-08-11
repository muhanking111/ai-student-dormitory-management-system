[CmdletBinding()]
param(
    [ValidateSet('demo-readonly', 'demo-approval')]
    [string]$Mode = 'demo-readonly',
    [int]$BackendPort = 8081,
    [int]$FrontendPort = 5174,
    [int]$MySqlPort = 3307,
    [int]$RedisPort = 6380,
    [string]$MySqlImage = 'mysql:8.4',
    [string]$RedisImage = 'redis:7.4-alpine',
    [switch]$InstallDependencies,
    [switch]$NoBrowser,
    [switch]$PreflightOnly
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'demo-common.ps1')

$profile = Get-DemoProfile $Mode
Assert-DemoProfile $profile
foreach ($tool in @('java', 'mvn', 'node', 'npm', 'docker')) { Assert-DemoTool $tool }
foreach ($portEntry in @(
    @{ port = $BackendPort; name = '后端' },
    @{ port = $FrontendPort; name = '前端' },
    @{ port = $MySqlPort; name = 'MySQL' },
    @{ port = $RedisPort; name = 'Redis' }
)) { Assert-DemoPort -Port $portEntry.port -Name $portEntry.name }
$uniquePorts = @(@($BackendPort, $FrontendPort, $MySqlPort, $RedisPort) | Sort-Object -Unique)
if ($uniquePorts.Count -ne 4) {
    throw '前端、后端、MySQL 和 Redis 端口必须互不相同。'
}

$root = Get-DemoRepositoryRoot
$runtime = Get-DemoRuntimeRoot
$statePath = Get-DemoStatePath
$envValues = Read-DemoEnv
$composeEnvironment = Get-DemoComposeEnvironment -EnvValues $envValues -Profile $profile `
    -MySqlPort $MySqlPort -RedisPort $RedisPort -MySqlImage $MySqlImage -RedisImage $RedisImage
$adminUsername = Get-DemoRequiredValue $envValues 'BOOTSTRAP_ADMIN_USERNAME'
$adminPassword = Get-DemoRequiredValue $envValues 'BOOTSTRAP_ADMIN_PASSWORD'
$rolloutHmacKey = New-DemoEphemeralKey
$auditHmacKey = New-DemoEphemeralKey
$tokenizationHmacKey = New-DemoEphemeralKey
$stepUpHmacKey = New-DemoEphemeralKey

if (Test-Path -LiteralPath $statePath) {
    $existing = Read-DemoState
    if ($existing.status -eq 'running') {
        throw "演示环境已记录为 running；请先运行 scripts/demo-health.ps1 或 scripts/demo-stop.ps1。"
    }
} else {
    $existingContainers = @(Invoke-WithDemoEnvironment $composeEnvironment {
        $containers = @(& docker compose -p (Get-DemoComposeProject) -f (Get-DemoComposeFile) ps -q)
        if ($LASTEXITCODE -ne 0) { throw 'Docker daemon 不可用，无法检查演示容器归属。' }
        return $containers
    })
    if ($existingContainers.Count -gt 0) {
        throw '发现无状态文件归属的 dormitory-local-demo 容器；脚本拒绝接管，请人工核对。'
    }
}

$preflight = [ordered]@{
    mode = $Mode
    database = $profile.database
    redisDatabase = $profile.redisDatabase
    redisPrefix = $profile.redisPrefix
    writeExecutionEnabled = $profile.writeExecutionEnabled
    urls = [ordered]@{
        frontend = "http://127.0.0.1:$FrontendPort"
        backend = "http://127.0.0.1:$BackendPort"
    }
    ports = [ordered]@{ frontend = $FrontendPort; backend = $BackendPort; mysql = $MySqlPort; redis = $RedisPort }
    provider = 'fake'
    productionResources = $false
    images = [ordered]@{ mysql = $MySqlImage; redis = $RedisImage }
}
if ($PreflightOnly) {
    $preflight | ConvertTo-Json -Depth 6
    return
}

foreach ($portEntry in @(
    @{ port = $BackendPort; name = '后端' },
    @{ port = $FrontendPort; name = '前端' },
    @{ port = $MySqlPort; name = 'MySQL' },
    @{ port = $RedisPort; name = 'Redis' }
)) { Assert-DemoPortAvailable -Port $portEntry.port -Name $portEntry.name }

$frontendDirectory = Join-Path $root 'frontend'
if (-not (Test-Path -LiteralPath (Join-Path $frontendDirectory 'node_modules') -PathType Container)) {
    if (-not $InstallDependencies) {
        throw 'frontend/node_modules 不存在。请先运行 npm ci，或显式使用 -InstallDependencies。'
    }
    Push-Location $frontendDirectory
    try {
        & npm ci
        if ($LASTEXITCODE -ne 0) { throw "npm ci 失败，退出码 $LASTEXITCODE。" }
    } finally { Pop-Location }
}

New-Item -ItemType Directory -Force -Path (Join-Path $runtime 'logs') | Out-Null
$backendProcess = $null
$frontendProcess = $null
$state = $null
try {
    Invoke-DemoCompose -Environment $composeEnvironment -Arguments @('up', '-d', 'mysql', 'redis')
    Wait-DemoTcpPort -Port $MySqlPort -TimeoutSeconds 120 -Name 'MySQL'
    Wait-DemoTcpPort -Port $RedisPort -TimeoutSeconds 120 -Name 'Redis'
    Wait-DemoComposeHealthy -Environment $composeEnvironment -Service mysql -TimeoutSeconds 180
    Wait-DemoComposeHealthy -Environment $composeEnvironment -Service redis -TimeoutSeconds 120
    Initialize-DemoDatabase -ComposeEnvironment $composeEnvironment -EnvValues $envValues -Profile $profile

    $backendEnvironment = @{
        SPRING_PROFILES_ACTIVE = 'dev'
        SERVER_ADDRESS = '127.0.0.1'
        SERVER_PORT = [string]$BackendPort
        DB_URL = "jdbc:mysql://127.0.0.1:$MySqlPort/$($profile.database)?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
        DB_USERNAME = (Get-DemoRequiredValue $envValues 'MYSQL_USER')
        DB_PASSWORD = (Get-DemoRequiredValue $envValues 'MYSQL_PASSWORD')
        SPRING_DATASOURCE_URL = "jdbc:mysql://127.0.0.1:$MySqlPort/$($profile.database)?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
        SPRING_DATASOURCE_USERNAME = (Get-DemoRequiredValue $envValues 'MYSQL_USER')
        SPRING_DATASOURCE_PASSWORD = (Get-DemoRequiredValue $envValues 'MYSQL_PASSWORD')
        SPRING_DATASOURCE_DRIVER_CLASS_NAME = 'com.mysql.cj.jdbc.Driver'
        REDIS_HOST = '127.0.0.1'
        REDIS_PORT = [string]$RedisPort
        REDIS_USERNAME = ''
        REDIS_PASSWORD = ''
        REDIS_DATABASE = [string]$profile.redisDatabase
        BOOTSTRAP_ADMIN_USERNAME = $adminUsername
        BOOTSTRAP_ADMIN_PASSWORD = $adminPassword
        DEMO_DATA_ENABLED = 'true'
        CORS_ALLOWED_ORIGINS = "http://127.0.0.1:$FrontendPort,http://localhost:$FrontendPort"
        REQUIRE_LOGIN_ORIGIN = 'true'
        SESSION_COOKIE_SECURE = 'false'
        SPRING_AI_MODEL_CHAT = 'none'
        SPRING_AI_MODEL_EMBEDDING = 'none'
        AI_ENABLED = 'true'
        AI_CAPABILITY_ASSISTANT_ENABLED = 'true'
        AI_CAPABILITY_KNOWLEDGE_ENABLED = 'true'
        AI_CAPABILITY_DASHBOARD_ENABLED = 'true'
        AI_CAPABILITY_REPAIR_ENABLED = 'true'
        AI_CAPABILITY_NOTICE_ENABLED = 'true'
        AI_CAPABILITY_RISK_ENABLED = 'true'
        AI_PROVIDER_ACTIVE = 'fake'
        AI_STREAMING_ENABLED = 'true'
        AI_WRITE_EXECUTION_ENABLED = $(if ($profile.writeExecutionEnabled) { 'true' } else { 'false' })
        AI_ROLLOUT_ENABLED = 'true'
        AI_ROLLOUT_BASIS_POINTS = '10000'
        AI_ROLLOUT_POLICY_VERSION = 'local-demo-v1'
        AI_ROLLOUT_HMAC_KEY = $rolloutHmacKey
        AI_AUDIT_HMAC_KEY = $auditHmacKey
        AI_TOKENIZATION_HMAC_KEY = $tokenizationHmacKey
        AI_STEP_UP_HMAC_KEY = $stepUpHmacKey
        AI_REDIS_KEY_PREFIX = $profile.redisPrefix
        AI_RUN_RESERVED_TOKENS = '20000'
    }
    $backendProcess = Start-DemoProcess -CommandPath (Get-Command mvn.cmd).Source `
        -Arguments @('-q', 'spring-boot:run') -WorkingDirectory (Join-Path $root 'backend') `
        -Environment $backendEnvironment `
        -StdoutPath (Join-Path $runtime 'logs\backend.out.log') `
        -StderrPath (Join-Path $runtime 'logs\backend.err.log')

    Wait-DemoHttp -Uri "http://127.0.0.1:$BackendPort/api/health" -TimeoutSeconds 240 | Out-Null
    Wait-DemoAiSchema -ComposeEnvironment $composeEnvironment -Profile $profile -TimeoutSeconds 120
    Wait-DemoBootstrapPrompts -ComposeEnvironment $composeEnvironment -Profile $profile -TimeoutSeconds 120
    Invoke-DemoControlPlaneSeed -ComposeEnvironment $composeEnvironment -Profile $profile

    $frontendEnvironment = @{
        VITE_AI_ENABLED = 'true'
        VITE_AI_DEMO_ENABLED = 'false'
        VITE_VISUAL_EVIDENCE_ENABLED = 'false'
        VITE_BACKEND_PROXY_TARGET = "http://127.0.0.1:$BackendPort"
    }
    $frontendProcess = Start-DemoProcess -CommandPath (Get-Command npm.cmd).Source `
        -Arguments @('run', 'dev', '--', '--host', '127.0.0.1', '--port', [string]$FrontendPort, '--strictPort') `
        -WorkingDirectory $frontendDirectory -Environment $frontendEnvironment `
        -StdoutPath (Join-Path $runtime 'logs\frontend.out.log') `
        -StderrPath (Join-Path $runtime 'logs\frontend.err.log')

    $state = [ordered]@{
        schemaVersion = 1
        status = 'running'
        mode = $Mode
        startedAt = (Get-Date).ToString('o')
        repositoryRoot = $root
        composeProject = Get-DemoComposeProject
        infrastructureOwned = $true
        database = $profile.database
        redis = [ordered]@{ database = $profile.redisDatabase; prefix = $profile.redisPrefix }
        ports = [ordered]@{ frontend = $FrontendPort; backend = $BackendPort; mysql = $MySqlPort; redis = $RedisPort }
        images = [ordered]@{ mysql = $MySqlImage; redis = $RedisImage }
        urls = [ordered]@{ frontend = "http://127.0.0.1:$FrontendPort"; backend = "http://127.0.0.1:$BackendPort" }
        provider = 'fake'
        writeExecutionEnabled = $profile.writeExecutionEnabled
        processes = [ordered]@{
            backend = New-DemoProcessState -Process $backendProcess -Name 'backend'
            frontend = New-DemoProcessState -Process $frontendProcess -Name 'frontend'
        }
        logs = [ordered]@{
            backend = '.demo/logs/backend.out.log'
            backendError = '.demo/logs/backend.err.log'
            frontend = '.demo/logs/frontend.out.log'
            frontendError = '.demo/logs/frontend.err.log'
        }
    }
    Write-DemoState $state
    & (Join-Path $PSScriptRoot 'demo-health.ps1') -WaitSeconds 180 | Out-Null
    if (-not $NoBrowser) { Start-Process $state.urls.frontend | Out-Null }
    $preflight.status = 'running'
    $preflight.state = '.demo/state.json'
    $preflight | ConvertTo-Json -Depth 6
} catch {
    if ($null -ne $frontendProcess) {
        Stop-DemoOwnedProcessTree (New-DemoProcessState -Process $frontendProcess -Name 'frontend') | Out-Null
    }
    if ($null -ne $backendProcess) {
        Stop-DemoOwnedProcessTree (New-DemoProcessState -Process $backendProcess -Name 'backend') | Out-Null
    }
    try { Invoke-DemoCompose -Environment $composeEnvironment -Arguments @('stop', 'mysql', 'redis') } catch { }
    if ($null -ne $state) {
        $state.status = 'failed'
        Set-DemoStateProperty -State $state -Name 'failedAt' -Value (Get-Date).ToString('o')
        Write-DemoState $state
    }
    throw
}
