# What Lumen TV adds to Free My TV

Lumen TV starts from [Free My TV](https://github.com/simbasjungles/free-my-tv) and keeps all of it: the safe switch
with *Original* to undo, the watchdog, the brand lists, the LG and Samsung web versions. These are the changes on
Google TV and Android TV, built and measured on a Vu 75″ Vibe (Google TV 12, MediaTek).

## Look

- **Apple TV–style home screen.** A large clock with a small AM/PM, the date and weather beside it, then *Explore ·
  Movies · Shows · Stremio* tabs with slim frosted focus pills.
- **Suggestion row with real art.** Nine titles per tab in a looping row. The selected card is wide, with the title's
  official **logo** (the first logo bright enough to read is used, never repainted). The others show the official
  **poster**. Details (genre, runtime, app) only on the selected card.
- **Auto-advance** every 10 s with an Apple-style page indicator whose dash fills up; it rests after 2 minutes without
  a key press, so an idle TV draws nothing.
- **Shelf** in Apple TV style: brand-colored tiles (YouTube red, Stremio purple…) with white logos, *All Apps* at the
  end after a divider, and a barely-there focus ring.
- **Wallpapers** (presets or your own photos), used by the screensaver too, and soft UI sounds (from cuelume).

## Second page (press down from the shelf)

- **Continue Watching** right under the shelf, then **one dock** for Live TV, HDMI inputs and shortcuts (Wallpaper,
  Home Settings, TV Settings), then the **weather**. Centered, with no second scroll.
- The shelf turns to **smoked glass** on this page and fades back to frosted glass on the way up, with nothing redrawn
  while it slides. The dock and weather use flat surfaces: focus moves went from 40 to 27 ms a frame.

## Suggestions

- **A mix** of your country's popular recent movies and shows (`tmdb_origin`, e.g. `IN`), the big worldwide hits,
  top recent shows and this week's trending, taking turns. Each title is well rated and has a real poster.
- **Family filter** (`family_friendly=true`): leaves out titles with sexual content (keywords, adult flag, NC-17);
  violence ratings don't count.
- **Removed what doesn't apply:** the crypto quotes widget and its coin rows; *Remote Buttons* is hidden on TVs
  whose remote buttons it can't change (it listed TCL buttons on a Vu).
- **Suggestions On/Off** in *Home Settings*: off shows a big clock (with AM/PM) and the weather, and fetches nothing.
- **Sony LIV** added to the streaming apps; posters in Hindi, Tamil, Telugu, Malayalam and Kannada, and logos only in
  English or Hindi.

## Opening titles

- With a **Watchmode key**, choosing a card opens the title's own page in **Netflix, Prime Video, JioHotstar and Sony
  LIV** (tested). Links open in a fresh task, so an app that was already open goes to the title. Netflix needs a
  `source` tag on its links, which is added.

## Stremio

- **A Stremio tab** with its own suggestions (popular movies and shows, trending, your country's hits), each opening
  on its page in Stremio (`stremio:///detail/...`). The tab hides itself if Stremio isn't installed.
- **Your Stremio progress** in Continue Watching (episode, time left, a progress line) with a Stremio key from
  `tools/stremio-key.sh`, opening on that episode.

## Continue Watching

- Titles you opened from the home screen (kept 45 days), plus Stremio progress, newest first.
- **Hold OK** on a card to remove it. Stremio titles are only hidden on the TV and come back if watched again.
- Android lets only the TV's built-in home screen read the apps' own "Watch Next" lists, so Netflix and Prime
  progress started inside those apps can't be shown.

## Speed

- Suggestion images are prepared once and saved, so after the TV turns on they show in ~0.1 s instead of ~1.3 s of
  grey cards. They load on two threads, the ones on screen first.
- Smooth fades: the background and shelf glass fade every frame; the shelf's smoke is its own layer.

## From Free My TV 1.1

- **Switch safety** (`HomeMode.java`, as in Free My TV 1.1): Google TV's setup app goes off first and comes back last;
  Android's own parts and factory menus can never be turned off; apps that run the HDMI inputs or receive the
  remote's Input key are checked on the TV and kept on; the switch stops when the Wi-Fi service doesn't answer;
  *Original* finds the TV's own home screen when none was recorded. Tested on the Vu: Original and back left the same
  apps off as before.
- **Lighter return home** (`HomeView.java`): the shelf and All Apps are read again only when apps are installed,
  removed or updated, the shelf or language changes, or after 15 minutes; a burst of Home presses reads them once.
  Measured on the Vu: the home screen's CPU time over 8 returns 7.4 → 6.1 s; the return itself unchanged (~0.13 s).
- Not taken: their translation catalogue, the LG/Samsung, prices and top-button changes, and their Android 8–12
  glass change (Lumen's glass reads the frosted image there).

## From Free My TV 1.1 (24 Oct)

- **My Photos:** your photos in the screensaver, sent from a computer (`tools/photos.py`, adb into the app's folder, or
  its `PhotoInbox` on Android 11) **or imported from a USB stick** (Home Settings → My Photos; Lumen keeps the stick
  import the original removed, through the same import). A chosen wallpaper still comes first.
- **Shelf of any size:** up to 10 tiles show, the apps scroll with the focus and All Apps stays at the end; arrows
  outside the glass; low-memory TVs keep 8. The shelf editor shows a check next to the apps on it.
- **Correct streaming apps:** "… Amazon Channel" entries are skipped and Apple TV is only TMDB's streaming service
  (350), not the Apple TV Store (rent or buy).
- **Memory leak fixed:** each image's shader lives with its image (a cache in HomeView kept every card drawn).
- **Sharper on MediaTek / 4K:** rounded tiles drawn anti-aliased (no clipPath), images and icons shrunk in steps of
  at most 2×, shapes on whole pixels at rest.
- **Home Settings scrolls** past ten rows; the screensaver re-reads settings on Android 11 and refetches the weather
  when the city changed.
- **Safety:** onn 4K and Amazon Fire TV system apps protected; on Fire TV the switch is refused and the app's icon
  opens the home screen; two connection fixes (Android 8–10 with a computer still connected, a dropped connection).
- **Fixed:** with Remote Buttons hidden, *TV Settings* in the main menu opened the hidden screen.
- Not taken: the original's new logo-cutting method (Lumen's Apple TV-style icons depend on the current one) and
  its glass rewrite for Android 8–12.

## Smoother (25 Oct)

- **Up from any suggestion** goes to the tabs, on the selected one; up again reaches the corner buttons (as in Free
  My TV 1.1; Lumen used to jump from the right-hand cards straight to the buttons).
- **Page 2 slides without stutter**: nothing is repainted while it moves. The background's veil is its own flat
  layer, page 2 is painted just before it starts moving, the top half keeps its GPU copy while hidden, the focused
  app settles as page 2 opens and grows once the shelf has landed, and the shelf's glass is painted again only if it
  changed. Measured on the Vu 75 (frames reaching the screen): stalls during the slide went from 1–2 per opening,
  some mid-slide, to none in most openings.
- **Moving along the shelf**: focus shadows are pre-blurred on every Android (Android 12 blurred them again on the CPU
  on every frame of a focus animation): 6 stalls in four moves → 0–2, half the GPU time.
- **Corner buttons of your choice** (Home Settings → Corner Buttons): up to five, in any order, from Live TV, HDMI
  Inputs, AirPlay, Search, All Apps, Wallpaper, the TV's Input Menu, Picture, Sound, Network, TV Settings, Free My TV's
  settings and each HDMI port. Only what works on the TV is offered; a button only opens a screen (never changes a
  setting), and one the TV loses is skipped instead of shown broken. Messages and the wallpaper picker fit between
  the date and the buttons.

## Settings app

- Restyled in Lumen's dark look (a still gray gradient instead of the animated blue sky, which redrew 10 times a
  second). The Comparison and Notices sections are gone; the menu ends with one Suggestions status line and the TMDB
  and Watchmode credits, so nothing is cut off at the bottom.

## Light look

- The shelf, top buttons and drawer glass read the lightened background (they took the poster's raw colors).
- All Apps, messages and the wallpaper picker follow light and dark; messages and the picker sit beside the
  header's date instead of over it.
- The home screen uses 0% CPU while you're in an app and stays Android's protected home process.
