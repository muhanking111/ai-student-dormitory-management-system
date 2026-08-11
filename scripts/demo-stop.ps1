[CmdletBinding()]
param([switch]$KeepInfrastructure)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'demo-common.ps1')

$state = Read-DemoState
$results = [ordered]@{}
foreach ($name in @('frontend', 'backend')) {
    $entry = $state.processes.$name
    try { $results[$name] = Stop-DemoOwnedProcessTree $entry }
    catch {
        $results[$name] = "refused: $($_.Exception.Message)"
        throw
    }
}

if (-not $KeepInfrastructure -and [bool]$state.infrastructureOwned) {
    $envValues = Read-DemoEnv
    $profile = Get-DemoProfile ([string]$state.mode)
    $composeEnvironment = Get-DemoComposeEnvironment -EnvValues $envValues -Profile $profile `
        -MySqlPort ([int]$state.ports.mysql) -RedisPort ([int]$state.ports.redis) `
        -MySqlImage ([string]$state.images.mysql) -RedisImage ([string]$state.images.redis)
    Invoke-DemoCompose -Environment $composeEnvironment -Arguments @('stop', 'mysql', 'redis')
    $results.infrastructure = 'stopped'
} else {
    $results.infrastructure = 'kept-running'
}

$state.status = 'stopped'
Set-DemoStateProperty -State $state -Name 'stoppedAt' -Value (Get-Date).ToString('o')
Set-DemoStateProperty -State $state -Name 'stopResults' -Value $results
Write-DemoState $state
[ordered]@{ status = 'PASS'; results = $results; state = '.demo/state.json' } | ConvertTo-Json -Depth 6
