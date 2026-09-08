[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'demo-common.ps1')

$checks = 0
function Assert-Safety([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:checks++
}

function Assert-Throws([scriptblock]$Action, [string]$Message) {
    $threw = $false
    try { & $Action } catch { $threw = $true }
    Assert-Safety $threw $Message
}

function Assert-OwnerOnlyAcl([string]$Path) {
    $acl = Get-Acl -LiteralPath $Path
    Assert-Safety $acl.AreAccessRulesProtected "ACL inheritance must be disabled: $Path"
    $allowedSids = @(
        [Security.Principal.WindowsIdentity]::GetCurrent().User.Value,
        'S-1-5-18',
        'S-1-5-32-544'
    )
    $dangerousRights = [Security.AccessControl.FileSystemRights]::Write -bor
        [Security.AccessControl.FileSystemRights]::Modify -bor
        [Security.AccessControl.FileSystemRights]::Delete -bor
        [Security.AccessControl.FileSystemRights]::ChangePermissions -bor
        [Security.AccessControl.FileSystemRights]::TakeOwnership -bor
        [Security.AccessControl.FileSystemRights]::FullControl
    $unexpected = @($acl.Access | Where-Object {
        $_.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
        $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin $allowedSids -and
        (($_.FileSystemRights -band $dangerousRights) -ne 0)
    })
    Assert-Safety ($unexpected.Count -eq 0) "Unexpected write-capable ACL entry: $Path"
}

$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testRoot = Join-Path $tempBase ('dormitory-demo-safety-' + [guid]::NewGuid().ToString('N'))
$mutexJob = $null
try {
    Initialize-DemoProtectedDirectory $testRoot
    $keyRoot = Join-Path $testRoot 'keys'
    $keys = Get-DemoRuntimeKeys -Mode 'demo-readonly' -StorageRoot $keyRoot
    Assert-OwnerOnlyAcl $testRoot
    Assert-Safety ($keys.Count -eq 4) 'Protected keyring must expose four runtime keys.'
    Assert-OwnerOnlyAcl $keyRoot
    Assert-OwnerOnlyAcl (Join-Path $keyRoot 'demo-readonly.json')

    $statePath = Join-Path $testRoot 'state.json'
    Write-DemoState ([ordered]@{schemaVersion=1;status='first'}) -Path $statePath
    Assert-OwnerOnlyAcl $statePath
    $original = Get-Content -Raw -LiteralPath $statePath
    $lockStream = [IO.File]::Open($statePath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        Assert-Throws {
            Write-DemoState ([ordered]@{schemaVersion=1;status='replacement'}) -Path $statePath
        } 'A failed atomic replacement must surface an error.'
    } finally {
        $lockStream.Dispose()
    }
    Assert-Safety ((Get-Content -Raw -LiteralPath $statePath) -eq $original) `
        'A failed atomic replacement must preserve the prior state byte-for-byte.'
    Write-DemoState ([ordered]@{schemaVersion=1;status='second'}) -Path $statePath
    Assert-Safety (((Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json).status) -eq 'second') `
        'Replacing an existing state must succeed after the file lock is released.'
    Assert-OwnerOnlyAcl $statePath

    $mutexName = 'Local\dormitory-demo-contract-' + [guid]::NewGuid().ToString('N')
    $readyPath = Join-Path $testRoot 'mutex-ready'
    $releasePath = Join-Path $testRoot 'mutex-release'
    $mutexJob = Start-Job -ScriptBlock {
        param($Name, $ReadyPath, $ReleasePath)
        $mutex = [Threading.Mutex]::new($false, $Name)
        $acquired = $false
        try {
            $acquired = $mutex.WaitOne(5000)
            if (-not $acquired) { throw 'Unable to acquire contract mutex.' }
            [IO.File]::WriteAllText($ReadyPath, 'ready')
            $deadline = [DateTime]::UtcNow.AddSeconds(15)
            while (-not [IO.File]::Exists($ReleasePath) -and [DateTime]::UtcNow -lt $deadline) {
                Start-Sleep -Milliseconds 50
            }
        } finally {
            if ($acquired) { $mutex.ReleaseMutex() }
            $mutex.Dispose()
        }
    } -ArgumentList $mutexName, $readyPath, $releasePath
    $readyDeadline = [DateTime]::UtcNow.AddSeconds(8)
    while (-not (Test-Path -LiteralPath $readyPath) -and [DateTime]::UtcNow -lt $readyDeadline) {
        Start-Sleep -Milliseconds 50
    }
    Assert-Safety (Test-Path -LiteralPath $readyPath) 'Mutex holder did not become ready.'
    Assert-Throws {
        Enter-DemoLifecycleLock -MutexName $mutexName -TimeoutMilliseconds 0 | Out-Null
    } 'A concurrent demo lifecycle command must fail closed.'
    [IO.File]::WriteAllText($releasePath, 'release')
    Wait-Job -Job $mutexJob -Timeout 8 | Out-Null
    Assert-Safety ($mutexJob.State -eq 'Completed') 'Mutex holder did not release cleanly.'
    Remove-Job -Job $mutexJob -Force
    $mutexJob = $null
    $lease = Enter-DemoLifecycleLock -MutexName $mutexName -TimeoutMilliseconds 1000
    Exit-DemoLifecycleLock $lease

    $state = [pscustomobject]@{
        schemaVersion = 1
        status = 'stopped'
        repositoryRoot = Get-DemoRepositoryRoot
        composeProject = Get-DemoComposeProject
        mode = 'demo-readonly'
        database = 'student_dormitory_readonly_demo'
        redis = [pscustomobject]@{database=12;prefix='dormitory:demo:readonly'}
        ports = [pscustomobject]@{frontend=5174;backend=8081;mysql=3307;redis=6380}
        processes = [pscustomobject]@{frontend=[pscustomobject]@{id=1};backend=[pscustomobject]@{id=2}}
    }
    $ownedInventory = [pscustomobject]@{
        containers = @([pscustomobject]@{
            id = 'owned-container'
            project = Get-DemoComposeProject
            workingDirectory = Get-DemoRepositoryRoot
            configFiles = @(Get-DemoComposeFile)
            service = 'mysql'
            oneoff = 'False'
            status = 'exited'
            volumeMounts = @([pscustomobject]@{
                name = ((Get-DemoComposeProject) + '_dormitory-mysql-data')
                destination = '/var/lib/mysql'
            })
        })
        volumes = @([pscustomobject]@{
            name = ((Get-DemoComposeProject) + '_dormitory-mysql-data')
            project = Get-DemoComposeProject
            volume = 'dormitory-mysql-data'
        })
    }
    Assert-DemoComposeOwnership -State $state -Inventory $ownedInventory
    Assert-Safety $true 'Current-checkout Compose resources must be accepted with matching state.'

    $foreignInventory = [pscustomobject]@{
        containers = @([pscustomobject]@{
            id = 'foreign-container'
            project = Get-DemoComposeProject
            workingDirectory = Join-Path $testRoot 'foreign-checkout'
            configFiles = @(Join-Path $testRoot 'foreign-checkout\compose.yml')
            service = 'mysql'
            oneoff = 'False'
            status = 'exited'
            volumeMounts = @()
        })
        volumes = @()
    }
    Assert-Throws {
        Assert-DemoComposeOwnership -State $state -Inventory $foreignInventory
    } 'A fixed-project container from another checkout must be rejected.'
    Assert-Throws {
        Assert-DemoComposeOwnership -State $null -Inventory $ownedInventory
    } 'Compose resources without state ownership must be rejected.'
    Assert-DemoComposeOwnership -State $null -Inventory ([pscustomobject]@{containers=@();volumes=@()})
    Assert-Safety $true 'An empty fixed Compose project may start without prior state.'

    foreach ($name in @('demo-start.ps1','demo-stop.ps1','demo-reset.ps1')) {
        $source = Get-Content -Raw -LiteralPath (Join-Path (Split-Path -Parent $PSScriptRoot) $name)
        Assert-Safety ($source -match 'Enter-DemoLifecycleLock') "$name must acquire the lifecycle mutex."
        Assert-Safety ($source -match 'Exit-DemoLifecycleLock') "$name must release the lifecycle mutex."
        Assert-Safety ($source -match 'Assert-DemoComposeOwnership') "$name must verify fixed-project ownership."
        Assert-Safety ($source.IndexOf('Initialize-DemoProtectedDirectory') -lt $source.IndexOf('Read-DemoState') `
            -and $source.IndexOf('Initialize-DemoProtectedDirectory') -gt 0) "$name must protect the runtime parent before reading state."
    }

    [ordered]@{status='PASS';assertions=$checks} | ConvertTo-Json
} finally {
    if ($null -ne $mutexJob) {
        Stop-Job -Job $mutexJob -ErrorAction SilentlyContinue
        Remove-Job -Job $mutexJob -Force -ErrorAction SilentlyContinue
    }
    $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
    if ($resolvedTestRoot.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolvedTestRoot).StartsWith('dormitory-demo-safety-')) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
