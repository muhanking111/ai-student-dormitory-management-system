[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ConfirmTarget
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'demo-common.ps1')

$state = Read-DemoState
$expectedToken = Get-DemoResetToken $state
if ($ConfirmTarget -ne $expectedToken) {
    Write-Output "拒绝重置。确认令牌必须精确等于：$expectedToken"
    throw '重置确认令牌不匹配。'
}
$profile = Get-DemoProfile ([string]$state.mode)
Assert-DemoProfile $profile
if ($state.database -ne $profile.database -or
    [int]$state.redis.database -ne $profile.redisDatabase -or
    [string]$state.redis.prefix -ne $profile.redisPrefix) {
    throw '状态文件目标与固定演示 profile 不一致，拒绝重置。'
}

foreach ($name in @('frontend', 'backend')) {
    Stop-DemoOwnedProcessTree $state.processes.$name | Out-Null
}
$envValues = Read-DemoEnv
$composeEnvironment = Get-DemoComposeEnvironment -EnvValues $envValues -Profile $profile `
    -MySqlPort ([int]$state.ports.mysql) -RedisPort ([int]$state.ports.redis) `
    -MySqlImage ([string]$state.images.mysql) -RedisImage ([string]$state.images.redis)
Invoke-DemoCompose -Environment $composeEnvironment -Arguments @('up', '-d', 'mysql', 'redis')
Wait-DemoTcpPort -Port ([int]$state.ports.mysql) -TimeoutSeconds 120 -Name 'MySQL'
Wait-DemoTcpPort -Port ([int]$state.ports.redis) -TimeoutSeconds 120 -Name 'Redis'
Wait-DemoComposeHealthy -Environment $composeEnvironment -Service mysql -TimeoutSeconds 180
Wait-DemoComposeHealthy -Environment $composeEnvironment -Service redis -TimeoutSeconds 120

$user = Get-DemoRequiredValue $envValues 'MYSQL_USER'
if ($user -notmatch '^[A-Za-z0-9_]{1,32}$') { throw 'MYSQL_USER 只能包含字母、数字和下划线。' }
$passwordLiteral = ConvertTo-DemoSqlLiteral (Get-DemoRequiredValue $envValues 'MYSQL_PASSWORD')
$sql = @"
DROP DATABASE IF EXISTS ``$($profile.database)``;
CREATE DATABASE ``$($profile.database)`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '$user'@'%' IDENTIFIED BY $passwordLiteral;
ALTER USER '$user'@'%' IDENTIFIED BY $passwordLiteral;
GRANT ALL PRIVILEGES ON ``$($profile.database)``.* TO '$user'@'%';
FLUSH PRIVILEGES;
"@
Invoke-DemoMySql -ComposeEnvironment $composeEnvironment -Sql $sql

$pattern = "$($profile.redisPrefix):*"
$lua = "local c='0'; local n=0; repeat local r=redis.call('SCAN',c,'MATCH',ARGV[1],'COUNT',500); c=r[1]; if #r[2]>0 then n=n+redis.call('DEL',unpack(r[2])); end until c=='0'; return n"
$deletedKeys = Invoke-WithDemoEnvironment $composeEnvironment {
    & docker compose -p (Get-DemoComposeProject) -f (Get-DemoComposeFile) `
        exec -T redis redis-cli -n $profile.redisDatabase EVAL $lua 0 $pattern
    if ($LASTEXITCODE -ne 0) { throw "Redis prefix 重置失败，退出码 $LASTEXITCODE。" }
}
Invoke-DemoCompose -Environment $composeEnvironment -Arguments @('stop', 'mysql', 'redis')

$state.status = 'reset'
Set-DemoStateProperty -State $state -Name 'resetAt' -Value (Get-Date).ToString('o')
Set-DemoStateProperty -State $state -Name 'resetDeletedRedisKeys' `
    -Value ([int](($deletedKeys | Select-Object -Last 1).ToString().Trim()))
Write-DemoState $state
[ordered]@{
    status = 'PASS'
    database = $profile.database
    redisDatabase = $profile.redisDatabase
    redisPrefix = $profile.redisPrefix
    deletedRedisKeys = $state.resetDeletedRedisKeys
    next = "scripts/demo-start.ps1 -Mode $($profile.mode) -NoBrowser"
} | ConvertTo-Json -Depth 6
