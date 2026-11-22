param(
    [Parameter(Mandatory)] [string]$JavaHome,
    [Parameter(Mandatory)] [ValidateSet('msi','exe')] [string]$Format,
    [Parameter(Mandatory)] [string]$Version,
    [Parameter(Mandatory)] [string]$AppImage,
    [Parameter(Mandatory)] [string]$OutputDir,
    [Parameter(Mandatory)] [string]$UpgradeId,
    [string]$WixDir
)
$ErrorActionPreference = 'Stop'
if ($WixDir) { $env:PATH = "$WixDir;$WixDir\bin;$env:PATH" }
$resources = New-Item -ItemType Directory -Force "build/windows-installer/$Format"
$dll = (Resolve-Path 'build/windows-helper/Release/alter-upgrade.dll').Path
# Use the template distributed with the same JDK that will compile the installer.
Push-Location $resources
try {
    & "$JavaHome/bin/jmod.exe" extract --dir jpackage "$JavaHome/jmods/jdk.jpackage.jmod"
    if ($LASTEXITCODE) { throw 'Could not read the Windows JDK installer template.' }
    $template = Get-Content 'jpackage/classes/jdk/jpackage/internal/resources/main.wxs' -Raw
    $sequence = '<InstallExecuteSequence>'
    if (-not $template.Contains($sequence) -or -not $template.Contains('<RemoveExistingProducts ')) {
        throw 'Unrecognized JDK installer sequence.'
    }
    $declarations = @"
<Binary Id="AlterUpgradeDll" SourceFile="$([Security.SecurityElement]::Escape($dll))" />
<CustomAction Id="AlterProtectWorkspace" BinaryKey="AlterUpgradeDll" DllEntry="ProtectLegacyWorkspace" Return="check" />
<InstallExecuteSequence>
      <Custom Action="AlterProtectWorkspace" Before="RemoveExistingProducts">NOT Installed AND JP_UPGRADABLE_FOUND</Custom>
"@
    [IO.File]::WriteAllText((Join-Path $resources.FullName 'main.wxs'), $template.Replace($sequence, $declarations))
} finally { Pop-Location }
& "$JavaHome/bin/jpackage.exe" --type $Format --name Alter --app-version $Version --app-image $AppImage --dest $OutputDir --resource-dir $resources.FullName --win-per-user-install --install-dir 'Programs\Alter' --win-menu --win-menu-group Alter --win-shortcut --win-upgrade-uuid $UpgradeId --icon packaging/alter.ico
if ($LASTEXITCODE) { throw 'Windows installer packaging failed.' }
