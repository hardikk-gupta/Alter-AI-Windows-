$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$installers = @(Get-ChildItem "build\compose\binaries\main\msi" -Filter "*.msi" -File)
if ($installers.Count -ne 1) { throw "Expected exactly one Alter MSI, found $($installers.Count)." }

$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase($installers[0].FullName, 0)
$view = $database.OpenView("SELECT FileName FROM File")
$view.Execute()
$files = @()
while ($record = $view.Fetch()) { $files += $record.StringData(1) }
$view.Close()

foreach ($required in @("llama-server.exe", "whisper-cli.exe")) {
    if (-not ($files | Where-Object { $_ -match "(^|\|)$([regex]::Escape($required))$" })) {
        throw "The installer does not contain $required."
    }
}
Write-Host "Verified $($installers[0].Name) contains both local inference engines."
