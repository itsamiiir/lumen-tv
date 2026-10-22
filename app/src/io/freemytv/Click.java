package io.freemytv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Home screen sounds (moving focus, opening, the bottom drawer), only on the home screen, never inside apps.
 * "original": a short, low "tock" the app synthesizes and writes once as a WAV in the cache. The other styles are
 * cuelume's cues (github.com/danielwh2/cuelume, MIT), rendered offline into assets/sfx/<style>/ and stored
 * uncompressed so SoundPool can read them straight from the APK. Home Settings → Sound style picks one.
 */
final class Click {
    private static final int RATE = 48_000;
    static final String[] STYLES = {"original", "press", "default", "bubble", "mech"};
    private final Context context;
    private SoundPool pool;
    private int move, open, drawerOpen, drawerClose;
    private String style;
    private long drawerAt;

    Click(Context c) {
        context = c.getApplicationContext();
        load();
    }

    static String label(String style) {
        switch (style) {
            case "press": return "Press";
            case "default": return Ui.tr("Vidro", "Glass");
            case "bubble": return Ui.tr("Bolha", "Bubble");
            case "mech": return Ui.tr("Mecânico", "Mech");
            default: return Ui.tr("Original", "Original");
        }
    }

    /** (Re)loads the sounds for the chosen style. */
    private void load() {
        release();
        style = Prefs.soundStyle();
        try {
            pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                    // The media channel (100 steps, follows the TV's volume smoothly), not "system sounds": on this
                    // TV that one has 7 steps and drops to almost nothing at low volumes.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            if (style.equals("original")) {
                move = pool.load(wav(context, "click-move.wav", 1450f, 0.009f, 0.030f), 1);
                open = pool.load(wav(context, "click-open.wav", 880f, 0.022f, 0.090f), 1);
                drawerOpen = drawerClose = 0;
            } else {
                move = asset("move");
                open = asset("open");
                drawerOpen = asset("drawerOpen");
                drawerClose = asset("drawerClose");
            }
        } catch (IOException | RuntimeException e) {
            AppLog.w("click sound unavailable: " + e.getMessage());
            release();
        }
    }

    private int asset(String name) throws IOException {
        try (android.content.res.AssetFileDescriptor fd = context.getAssets().openFd("sfx/" + style + "/" + name + ".wav")) {
            return pool.load(fd, 1);
        }
    }

    /** Ready to play, with the style the settings ask for now (it may have changed while the TV was elsewhere). */
    private boolean ready() {
        if (!Prefs.sounds()) return false;
        if (!Prefs.soundStyle().equals(style)) load();
        return pool != null;
    }

    void move() {
        // The drawer's own sound just played for this same press: no click on top of it.
        if (SystemClock.uptimeMillis() - drawerAt < 150) return;
        if (!ready()) return;
        float v = style.equals("original") ? 0.3f : 0.34f;
        pool.play(move, v, v, 1, 0, 1f);
    }

    void open() {
        if (!ready()) return;
        float v = style.equals("original") ? 0.38f : 0.42f;
        pool.play(open, v, v, 1, 0, 1f);
    }

    /** The bottom drawer opening or closing (styles from cuelume only; the original has no drawer sound). */
    void drawer(boolean opening) {
        if (!ready() || drawerOpen == 0) return;
        drawerAt = SystemClock.uptimeMillis();
        pool.play(opening ? drawerOpen : drawerClose, 0.34f, 0.34f, 1, 0, 1f);
    }

    void release() {
        if (pool != null) pool.release();
        pool = null;
    }

    /** Sine wave plus one harmonic, 1.5 ms attack (no pop) and exponential decay. */
    private static String wav(Context c, String name, float freq, float decay, float seconds) throws IOException {
        File f = new File(c.getCacheDir(), name);
        if (f.exists() && f.length() > 44) return f.getPath();
        int n = Math.round(RATE * seconds);
        ByteBuffer b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes("US-ASCII")).putInt(36 + n * 2).put("WAVE".getBytes("US-ASCII"));
        b.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes("US-ASCII")).putInt(n * 2);
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double env = Math.min(1, t / 0.0015) * Math.exp(-t / decay);
            double s = 0.75 * Math.sin(2 * Math.PI * freq * t) + 0.25 * Math.sin(2 * Math.PI * freq * 2.01 * t);
            b.putShort((short) Math.round(s * env * 0.7 * Short.MAX_VALUE));
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b.array());
        }
        return f.getPath();
    }
}
