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

## Family board (notes and photos on the screensaver)

Private, and works from anywhere — the phones do not need to be on your Wi-Fi.

1. In Telegram, message **@BotFather**, send `/newbot`, pick any name. It replies with a **token**
   like `8012345678:AAE...`. Treat it as a password.
2. Open your new bot and send it `hi` from every phone that should be allowed to post.
3. On the PC: `.\windows\tv.ps1 chatid <that token>` — it prints one chat ID per person.
4. Put the token in `telegram_bot_token` and the IDs (comma separated) in `telegram_allowed_chats`
   in `launcher\app\src\main\res\values\config.xml`.
5. `.\windows\tv.ps1 install`

Now anyone on that list can send the bot a note or a photo and it appears on the screensaver. A new
photo fills the screen for 10 minutes (`photo_feature_minutes`), then photos and the clock take
turns. Notes and photos drop off after 12 hours. The TV checks once a minute while the screensaver
is showing, so a photo arrives within about a minute.

Messages from anyone **not** on the list are ignored, and with the list empty nothing is shown at all.

If something fails, the first thing to check is the message from `install`: the usual causes are a
missing SDK platform (install API 34 in SDK Manager) or the TV asking for USB debugging approval again.
