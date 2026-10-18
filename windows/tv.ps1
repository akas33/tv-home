<#
  Windows version of the tv-vibehack helper scripts. Run from PowerShell, from the repo folder:

    .\windows\tv.ps1 setup                      one time: Python environment for talking to the TV
    .\windows\tv.ps1 probe                      check the connection (accept the prompt on the TV)
    .\windows\tv.ps1 backup                     save package list and settings into .\backup
    .\windows\tv.ps1 packages                   list everything installed on the TV
    .\windows\tv.ps1 disable <list.txt>         switch off the apps in a debloat list
    .\windows\tv.ps1 restore <list.txt>         undo it and bring the stock launcher back
    .\windows\tv.ps1 install                    build and install the launcher + screensaver
    .\windows\tv.ps1 screensaver [preview]      select the Ambient screensaver (preview starts it now)
    .\windows\tv.ps1 brief "line 1" "line 2"    optional: send your own lines to the screensaver

  The TV address is read from the TV_HOST environment variable, default 192.168.29.8.
#>
param(
    [Parameter(Position = 0)][string]$Cmd = "help",
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)][string[]]$Rest
)
$ErrorActionPreference = "Stop"
$Repo = Split-Path -Parent $PSScriptRoot
Set-Location $Repo
if (-not $env:TV_HOST) { $env:TV_HOST = "192.168.29.8" }
$Py = Join-Path $Repo "tools\venv\Scripts\python.exe"
$StockLauncher = if ($env:STOCK_LAUNCHER) { $env:STOCK_LAUNCHER } else { "com.google.android.tvlauncher" }

function Need-Venv { if (-not (Test-Path $Py)) { throw "Run first:  .\windows\tv.ps1 setup" } }

# Run a list of shell commands on the TV, one per call, over one connection.
function Run-Batch([string[]]$Lines) {
    Need-Venv
    ($Lines -join "`n") | & $Py -I tools\tv.py --batch
}

function Read-Packages([string]$List) {
    Get-Content $List | ForEach-Object { ($_ -replace "#.*$", "").Trim() } | Where-Object { $_ -ne "" } |
        ForEach-Object { ($_ -split "\s+")[0] }
}

switch ($Cmd) {
    "setup" {
        $pyCmd = if (Get-Command py -ErrorAction SilentlyContinue) { "py" } else { "python" }
        & $pyCmd -m venv tools\venv
        & $Py -m pip install -r tools\requirements.txt
        Write-Host "Setup done. Now: .\windows\tv.ps1 probe"
    }
    "probe" { Need-Venv; & $Py -I tools\tv.py --probe }
    "packages" { Need-Venv; & $Py -I tools\tv.py "pm list packages" }
    "backup" {
        $dir = Join-Path $Repo ("backup\" + (Get-Date -Format "yyyyMMdd-HHmmss"))
        New-Item -ItemType Directory -Force $dir | Out-Null
        Need-Venv
        & $Py -I tools\tv.py "pm list packages" | Out-File "$dir\packages.txt" -Encoding utf8
        & $Py -I tools\tv.py "pm list packages -d" | Out-File "$dir\disabled.txt" -Encoding utf8
        & $Py -I tools\tv.py "getprop" | Out-File "$dir\getprop.txt" -Encoding utf8
        foreach ($ns in "global", "secure", "system") {
            & $Py -I tools\tv.py "settings list $ns" | Out-File "$dir\settings-$ns.txt" -Encoding utf8
        }
        Write-Host "Backup saved in $dir"
    }
    "disable" {
        if (-not $Rest) { throw "usage: tv.ps1 disable <debloat list>" }
        $lines = @(Read-Packages $Rest[0] | ForEach-Object { "pm disable-user --user 0 $_" }) + "pm list packages -d"
        Run-Batch $lines
    }
    "restore" {
        if (-not $Rest) { throw "usage: tv.ps1 restore <debloat list> [stock launcher package]" }
        $stock = if ($Rest.Count -gt 1) { $Rest[1] } else { $StockLauncher }
        $lines = @(Read-Packages $Rest[0] | ForEach-Object { "pm enable $_" }) + @(
            "pm enable $stock",
            "cmd package set-home-activity --user 0 $stock",
            "settings put global window_animation_scale 1.0",
            "settings put global transition_animation_scale 1.0",
            "settings put global animator_duration_scale 1.0",
            "pm list packages -d")
        Run-Batch $lines
    }
    "install" {
        Need-Venv
        Set-Location (Join-Path $Repo "launcher")
        if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
        if (-not $env:JAVA_HOME) {
            $jbr = "C:\Program Files\Android\Android Studio\jbr"
            if (Test-Path $jbr) { $env:JAVA_HOME = $jbr }
        }
        if (-not (Test-Path $env:ANDROID_HOME)) { throw "Android SDK not found at $env:ANDROID_HOME. Install Android Studio first (see windows\README.md)." }
        if (-not $env:JAVA_HOME) { throw "JAVA_HOME not set. Install Android Studio or JDK 17 (see windows\README.md)." }
        $sdk = $env:ANDROID_HOME -replace "\\", "/"
        "sdk.dir=$sdk" | Out-File local.properties -Encoding ascii
        & .\gradlew.bat assembleDebug -q --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Build failed (see messages above)." }
        $apk = "app\build\outputs\apk\debug\app-debug.apk"
        & $Py -I ..\tools\b64push.py $apk /data/local/tmp/tvhome.apk
        if ($LASTEXITCODE -ne 0) { throw "Upload to the TV failed (md5 mismatch)." }
        $cmds = @(
            "pm install -r /data/local/tmp/tvhome.apk",
            "rm -f /data/local/tmp/tvhome.apk",
            "pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION",
            "cmd package set-home-activity --user 0 com.gathin.tvhome",
            "pm disable-user --user 0 $StockLauncher")
        ($cmds -join "`n") | & $Py -I ..\tools\tv.py --batch
        Write-Host "Installed. Press Home on the remote to see it."
    }
    "screensaver" {
        $lines = @(
            "settings put secure screensaver_components com.gathin.tvhome/com.gathin.tvhome.AmbientDream",
            "settings put secure screensaver_enabled 1",
            "settings get secure screensaver_components")
        if ($Rest -and $Rest[0] -eq "preview") { $lines += "am start -n com.android.systemui/.Somnambulator" }
        Run-Batch $lines
        Write-Host "Set the start time on the TV: Settings > Device Preferences > Screen saver > When to start."
    }
    "brief" {
        Need-Venv
        & $Py -I tools\push-brief.py @Rest
    }
    default { Get-Help $PSCommandPath | Out-String | Write-Host }
}
