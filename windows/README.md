# Windows guide (simple)

Everything is done from PowerShell in the repo folder. The TV address defaults to `192.168.29.8`;
if it changes, run `$env:TV_HOST = "new.ip.address"` first.

## One-time installs on the PC

1. **Python 3:** `winget install Python.Python.3.12` (tick "Add to PATH" if asked).
2. **Android Studio:** `winget install Google.AndroidStudio`. Open it once, let it download the
   Android SDK, and in *SDK Manager* tick **Android 14 (API 34)** and **Build-Tools 34.0.0**.
   It brings its own Java, so you don't need to install Java separately.
3. Allow scripts for this session: `Set-ExecutionPolicy -Scope Process Bypass`

## On the TV

Settings > Device Preferences > About > press OK on **Build** 7 times > back > Developer options >
turn on **USB debugging**. Keep the TV on and on the same Wi-Fi as the PC.

## Steps

```powershell
.\windows\tv.ps1 setup        # one time
.\windows\tv.ps1 probe        # accept "Allow USB debugging?" on the TV (tick "always allow")
.\windows\tv.ps1 backup       # saves package list and settings to .\backup
.\windows\tv.ps1 packages     # compare with examples\xiaomi-mitv-axsoo\debloat.txt
.\windows\tv.ps1 install      # build and install the launcher + screensaver
.\windows\tv.ps1 disable examples\xiaomi-mitv-axsoo\debloat.txt
.\windows\tv.ps1 screensaver preview
```

Install the launcher **before** running `disable`, because the disable list also switches off the
stock launcher. Undo everything with `.\windows\tv.ps1 restore examples\xiaomi-mitv-axsoo\debloat.txt`.

If something fails, the first thing to check is the message from `install`: the usual causes are a
missing SDK platform (install API 34 in SDK Manager) or the TV asking for USB debugging approval again.
