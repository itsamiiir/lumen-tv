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

## Light look

- The shelf, top buttons and drawer glass read the lightened background (they took the poster's raw colors).
- All Apps, messages and the wallpaper picker follow light and dark; messages and the picker sit beside the
  header's date instead of over it.
- The home screen uses 0% CPU while you're in an app and stays Android's protected home process.
