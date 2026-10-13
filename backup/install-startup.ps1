$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$ScriptPath = Join-Path $PSScriptRoot "sync_auravision.py"

Write-Host "Installing the free AuraVision laptop backup task..."
py -m pip install --user -r (Join-Path $PSScriptRoot "requirements.txt")
py $ScriptPath --configure

$PythonExe = (py -c "import sys; print(sys.executable)").Trim()
if (-not (Test-Path $PythonExe)) {
    throw "Could not find the Python executable. Install Python 3.10+ and try again."
}

$Action = New-ScheduledTaskAction -Execute $PythonExe -Argument ('"' + $ScriptPath + '"') -WorkingDirectory $RepoRoot
$Trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$Settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName "AuraVisionAI-Laptop-Backup" -Action $Action -Trigger $Trigger -Settings $Settings -Description "Sync AuraVision AI metadata and generated media to this laptop every 15 minutes." -Force | Out-Null

Write-Host ""
Write-Host "Installed. The task starts when you sign in to Windows."
Write-Host "The local archive is under: $env:LOCALAPPDATA\AuraVisionAI\backup"
Write-Host "Before sync can succeed, set BACKUP_API_TOKEN in Render and deploy the backup-enabled branch."
Write-Host "To test manually: py .\backup\sync_auravision.py --once"
