$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$msi = New-Object -ComObject WindowsInstaller.Installer

function Read-Product($file) {
    $db = $msi.OpenDatabase($file.FullName, 0)
    $properties = @{}
    foreach ($name in @('ProductCode','ProductVersion','ProductName')) {
        $view = $db.OpenView("SELECT Value FROM Property WHERE Property='$name'")
        [void]$view.Execute()
        $properties[$name] = $view.Fetch().StringData(1)
        [void]$view.Close()
    }
    if ($properties.ProductName -ne 'Alter') { throw 'Unexpected MSI product name.' }
    return [pscustomobject]$properties
}

function Install-Alter($file, $info) {
    $log = Join-Path $env:RUNNER_TEMP "alter-msi-$($info.ProductVersion).log"
    $process = Start-Process msiexec.exe -Wait -PassThru -ArgumentList @('/i',"`"$($file.FullName)`"",'/qn','/norestart','/l*v',"`"$log`"")
    if ($process.ExitCode -notin @(0,3010)) {
        Get-Content $log -Tail 40
        throw "MSI install failed with $($process.ExitCode)."
    }
    if ($msi.ProductState($info.ProductCode) -ne 5) { throw 'Alter was not registered as installed.' }
    $paths = @([Environment]::GetFolderPath('Programs'), [Environment]::GetFolderPath('CommonPrograms'))
    $link = Get-ChildItem -LiteralPath $paths -Recurse -File -Filter Alter.lnk | Select-Object -First 1
    if (-not $link) { throw 'Start menu entry was not installed.' }
    $shell = New-Object -ComObject WScript.Shell
    $target = $shell.CreateShortcut($link.FullName).TargetPath
    if (-not $target -or -not $target.EndsWith('Alter.exe', [StringComparison]::OrdinalIgnoreCase)) {
        $record = $msi.ShortcutTarget($link.FullName)
        $target = $msi.ComponentPath($record.StringData(1), $record.StringData(3))
    }
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) { throw 'Start menu shortcut target is missing.' }
    Write-Host "Installed Alter $($info.ProductVersion) with a Start menu shortcut."
    return $target
}

function Open-Screen($target, $screen) {
    $app = Start-Process $target -ArgumentList "--preview=$screen",'--screenshot' -WorkingDirectory $pwd -PassThru
    if (-not $app.WaitForExit(90000)) { $app.Kill(); throw "Installed Alter did not finish rendering $screen." }
    if ($app.ExitCode -ne 0 -or -not (Test-Path "build/screenshots/$screen.png")) { throw "Installed Alter did not render $screen." }
}

$current = Get-ChildItem build/compose/binaries/main/msi/*.msi -File | Select-Object -First 1
$currentInfo = Read-Product $current
$previousInfo = $null
$run = gh api "repos/$env:GITHUB_REPOSITORY/actions/workflows/windows.yml/runs?event=push&branch=main&status=success&per_page=1" --jq '.workflow_runs[0].id // empty'
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect prior main installer.' }
if ($run) {
    $artifact = gh api "repos/$env:GITHUB_REPOSITORY/actions/runs/$run/artifacts" --jq '.artifacts[] | select(.name=="Alter-Windows-Installer" and .expired==false) | .id'
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect prior installer artifact.' }
    if ($artifact) {
        $cache = Join-Path $env:RUNNER_TEMP 'alter-previous-msi'
        gh run download $run --repo $env:GITHUB_REPOSITORY --name Alter-Windows-Installer --dir $cache
        if ($LASTEXITCODE -ne 0) { throw 'Could not download prior installer.' }
        $previous = Get-ChildItem "$cache/*.msi" -File | Select-Object -First 1
        $info = Read-Product $previous
        if ([version]$info.ProductVersion -lt [version]$currentInfo.ProductVersion) {
            $previousInfo = $info
            $oldApp = Install-Alter $previous $info
            Open-Screen $oldApp 'home'
            python -c "import sqlite3,os; db=sqlite3.connect(os.path.join(os.environ['LOCALAPPDATA'],'Alter','alter-desktop.db')); db.execute('INSERT INTO cards(type,rawInput,processedContent,timestamp) VALUES(?,?,?,?)', ('note','Alter installer test','Synthetic upgrade fixture',0)); db.commit()"
            if ($LASTEXITCODE) { throw 'Could not seed the prior workspace.' }
        }
    }
}
$installedApp = Install-Alter $current $currentInfo
Open-Screen $installedApp 'plugins'
if ($previousInfo) {
    if ($msi.ProductState($previousInfo.ProductCode) -eq 5) { throw 'The older product is still installed.' }
    python -c "import sqlite3,os; db=sqlite3.connect(os.path.join(os.environ['LOCALAPPDATA'],'Alter','alter-desktop.db')); assert db.execute('SELECT count(*) FROM cards WHERE processedContent=? AND isDeleted=0',('Synthetic upgrade fixture',)).fetchone()[0]==1"
    if ($LASTEXITCODE) { throw 'The previous note did not survive the upgrade.' }
    Write-Host "Verified upgrade from $($previousInfo.ProductVersion) to $($currentInfo.ProductVersion) with existing notes."
} else { Write-Host 'Verified a fresh installation. No older main artifact was available for upgrade testing.' }
