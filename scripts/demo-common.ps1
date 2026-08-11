[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:DemoRepositoryRoot = Split-Path -Parent $PSScriptRoot
$script:DemoRuntimeRoot = Join-Path $script:DemoRepositoryRoot '.demo'
$script:DemoStatePath = Join-Path $script:DemoRuntimeRoot 'state.json'
$script:DemoComposeFile = Join-Path $script:DemoRepositoryRoot 'compose.yml'
$script:DemoComposeProject = 'dormitory-local-demo'

function Get-DemoRepositoryRoot { return $script:DemoRepositoryRoot }
function Get-DemoRuntimeRoot { return $script:DemoRuntimeRoot }
function Get-DemoStatePath { return $script:DemoStatePath }
function Get-DemoComposeFile { return $script:DemoComposeFile }
function Get-DemoComposeProject { return $script:DemoComposeProject }

function Read-DemoEnv {
    param([string]$Path = (Join-Path $script:DemoRepositoryRoot '.env'))

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "缺少本地 .env：$Path。请先从 .env.example 创建并填写本地演示凭据。"
    }
    $values = @{}
    foreach ($rawLine in Get-Content -LiteralPath $Path) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) { continue }
        $name = $line.Substring(0, $separator).Trim()
        $value = $line.Substring($separator + 1).Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$name] = $value
    }
    return $values
}

function Get-DemoRequiredValue {
    param([hashtable]$Values, [string]$Name)

    if (-not $Values.ContainsKey($Name) -or [string]::IsNullOrWhiteSpace([string]$Values[$Name])) {
        throw "本地 .env 缺少必填项 $Name。"
    }
    $value = [string]$Values[$Name]
    if ($value -match '^(replace-with|change-me|your-|example|password)$') {
        throw "本地 .env 的 $Name 仍是占位值。"
    }
    return $value
}

function New-DemoEphemeralKey {
    param([int]$ByteLength = 32)
    if ($ByteLength -lt 32) { throw '演示临时 key 至少需要 32 bytes。' }
    $bytes = New-Object byte[] $ByteLength
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return [Convert]::ToBase64String($bytes)
}

function Assert-DemoTool {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "缺少必需工具：$Name"
    }
}

function Assert-DemoPort {
    param([int]$Port, [string]$Name)
    if ($Port -lt 1024 -or $Port -gt 65535) {
        throw "$Name 端口必须在 1024-65535：$Port"
    }
}

function Test-DemoTcpPort {
    param([string]$HostName = '127.0.0.1', [int]$Port, [int]$TimeoutMilliseconds = 500)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($TimeoutMilliseconds)) { return $false }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Assert-DemoPortAvailable {
    param([int]$Port, [string]$Name)
    if (Test-DemoTcpPort -Port $Port) {
        throw "$Name 端口 $Port 已被占用；脚本不会终止或复用未知进程。"
    }
}

function Wait-DemoTcpPort {
    param([int]$Port, [int]$TimeoutSeconds = 90, [string]$Name = '服务')
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-DemoTcpPort -Port $Port -TimeoutMilliseconds 1000) { return }
        Start-Sleep -Milliseconds 500
    }
    throw "$Name 未在 $TimeoutSeconds 秒内监听 127.0.0.1:$Port。"
}

function Wait-DemoHttp {
    param([string]$Uri, [int]$TimeoutSeconds = 180)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = $null
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400) { return $response }
        } catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Seconds 1
    }
    throw "HTTP 健康检查超时：$Uri；最后错误：$lastError"
}

function Get-DemoProfile {
    param([ValidateSet('demo-readonly', 'demo-approval')][string]$Mode)
    if ($Mode -eq 'demo-approval') {
        return [pscustomobject]@{
            mode = $Mode
            database = 'student_dormitory_approval_demo'
            redisDatabase = 13
            redisPrefix = 'dormitory:demo:approval'
            writeExecutionEnabled = $true
        }
    }
    return [pscustomobject]@{
        mode = 'demo-readonly'
        database = 'student_dormitory_readonly_demo'
        redisDatabase = 12
        redisPrefix = 'dormitory:demo:readonly'
        writeExecutionEnabled = $false
    }
}

function Assert-DemoProfile {
    param($Profile)
    if ($Profile.database -notmatch '^student_dormitory_(readonly|approval)_demo$') {
        throw "拒绝非固定演示数据库：$($Profile.database)"
    }
    if ($Profile.redisDatabase -notin @(12, 13)) {
        throw "拒绝非固定演示 Redis DB：$($Profile.redisDatabase)"
    }
    if ($Profile.redisPrefix -notmatch '^dormitory:demo:(readonly|approval)$') {
        throw "拒绝非固定演示 Redis prefix：$($Profile.redisPrefix)"
    }
}

function Invoke-WithDemoEnvironment {
    param([hashtable]$Variables, [scriptblock]$Action)
    $previous = @{}
    try {
        foreach ($name in $Variables.Keys) {
            $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            [Environment]::SetEnvironmentVariable($name, [string]$Variables[$name], 'Process')
        }
        return & $Action
    } finally {
        foreach ($name in $Variables.Keys) {
            [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
        }
    }
}

function Get-DemoComposeEnvironment {
    param(
        [hashtable]$EnvValues,
        $Profile,
        [int]$MySqlPort,
        [int]$RedisPort,
        [string]$MySqlImage = 'mysql:8.4',
        [string]$RedisImage = 'redis:7.4-alpine'
    )
    return @{
        MYSQL_IMAGE = $MySqlImage
        MYSQL_BIND_ADDRESS = '127.0.0.1'
        MYSQL_PORT = [string]$MySqlPort
        MYSQL_DATABASE = $Profile.database
        MYSQL_USER = (Get-DemoRequiredValue $EnvValues 'MYSQL_USER')
        MYSQL_PASSWORD = (Get-DemoRequiredValue $EnvValues 'MYSQL_PASSWORD')
        MYSQL_ROOT_PASSWORD = (Get-DemoRequiredValue $EnvValues 'MYSQL_ROOT_PASSWORD')
        REDIS_BIND_ADDRESS = '127.0.0.1'
        REDIS_PORT = [string]$RedisPort
        REDIS_IMAGE = $RedisImage
    }
}

function Invoke-DemoCompose {
    param([hashtable]$Environment, [string[]]$Arguments)
    Invoke-WithDemoEnvironment $Environment {
        & docker compose -p $script:DemoComposeProject -f $script:DemoComposeFile @Arguments
        if ($LASTEXITCODE -ne 0) { throw "docker compose 失败，退出码 $LASTEXITCODE。" }
    } | Out-Null
}

function Wait-DemoComposeHealthy {
    param(
        [hashtable]$Environment,
        [ValidateSet('mysql', 'redis')][string]$Service,
        [int]$TimeoutSeconds = 180
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastStatus = 'missing'
    while ((Get-Date) -lt $deadline) {
        $containerId = Invoke-WithDemoEnvironment $Environment {
            $ids = @(& docker compose -p $script:DemoComposeProject -f $script:DemoComposeFile ps -q $Service)
            if ($LASTEXITCODE -ne 0) { throw "无法读取 $Service 容器状态。" }
            if ($ids.Count -eq 0) { return '' }
            return [string]$ids[0]
        }
        if (-not [string]::IsNullOrWhiteSpace([string]$containerId)) {
            $lastStatus = (& docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $containerId).Trim()
            if ($LASTEXITCODE -ne 0) { throw "无法检查 $Service 容器 health。" }
            if ($lastStatus -eq 'healthy') { return }
            if ($lastStatus -eq 'unhealthy') { throw "$Service 容器 health=unhealthy。" }
        }
        Start-Sleep -Seconds 2
    }
    throw "$Service 容器未在 $TimeoutSeconds 秒内达到 healthy；最后状态：$lastStatus"
}

function ConvertTo-DemoSqlLiteral {
    param([string]$Value)
    return "'" + $Value.Replace("'", "''") + "'"
}

function Invoke-DemoMySql {
    param([hashtable]$ComposeEnvironment, [string]$Sql)
    $rootPassword = [string]$ComposeEnvironment.MYSQL_ROOT_PASSWORD
    Invoke-WithDemoEnvironment $ComposeEnvironment {
        $Sql | & docker compose -p $script:DemoComposeProject -f $script:DemoComposeFile `
            exec -T -e "MYSQL_PWD=$rootPassword" mysql `
            mysql --protocol=tcp --host=127.0.0.1 --user=root --batch
        if ($LASTEXITCODE -ne 0) { throw "演示 MySQL 命令失败，退出码 $LASTEXITCODE。" }
    } | Out-Null
}

function Invoke-DemoMySqlQuery {
    param([hashtable]$ComposeEnvironment, [string]$Sql)
    $rootPassword = [string]$ComposeEnvironment.MYSQL_ROOT_PASSWORD
    return @(Invoke-WithDemoEnvironment $ComposeEnvironment {
        $output = @($Sql | & docker compose -p $script:DemoComposeProject -f $script:DemoComposeFile `
            exec -T -e "MYSQL_PWD=$rootPassword" mysql `
            mysql --protocol=tcp --host=127.0.0.1 --user=root --batch --skip-column-names)
        if ($LASTEXITCODE -ne 0) { throw "演示 MySQL 查询失败，退出码 $LASTEXITCODE。" }
        return $output
    })
}

function Wait-DemoAiSchema {
    param([hashtable]$ComposeEnvironment, $Profile, [int]$TimeoutSeconds = 120)
    Assert-DemoProfile $Profile
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastCount = 0
    while ((Get-Date) -lt $deadline) {
        $sql = "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$($Profile.database)' AND table_name IN ('ai_prompt_version','ai_tool_catalog_version','ai_quota_policy','ai_budget_bucket');"
        try {
            $result = @(Invoke-DemoMySqlQuery -ComposeEnvironment $ComposeEnvironment -Sql $sql)
            if ($result.Count -gt 0) { $lastCount = [int]([string]$result[-1]).Trim() }
            if ($lastCount -eq 4) { return }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw "AI schema 未在 $TimeoutSeconds 秒内就绪；关键表数量：$lastCount/4"
}

function Wait-DemoBootstrapPrompts {
    param([hashtable]$ComposeEnvironment, $Profile, [int]$TimeoutSeconds = 120)
    Assert-DemoProfile $Profile
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastCount = 0
    while ((Get-Date) -lt $deadline) {
        $sql = "SELECT COUNT(*) FROM ``$($Profile.database)``.ai_prompt_version WHERE version='v1' AND prompt_key IN ('assistant.system','dashboard.system','knowledge.system','notice.system','repair.system','risk.system');"
        try {
            $result = @(Invoke-DemoMySqlQuery -ComposeEnvironment $ComposeEnvironment -Sql $sql)
            if ($result.Count -gt 0) { $lastCount = [int]([string]$result[-1]).Trim() }
            if ($lastCount -eq 6) { return }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw "演示 prompt 未在 $TimeoutSeconds 秒内完成导入；内置 v1 prompt 数量：$lastCount/6"
}

function Initialize-DemoDatabase {
    param([hashtable]$ComposeEnvironment, [hashtable]$EnvValues, $Profile)
    Assert-DemoProfile $Profile
    $user = Get-DemoRequiredValue $EnvValues 'MYSQL_USER'
    if ($user -notmatch '^[A-Za-z0-9_]{1,32}$') { throw 'MYSQL_USER 只能包含字母、数字和下划线。' }
    $password = Get-DemoRequiredValue $EnvValues 'MYSQL_PASSWORD'
    $passwordLiteral = ConvertTo-DemoSqlLiteral $password
    $sql = @"
CREATE DATABASE IF NOT EXISTS ``$($Profile.database)`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '$user'@'%' IDENTIFIED BY $passwordLiteral;
ALTER USER '$user'@'%' IDENTIFIED BY $passwordLiteral;
GRANT ALL PRIVILEGES ON ``$($Profile.database)``.* TO '$user'@'%';
FLUSH PRIVILEGES;
"@
    Invoke-DemoMySql -ComposeEnvironment $ComposeEnvironment -Sql $sql
}

function Invoke-DemoControlPlaneSeed {
    param([hashtable]$ComposeEnvironment, $Profile)
    Assert-DemoProfile $Profile
    $seedPath = Join-Path $PSScriptRoot 'demo-control-plane.sql'
    if (-not (Test-Path -LiteralPath $seedPath -PathType Leaf)) { throw "缺少演示控制面 SQL：$seedPath" }
    $sql = "USE ``$($Profile.database)``;`n" + (Get-Content -Raw -LiteralPath $seedPath)
    Invoke-DemoMySql -ComposeEnvironment $ComposeEnvironment -Sql $sql
}

function Start-DemoProcess {
    param(
        [string]$CommandPath,
        [string[]]$Arguments,
        [string]$WorkingDirectory,
        [hashtable]$Environment,
        [string]$StdoutPath,
        [string]$StderrPath
    )
    $quotedCommand = '"' + $CommandPath + '" ' + (($Arguments | ForEach-Object {
        if ($_ -match '[\s"]') { '"' + $_.Replace('"', '\"') + '"' } else { $_ }
    }) -join ' ')
    return Invoke-WithDemoEnvironment $Environment {
        Start-Process -FilePath $env:ComSpec -ArgumentList @('/d', '/s', '/c', $quotedCommand) `
            -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $StdoutPath -RedirectStandardError $StderrPath
    }
}

function New-DemoProcessState {
    param($Process, [string]$Name)
    $Process.Refresh()
    return [pscustomobject]@{
        name = $Name
        id = $Process.Id
        startedAtUtc = $Process.StartTime.ToUniversalTime().ToString('o')
    }
}

function Get-DemoOwnedProcess {
    param($Entry)
    if ($null -eq $Entry -or $null -eq $Entry.id) { return $null }
    $process = Get-Process -Id ([int]$Entry.id) -ErrorAction SilentlyContinue
    if ($null -eq $process) { return $null }
    $expected = [DateTime]::Parse([string]$Entry.startedAtUtc).ToUniversalTime()
    $actual = $process.StartTime.ToUniversalTime()
    if ([Math]::Abs(($actual - $expected).TotalSeconds) -gt 2) {
        throw "PID $($Entry.id) 已被复用；拒绝终止未知进程。"
    }
    return $process
}

function Stop-DemoOwnedProcessTree {
    param($Entry)
    $root = Get-DemoOwnedProcess $Entry
    if ($null -eq $root) { return 'already-stopped' }
    $all = @(Get-CimInstance Win32_Process)
    $descendants = New-Object System.Collections.Generic.List[int]
    $queue = New-Object System.Collections.Generic.Queue[int]
    $queue.Enqueue([int]$root.Id)
    while ($queue.Count -gt 0) {
        $parent = $queue.Dequeue()
        foreach ($child in $all | Where-Object { [int]$_.ParentProcessId -eq $parent }) {
            $childId = [int]$child.ProcessId
            $descendants.Add($childId)
            $queue.Enqueue($childId)
        }
    }
    for ($index = $descendants.Count - 1; $index -ge 0; $index -= 1) {
        Stop-Process -Id $descendants[$index] -Force -ErrorAction SilentlyContinue
    }
    Stop-Process -Id $root.Id -Force -ErrorAction SilentlyContinue
    return 'stopped'
}

function Read-DemoState {
    if (-not (Test-Path -LiteralPath $script:DemoStatePath -PathType Leaf)) {
        throw "没有演示状态文件：$script:DemoStatePath"
    }
    return Get-Content -Raw -LiteralPath $script:DemoStatePath | ConvertFrom-Json
}

function Write-DemoState {
    param($State)
    New-Item -ItemType Directory -Force -Path $script:DemoRuntimeRoot | Out-Null
    $State | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $script:DemoStatePath -Encoding utf8
}

function Set-DemoStateProperty {
    param($State, [string]$Name, $Value)
    if ($State -is [System.Collections.IDictionary]) {
        $State[$Name] = $Value
        return
    }
    $State | Add-Member -NotePropertyName $Name -NotePropertyValue $Value -Force
}

function Get-DemoResetToken {
    param($State)
    return "$($State.database)|redis:$($State.redis.database)|$($State.redis.prefix)"
}
