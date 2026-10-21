<#
  Windows version of the tv-vibehack helper scripts. Run from PowerShell, from the repo folder:

    .\windows\tv.ps1 prereqs-lite               one time, LIGHT (~460 MB): Java + Android build tools
    .\windows\tv.ps1 prereqs                    one time, FULL (~1.5 GB): adds the Android Studio editor
    .\windows\tv.ps1 setup                      one time: Python environment for talking to the TV
    .\windows\tv.ps1 probe                      check the connection (accept the prompt on the TV)
    .\windows\tv.ps1 backup                     save package list and settings into .\backup
    .\windows\tv.ps1 packages                   list everything installed on the TV
    .\windows\tv.ps1 ads-only <list.txt>        ads gone, stock home screen kept, nothing to build
    .\windows\tv.ps1 disable <list.txt>         switch off the apps in a debloat list
    .\windows\tv.ps1 restore <list.txt>         undo it and bring the stock launcher back
    .\windows\tv.ps1 install                    build and install the launcher + screensaver
    .\windows\tv.ps1 screensaver [preview]      select the Ambient screensaver (preview starts it now)
    .\windows\tv.ps1 shot [name]                screenshot; with a name it goes to docs\<name>.png
    .\windows\tv.ps1 wallpaper <folder|file>    pictures behind the home screen, one a day (no path: remove)
    .\windows\tv.ps1 lockdown                   turn TV debugging back off when you are finished
    .\windows\tv.ps1 chatid <bot token>         list the Telegram chat IDs allowed on the TV board
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
    "prereqs-lite" {
        # Same result as "prereqs" but ~460 MB instead of ~1.5 GB: Java plus the Android command
        # line tools, skipping the Android Studio editor entirely. Uses BITS, which resumes by
        # itself after a dropped connection, and checks the download against Google's SHA-256.
        $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
        $zipUrl = "https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip"
        $zipSha = "90AE805D20434428BFFCB699C290860F19BB5F66A67E6B330067E3DE801FB04A"
        $zip = Join-Path $env:TEMP "commandlinetools-win.zip"

        Write-Host "1/4 Java 17 (about 180 MB)..."
        winget install --id Microsoft.OpenJDK.17 -e --accept-package-agreements --accept-source-agreements

        Write-Host "2/4 Android command line tools (156 MB, resumes if the connection drops)..."
        if ((Test-Path $zip) -and (Get-FileHash $zip -Algorithm SHA256).Hash -eq $zipSha) {
            Write-Host "    already downloaded and verified, skipping"
        } else {
            try {
                Import-Module BitsTransfer -ErrorAction Stop
                Start-BitsTransfer -Source $zipUrl -Destination $zip -Description "Android tools"
            } catch {
                Write-Host "    BITS unavailable, falling back to a plain download..."
                Invoke-WebRequest -Uri $zipUrl -OutFile $zip -UseBasicParsing
            }
            $got = (Get-FileHash $zip -Algorithm SHA256).Hash
            if ($got -ne $zipSha) {
                Remove-Item $zip -Force -ErrorAction SilentlyContinue
                throw "Download is corrupt (checksum mismatch). Run this command again - it resumes."
            }
            Write-Host "    checksum verified"
        }

        Write-Host "3/4 Unpacking..."
        $stage = Join-Path $env:TEMP "cmdline-stage"
        Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
        Expand-Archive -Path $zip -DestinationPath $stage -Force
        $dest = Join-Path $sdk "cmdline-tools"
        New-Item -ItemType Directory -Force -Path $dest | Out-Null
        Remove-Item (Join-Path $dest "latest") -Recurse -Force -ErrorAction SilentlyContinue
        Move-Item (Join-Path $stage "cmdline-tools") (Join-Path $dest "latest")
        Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue

        Write-Host "4/4 SDK pieces for this project (about 125 MB)..."
        if (-not $env:JAVA_HOME) {
            $jdk = Get-ChildItem "C:\Program Files\Microsoft" -Filter "jdk-17*" -Directory -ErrorAction SilentlyContinue |
                   Sort-Object Name -Descending | Select-Object -First 1
            if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
        }
        if (-not $env:JAVA_HOME) { throw "Java 17 not found. Close this window, open a new one, and run this command again." }
        $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
        $mgr = Join-Path $dest "latest\bin\sdkmanager.bat"
        # sdkmanager prints a deprecation warning to stderr. With ErrorActionPreference=Stop that
        # alone aborts the step, so relax it here and judge success by what lands on disk instead.
        $prevEAP = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        # sdkmanager is a .bat wrapping java, and piping into it from PowerShell does not reach the
        # prompt, so the licence question stalls the run. Feed it a file of y's through cmd instead.
        $yes = Join-Path $env:TEMP "sdk-yes.txt"
        ("y`r`n" * 60) | Set-Content -Path $yes -Encoding ascii -NoNewline
        try {
            cmd /c "`"$mgr`" --sdk_root=`"$sdk`" --licenses < `"$yes`"" 2>&1 |
                ForEach-Object { if ($_ -notmatch '^\s*$') { Write-Host "    $_" } } | Out-Null
            cmd /c "`"$mgr`" --sdk_root=`"$sdk`" `"platforms;android-34`" `"build-tools;34.0.0`" `"platform-tools`" < `"$yes`"" 2>&1 |
                ForEach-Object { Write-Host "    $_" }
        } finally {
            $ErrorActionPreference = $prevEAP
            Remove-Item $yes -Force -ErrorAction SilentlyContinue
        }

        Write-Host ""
        Write-Host "=== checking what actually installed ==="
        $need = @{
            "Android 34 platform" = Join-Path $sdk "platforms\android-34"
            "Build tools 34.0.0"  = Join-Path $sdk "build-tools\34.0.0"
            "Java 17"             = $env:JAVA_HOME
        }
        $missing = @()
        foreach ($k in $need.Keys) {
            if (Test-Path $need[$k]) { Write-Host "  OK      $k" }
            else { Write-Host "  MISSING $k"; $missing += $k }
        }
        if ($missing.Count -gt 0) {
            Write-Host ""
            Write-Host "Some pieces are missing. Run this command again - it resumes and skips what is done."
            Write-Host "If it keeps failing, send Claude everything printed above."
        } else {
            Write-Host ""
            Write-Host "All set. SDK is at $sdk"
            Write-Host "Next: turn USB debugging back on on the TV, then run  .\windows\tv.ps1 install"
        }
    }
    "prereqs" {
        # Everything the PC needs, in one command. The Android Studio download is a few GB, so
        # start this first and run the TV steps in another PowerShell window while it works.
        Write-Host "1/3 Python..."
        winget install --id Python.Python.3.12 -e --accept-package-agreements --accept-source-agreements
        Write-Host "2/3 Android Studio (large download, please wait)..."
        winget install --id Google.AndroidStudio -e --accept-package-agreements --accept-source-agreements
        Write-Host "3/3 Android SDK pieces for this project..."
        $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
        $mgr = Join-Path $sdk "cmdline-tools\latest\bin\sdkmanager.bat"
        if (-not (Test-Path $mgr)) {
            $alt = Get-ChildItem -Path $sdk -Filter sdkmanager.bat -Recurse -ErrorAction SilentlyContinue |
                   Select-Object -First 1
            if ($alt) { $mgr = $alt.FullName }
        }
        if (Test-Path $mgr) {
            $yes = ("y`n" * 40)
            $yes | & $mgr --sdk_root="$sdk" --licenses 2>&1 | Out-Null
            & $mgr --sdk_root="$sdk" "platforms;android-34" "build-tools;34.0.0"
            Write-Host "SDK ready at $sdk"
        } else {
            Write-Host "Could not find sdkmanager yet. Open Android Studio once, let it finish its"
            Write-Host "first-run setup, then run this command again."
        }
        Write-Host ""
        Write-Host "Now close this PowerShell window, open a new one, and run: .\windows\tv.ps1 setup"
    }
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
        # Always come back to where the user started, even on failure or Ctrl+C: otherwise they are
        # left in launcher\ where ".\windows\tv.ps1" no longer resolves.
        $startLocation = Get-Location
        try {
        Set-Location (Join-Path $Repo "launcher")
        # secrets.xml holds passwords and tokens and is never committed; start it from the template.
        $secrets = "app\src\main\res\values\secrets.xml"
        if (-not (Test-Path $secrets)) {
            Copy-Item "secrets.example.xml" $secrets
            Write-Host "Created $secrets from the template. Email and Telegram stay off until you fill it in."
        }
        if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
        if (-not $env:JAVA_HOME) {
            $jbr = "C:\Program Files\Android\Android Studio\jbr"
            if (Test-Path $jbr) {
                $env:JAVA_HOME = $jbr          # Android Studio brings its own Java
            } else {
                $jdk = Get-ChildItem "C:\Program Files\Microsoft" -Filter "jdk-17*" -Directory -ErrorAction SilentlyContinue |
                       Sort-Object Name -Descending | Select-Object -First 1
                if ($jdk) { $env:JAVA_HOME = $jdk.FullName }   # the prereqs-lite path
            }
        }
        if (-not (Test-Path $env:ANDROID_HOME)) { throw "Android SDK not found at $env:ANDROID_HOME. Install Android Studio first (see windows\README.md)." }
        if (-not $env:JAVA_HOME) { throw "JAVA_HOME not set. Install Android Studio or JDK 17 (see windows\README.md)." }
        $sdk = $env:ANDROID_HOME -replace "\\", "/"
        "sdk.dir=$sdk" | Out-File local.properties -Encoding ascii

        # Gradle downloads itself on first build, with a 10s timeout and no retries - hopeless on a
        # slow line. Fetch it once over BITS (resumable) and point the wrapper at the local copy.
        $props = "gradle\wrapper\gradle-wrapper.properties"
        $gradleVer = ([regex]::Match((Get-Content $props -Raw), 'gradle-([0-9.]+)-bin\.zip')).Groups[1].Value
        if (-not $gradleVer) { $gradleVer = "8.9" }
        $dist = Join-Path $Repo "gradle-$gradleVer-bin.zip"
        if (-not (Test-Path $dist)) {
            Write-Host "Fetching Gradle $gradleVer once (about 130 MB, resumes if the line drops)..."
            Write-Host "  (leave this window alone until it finishes; Ctrl+C cancels the download)"
            try {
                Import-Module BitsTransfer -ErrorAction Stop
                # clear any job a previous Ctrl+C left behind, which would otherwise block this one
                Get-BitsTransfer -ErrorAction SilentlyContinue |
                    Where-Object { $_.DisplayName -eq "Gradle $gradleVer" } |
                    Remove-BitsTransfer -ErrorAction SilentlyContinue
                Start-BitsTransfer -Source "https://services.gradle.org/distributions/gradle-$gradleVer-bin.zip" `
                                   -Destination $dist -Description "Gradle $gradleVer" -DisplayName "Gradle $gradleVer"
            } catch {
                Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-$gradleVer-bin.zip" `
                                  -OutFile $dist -UseBasicParsing
            }
        }
        if ((Get-Item $dist).Length -lt 50MB) {
            Remove-Item $dist -Force
            throw "Gradle download came out too small. Run install again - it resumes."
        }
        # a .properties file needs its colons escaped
        $escaped = ($dist -replace '\\', '/') -replace ':', '\:'
        (Get-Content $props) -replace '^distributionUrl=.*', "distributionUrl=file\:///$escaped" |
            Set-Content $props -Encoding ascii
        Write-Host "Using local Gradle at $dist"
        & .\gradlew.bat assembleDebug -q --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Build failed (see messages above)." }
        $apk = "app\build\outputs\apk\debug\app-debug.apk"
        & $Py -I ..\tools\b64push.py $apk /data/local/tmp/tvhome.apk
        if ($LASTEXITCODE -ne 0) { throw "Upload to the TV failed (md5 mismatch)." }
        $cmds = @(
            "pm install -r /data/local/tmp/tvhome.apk",
            "rm -f /data/local/tmp/tvhome.apk",
            # lets the board draw its banner over Netflix; Android TV has no screen to grant this
            "appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow",
            # set-home-activity wants a full component on Android 9, not just the package name
            "cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity",
            "pm disable-user --user 0 $StockLauncher",
            # BoardService is not exported, so the shell cannot start it directly; launching the
            # home screen starts it, which is what happens on every boot anyway
            "am start -n com.gathin.tvhome/.MainActivity")
        ($cmds -join "`n") | & $Py -I ..\tools\tv.py --batch
        Write-Host "Installed. Press Home on the remote to see it."
        Write-Host "Family board page: http://$($env:TV_HOST):8080  (open it on a phone on your Wi-Fi)"
        } finally {
            Set-Location $startLocation
        }
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
    "ads-only" {
        # Ads gone, stock home screen kept, no APK to build. Order matters: if PatchWall is the
        # current home screen, disabling it first would leave the Home button with nowhere to go,
        # so the Google TV launcher is made the home screen before anything is switched off.
        Need-Venv
        if (-not $Rest) { throw "usage: tv.ps1 ads-only examples\xiaomi-mitv-axsoo\ads-only.txt" }
        $list = $Rest[0]
        if (-not (Test-Path $list)) { throw "list not found: $list" }
        $pkgs = @(Read-Packages $list)
        if ($pkgs -contains "com.google.android.tvlauncher") {
            throw "that list disables the stock launcher - use 'disable' after installing the new one"
        }
        Write-Host "Checking the Google TV launcher is present before touching PatchWall..."
        $present = (& $Py -I tools\tv.py "pm list packages com.google.android.tvlauncher") -join ""
        if ($present -notmatch "com.google.android.tvlauncher") {
            throw "The Google TV launcher is not on this TV, so disabling PatchWall would leave no home screen. Stop here and tell Claude."
        }
        $lines = @(
            "pm enable com.google.android.tvlauncher",
            "cmd package set-home-activity --user 0 com.google.android.tvlauncher"
        ) + ($pkgs | ForEach-Object { "pm disable-user --user 0 $_" }) + @(
            "cmd package resolve-activity -c android.intent.category.HOME --user 0 | grep -i packageName",
            "pm list packages -d"
        )
        Run-Batch $lines
        Write-Host ""
        Write-Host "Done. Press Home on the remote: you should get the Google TV home screen, no Xiaomi ads."
        Write-Host "If anything looks wrong: .\windows\tv.ps1 restore $list"
        Write-Host "When you are happy, close the door you opened: .\windows\tv.ps1 lockdown"
    }
    "inputs" {
        # What the TV actually reports for each input: id, built-in label, user-set custom label.
        # Useful when a port shows up nameless and we need to know why.
        Need-Venv
        & $Py -I tools\tv.py "dumpsys tv_input | grep -iE 'TvInputInfo|mId=|mLabel|customLabel|mType|state' | head -60"
        Write-Host ""
        Write-Host "Send the above to Claude."
    }
    "health" {
        # Is the TV short of memory, and is this launcher a meaningful part of it?
        Need-Venv
        Run-Batch @(
            "cat /proc/meminfo | grep -E 'MemTotal|MemFree|MemAvailable'",
            "dumpsys meminfo com.gathin.tvhome | grep -E 'TOTAL|Native Heap|Dalvik Heap' | head -4",
            "dumpsys meminfo | grep -A 14 'Total PSS by process' ",
            "dumpsys activity services com.gathin.tvhome | grep -cE 'ServiceRecord'"
        )
        Write-Host ""
        Write-Host "Send the above to Claude."
    }
    "shot" {
        # Grab what is on the TV right now. With a name, it is filed in docs\ ready for GitHub:
        #   .\windows\tv.ps1 shot home     ->  docs\home.png
        Need-Venv
        if ($Rest -and $Rest[0]) {
            $dir = Join-Path $Repo "docs"
            New-Item -ItemType Directory -Force -Path $dir | Out-Null
            $out = Join-Path $dir ("{0}.png" -f ($Rest[0] -replace '[^A-Za-z0-9_-]', ''))
        } else {
            $out = Join-Path $Repo "shot.png"
        }
        & $Py -I tools\tv.py "screencap -p /sdcard/tvshot.png" | Out-Null
        & $Py -I tools\tv.py --pull /sdcard/tvshot.png "$out"
        & $Py -I tools\tv.py "rm -f /sdcard/tvshot.png" | Out-Null
        if (Test-Path $out) {
            Write-Host ("Saved {0} ({1} KB)" -f $out, [math]::Round((Get-Item $out).Length/1KB))
        } else {
            Write-Host "Screenshot failed - is the TV awake and USB debugging on?"
        }
    }
    "wallpaper" {
        # Pictures behind the home screen. A whole folder, or one picture:
        #   .\windows\tv.ps1 wallpaper C:\Users\me\Desktop\tv
        #   .\windows\tv.ps1 wallpaper C:\Users\me\Pictures\krishna.jpg
        # The TV shows one a day, in name order, and notices new ones within a minute - nothing to
        # rebuild. Run it with no path to take them all off again.
        Need-Venv
        # The app's own folder: readable by the launcher with no storage permission at all.
        $remote = "/sdcard/Android/data/com.gathin.tvhome/files/wallpapers"
        if (-not ($Rest -and $Rest[0])) {
            & $Py -I tools\tv.py "rm -rf $remote" | Out-Null
            Write-Host "Pictures removed. The home screen goes back to the colour of the hour."
            break
        }
        $src = $Rest[0]
        if (-not (Test-Path $src)) { Write-Host "No such file or folder: $src"; break }

        if (Test-Path $src -PathType Container) {
            $pics = Get-ChildItem -Path $src -File |
                    Where-Object { $_.Extension -match '^\.(jpg|jpeg|png|webp|jfif|bmp)$' }
        } else {
            $pics = @(Get-Item $src)
        }
        if (-not $pics -or $pics.Count -eq 0) {
            Write-Host "No pictures there. Looking for .jpg .jpeg .png .webp .jfif .bmp"
            break
        }

        # Start clean, so pictures taken out of the folder leave the TV too.
        & $Py -I tools\tv.py "rm -rf $remote" | Out-Null
        & $Py -I tools\tv.py "mkdir -p $remote" | Out-Null

        $sent = 0; $skipped = 0
        foreach ($p in $pics) {
            if ($p.Length -gt 12MB) {
                Write-Host ("  skipped {0} - {1} MB is too big for a 1 GB TV" -f $p.Name, [math]::Round($p.Length/1MB,1))
                $skipped++
                continue
            }
            # Keep the order but give every file a plain safe name: the TV sorts by name.
            $safe = "{0:d2}-{1}" -f ($sent + 1), ($p.Name -replace '[^A-Za-z0-9._-]', '_')
            & $Py -I tools\tv.py --push $p.FullName "$remote/$safe" | Out-Null
            Write-Host ("  sent {0}" -f $p.Name)
            $sent++
        }
        Write-Host ""
        if ($sent -eq 0) {
            Write-Host "Nothing was sent."
        } else {
            Write-Host ("{0} picture(s) on the TV - one a day, then round again ({1} days of pictures)." -f $sent, $sent)
            if ($skipped -gt 0) { Write-Host ("{0} were too big and were left out." -f $skipped) }
            Write-Host "The first appears on the home screen within a minute."
            Write-Host "Add more to the folder and run this again; run 'wallpaper' with no path to remove them."
        }
    }
    "lockdown" {
        # Network ADB is the biggest hole this project opens: anything on your Wi-Fi can reach
        # port 5555, and there are worms that scan home networks for exactly that. Run this once
        # you have finished installing. The TV drops this connection straight away, which is normal.
        Write-Host "Turning off debugging on the TV. This connection will drop - that is expected."
        try { Run-Batch @("settings put global adb_enabled 0") } catch { }
        Write-Host ""
        Write-Host "Done. Also switch off 'USB debugging' in Settings > Device Preferences > Developer options."
        Write-Host "To change anything later, turn USB debugging back on and run this script again."
    }
    "chatid" {
        # Prints the chat IDs allowed to post to the TV board. Each person sends any message to the
        # bot first, then you run this and paste the IDs into telegram_allowed_chats in config.xml.
        if (-not $Rest) { throw "usage: tv.ps1 chatid <bot token from @BotFather>" }
        $resp = Invoke-RestMethod "https://api.telegram.org/bot$($Rest[0])/getUpdates"
        if (-not $resp.ok) { throw "Telegram refused the token: $($resp.description)" }
        $msgs = @($resp.result | ForEach-Object { $_.message } | Where-Object { $_ })
        if ($msgs.Count -eq 0) {
            Write-Host "No messages yet. Open Telegram, send 'hi' to your bot from each phone, then run this again."
        } else {
            Write-Host "chat ID        who            type"
            $msgs | ForEach-Object { "{0,-14} {1,-14} {2}" -f $_.chat.id, $_.from.first_name, $_.chat.type } |
                Sort-Object -Unique | ForEach-Object { Write-Host $_ }
            Write-Host ""
            Write-Host "Put these IDs (comma separated) in telegram_allowed_chats, then run: .\windows\tv.ps1 install"
        }
    }
    default { Get-Help $PSCommandPath | Out-String | Write-Host }
}
