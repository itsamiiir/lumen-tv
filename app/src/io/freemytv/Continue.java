package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Continue Watching, under Inputs and Shortcuts: the titles you opened from the home screen (the apps keep their own
 * progress, which Android lets only the TV's built-in home screen read) and, with a Stremio key
 * (config.properties "stremio_auth_key"), what you're partway through in Stremio, with its progress.
 */
final class Continue {
    static final class Entry {
        String key, title, sub = "", app = "", pkg, link, backdrop;   // backdrop: TMDB path
        float progress = -1;    // 0..1 (Stremio), or -1
        long at;
    }

    interface Listener { void onContinue(); }

    /** The card's image at its focused size (the row's cards are 320 × 180, grown 1.08×), with the title's fade. */
    static final int IMG_W = Math.round(346 * Backdrop.W / 1920f), IMG_H = Math.round(194 * Backdrop.W / 1920f);
    private static final int MAX = 10, HISTORY = 20;
    private static final long STREMIO_EVERY = 5 * 60_000L, KEEP = 45L * 24 * 3600_000L;

    private static Continue instance;

    static synchronized Continue get(Context c) {
        if (instance == null) instance = new Continue(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    private final List<Entry> history = new ArrayList<>();
    private volatile List<Entry> stremio = new ArrayList<>();
    private final Map<String, Bitmap> images = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<String> loading = new HashSet<>();
    private volatile long stremioAt;
    private volatile boolean fetching;

    private Continue(Context c) {
        context = c;
        prefs = c.getSharedPreferences("continue", Context.MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(prefs.getString("history", "[]"));
            for (int i = 0; i < a.length(); i++) history.add(entry(a.getJSONObject(i)));
            JSONArray s = new JSONArray(prefs.getString("stremio", "[]"));
            List<Entry> saved = new ArrayList<>();
            for (int i = 0; i < s.length(); i++) saved.add(entry(s.getJSONObject(i)));
            stremio = saved;
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    private void changed() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onContinue(); });
    }

    /** What the row shows: newest first, Stremio's with their progress, at most 10. */
    synchronized List<Entry> items() {
        List<Entry> all = new ArrayList<>(stremio);
        Set<String> seen = new HashSet<>();
        for (Entry e : all) seen.add(e.key);
        long old = System.currentTimeMillis() - KEEP;
        for (Entry e : history) if (e.at > old && !seen.contains(e.key)) all.add(e);
        all.sort((a, b) -> Long.compare(b.at, a.at));
        return all.size() > MAX ? new ArrayList<>(all.subList(0, MAX)) : all;
    }

    /** A suggestion was opened: it goes first in the row. */
    synchronized void opened(Recs.Pick p) {
        if (p == null || p.pkg == null) return;
        Entry e = new Entry();
        e.key = sameKey(p);
        e.title = p.title;
        e.app = Recs.serviceName(p);
        e.sub = e.app;
        e.pkg = p.pkg;
        e.link = p.link;
        e.backdrop = p.backdrop;
        e.at = System.currentTimeMillis();
        history.removeIf(h -> h.key.equals(e.key));
        history.add(0, e);
        while (history.size() > HISTORY) history.remove(history.size() - 1);
        saveHistory();
        changed();
    }

    /** An entry of the row was opened again: it moves to the front. */
    synchronized void reopened(Entry e) {
        for (Entry h : history) if (h.key.equals(e.key)) h.at = System.currentTimeMillis();
        saveHistory();
        changed();
    }

    /** Stremio titles are keyed by IMDb id, so a Stremio pick and its Stremio progress are one entry. */
    private static String sameKey(Recs.Pick p) {
        if (p.link != null && p.link.startsWith("stremio:///detail/")) {
            String[] parts = p.link.split("/");
            return "stremio:" + parts[parts.length - 1];
        }
        return p.kind + ":" + p.id + ":" + p.app;
    }

    private void saveHistory() {
        prefs.edit().putString("history", toJson(history).toString()).apply();
    }

    // MARK: - Stremio

    static boolean stremioOn() {
        return !Config.STREMIO_AUTH_KEY.trim().isEmpty();
    }

    /** Reads Stremio's library again when it's older than 5 minutes (the drawer opening asks). */
    void refresh() {
        if (!stremioOn() || fetching || System.currentTimeMillis() - stremioAt < STREMIO_EVERY) return;
        fetching = true;
        io.execute(() -> {
            try {
                List<Entry> list = fetchStremio();
                synchronized (this) {
                    stremio = list;
                }
                prefs.edit().putString("stremio", toJson(list).toString()).apply();
                stremioAt = System.currentTimeMillis();
                changed();
            } catch (Exception e) {
                stremioAt = System.currentTimeMillis() - STREMIO_EVERY + 60_000L;   // try again in a minute
                AppLog.w("continue (stremio): " + e.getMessage());
            } finally {
                fetching = false;
            }
        });
    }

    /** Library items partway watched (a position past the start, not near the end), newest first. */
    private List<Entry> fetchStremio() throws Exception {
        JSONObject body = new JSONObject().put("authKey", Config.STREMIO_AUTH_KEY.trim()).put("collection", "libraryItem")
                .put("ids", new JSONArray()).put("all", true);
        JSONObject reply = new JSONObject(post("https://api.strem.io/api/datastoreGet", body.toString()));
        if (reply.has("error")) throw new IOException("Stremio: " + reply.optJSONObject("error"));
        JSONArray items = reply.optJSONArray("result");
        List<Entry> out = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            JSONObject st = it.optJSONObject("state");
            String id = it.optString("_id"), type = it.optString("type");
            if (st == null || !id.startsWith("tt") || (it.optBoolean("removed") && !it.optBoolean("temp"))) continue;
            long offset = st.optLong("timeOffset"), duration = st.optLong("duration");
            if (offset <= 60_000L || duration <= 0 || offset > duration * 0.95) continue;
            Entry e = new Entry();
            e.key = "stremio:" + id;
            e.title = it.optString("name");
            String video = st.optString("video_id", "");
            boolean series = type.equals("series");
            String[] se = video.split(":");
            long left = Math.max(1, (duration - offset) / 60_000L);
            String remaining = left >= 60 ? (left / 60) + Ui.tr(" h ", "h ") + (left % 60) + Ui.tr(" min restantes", "m left")
                    : left + Ui.tr(" min restantes", " min left");
            e.sub = (series && se.length == 3 ? "S" + se[1] + " E" + se[2] + " · " : "") + remaining;
            e.app = "Stremio";
            e.pkg = Recs.STREMIO;
            e.link = "stremio:///detail/" + (series ? "series" : "movie") + "/" + id + "/" + (video.isEmpty() ? id : video);
            e.progress = Math.min(1, offset / (float) duration);
            e.at = parseTime(st.optString("lastWatched", it.optString("_mtime")));
            e.backdrop = backdropFor(id);
            out.add(e);
        }
        out.sort((a, b) -> Long.compare(b.at, a.at));
        return out.size() > MAX ? new ArrayList<>(out.subList(0, MAX)) : out;
    }

    private static long parseTime(String iso) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(iso.length() >= 19 ? iso.substring(0, 19) : iso).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** The title's TMDB backdrop for an IMDb id (looked up once, then remembered). */
    private String backdropFor(String imdb) {
        String saved = prefs.getString("bd:" + imdb, null);
        if (saved != null) return saved.isEmpty() ? null : saved;
        String path = "";
        try {
            JSONObject found = tmdb("/find/" + imdb + "?external_source=imdb_id");
            for (String k : new String[] {"movie_results", "tv_results", "tv_episode_results"}) {
                JSONArray a = found.optJSONArray(k);
                if (a != null && a.length() > 0 && path.isEmpty()) {
                    JSONObject o = a.getJSONObject(0);
                    path = o.optString(k.equals("tv_episode_results") ? "still_path" : "backdrop_path", "");
                    if ("null".equals(path)) path = "";
                }
            }
        } catch (Exception e) {
            return null;   // not remembered: tried again next time
        }
        prefs.edit().putString("bd:" + imdb, path).apply();
        return path.isEmpty() ? null : path;
    }

    // MARK: - Images

    /** The card's image, or null (it loads in the background and the row is told). */
    Bitmap image(Entry e) {
        if (e.backdrop == null) return null;
        synchronized (images) {
            Bitmap b = images.get(e.backdrop);
            if (b != null) return b;
            if (!loading.add(e.backdrop)) return null;
        }
        final String path = e.backdrop;
        io.execute(() -> {
            Bitmap card = null;
            try {
                File f = new File(new File(context.getCacheDir(), "continue"), path.replace("/", ""));
                if (!f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.getParentFile().mkdirs();
                    download("https://image.tmdb.org/t/p/w780" + path, f);
                }
                Bitmap src = BitmapFactory.decodeFile(f.getPath());
                if (src != null) card = card(src);
            } catch (Exception | OutOfMemoryError ex) {
                AppLog.w("continue image: " + ex.getMessage());
            }
            synchronized (images) {
                loading.remove(path);
                if (card != null) {
                    images.put(path, card);
                    while (images.size() > 12) images.remove(images.keySet().iterator().next());
                }
            }
            if (card != null) changed();
        });
        return null;
    }

    /** Center-cropped to the card, with a dark fade at the bottom for the title (painted once here). */
    private static Bitmap card(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(IMG_W, IMG_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float scale = Math.max((float) IMG_W / src.getWidth(), (float) IMG_H / src.getHeight());
        float sw = src.getWidth() * scale, sh = src.getHeight() * scale;
        c.drawBitmap(src, null, new RectF((IMG_W - sw) / 2, (IMG_H - sh) / 2, (IMG_W + sw) / 2, (IMG_H + sh) / 2),
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        src.recycle();
        Paint fade = new Paint(Paint.DITHER_FLAG);
        fade.setShader(new LinearGradient(0, 0, 0, IMG_H, new int[] {0x00000000, 0x00000000, 0x99000000, 0xD9000000},
                new float[] {0, 0.35f, 0.72f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, IMG_W, IMG_H, fade);
        return Backdrop.gpu(out);
    }

    // MARK: - Network

    private static JSONObject tmdb(String path) throws Exception {
        String key = Config.TMDB_KEY.trim();
        if (key.isEmpty()) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");
        String url = "https://api.themoviedb.org/3" + path
                + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            if (c.getResponseCode() != 200) throw new IOException("TMDB returned HTTP " + c.getResponseCode());
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private static String post(String url, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(json.getBytes("UTF-8"));
            }
            if (c.getResponseCode() != 200) throw new IOException("Stremio returned HTTP " + c.getResponseCode());
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File f) throws IOException {
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString("UTF-8");
    }

    // MARK: - Saving

    private static JSONArray toJson(List<Entry> list) {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : list) {
                a.put(new JSONObject().put("key", e.key).put("title", e.title).put("sub", e.sub).put("app", e.app)
                        .put("pkg", e.pkg).put("link", e.link == null ? "" : e.link).put("backdrop", e.backdrop == null ? "" : e.backdrop)
                        .put("progress", e.progress).put("at", e.at));
            }
        } catch (Exception ignored) {
        }
        return a;
    }

    private static Entry entry(JSONObject o) {
        Entry e = new Entry();
        e.key = o.optString("key");
        e.title = o.optString("title");
        e.sub = o.optString("sub");
        e.app = o.optString("app");
        e.pkg = o.optString("pkg");
        e.link = o.optString("link").isEmpty() ? null : o.optString("link");
        e.backdrop = o.optString("backdrop").isEmpty() ? null : o.optString("backdrop");
        e.progress = (float) o.optDouble("progress", -1);
        e.at = o.optLong("at");
        return e;
    }
}
