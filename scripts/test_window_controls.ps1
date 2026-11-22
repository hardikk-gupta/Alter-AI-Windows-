param([Parameter(Mandatory)][string]$Helper)
$ErrorActionPreference = 'Stop'
$title = "AlterWindowTest-$([guid]::NewGuid())"
$fixtureExe = Join-Path (Split-Path $Helper) 'alter-test-window.exe'
$fixture = Start-Process $fixtureExe -ArgumentList $title -PassThru
try {
    $target = $null
    for ($attempt = 0; $attempt -lt 40 -and -not $target; $attempt++) {
        $target = & $Helper windows $PID | Where-Object { $_.EndsWith("`t$title") } | Select-Object -First 1
        if (-not $target) { Start-Sleep -Milliseconds 250 }
    }
    if (-not $target) {
        $fixture.Refresh()
        throw "Could not enumerate fixture PID $($fixture.Id), exited=$($fixture.HasExited), handle=$($fixture.MainWindowHandle)."
    }
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
