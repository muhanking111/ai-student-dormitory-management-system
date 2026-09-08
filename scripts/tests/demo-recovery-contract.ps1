[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'demo-common.ps1')
$checks = 0
function Assert-Recovery([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
    $script:checks++
}
$currentProcess = Get-Process -Id $PID
$roundTrippedEntry = (New-DemoProcessState $currentProcess 'contract-test' | ConvertTo-Json | ConvertFrom-Json)
Assert-Recovery ((Get-DemoOwnedProcess $roundTrippedEntry).Id -eq $PID) 'JSON timestamps must preserve UTC process identity on PowerShell 7.'
$stringEntry = New-DemoProcessState $currentProcess 'contract-test'
Assert-Recovery ((Get-DemoOwnedProcess $stringEntry).Id -eq $PID) 'String timestamps must preserve UTC process identity on Windows PowerShell.'
$roundTrippedEntry.startedAtUtc = $currentProcess.StartTime.ToUniversalTime().AddHours(-1)
$rejected = $false
try { Get-DemoOwnedProcess $roundTrippedEntry | Out-Null } catch { $rejected = $true }
Assert-Recovery $rejected 'A genuinely mismatched process timestamp must remain rejected.'
$rejected = $false
try { Get-DemoRequiredValue @{ MYSQL_PASSWORD = 'replace-with-a-local-password' } 'MYSQL_PASSWORD' | Out-Null }
catch { $rejected = $true }
Assert-Recovery $rejected 'Example password placeholder must not pass preflight.'
$state = [pscustomobject]@{
    schemaVersion = 1
    status = 'running'
    repositoryRoot = Get-DemoRepositoryRoot
    composeProject = Get-DemoComposeProject
    mode = 'demo-readonly'
    database = 'student_dormitory_readonly_demo'
    redis = [pscustomobject]@{ database = 12; prefix = 'dormitory:demo:readonly' }
    ports = [pscustomobject]@{ frontend = 5174; backend = 8081; mysql = 3307; redis = 6380 }
    processes = [pscustomobject]@{ frontend = [pscustomobject]@{id=1}; backend=[pscustomobject]@{id=2} }
}
Assert-Recovery (Test-DemoStateStale $state -ProcessProbe { param($entry) $false } -PortProbe { param($port) $false }) 'Dead owned state should be recoverable.'
Assert-Recovery (-not (Test-DemoStateStale $state -ProcessProbe { param($entry) $entry.id -eq 1 } -PortProbe { param($port) $false })) 'A live process must block stale recovery.'
Assert-Recovery (-not (Test-DemoStateStale $state -ProcessProbe { param($entry) $false } -PortProbe { param($port) $port -eq 3307 })) 'A listening infrastructure port must block stale recovery.'
$state.composeProject='unrelated-project'
$rejected=$false
try { Test-DemoStateStale $state -ProcessProbe { param($entry) $false } -PortProbe { param($port) $false } | Out-Null } catch { $rejected=$true }
Assert-Recovery $rejected 'Foreign project state must never be adopted.'
$state.composeProject=Get-DemoComposeProject
$state.database='student_dormitory'
$rejected=$false
try { Test-DemoStateStale $state -ProcessProbe { param($entry) $false } -PortProbe { param($port) $false } | Out-Null } catch { $rejected=$true }
Assert-Recovery $rejected 'Ordinary databases must never be adopted.'
[ordered]@{status='PASS'; assertions=$checks} | ConvertTo-Json
