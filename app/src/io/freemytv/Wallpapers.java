package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import java.io.File;

/**
 * Home screen wallpapers (Home Settings → Wallpaper): soft color fields made on the TV, in the spirit of Apple's
 * abstract wallpapers (no image files: a few blurred color blobs on a base, at 480×270, scaled up smooth), or the
 * person's own photos from the app's Wallpapers folder on the TV, each one its own choice, named after the file
 * ("03-lake-garda.jpg" → "Lake Garda"; no storage permission needed; copy them with
 * `adb push photo.jpg /sdcard/Android/data/io.freemytv/files/Wallpapers/`). Built once off the UI thread and
 * cached by choice.
 */
final class Wallpapers {
    private Wallpapers() {}

    static final String[] IDS = {"none", "dawn", "lagoon", "lavender", "sand", "dusk", "midnight"};
    /** A photo's choice: this prefix + its file name. */
    static final String PHOTO = "photo:";
    private static final int PW = Backdrop.GW, PH = Backdrop.GH;

    /** Every choice, in order: none, the color fields, then the photos by file name. */
    static java.util.List<String> ids(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>(java.util.Arrays.asList(IDS));
        for (File f : photos(c)) out.add(PHOTO + f.getName());
        return out;
    }

    static String label(String id) {
        if (id.startsWith(PHOTO)) return photoName(id.substring(PHOTO.length()));
        switch (id) {
            case "dawn": return Ui.tr("Aurora", "Dawn");
            case "lagoon": return Ui.tr("Lagoa", "Lagoon");
            case "lavender": return Ui.tr("Lavanda", "Lavender");
            case "sand": return Ui.tr("Areia", "Sand");
            case "dusk": return Ui.tr("Crepúsculo", "Dusk");
            case "midnight": return Ui.tr("Meia-noite", "Midnight");
            default: return Ui.tr("Nenhum", "None");
        }
    }

    /** {base, blob colors…} and each blob's {x, y, radius} as fractions of the width. */
    private static int[] colors(String id) {
        switch (id) {
            case "dawn": return new int[] {0xFFF6DCCD, 0xFFF4A3A0, 0xFFFBCF8A, 0xFFC9B4EE, 0xFFFFF0E4};
            case "lagoon": return new int[] {0xFFCFE6F0, 0xFF57C2D6, 0xFF7EA4F2, 0xFFB4EDD6, 0xFFEAF5FF};
            case "lavender": return new int[] {0xFFE3DCF6, 0xFFAE97EE, 0xFFF0B4D6, 0xFFA6C4F2, 0xFFF6F1FF};
            case "sand": return new int[] {0xFFEEE5D6, 0xFFE0C49A, 0xFFD6AF9C, 0xFFF3E6C4, 0xFFC6D2BC};
            case "dusk": return new int[] {0xFF1B1934, 0xFF6A3FB5, 0xFFD2557A, 0xFF2D68D2, 0xFFE9965F};
            default: return new int[] {0xFF0A111E, 0xFF1D4A86, 0xFF0D7681, 0xFF482A7C, 0xFF132E4D};   // midnight
        }
    }

    private static final float[][] BLOBS = {
        {0.18f, 0.22f, 0.55f}, {0.86f, 0.30f, 0.50f}, {0.62f, 0.95f, 0.60f}, {0.08f, 0.92f, 0.42f},
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static String building;
    /** Built wallpapers by key, most recent last: a few while picking (back and forth is instant), one otherwise. */
    private static final java.util.LinkedHashMap<String, Backdrop> CACHE = new java.util.LinkedHashMap<>(4, 0.75f, true);
    private static int keep = 1;
    /** While the picker is open, the wallpaper on screen (not saved yet); null = the saved one. */
    private static String preview;

    /** Shows `id` without saving it (null = back to the saved one). Keeps a few built while picking. */
    static void setPreview(String id) {
        preview = id;
        keep = id != null ? 3 : 1;
        trim();
    }

    private static void trim() {
        java.util.Iterator<String> it = CACHE.keySet().iterator();
        while (CACHE.size() > keep && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    /**
     * The chosen wallpaper, or null for none. The first call for a new choice starts building it and returns the
     * previous one (or null); `ready` runs on the UI thread once it's done.
     */
    static Backdrop get(Context c, Runnable ready) {
        String id = preview != null ? preview : Prefs.wallpaper();
        if (id.equals("none")) return null;
        File photo = id.startsWith(PHOTO) ? photoFile(c, id.substring(PHOTO.length())) : null;
        if (id.startsWith(PHOTO) && photo == null) return null;   // the photo was removed: plain background
        String key = photo == null ? id : "photo:" + photo.getPath() + ":" + photo.lastModified();
        if (CACHE.containsKey(key)) return CACHE.get(key);
        if (!key.equals(building)) {
            building = key;
            new Thread(() -> {
                Backdrop b = null;
                try {
                    b = photo == null ? Backdrop.wallpaper(blend(colors(id)), false)
                            : Backdrop.wallpaper(Backdrop.photo(photo, Backdrop.W, Backdrop.H, null)[0], true);
                } catch (Exception | OutOfMemoryError e) {
                    AppLog.w("wallpaper " + key + " failed: " + e);
                }
                Backdrop done = b;
                if (b != null) AppLog.w("wallpaper " + key + ": darkness " + b.darkness + (b.photo ? ", photo" : ""));
                MAIN.post(() -> {
                    if (key.equals(building)) building = null;
                    CACHE.put(key, done);
                    trim();
                    ready.run();
                });
            }, "wallpaper").start();
        }
        return null;
    }

    /** Soft color field: each blob fades out as a Gaussian, blended over the base. */
    private static Bitmap blend(int[] c) {
        int[] px = new int[PW * PH];
        float[] r = new float[BLOBS.length], g = new float[BLOBS.length], b = new float[BLOBS.length];
        for (int i = 0; i < BLOBS.length; i++) {
            r[i] = (c[i + 1] >> 16) & 255;
            g[i] = (c[i + 1] >> 8) & 255;
            b[i] = c[i + 1] & 255;
        }
        float br = (c[0] >> 16) & 255, bg = (c[0] >> 8) & 255, bb = c[0] & 255;
        for (int y = 0; y < PH; y++) {
            float v = y / (float) PW;   // both axes in widths: round blobs on a 16:9 image
            for (int x = 0; x < PW; x++) {
                float u = x / (float) PW, cr = br, cg = bg, cb = bb;
                for (int i = 0; i < BLOBS.length; i++) {
                    float dx = u - BLOBS[i][0], dy = v - BLOBS[i][1] * PH / PW, s = BLOBS[i][2];
                    float a = (float) Math.exp(-(dx * dx + dy * dy) / (s * s * 0.18f)) * 0.9f;
                    cr += (r[i] - cr) * a;
                    cg += (g[i] - cg) * a;
                    cb += (b[i] - cb) * a;
                }
                // A touch of noise breaks up banding when the image is scaled up 4×.
                float n = (((x * 73856093) ^ (y * 19349663)) & 255) / 255f - 0.5f;
                px[y * PW + x] = 0xFF000000 | (clamp(cr + n) << 16) | (clamp(cg + n) << 8) | clamp(cb + n);
            }
        }
        return Bitmap.createBitmap(px, PW, PH, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true);
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    /** "03-lake-garda.jpg" → "Lake Garda". */
    private static String photoName(String file) {
        String n = file.replaceFirst("\\.[^.]+$", "").replaceFirst("^[0-9]+[-_ ]*", "").replace('-', ' ').replace('_', ' ').trim();
        StringBuilder b = new StringBuilder();
        for (String w : n.split(" +")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.length() > 0 ? b.toString() : file;
    }

    private static File photoFile(Context c, String name) {
        for (File f : photos(c)) if (f.getName().equals(name)) return f;
        return null;
    }

    /** The images in the app's Wallpapers folder, by file name. */
    private static java.util.List<File> photos(Context c) {
        java.util.List<File> out = new java.util.ArrayList<>();
        File dir = c.getExternalFilesDir("Wallpapers");
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))) out.add(f);
        }
        java.util.Collections.sort(out, (a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }
}
