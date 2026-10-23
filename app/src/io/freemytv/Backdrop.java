package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The home screen background made from the recommendation's image, built once in the background:
 *  - the photo cropped to 16:9 at the TV's UI size (1920×1080; 1280×720 on TVs that draw their UI at 720p, like TCL's
 *    1 GB models, where every bitmap here is 2/3 as wide), with the dark scrims already applied (on the left,
 *    where the hero text sits; at the bottom, behind the shelf; a faint one at the top, behind the buttons); the left
 *    scrim gets stronger when the photo is bright there;
 *  - the frosted version (192×108, heavily blurred) of the same image: the background of the frosted glass and of the
 *    bottom drawer;
 *  - the Liquid Glass version (480×270, lightly blurred): the photo shows through the shelf and the cards;
 *  - with forLight: the cards (the photo without scrims, cropped wide and tall, already at the focused card size) and
 *    the frosted and glass versions blended with white (by day) and with near-black (at night).
 * Only what is needed gets built (with forLight, no full photo: the cards are lightweight, so all 9 for the three tabs
 * fit in memory, ready); the other fields stay null.
 * The final image lives only in GPU memory (HARDWARE): drawing it costs one texture, and the process doesn't hold 8 MB.
 */
final class Backdrop {
    /** The UI's size against 1920×1080 (at most 1): nothing is built bigger than the screen can show. */
    private static final float UI_SCALE = uiScale();
    static final int W = ui(1920), H = ui(1080), FW = 192, FH = 108, GW = 480, GH = 270;
    /** The cards at their focused size (1.05×): the wide one and the two tall ones. */
    static final int WIDE_W = ui(1092), WIDE_H = ui(504), TALL_W = ui(336), TALL_H = ui(504);

    private static float uiScale() {
        android.util.DisplayMetrics m = android.content.res.Resources.getSystem().getDisplayMetrics();
        int longest = Math.max(m.widthPixels, m.heightPixels);
        return longest > 0 ? Math.min(1f, longest / 1920f) : 1f;
    }

    private static int ui(int px) {
        return Math.round(px * UI_SCALE);
    }
    final Bitmap image;
    final int[] frost;      // heavily blurred (frosted glass, bottom drawer)
    final int[] glass;      // lightly blurred (Liquid Glass: the photo shows through the glass)
    final Bitmap wide;                  // the photo on the wide card
    Bitmap tall;                        // on the tall cards: the official poster when there is one, else the photo
    boolean poster;                     // tall is the poster (its title is on it: the card draws none)
    Bitmap logo;                        // the title's logo, drawn on the wide card instead of its name, or null
    final int[] light, lightGlass;      // by day: the frosted and glass versions, lightened
    final Bitmap lightImage;            // by day: the background (the lightened frosted version, scaled up on screen)
    final int[] nightFrost, nightGlass; // at night: frosted (the bottom drawer's frosted glass) and glass, darkened
    final Bitmap nightImage;            // at night: the background (the darkened frosted version)
    /** A wallpaper (Wallpapers): drawn as it is instead of lightImage, dimmed at night. Null for the picks' photos. */
    Bitmap sharp;
    /** How dark the wallpaper is where the clock sits (0 = light, 1 = dark): dark ones get white text and smoked glass. */
    float darkness;
    /** A photo wallpaper (it has its own scrims, so it dims less at night). */
    boolean photo;

    private Backdrop(Bitmap image, int[] frost, int[] glass, Bitmap wide, Bitmap tall, int[] light, int[] lightGlass,
                     int[] night, int[] nightGlass) {
        this.image = image;
        this.frost = frost;
        this.glass = glass;
        this.wide = wide;
        this.tall = tall;
        this.light = light;
        this.lightGlass = lightGlass;
        this.nightFrost = night;
        this.nightGlass = nightGlass;
        lightImage = light == null ? null : Bitmap.createBitmap(light, FW, FH, Bitmap.Config.ARGB_8888);
        nightImage = night == null ? null : Bitmap.createBitmap(night, FW, FH, Bitmap.Config.ARGB_8888);
    }

    static Backdrop make(Bitmap src, boolean forLight) {
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        float scale = Math.max((float) W / src.getWidth(), (float) H / src.getHeight());
        float sw = src.getWidth() * scale, sh = src.getHeight() * scale;
        c.drawBitmap(src, null, new RectF((W - sw) / 2, (H - sh) / 2, (W + sw) / 2, (H + sh) / 2), photo);
        src.recycle();
        if (forLight) {
            // Light design: everything comes from the photo without scrims.
            Bitmap wide = card(out, WIDE_W, WIDE_H), tall = card(out, TALL_W, TALL_H);
            int[] frost = reduce(out, FW, FH, 3, 3);
            int[] light = whiten(frost.clone(), 0.82f);
            int[] glass = reduce(out, GW, GH, 2, 2);
            int[] lightGlass = whiten(glass.clone(), 0.74f);
            int[] night = darken(frost.clone(), 0.76f);
            int[] nightGlass = darken(glass, 0.56f);
            out.recycle();
            return new Backdrop(null, frost, null, wide, tall, light, lightGlass, night, nightGlass);
        }

        float boost = Math.max(0, Math.min(0.3f, (luminance(out, 0, 0.22f, 0.5f, 0.62f) - 0.22f) * 1.1f));
        Paint scrim = new Paint(Paint.DITHER_FLAG);
        scrim.setShader(new LinearGradient(0, 0, W * 0.68f, 0,
                new int[] {black(0.66f + boost), black(0.52f + boost * 0.8f), black(0.26f + boost * 0.4f), black(0.07f), 0},
                new float[] {0, 0.25f, 0.55f, 0.8f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, scrim);
        // At the bottom, a faint scrim: the photo must show through the glass shelf (the glass provides the contrast).
        scrim.setShader(new LinearGradient(0, H, 0, H * 0.4f,
                new int[] {black(0.38f), black(0.3f), black(0.18f), black(0.05f), 0},
                new float[] {0, 0.25f, 0.55f, 0.8f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, H * 0.4f, W, H, scrim);
        // Behind the prices (above the shelf, on the right), a localized scrim for the small text.
        scrim.setShader(new android.graphics.RadialGradient(W * 0.8f, H * 0.65f, W * 0.24f,
                new int[] {black(0.42f), black(0.18f), 0}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1, 0.32f, W * 0.8f, H * 0.65f);
        c.drawCircle(W * 0.8f, H * 0.65f, W * 0.24f, scrim);
        c.restore();
        scrim.setShader(new LinearGradient(0, 0, 0, H * 0.24f, new int[] {black(0.34f), black(0.1f), 0},
                new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H * 0.24f, scrim);

        int[] frost = reduce(out, FW, FH, 3, 3);
        int[] glass = reduce(out, GW, GH, 2, 2);
        return new Backdrop(gpu(out), frost, glass, null, null, null, null, null, null);
    }

    /**
     * A wallpaper as the background: `src` is drawn as it is (photos get soft scrims for the clock and the shelf), and
     * its frosted version is what the glass shows. Built once; the image lives in GPU memory.
     */
    static Backdrop wallpaper(Bitmap src, boolean photo) {
        Bitmap out = src.isMutable() ? src : src.copy(Bitmap.Config.ARGB_8888, true);
        if (out != src) src.recycle();
        int w = out.getWidth(), h = out.getHeight();
        float lum = luminance(out, 0, 0.22f, 0.5f, 0.62f);
        // A bright photo (a white car on a gray studio floor) keeps the light look: dark text, white glass, no scrim.
        boolean lightPhoto = photo && lum > 0.6f;
        if (photo && !lightPhoto) {
            // White text on any photo: a gentle scrim on the left (clock) and at the bottom (shelf).
            Canvas c = new Canvas(out);
            Paint scrim = new Paint(Paint.DITHER_FLAG);
            scrim.setShader(new LinearGradient(0, 0, w * 0.62f, 0, new int[] {black(0.58f), black(0.34f), 0},
                    new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, scrim);
            scrim.setShader(new LinearGradient(0, h, 0, h * 0.55f, new int[] {black(0.3f), 0},
                    null, Shader.TileMode.CLAMP));
            c.drawRect(0, h * 0.55f, w, h, scrim);
        }
        int[] frost = reduce(out, FW, FH, 3, 3);
        int[] glass = reduce(out, GW, GH, 2, 2);
        int[] lightGlass = whiten(glass.clone(), 0.3f);
        int[] night = darken(frost.clone(), 0.5f);
        int[] nightGlass = darken(glass, 0.56f);
        Backdrop b = new Backdrop(null, frost, null, null, null, frost.clone(), lightGlass, night, nightGlass);
        b.sharp = gpu(out);
        b.photo = photo;
        b.darkness = photo ? (lightPhoto ? 0 : 1) : lum < 0.5f ? 1 : 0;
        return b;
    }

    /** How many times smaller the screensaver photo's frosted version is (the glass math uses the same number). */
    static final int PHOTO_FROST = 6;

    /**
     * Screensaver: the whole original from disk at w × h (center-cropped to that aspect ratio), filtered down, plus the
     * frosted version (1/6, blurred) for the glass clock. `light` receives the luminance (0 to 1) where the caption
     * (bottom left) and the clock (top right) sit, for the scrims. Returns {photo (mutable), frosted}.
     */
    static Bitmap[] photo(java.io.File f, int w, int h, float[] light) throws java.io.IOException {
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new java.io.IOException("unreadable image");
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= w && bounds.outHeight / (opts.inSampleSize * 2) >= h) opts.inSampleSize *= 2;
        Bitmap src = android.graphics.BitmapFactory.decodeFile(f.getPath(), opts);
        if (src == null) throw new java.io.IOException("unreadable image");
        float ratio = (float) w / h;
        int cw = Math.min(src.getWidth(), Math.round(src.getHeight() * ratio));
        int ch = Math.min(src.getHeight(), Math.round(cw / ratio));
        Bitmap b = Bitmap.createBitmap(src, (src.getWidth() - cw) / 2, (src.getHeight() - ch) / 2, cw, ch);
        if (b != src) src.recycle();
        while (b.getWidth() / 2 >= w) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            b.recycle();
            b = half;
        }
        Bitmap out = b.getWidth() == w && b.getHeight() == h ? b : Bitmap.createScaledBitmap(b, w, h, true);
        if (out != b) b.recycle();
        if (light != null && light.length >= 2) {
            light[0] = luminance(out, 0, 0.6f, 0.5f, 0.95f);
            light[1] = luminance(out, 0.62f, 0.04f, 0.96f, 0.36f);
        }
        int fw = w / PHOTO_FROST, fh = h / PHOTO_FROST;
        Bitmap frost = Bitmap.createBitmap(reduce(out, fw, fh, 3, 2), fw, fh, Bitmap.Config.ARGB_8888);
        if (!out.isMutable()) {
            Bitmap copy = out.copy(Bitmap.Config.ARGB_8888, true);
            out.recycle();
            out = copy;
        }
        return new Bitmap[] {out, frost};
    }

    /** The logo's largest size on the wide card (at the focused card's size). */
    static final int LOGO_W = ui(460), LOGO_H = ui(150);

    /** Takes this logo if it reads well on the card (bright, see-through); returns whether it did. */
    boolean tryLogo(Bitmap logoArt) {
        if (logoArt != null) logo = logo(logoArt);
        return logo != null;
    }

    /** Takes the poster for the tall cards and the logo for the wide one (either can be null). */
    void art(Bitmap posterArt, Bitmap logoArt) {
        if (posterArt != null) {
            Bitmap old = tall;
            tall = cover(posterArt, TALL_W, TALL_H);
            poster = true;
            if (old != null) old.recycle();
        }
        if (logoArt != null) logo = logo(logoArt);
    }

    /** The poster filling the card (both are 2:3), without the fade: its own title is part of it. */
    private static Bitmap cover(Bitmap src, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        float sw = src.getWidth() * scale, sh = src.getHeight() * scale;
        new Canvas(out).drawBitmap(src, null, new RectF((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2),
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        src.recycle();
        return gpu(out);
    }

    /**
     * The logo trimmed to what it draws and fitted into LOGO_W × LOGO_H. A dark logo turns white (Apple TV does the
     * same): over the card's dark fade it wouldn't show.
     */
    private static Bitmap logo(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        double bright = 0;
        int solid = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = px[y * w + x];
                if ((p >>> 24) < 24) continue;
                if (x < x0) x0 = x;
                if (x > x1) x1 = x;
                if (y < y0) y0 = y;
                if (y > y1) y1 = y;
                if ((p >>> 24) > 128) {
                    bright += Math.max((p >> 16) & 255, Math.max((p >> 8) & 255, p & 255)) / 255.0;
                    solid++;
                }
            }
        }
        // Nothing drawn, a box with no see-through parts (some uploads are a picture, not a logo), or too dark to read
        // on the card's dark fade (a bronze or black version; painting those white made blobs): the next one, or the name.
        if (x1 < x0 || y1 < y0 || solid > 0.9 * (x1 - x0 + 1) * (y1 - y0 + 1) || bright / Math.max(1, solid) < 0.6) {
            src.recycle();
            return null;
        }
        int cw = x1 - x0 + 1, ch = y1 - y0 + 1;
        float scale = Math.min(1, Math.min((float) LOGO_W / cw, (float) LOGO_H / ch));
        int ow = Math.max(1, Math.round(cw * scale)), oh = Math.max(1, Math.round(ch * scale));
        Bitmap out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, new android.graphics.Rect(x0, y0, x1 + 1, y1 + 1), new RectF(0, 0, ow, oh),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        return gpu(out);
    }

    /** The center of the photo at the card's aspect ratio, filtered down (halving while possible) to the exact size. */
    private static Bitmap card(Bitmap full, int w, int h) {
        float ratio = (float) w / h;
        int cw = Math.min(full.getWidth(), Math.round(full.getHeight() * ratio));
        int ch = Math.min(full.getHeight(), Math.round(cw / ratio));
        Bitmap b = Bitmap.createBitmap(full, (full.getWidth() - cw) / 2, (full.getHeight() - ch) / 2, cw, ch);
        while (b.getWidth() / 2 >= w) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != full) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != full && b != out) b.recycle();
        if (!out.isMutable()) {
            Bitmap copy = out.copy(Bitmap.Config.ARGB_8888, true);
            out.recycle();
            out = copy;
        }
        // The dark fade under the card's title, painted in once here: drawn live it cost the GPU ~6 ms a frame.
        Paint fade = new Paint(Paint.DITHER_FLAG);
        fade.setShader(new LinearGradient(0, 0, 0, h, new int[] {0x00000000, 0x00000000, 0x80000000, 0xC7000000},
                new float[] {0, 0.42f, 0.74f, 1}, Shader.TileMode.CLAMP));
        new Canvas(out).drawRect(0, 0, w, h, fade);
        return gpu(out);
    }

    /** Copies it to GPU memory (cheap to draw; the process doesn't keep the pixels). */
    static Bitmap gpu(Bitmap b) {
        Bitmap g = b.copy(Bitmap.Config.HARDWARE, false);
        if (g == null) return b;
        b.recycle();
        return g;
    }

    /** Blends with a near-black (the darkest gray of Apple's dark-mode backgrounds), keeping the photo's tint. */
    private static int[] darken(int[] px, float amount) {
        float keep = 1 - amount, base = 11 * amount;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Math.round(((p >> 16) & 255) * keep + base);
            int g = Math.round(((p >> 8) & 255) * keep + base);
            int b = Math.round((p & 255) * keep + base + 2 * amount);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return px;
    }

    private static int[] whiten(int[] px, float amount) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Math.round(((p >> 16) & 255) * (1 - amount) + 255 * amount);
            int g = Math.round(((p >> 8) & 255) * (1 - amount) + 255 * amount);
            int b = Math.round((p & 255) * (1 - amount) + 255 * amount);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return px;
    }

    /** Halved at each step (a true average, no aliasing), then blurred (box passes of radius r). */
    private static int[] reduce(Bitmap full, int w, int h, int radius, int passes) {
        Bitmap b = full;
        while (b.getWidth() / 2 >= w * 1.5f) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != full) b.recycle();
            b = half;
        }
        Bitmap small = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != full) b.recycle();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        small.recycle();
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < passes; pass++) {
            box(px, tmp, w, h, radius, true);
            box(tmp, px, w, h, radius, false);
        }
        return px;
    }

    /** Moving average of radius r in one direction, repeating the edge pixels. */
    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        float k = 1f / (2 * r + 1);
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            float sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[base + Math.max(0, Math.min(n - 1, i)) * step];
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            for (int i = 0; i < n; i++) {
                out[base + i * step] = 0xFF000000 | (Math.round(sr * k) << 16) | (Math.round(sg * k) << 8) | Math.round(sb * k);
                int add = in[base + Math.min(n - 1, i + r + 1) * step], sub = in[base + Math.max(0, i - r) * step];
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    /** Average luminance (0 to 1) of a rectangle given as fractions of the image, by sampling. */
    private static float luminance(Bitmap b, float x0, float y0, float x1, float y1) {
        float sum = 0;
        int count = 0;
        for (int j = 0; j < 24; j++) {
            for (int i = 0; i < 32; i++) {
                int x = Math.round((x0 + (x1 - x0) * (i + 0.5f) / 32) * (b.getWidth() - 1));
                int y = Math.round((y0 + (y1 - y0) * (j + 0.5f) / 24) * (b.getHeight() - 1));
                int p = b.getPixel(x, y);
                sum += (0.2126f * ((p >> 16) & 255) + 0.7152f * ((p >> 8) & 255) + 0.0722f * (p & 255)) / 255f;
                count++;
            }
        }
        return sum / count;
    }

    private static int black(float a) {
        return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24;
    }
}
