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
    if ($value -match '^(replace-with(?:-|$)|change-me(?:-|$)|your-|example$|password$)') {
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

function Set-DemoRestrictedAcl {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [switch]$Directory
    )
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        throw '演示密钥和状态 ACL 仅支持 Windows。'
    }
    $item = Get-Item -Force -LiteralPath $Path
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "拒绝在重解析点保存演示密钥或状态：$Path"
    }
    $currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $allowedSids = @($currentSid, 'S-1-5-18', 'S-1-5-32-544')
    $rights = if ($Directory) { '(OI)(CI)F' } else { 'F' }
    $grants = @($allowedSids | ForEach-Object { '*' + $_ + ':' + $rights })
    & icacls.exe $Path '/inheritance:r' '/grant:r' @grants '/c' '/q' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "无法收紧演示密钥或状态 ACL：$Path" }

    $acl = Get-Acl -LiteralPath $Path
    foreach ($rule in @($acl.Access)) {
        try {
            $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        } catch {
            $sid = [string]$rule.IdentityReference
        }
        if ($sid -notin $allowedSids) {
            & icacls.exe $Path '/remove:g' ('*' + $sid) '/remove:d' ('*' + $sid) '/c' '/q' | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "无法移除演示路径的额外 ACL：$Path" }
        }
    }
    $acl = Get-Acl -LiteralPath $Path
    if (-not $acl.AreAccessRulesProtected) { throw "演示路径仍继承外部 ACL：$Path" }
    $seenAllowed = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($rule in @($acl.Access)) {
        try {
            $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        } catch {
            throw "演示路径包含无法识别的 ACL：$Path"
        }
        if ($sid -notin $allowedSids -or
            $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
            (($rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -ne
                [Security.AccessControl.FileSystemRights]::FullControl)) {
            throw "演示路径 ACL 未收敛到受信任主体：$Path"
        }
        [void]$seenAllowed.Add($sid)
    }
    if (@($allowedSids | Where-Object { -not $seenAllowed.Contains($_) }).Count -gt 0) {
        throw "演示路径缺少受信任主体 ACL：$Path"
    }
}

function Initialize-DemoProtectedDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($Path))
    Set-DemoRestrictedAcl -Path $Path -Directory
}

function Write-DemoProtectedUtf8FileAtomic {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Content,
        [switch]$CreateNew
    )
    $fullPath = [IO.Path]::GetFullPath($Path)
    $directory = [IO.Path]::GetDirectoryName($fullPath)
    if ([string]::IsNullOrWhiteSpace($directory)) { throw "无法解析安全写入目录：$Path" }
    Initialize-DemoProtectedDirectory $directory
    if ($CreateNew -and (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        Set-DemoRestrictedAcl -Path $fullPath
        return $false
    }

    $temporaryPath = Join-Path $directory ('.' + [IO.Path]::GetFileName($fullPath) + '.' +
        [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes($Content)
        $stream = [IO.File]::Open(
            $temporaryPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try {
            $stream.Write($bytes, 0, $bytes.Length)
            $stream.Flush($true)
        } finally {
            $stream.Dispose()
        }
        Set-DemoRestrictedAcl -Path $temporaryPath
        if ($CreateNew) {
            try {
                [IO.File]::Move($temporaryPath, $fullPath)
            } catch [IO.IOException] {
                if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) { throw }
                return $false
            }
        } elseif (Test-Path -LiteralPath $fullPath -PathType Leaf) {
            [IO.File]::Replace($temporaryPath, $fullPath, [NullString]::Value)
        } else {
            [IO.File]::Move($temporaryPath, $fullPath)
        }
        Set-DemoRestrictedAcl -Path $fullPath
        return $true
    } finally {
        if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
            [IO.File]::Delete($temporaryPath)
        }
    }
}

function Enter-DemoLifecycleLock {
    param(
        [ValidatePattern('^(Local|Global)\\[A-Za-z0-9._-]+$')]
        [string]$MutexName = 'Global\dormitory-local-demo-lifecycle-v1',
        [ValidateRange(0, 30000)][int]$TimeoutMilliseconds = 0
    )
    $mutex = [Threading.Mutex]::new($false, $MutexName)
    $acquired = $false
    try {
        try {
            $acquired = $mutex.WaitOne($TimeoutMilliseconds)
        } catch [Threading.AbandonedMutexException] {
            $acquired = $true
        }
        if (-not $acquired) {
            throw '另一条 demo start/stop/reset 命令正在运行；拒绝并发修改演示资源。'
        }
        return [pscustomobject]@{mutex=$mutex;acquired=$true;name=$MutexName}
    } catch {
        $mutex.Dispose()
        throw
    }
}

function Exit-DemoLifecycleLock {
    param($Lock)
    if ($null -eq $Lock -or $null -eq $Lock.mutex) { return }
    try {
        if ([bool]$Lock.acquired) { $Lock.mutex.ReleaseMutex() }
    } finally {
        $Lock.acquired = $false
        $Lock.mutex.Dispose()
    }
}

function Get-DemoRuntimeKeys {
    param(
        [ValidateSet('demo-readonly','demo-approval')][string]$Mode,
        [string]$StorageRoot = (Join-Path $script:DemoRuntimeRoot 'keys')
    )
    Initialize-DemoProtectedDirectory $StorageRoot
    $keyPath = Join-Path $StorageRoot ($Mode + '.json')
    if (-not (Test-Path -LiteralPath $keyPath)) {
        $protectedKeys = [ordered]@{}
        foreach ($name in @('audit','tokenization','stepUp','rollout')) {
            $secure = ConvertTo-SecureString (New-DemoEphemeralKey) -AsPlainText -Force
            try { $protectedKeys[$name] = ConvertFrom-SecureString $secure } finally { $secure.Dispose() }
        }
        $payload = [ordered]@{schemaVersion=1;mode=$Mode;keys=$protectedKeys} | ConvertTo-Json -Depth 4
        [void](Write-DemoProtectedUtf8FileAtomic -Path $keyPath -Content $payload -CreateNew)
    }
    Set-DemoRestrictedAcl -Path $keyPath
    $saved = Get-Content -Raw -LiteralPath $keyPath | ConvertFrom-Json
    if ($saved.schemaVersion -ne 1 -or $saved.mode -ne $Mode) { throw '演示 keyring 版本或模式不符。' }
    $keys = @{}
    foreach ($name in @('audit','tokenization','stepUp','rollout')) {
        $secure = ConvertTo-SecureString ([string]$saved.keys.$name)
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try { $keys[$name] = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secure.Dispose() }
        if ([Convert]::FromBase64String($keys[$name]).Length -lt 32) { throw '演示 keyring 密钥长度不足。' }
    }
    return $keys
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

function ConvertTo-DemoComparablePath {
    param([Parameter(Mandatory = $true)][string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path) -or -not [IO.Path]::IsPathRooted($Path)) {
        throw "演示资源路径必须是绝对路径：$Path"
    }
    return [IO.Path]::GetFullPath($Path).TrimEnd([char[]]@('\', '/'))
}

function Get-DemoLabelValue {
    param($Labels, [Parameter(Mandatory = $true)][string]$Name)
    if ($null -eq $Labels) { return '' }
    if ($Labels -is [System.Collections.IDictionary]) {
        if (-not $Labels.Contains($Name)) { return '' }
        return [string]$Labels[$Name]
    }
    $property = $Labels.PSObject.Properties[$Name]
    if ($null -eq $property) { return '' }
    return [string]$property.Value
}

function Invoke-DemoDockerRead {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $output = @(& docker @Arguments)
    if ($LASTEXITCODE -ne 0) {
        throw "无法读取演示 Docker 资源，退出码 $LASTEXITCODE。"
    }
    return $output
}

function Get-DemoComposeResourceInventory {
    $project = $script:DemoComposeProject
    $containerIds = @(Invoke-DemoDockerRead @(
        'container', 'ls', '--all', '--quiet', '--filter', "label=com.docker.compose.project=$project"))
    $containers = @($containerIds | ForEach-Object {
        $id = [string]$_
        $labelsJson = [string](@(Invoke-DemoDockerRead @(
            'container', 'inspect', '--format', '{{json .Config.Labels}}', $id)) | Select-Object -Last 1)
        $status = [string](@(Invoke-DemoDockerRead @(
            'container', 'inspect', '--format', '{{.State.Status}}', $id)) | Select-Object -Last 1)
        $mountsJson = [string](@(Invoke-DemoDockerRead @(
            'container', 'inspect', '--format', '{{json .Mounts}}', $id)) | Select-Object -Last 1)
        $labels = if ([string]::IsNullOrWhiteSpace($labelsJson)) { $null } else { $labelsJson | ConvertFrom-Json }
        $mounts = if ([string]::IsNullOrWhiteSpace($mountsJson)) { @() } else { @($mountsJson | ConvertFrom-Json) }
        $configFiles = @(([string](Get-DemoLabelValue $labels 'com.docker.compose.project.config_files') -split ',') |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_.Trim() })
        [pscustomobject]@{
            id = $id
            project = Get-DemoLabelValue $labels 'com.docker.compose.project'
            workingDirectory = Get-DemoLabelValue $labels 'com.docker.compose.project.working_dir'
            configFiles = $configFiles
            service = Get-DemoLabelValue $labels 'com.docker.compose.service'
            oneoff = Get-DemoLabelValue $labels 'com.docker.compose.oneoff'
            status = $status.Trim()
            volumeMounts = @($mounts | Where-Object { $_.Type -eq 'volume' } | ForEach-Object {
                [pscustomobject]@{name=[string]$_.Name;destination=[string]$_.Destination}
            })
        }
    })

    $expectedVolumeNames = @(
        "${project}_dormitory-mysql-data",
        "${project}_dormitory-redis-data"
    )
    $projectVolumeNames = @(Invoke-DemoDockerRead @(
        'volume', 'ls', '--quiet', '--filter', "label=com.docker.compose.project=$project"))
    $allVolumeNames = @(Invoke-DemoDockerRead @('volume', 'ls', '--quiet'))
    $volumeNames = @(($projectVolumeNames + @($allVolumeNames | Where-Object { $_ -in $expectedVolumeNames })) |
        Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Sort-Object -Unique)
    $volumes = @($volumeNames | ForEach-Object {
        $name = [string]$_
        $labelsJson = [string](@(Invoke-DemoDockerRead @(
            'volume', 'inspect', '--format', '{{json .Labels}}', $name)) | Select-Object -Last 1)
        $labels = if ([string]::IsNullOrWhiteSpace($labelsJson)) { $null } else { $labelsJson | ConvertFrom-Json }
        [pscustomobject]@{
            name = $name
            project = Get-DemoLabelValue $labels 'com.docker.compose.project'
            volume = Get-DemoLabelValue $labels 'com.docker.compose.volume'
        }
    })
    return [pscustomobject]@{containers=$containers;volumes=$volumes}
}

function Assert-DemoStateIdentity {
    param([Parameter(Mandatory = $true)]$State)
    if ([int]$State.schemaVersion -ne 1 -or
        (ConvertTo-DemoComparablePath ([string]$State.repositoryRoot)) -ne
            (ConvertTo-DemoComparablePath $script:DemoRepositoryRoot) -or
        [string]$State.composeProject -ne $script:DemoComposeProject) {
        throw '状态文件不属于当前演示项目，拒绝接管。'
    }
    if ([string]$State.status -notin @('running','stale','stopped','failed','reset')) {
        throw "状态文件包含未知生命周期状态：$($State.status)"
    }
    $expectedProfile = Get-DemoProfile ([string]$State.mode)
    if ([string]$State.database -ne $expectedProfile.database -or
        [int]$State.redis.database -ne $expectedProfile.redisDatabase -or
        [string]$State.redis.prefix -ne $expectedProfile.redisPrefix) {
        throw '状态文件数据库或 Redis 目标不符合固定演示模式。'
    }
    foreach ($name in @('frontend', 'backend', 'mysql', 'redis')) {
        $port = [int]$State.ports.$name
        Assert-DemoPort -Port $port -Name $name
        if ($port -in @(3306,6379)) { throw '拒绝接管普通数据库服务端口。' }
    }
}

function Assert-DemoComposeOwnership {
    param(
        [AllowNull()]$State,
        $Inventory = $null
    )
    if ($null -ne $State) { Assert-DemoStateIdentity $State }
    if ($null -eq $Inventory) { $Inventory = Get-DemoComposeResourceInventory }
    $containers = @($Inventory.containers)
    $volumes = @($Inventory.volumes)
    if ($null -eq $State -and ($containers.Count -gt 0 -or $volumes.Count -gt 0)) {
        throw '发现无状态文件归属的 dormitory-local-demo Docker 资源；拒绝接管。'
    }
    if ($containers.Count -eq 0 -and $volumes.Count -eq 0) { return }

    $expectedRoot = ConvertTo-DemoComparablePath $script:DemoRepositoryRoot
    $expectedConfig = ConvertTo-DemoComparablePath $script:DemoComposeFile
    $allowedServices = @('mysql','redis')
    $byService = @{}
    foreach ($container in $containers) {
        $configFiles = @($container.configFiles)
        $workingDirectory = ConvertTo-DemoComparablePath ([string]$container.workingDirectory)
        $config = if ($configFiles.Count -eq 1) {
            ConvertTo-DemoComparablePath ([string]$configFiles[0])
        } else { '' }
        $service = [string]$container.service
        if ([string]$container.project -ne $script:DemoComposeProject -or
            $service -notin $allowedServices -or
            $byService.ContainsKey($service) -or
            [string]$container.oneoff -ne 'False' -or
            $workingDirectory -ne $expectedRoot -or $config -ne $expectedConfig) {
            throw "固定 Compose project 包含未知或其他 checkout 的容器：$($container.id)"
        }
        $byService[$service] = $container
    }

    $volumeContracts = @{
        'dormitory-mysql-data' = [pscustomobject]@{
            name = "$($script:DemoComposeProject)_dormitory-mysql-data"; service = 'mysql'; destination = '/var/lib/mysql'
        }
        'dormitory-redis-data' = [pscustomobject]@{
            name = "$($script:DemoComposeProject)_dormitory-redis-data"; service = 'redis'; destination = '/data'
        }
    }
    foreach ($volume in $volumes) {
        $logicalName = [string]$volume.volume
        $contract = $volumeContracts[$logicalName]
        if ([string]$volume.project -ne $script:DemoComposeProject -or $null -eq $contract -or
            [string]$volume.name -ne $contract.name -or -not $byService.ContainsKey($contract.service)) {
            throw "固定 Compose project 包含归属不可证明的卷：$($volume.name)"
        }
        $matchingMounts = @($byService[$contract.service].volumeMounts | Where-Object {
            [string]$_.name -eq $contract.name -and [string]$_.destination -eq $contract.destination
        })
        if ($matchingMounts.Count -ne 1) {
            throw "Compose 卷挂载与固定演示合同不符：$($volume.name)"
        }
    }
    foreach ($service in $byService.Keys) {
        $expectedLogical = if ($service -eq 'mysql') { 'dormitory-mysql-data' } else { 'dormitory-redis-data' }
        if (@($volumes | Where-Object { [string]$_.volume -eq $expectedLogical }).Count -ne 1) {
            throw "Compose $service 容器缺少唯一的固定命名卷。"
        }
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

function Enable-DemoStandardToolCatalog {
    param([string]$Backend, [string]$Frontend, [string]$Username, [string]$Password)
    $headers = @{ Origin = $Frontend; Referer = "$Frontend/" }
    $loginBody = @{ username = $Username; password = $Password } | ConvertTo-Json -Compress
    Invoke-RestMethod -Uri "$Backend/api/auth/login" -Method Post -Headers $headers `
        -ContentType 'application/json' -Body $loginBody -SessionVariable catalogSession -TimeoutSec 15 | Out-Null
    $catalogs = @( (Invoke-RestMethod -Uri "$Backend/api/ai/tool-catalogs" -WebSession $catalogSession -TimeoutSec 15).data )
    $target = $catalogs | Where-Object { $_.version -eq 'v2' -and @($_.toolIds).Count -eq 7 } | Select-Object -First 1
    if ($null -eq $target) { throw '当前标准 v2 ToolCatalog 不可用。' }
    if ([bool]$target.active) { return }
    $active = $catalogs | Where-Object { $_.active } | Select-Object -First 1
    $expectedActiveId = if ($null -eq $active) { '' } else { [string]$active.id }
    $csrf = Invoke-RestMethod -Uri "$Backend/api/security/csrf" -WebSession $catalogSession -TimeoutSec 15
    $headers['X-CSRF-Token'] = [string]$csrf.data.token
    $canonical = [ordered]@{ catalogId = [string]$target.id; expectedActiveId = $expectedActiveId;
        manifestHash = [string]$target.manifestHash; version = [string]$target.version } | ConvertTo-Json -Compress
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { $requestHash = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical)))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
    $proofBody = @{ password = $Password; actionCode = 'CONFIG_ACTIVATE'; resourcePublicId = [string]$target.id;
        requestHash = $requestHash } | ConvertTo-Json -Compress
    $proof = Invoke-RestMethod -Uri "$Backend/api/security/step-up" -Method Post -Headers $headers `
        -WebSession $catalogSession -ContentType 'application/json' -Body $proofBody -TimeoutSec 15
    $headers['X-Step-Up-Proof'] = [string]$proof.data.proof
    $headers['Idempotency-Key'] = [Guid]::NewGuid().ToString()
    $body = @{ version = [string]$target.version; manifestHash = [string]$target.manifestHash;
        expectedActiveId = $expectedActiveId } | ConvertTo-Json -Compress
    Invoke-RestMethod -Uri "$Backend/api/ai/tool-catalogs/$($target.id)/activate" -Method Post -Headers $headers `
        -WebSession $catalogSession -ContentType 'application/json' -Body $body -TimeoutSec 15 | Out-Null
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
    # PowerShell 7 会将 JSON ISO 时间自动解析为 DateTime；转成字符串会丢失 UTC Kind。
    $expected = if ($Entry.startedAtUtc -is [DateTime]) {
        $Entry.startedAtUtc.ToUniversalTime()
    } else {
        [DateTimeOffset]::Parse([string]$Entry.startedAtUtc).UtcDateTime
    }
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
    param([string]$Path = $script:DemoStatePath)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "没有演示状态文件：$Path"
    }
    Set-DemoRestrictedAcl -Path $Path
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Test-DemoStateStale {
    param(
        $State,
        [scriptblock]$ProcessProbe = { param($entry) $null -ne (Get-DemoOwnedProcess $entry) },
        [scriptblock]$PortProbe = { param($port) Test-DemoTcpPort -Port $port }
    )
    Assert-DemoStateIdentity $State
    foreach ($entry in @($State.processes.backend, $State.processes.frontend)) {
        if ($null -eq $entry -or $null -eq $entry.id) { throw '状态文件缺少进程归属信息。' }
        if (& $ProcessProbe $entry) { return $false }
    }
    foreach ($name in @('frontend', 'backend', 'mysql', 'redis')) {
        $port = [int]$State.ports.$name
        if (& $PortProbe $port) { return $false }
    }
    return $true
}

function Write-DemoState {
    param($State, [string]$Path = $script:DemoStatePath)
    $payload = $State | ConvertTo-Json -Depth 12
    [void](Write-DemoProtectedUtf8FileAtomic -Path $Path -Content $payload)
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
