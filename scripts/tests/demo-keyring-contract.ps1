[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'demo-common.ps1')
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('dormitory-keyring-test-'+[guid]::NewGuid().ToString('N'))
$first=Get-DemoRuntimeKeys -Mode 'demo-readonly' -StorageRoot $testRoot
$second=Get-DemoRuntimeKeys -Mode 'demo-readonly' -StorageRoot $testRoot
$approval=Get-DemoRuntimeKeys -Mode 'demo-approval' -StorageRoot $testRoot
foreach($name in @('audit','tokenization','stepUp','rollout')) {
    if($first[$name] -ne $second[$name]) { throw 'Demo keys changed across restart.' }
    if($first[$name] -eq $approval[$name]) { throw 'Demo modes must not share keys.' }
    if([Convert]::FromBase64String($first[$name]).Length -lt 32) { throw 'Key is too short.' }
    $stored=Get-Content -Raw -LiteralPath (Join-Path $testRoot 'demo-readonly.json')
    if($stored.Contains($first[$name])) { throw 'Key file contains plaintext.' }
}
[ordered]@{status='PASS';assertions=16} | ConvertTo-Json
