# Setting up Lumen TV, step by step

This guide is for anyone, no coding needed. You'll do a few things on the TV with the remote, and Claude Code does
the technical part on your computer. Plan for **30–45 minutes**. Nothing here is permanent: one button on the TV
brings everything back ([Going back](#9-going-back)).

**Contents:** [1. What you need](#1-what-you-need) · [2. Get the TV ready](#2-get-the-tv-ready) ·
[3. Get the computer ready](#3-get-the-computer-ready) · [4. Start the setup](#4-start-the-setup) ·
[5. Your free keys](#5-your-free-keys) · [6. Install](#6-install) · [7. Switch the TV over](#7-switch-the-tv-over) ·
[8. Make it yours](#8-make-it-yours) · [9. Going back](#9-going-back) · [Troubleshooting](#troubleshooting)

---

## 1. What you need

- **A Google TV or Android TV running Android 9 or newer**, or a Google TV streamer (Chromecast with Google TV,
  Google TV Streamer, NVIDIA Shield…) on any TV. Not sure? *Settings → System → About* shows the Android TV OS version.
  See [Which TVs](README.md#which-tvs).
- **A computer** (Mac, Windows or Linux) **on the same Wi-Fi network as the TV.** Same router, not a guest network.
- **Claude Code** ([claude.com/claude-code](https://claude.com/claude-code)), with a Claude account.
- **A free TMDB account** for the suggestions (step 5). Optional: a free Watchmode key, and your Stremio login if you
  use Stremio.

## 2. Get the TV ready

The computer talks to the TV through Android's "debugging" connection. It's the same thing app developers use, it
only works on your home network, and the TV asks your permission before any computer can connect.

**a. Turn on Developer options**

1. Open **Settings → System → About**. (On some TVs: *Settings → Device Preferences → About*.)
2. Scroll to **Android TV OS build** (or **Build**) and **press OK on it 7 times.** You'll see *"You are now a developer!"*

**b. Turn on debugging**

1. Go back to **Settings → System** (or *Device Preferences*) → **Developer options**.
2. Turn on **USB debugging**. If you also see **Network debugging** or **ADB over network**, turn that on too.
   (The name differs by brand; it's the switch with "debugging" in it.)

**c. Find the TV's IP address**

**Settings → Network & Internet →** your Wi-Fi network → **IP address**, something like `192.168.1.50`. Write it down.

> Tip: in your router's settings you can "reserve" this address for the TV so it never changes. Not required, but it
> saves a step next time.

## 3. Get the computer ready

1. Install **Claude Code** from [claude.com/claude-code](https://claude.com/claude-code) (the desktop app is the
   easiest) and sign in.
2. Make an empty folder, for example `TV` in your Documents, and open it in Claude Code.

That's all: Claude installs everything else it needs (Java, Android's build tools and adb) and asks you first.

## 4. Start the setup

In Claude Code, type something like this, with **your** TV's IP address and city:

> Set up Lumen TV on my TV from https://github.com/itsamiiir/lumen-tv. My TV's IP is 192.168.1.50 and I live in
> Jaipur, India.

Claude will:

1. **Download this project** into your folder and read its instructions.
2. **Check and install the tools** (asking before it installs anything).
3. **Connect to your TV.** The TV shows **"Allow USB debugging?"**: tick **"Always allow from this computer"** and
   choose **Allow**. You have about 30 seconds; if you miss it, just ask Claude to try again.
4. **Look at your TV** (brand, Android version, its own apps) and, if it isn't a Vu, add your brand's settings so its
   picture, sound and HDMI apps are protected.
5. **Fill in your settings** (city, country, temperature unit) and tell you what's left: your keys.

Answer its questions in plain words. Nothing on the TV changes until step 7, and only when you do it yourself.

## 5. Your free keys

Keys are like passwords for the services that provide suggestions. **They're yours: never paste them into the chat.**
Claude opens the settings file for you (`config.properties`), and you paste each key after its `=` and save.

**TMDB: needed for suggestions (free, 5 minutes)**

1. Create an account at [themoviedb.org](https://www.themoviedb.org/signup) and confirm your email.
2. Go to [Settings → API](https://www.themoviedb.org/settings/api) and request an API key: choose *Developer*, a
   personal/non-commercial use, and fill the short form.
3. Copy the **API Read Access Token** (the long one) and paste it after `tmdb_key=`.

> In India, some internet providers (Jio, Airtel) block TMDB's website. If it won't load, open it on mobile data or
> ask Claude to help you use a secure DNS. The TV itself usually reaches TMDB fine.

**Watchmode: optional, makes cards open the title in its app (free)**

Request a key at [api.watchmode.com/requestApiKey](https://api.watchmode.com/requestApiKey/), and it arrives by
email. Paste it after `watchmode_key=`. Without it, cards open the app's home screen instead.

**Stremio: optional, your Stremio progress in Continue Watching**

Ask Claude to *"run the Stremio sign-in"*. It opens `tools/stremio-key.sh` in a terminal where **you** type your
Stremio email and password. The password goes only to Stremio and isn't saved; the script writes the resulting key
into the settings file. (Accounts made with Google or Facebook need a password set on stremio.com first.)

When you're done, tell Claude **"done"**.

## 6. Install

Claude builds the app (a minute or two) and installs it on the TV. You'll see a new app called **Free My TV** in
your TV's apps. Nothing about your home screen has changed yet.

## 7. Switch the TV over

On the TV, with the remote:

1. Open the **Free My TV** app.
2. Choose **Home Screen → Free My TV**.
3. The TV asks **"Allow USB debugging?"** once more, this time for the app itself: tick **Always allow** and choose
   **Allow**.

It turns off the stock home screen and a few promo apps (never uninstalling anything) and shows the new home screen.

> If your Wi-Fi was saved during the TV's first setup, the switch stops and asks you to *forget* the Wi-Fi network
> and connect again in *Settings → Network*. That's a one-time thing; then switch again.

> **The first minute is slower.** Right after switching, turning the TV on, or an update, the home screen prepares
> its graphics and downloads and caches the suggestion images, while the TV's own apps are still starting. It can
> stutter or show grey cards briefly; give it a minute and it's smooth from then on.
> [Why, in detail →](README.md#a-slower-first-minute-is-normal)

## 8. Make it yours

- **Move between pages:** press **down** from the app shelf for Continue Watching, inputs, shortcuts and the weather.
- **Wallpaper, sounds, temperature, language, city:** *Free My TV → Home Settings* (or the gear button on the home
  screen).
- **Don't want suggestions?** *Home Settings → Suggestions → Off* shows a big clock with the weather instead.
- **Shelf apps and order:** *Free My TV → Shelf*, or **hold OK** on a shelf app to move it.
- **Remove something from Continue Watching:** hold OK on its card, then OK again.
- **Want more?** Ask Claude: *"add Disney+ to the shelf"*, *"make the clock bigger"*, *"hide anime from the
  suggestions"*. Then it rebuilds and reinstalls.

## 9. Going back

On the TV: **Free My TV → Home Screen → Original.** Your original home screen, screensaver and every app come back
as they were. To remove it completely afterwards, uninstall *Free My TV* in *Settings → Apps*.

You can also turn **USB debugging** off again in *Developer options* once you're done. The home screen keeps working;
you'll only need debugging back on for future changes.

---

## Troubleshooting

**"No route to host" or "failed to connect" on a Mac, though the TV is on.**
macOS's *Local Network* privacy is blocking the connection. Claude knows the fix (restarting adb from the terminal),
or allow your terminal / Claude app under *System Settings → Privacy & Security → Local Network*.

**The TV never showed "Allow USB debugging?"**
Check that *USB debugging* (and *Network debugging*, if your TV has it) is on, that the computer and TV are on the
same Wi-Fi, and that the IP address is right. Then ask Claude to connect again.

**The TV's IP address changed.**
Look it up again (step 2c) and tell Claude the new one.

**No suggestions, only the clock and apps.**
The TMDB key is missing or mistyped. Check `tmdb_key=` in `config.properties` (no spaces), then ask Claude to rebuild.

**Cards open the app's home screen, not the title.**
Add a Watchmode key (step 5). Some titles have no link on Watchmode; those always open the app.

**On TCL TVs the switch does nothing.**
TCL blocks background tasks by default; Claude allows the app to run (it's in its instructions).

**Still slow after a few minutes.**
Restart the TV once (hold the power button, or unplug it for 10 seconds). If it persists, check that the TV has a
stable internet connection: suggestion images that fail to download are retried, which keeps it busy.

**Something looks wrong after an update of the TV's software.**
Open *Free My TV* and choose *Home Screen → Free My TV* again. If anything misbehaves, *Original* always brings
the TV back.
