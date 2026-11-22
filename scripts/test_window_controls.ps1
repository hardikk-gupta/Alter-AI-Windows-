param([Parameter(Mandatory)][string]$Helper)
$ErrorActionPreference = 'Stop'
$title = 'Alter window test ' + [guid]::NewGuid()
$script = Join-Path $env:RUNNER_TEMP 'alter-window-fixture.ps1'
@'
param([string]$Title)
Add-Type -AssemblyName System.Windows.Forms
$form = New-Object System.Windows.Forms.Form
$form.Text = $Title
$form.ShowInTaskbar = $true
[System.Windows.Forms.Application]::Run($form)
'@ | Set-Content $script
$fixture = Start-Process powershell.exe -ArgumentList @('-NoProfile','-STA','-File',"`"$script`"","`"$title`"") -WindowStyle Hidden -PassThru
try {
    $target = $null
    for ($attempt = 0; $attempt -lt 40 -and -not $target; $attempt++) {
        $target = & $Helper windows $PID | Where-Object { $_.EndsWith("`t$title") } | Select-Object -First 1
        if (-not $target) { Start-Sleep -Milliseconds 250 }
    }
    if (-not $target) { throw 'Could not enumerate the test window.' }
    $handle, $targetPid, $caption = $target -split "`t",3
    foreach ($operation in @('maximize','restore')) {
        $caption | & $Helper $operation $handle $targetPid
        if ($LASTEXITCODE) { throw "Could not $operation the test window." }
    }
    'Different title' | & $Helper maximize $handle $targetPid
    if ($LASTEXITCODE -eq 0) { throw 'Stale window selection was accepted.' }
    $global:LASTEXITCODE = 0
} finally {
    if (-not $fixture.HasExited) { $fixture.Kill(); $fixture.WaitForExit() }
}
