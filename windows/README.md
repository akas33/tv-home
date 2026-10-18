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

## Family board: notes and photos on the TV

Three ways in. Set up whichever you want; they all feed the same board, and anything that arrives
shows on the screensaver **and** pops up a small banner over whatever is playing, so it is noticed
even mid-Netflix. Notes and photos drop off after 12 hours.

### 1. The TV's own page (nothing to install, home Wi-Fi only)

Already working after `install`. On any phone on your Wi-Fi, open **http://192.168.29.8:8080**,
type a note or pick a photo, press send. No app, no account, nothing leaves the house.
Anyone on your Wi-Fi can post; to require a code, set `upload_pin` in `config.xml` and reinstall.
Worth bookmarking on the home screen of each phone.

### 2. Email (nothing to install, works from anywhere)

For sending from the office. Make a mailbox **just for the TV** and never use your personal one:
the password sits in the app on the TV.

1. Create a new Gmail, e.g. `ourtvboard@gmail.com`.
2. Turn on 2-Step Verification on that account, then create an **App password**
   (Google Account > Security > App passwords). You get a 16-character code.
3. In `config.xml` set `mail_user` to the address, `mail_password` to that app password, and
   `mail_allowed_senders` to the addresses allowed to post, comma separated.
4. `.\windows\tv.ps1 install`

Family then mail a photo or note to that address from the mail app already on their phone. The
**subject line** is what shows on the TV, and an attached picture becomes the photo. Mail from
anyone not on the allowed list is ignored, and the TV only ever reads: it never sends or deletes.

### 3. Telegram bot (needs the app, but the nicest to use)

Optional, for family already on Telegram.

1. Message **@BotFather**, send `/newbot`, pick any name, copy the **token** it gives you.
2. Send `hi` to your new bot from every phone that should be allowed.
3. `.\windows\tv.ps1 chatid <that token>` prints one chat ID per person.
4. Put the token in `telegram_bot_token` and the IDs in `telegram_allowed_chats`, then `install`.

### How fast, and the banner

The TV checks email and Telegram **once a minute**, so those arrive within about a minute; the
TV's own page is instant. A newly arrived photo fills the screensaver for 10 minutes
(`photo_feature_minutes`), then photos and the clock take turns.

The banner over other apps needs the "draw over other apps" permission, which `install` grants over
ADB. If banners never appear, run this and relaunch the app:

```powershell
.\tools\venv\Scripts\python.exe -I tools\tv.py "appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow"
```

If something fails, the first thing to check is the message from `install`: the usual causes are a
missing SDK platform (install API 34 in SDK Manager) or the TV asking for USB debugging approval again.
