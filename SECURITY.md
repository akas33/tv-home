# Security

What this project can reach, what can reach it, and how to check for yourself rather than taking
anyone's word for it.

## Check it yourself

The whole launcher is about 3,000 lines of plain Java in
`launcher/app/src/main/java/com/gathin/tvhome/`. The APK is built on your own computer from
exactly these files; nothing prebuilt is downloaded. Four commands tell you most of what you want
to know. Run them in the repository.

**Every network address the app can reach:**

```
grep -rno 'https\?://[^"]*' launcher/app/src/main/java launcher/app/src/main/res
```

As of now the complete list is:

| Address | When | Why |
|---|---|---|
| `api.open-meteo.com` | every 30 min | temperature and conditions |
| `air-quality-api.open-meteo.com` | every 30 min | AQI |
| whatever `news_feed_url` is set to | every 30 min | screensaver headlines |
| `imap.gmail.com` (or your `mail_host`) | only if you configure mail | reads your own mailbox |
| `api.telegram.org` | only if you configure a bot | reads your own bot's messages |
| `ntfy.sh` | off unless you turn it on | the old public-topic board |
| YouTube | only when you press OK on the line of the day | plays the song you configured |

The weather and air-quality calls send the latitude and longitude in `config.xml`. **Use your city
centre, not your house** - those coordinates are in the repository and go to Open-Meteo on every
check.

**Every file that can open a connection at all:**

```
grep -rln "HttpURLConnection\|Socket\|URL(" launcher/app/src/main/java
```

Three: `BriefFetcher` (weather, air, news), `TelegramSource`, `UploadServer`.
`MailSource` goes through JavaMail over IMAPS.

**Nothing runs commands, loads code at runtime, or touches contacts, SMS, calls or the device id:**

```
grep -rn "Runtime.getRuntime\|ProcessBuilder\|DexClassLoader\|loadLibrary\|ContactsContract\|content://sms\|TelephonyManager\|getDeviceId\|AccountManager" launcher/app/src/main/java
```

That returns nothing.

**Every permission it asks for**, in `launcher/app/src/main/AndroidManifest.xml`: internet, network
state, Wi-Fi state, foreground service, draw-over-other-apps (for the banner), and start-at-boot.
That is the whole list. In particular there is **no location permission and no storage
permission** - wallpapers live in the app's own folder precisely so that reading `/sdcard` is
never necessary.

## What can reach the TV

**Nothing from the internet.** A home router does not forward inbound connections unless you tell
it to. Check that you have not, and that UPnP is off.

**The upload page is the only thing that listens**, and it is **off by default**
(`upload_server_enabled` in `config.xml`). While it is on, every device on your Wi-Fi can reach it:
a guest's phone, a phone with a bad app, a cheap smart plug. It is guarded by

- local addresses only - loopback, site-local, link-local; no use against anything already inside
- an `Origin` check, so a page on another website cannot post to it from a browser on your network
- a code, if you set `upload_pin`, compared without early exit, with a ten-minute lockout after
  five wrong tries

Worst case if someone gets past all of that is a photo or a rude note on the screen. It is not a
route to the rest of the TV. But if mail or Telegram covers you, leave the page off.

**Mail and Telegram only dial out.** Nothing on your network can reach the TV through them. Both
accept only senders on a list you write.

## Debugging is the real hole

Installing any of this needs ADB over the network, which means **port 5555 open to everything on
your Wi-Fi, with no password**. Worms scan home networks for exactly that port. When you have
finished installing:

```
.\windows\tv.ps1 lockdown
```

Turn it back on from the TV's Developer options when you next want to change something, and run
`lockdown` again afterwards.

## Secrets

`launcher/app/src/main/res/values/secrets.xml` holds the upload code, the mailbox password and the
bot token. It is in `.gitignore` and must never be committed; `launcher/secrets.example.xml` is the
template. Anything in it is compiled into the APK, so a person with physical access and a cable
could read it: **use a mail account created for this, with an app-password, never your main one.**

## The part no code here fixes

The TV runs Android 9 and no longer receives security patches from its manufacturer. That is the
largest risk on the device and it predates this project, which removes software rather than adding
it. Do not sideload APKs onto it, and keep guests on a guest Wi-Fi network.
