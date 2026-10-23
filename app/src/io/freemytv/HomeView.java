package io.freemytv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the small crypto quotes
 *    (logo + price) at the right end of the tab row;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without one, a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours
 *    and quotes with the 24 h change); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener,
        Continue.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: icons in each brand's color, 48 apart, and the glass bar hugging them with 40 of
    // padding (the focused icon grows 20% without touching its edge). The icons are 16:9, the shape of the apps' TV
    // banners, so those are never stretched: up to 224 × 126, 178 × 100 with eight apps.
    // The glass spans the same edges as everything else (MARGIN to CONTENT_R); the tiles sit inside it.
    private static final float SHELF_PAD = 32, TILE_GAP = 36, DIVIDER = 40;
    private static final float TILE_RADIUS = 22;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Page 2 (the bottom drawer): the shelf rises and the page is centered (drawerShift, from layoutDrawer).
    // The shelf rises to the top edge; below it Continue Watching, the dock (inputs and shortcuts) and the weather.
    private static final float DOCK_H = 116, DOCK_PAD = 14, CARDS_H = 300, PAGE_GAP = 40;
    /** Continue Watching, right under the shelf: 16:9 cards, five across (without entries the dock takes its place). */
    private static final float CW_W = 320, CW_H = 180, CW_GAP = 40;
    /** How far the shelf rises for page 2, and Continue Watching's title baseline (both from layoutDrawer). */
    private float drawerShift = 820, cwTitleY;
    private static final int Z_CONT = 6;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, DOTS = 8,
            SMOKE_LAYER = 9;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
    private static final float L_TITLE_BASE = 142, L_TABBAR_TOP = 170, L_TABBAR_H = 68, L_CARDS_TOP = 274, L_CARD_H = 480;
    private static final float L_TALL_W = 320, L_CARD_GAP = 40, L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 115;
    private static final float L_PLATTER_TOP = 802, L_TILE_H = 126;
    private static final float L_DATE_BASE = 330, L_CLOCK_BASE = 540, L_WEATHER_BASE = 626, L_QUOTES_BASE = 694;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static final String[] TABS = {"Explorar", "Filmes", "Séries", "Stremio"};
    private static final String[] TABS_RU = {"Обзор", "Фильмы", "Сериалы", "Stremio"};
    private static final String[] TABS_EN = {"Explore", "Movies", "Shows", "Stremio"};
    private static final String[] TABS_ES = {"Explorar", "Películas", "Series", "Stremio"};
    private static final String[] TABS_DE = {"Entdecken", "Filme", "Serien", "Stremio"};
    private static final String[] TAB_KIND = {null, "movie", "tv", Recs.STREMIO_APP};
    /** The tabs come first in the top row, then the corner buttons (the Stremio tab shows only with Stremio's picks). */
    private static final int NT = 4;
    private static final int Z_TOP = 4, Z_CARDS = 5;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final Continue cont;
    private List<Continue.Entry> cwItems = new ArrayList<>();
    private final Spring[] cwFocus = new Spring[10];
    private final RectF[] cwRects = new RectF[10];
    private final RectF cwCell = new RectF();
    private final Spring cwSlide = new Spring(0.3f, 0.88f, 0);
    /**
     * The shelf on page 2: solid smoked glass instead of the live frosted one, so nothing is redrawn while the page
     * slides; back on page 1 it fades to the frosted glass once the shelf stops.
     */
    private final Spring smoke = new Spring(0.3f, 0.9f, 0);
    private final RectF dockRect = new RectF();
    private int cwIndex;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, dotsLayer;
    /** The shelf's smoked glass on page 2, over the frosted shelf: it fades by its opacity alone (nothing redrawn). */
    private final Layer smokeLayer;
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    private int widgetIndex;    // with TalkBack on, the drawer's weather (0) or quotes (1) card can take the focus
    private List<Recs.Pick> cards = new ArrayList<>();
    // 3 tabs + up to 4 corner buttons (Live TV, HDMI, Wallpaper, Settings).
    private final Spring[] topFocus = new Spring[8];
    private final RectF[] topRects = new RectF[8];
    // The suggestion row (up to 9 per tab): 3 cards show at once; the focused one is wide, the others tall. Moving past
    // either end slides the row by one and loops around. rowStart = position of the first visible card (unbounded:
    // card = cards[floorMod(position, n)]); focusSlot = which of the 3 is focused; cardRects = the 3 slots at rest.
    private final Spring[] cardFocus = new Spring[3];
    private final RectF[] cardRects = new RectF[3];
    private int rowStart, focusSlot;
    // The row moves on by itself every 10 s (any key restarts the count); the page dots show the time left.
    private static final long AUTO_MS = 10_000;
    private static final float DOTS_CY = 792;   // between the cards (bottom 754) and the shelf (top ~829)
    private long autoFrom, lastKeyAt;
    // After 2 minutes without a key the row stops moving (and the home screen goes back to doing nothing) until the
    // next key; the screensaver takes over at 10 minutes anyway.
    private static final long AUTO_IDLE_MS = 120_000;
    private boolean autoStopped;
    private final Runnable autoTick = this::autoTick;
    private boolean rowDirect;
    private final Spring rowScroll = new Spring(0.42f, 0.88f, 0), wideAt = new Spring(0.36f, 0.86f, 0);
    private final Spring[] rowSprings = {rowScroll, wideAt};
    private Paint lHeadClock, lHeadDate, lHeadWx;
    private TextPaint lOnTitle;
    private Paint lOnMeta;
    private final java.util.HashMap<String, StaticLayout> titleLayouts = new java.util.HashMap<>();
    private final java.util.HashMap<String, String> metaFits = new java.util.HashMap<>();
    private final Rect srcRect = new Rect();
    private final android.graphics.Matrix unitMatrix = new android.graphics.Matrix();
    private LinearGradient cardSheenShader, cardRimShader;
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lQuote, lTip, lDate, lClock, lWeather, lClockQuote;
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        // Apple TV-style icons: the brand's color edge to edge, the logo in one color on it.
        TILE_STYLE.put("youtube", new int[] {0xFFFF2B3A, 0xFFE0001E, 3});   // red, white logo (the triangle cut out)
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 1});   // black, white logo
        TILE_STYLE.put("spotify", new int[] {0xFF161616, 0xFF000000, 0});   // black, its green logo
        TILE_STYLE.put("stremio", new int[] {0xFF8F6BFF, 0xFF5530D9, 3});   // Stremio purple, white logo (triangle cut out)
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;

    // Moving apps on the shelf (as on Apple TV): hold OK on a tile, then ← → move it, OK drops it, Back cancels. The
    // lifted tile wiggles; the others slide to their new places (each slot's x offset is a spring that returns to 0).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.5f;
    private boolean reordering, reorderAnim, okHeld, holdArmed;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) startHold(); };
    /** Held OK on a Continue Watching card: asking to remove it (OK removes, Back or any arrow keeps it). */
    private boolean removing, removeArmed;

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Shader photoScrim;
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen;
    private int gridIndex;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving, idle;
    private long lastNanos, resumedAt, lastStep;
    private int w, h;
    private float k = 1;
    private String clockText = "", amPmText = "", dateText = "", weatherLine = "";
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private String weatherLineLanguage = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint cwTitle, cwSub, lAmPm;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Map<Bitmap, BitmapShader> imageShaders = new WeakHashMap<>();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint logoPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF logoDst = new RectF();
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();
    private LinearGradient[] tileShaders = new LinearGradient[0];
    private LinearGradient tileGlow, cardSheen;
    private float tileShaderH, cardSheenH;
    private android.graphics.ColorFilter veilFilter;
    private float veilAmount = -1;
    private int veilColor;
    private HomeData.Weather textWeather;
    private long textPricesAt = -1, textMinute = -1;
    private int textPrefs = -1;
    private String textLanguage;
    private String weatherTitle, nowTemp, highLow, sunriseText, sunsetText, staleText;
    private final String[] hourText = new String[6], hourTempText = new String[6];
    private final String[] priceText = new String[HomeData.COINS.length], changeText = new String[HomeData.COINS.length];

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case SMOKE_LAYER: drawSmoke(c); break;
                case TILES: drawTiles(c); break;
                case DOTS: drawRowDots(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final Runnable reloadSoon = this::reload;

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // three new ones every hour
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        cont = Continue.get(a);
        for (int i = 0; i < cwFocus.length; i++) {
            cwFocus[i] = new Spring(0.28f, 0.85f, 0);
            cwRects[i] = new RectF();
        }
        cwItems = cont.items();
        click = new Click(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        smokeLayer = new Layer(a, SMOKE_LAYER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        dotsLayer = new Layer(a, DOTS);   // small, on its own: the progress line redraws only this strip
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 9 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        for (View v : new View[] {heroLayer, status, platter, tiles, drawerLayer, sheetLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, heroLayer, dotsLayer, status, platter, smokeLayer, tiles, drawerLayer, sheetLayer, toastLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("Free My TV");
        drawerLayer.setVisibility(INVISIBLE);
        smokeLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < 3; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardRects[i] = new RectF();
        }
        makeLightButtons();
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(withAllApps(HomeApps.shelf(a, false)));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        checkNight(false);
        resumedAt = SystemClock.uptimeMillis();
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        cont.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        checkNotices();
        reload();
        updatePalette();
        updatePicks();
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        idle = false;
        autoFrom = lastKeyAt = SystemClock.uptimeMillis();
        autoStopped = false;
        handler.post(autoTick);
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        cancelReorder(true);
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reloadSoon);
        handler.removeCallbacks(autoTick);
        data.removeListener(this);
        recs.removeListener(this);
        cont.removeListener(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        wake();
        cancelReorder(!animated);
        zone = Z_SHELF;
        shelfIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        if (tab != 0) selectTab(0);
        retarget(!animated);
        motionDirty = true;
    }

    /** The corner buttons, with their names in the current language. */
    private void makeLightButtons() {
        lightButtons.clear();
        lightButtons.add(HomeApps.action(HomeApps.LIVE_TV, "live", Ui.tr("TV aberta", "Live TV"), "live_tv"));
        lightButtons.add(HomeApps.action(HomeApps.INPUTS, "inputs", Ui.tr("Entradas HDMI", "HDMI Inputs"), "hdmi"));
        lightButtons.add(HomeApps.action(HomeApps.SETTINGS, "settings", Ui.tr("Configurações", "Settings"), "settings"));
    }

    private static String[] tabs() {
        return Ui.ru ? TABS_RU : Ui.es ? TABS_ES : Ui.de ? TABS_DE : Ui.en ? TABS_EN : TABS;
    }

    @Override public void onData() {
        wake();
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
        smokeLayer.invalidate();
    }

    @Override public void onContinue() {
        boolean was = cwItems.isEmpty();
        cwItems = cont.items();
        cwIndex = Math.min(cwIndex, Math.max(0, cwItems.size() - 1));
        if (cwItems.isEmpty() && zone == Z_CONT) zone = Z_DRAWER;
        if (was != cwItems.isEmpty()) layoutDrawer();
        drawerDirty = true;
        retarget(false);
        wake();
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, smokeLayer, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, Math.round(836 * kk));   // down to the row's page dots, just above the shelf
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(320 * kk));
        measure(smokeLayer, width, Math.round(320 * kk));
        measure(tiles, width, Math.round(270 * kk));
        measure(drawerLayer, width, Math.round(870 * kk));   // from Continue Watching's title down to the weather
        measure(sheetLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        measure(dotsLayer, Math.round(1000 * kk), Math.round(40 * kk));
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(750 * kk));
        place(smokeLayer, 0, Math.round(750 * kk));
        place(tiles, 0, Math.round(790 * kk));
        place(drawerLayer, 0, Math.round(200 * kk));
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
        place(dotsLayer, (width - dotsLayer.getMeasuredWidth()) / 2, Math.round(DOTS_CY * kk) - dotsLayer.getMeasuredHeight() / 2);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        hero = null;
        heroFrom = null;
        updatePicks();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 25 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 25 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        // Apple-style clock: Inter Display Semibold (the closest open font to SF Pro), fixed-width digits, the
        // colon centered on them ('case') and slightly tight, like the iPhone's lock screen.
        lClock = Ui.numbers(Ui.displaySemibold, 200 * k, L_INK);
        lClock.setFontFeatureSettings("'tnum', 'case'");
        lClock.setLetterSpacing(-0.02f);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(Ui.medium, 34 * k, L_DIM);
        lClockQuote = Ui.numbers(Ui.medium, 28 * k, L_DIM);
        // Header with suggestions: the clock in the home screen's style (smaller), the date and weather beside it.
        lHeadClock = Ui.numbers(Ui.displaySemibold, 108 * k, L_INK);
        lAmPm = Ui.text(Ui.semibold, 30 * k, L_INK);
        lHeadClock.setFontFeatureSettings("'tnum', 'case'");
        lHeadClock.setLetterSpacing(-0.02f);
        lHeadDate = Ui.text(Ui.semibold, 27 * k, L_INK);
        lHeadWx = Ui.text(Ui.medium, 25 * k, L_DIM);
        // Inside the cards: white on a soft dark fade, so they read on any picture.
        lOnTitle = new TextPaint(Ui.text(Ui.semibold, 32 * k, 0xFFFFFFFF));   // no text shadow: the fade does it, cheaper
        lOnMeta = Ui.text(Ui.medium, 23 * k, 0xD9FFFFFF);
        titleLayouts.clear();
        metaFits.clear();
        cardSheenShader = new LinearGradient(-0.5f, -0.5f, 0, 0.5f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        cardRimShader = new LinearGradient(0, -0.5f, 0, 0.5f, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
        lightGradient = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        cwTitle = Ui.text(Ui.semibold, 23 * k, 0xFFFFFFFF);
        cwSub = Ui.text(Ui.medium, 19 * k, 0xCCFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        if (reorderAnim) tilesMoving |= stepReorder(dt, up);
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        boolean rowMoving = step(rowSprings, dt);
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt) | rowMoving;
        // While the row slides, the hero is redrawn every frame anyway: drawing it straight to the screen (no cached
        // layer) saves the GPU a full pass, which kept slides just over the 16.7 ms budget (34 ms frames).
        if (rowMoving != rowDirect && !Ui.LEGACY) {
            rowDirect = rowMoving;
            heroLayer.setLayerType(rowMoving ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        }
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        // Back home: once the shelf has landed (its frosted glass redrawn there, under the smoke), the smoke fades.
        if (!drawerOpen && !drawerMoving && !drawerWasMoving) smoke.target = 0;
        boolean smokeMoving = smoke.step(dt);
        drawerItemsMoving |= step(cwFocus, dt) | cwSlide.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);

        if (drawerMoving || sheetMoving || smokeMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading) {
            heroLayer.invalidate();
            dotsLayer.invalidate();
        }
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (drawerMoving || bgFading) sky.invalidate();
        // The shelf glass follows the drawer as it slides (it reads the background where it really is). With shaders
        // that cost ~50 ms a frame, so there it's redrawn once the drawer stops; the flat glass is cheap every frame.
        if (bgFading || (drawerMoving && scene.flat() && smoke.value < 0.001f) || (drawerWasMoving && !drawerMoving)) {
            platter.invalidate();
        }
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
            heroLayer.invalidate();   // the clock: big one without suggestions, header one with them
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || smokeMoving || sheetMoving || toastFading || heroFading || bgFading || nightMoving || sheetOpen || sheet.value > 0.001f
                || drawerOpen || drawer.value > 0.001f || toast != null || !toasts.isEmpty();
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, 1000);
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = drawerShift;
        float lift = -d * shift;
        show(heroLayer, top, lift);
        show(dotsLayer, top, lift);
        show(status, top, lift);
        show(platter, home, lift);
        show(smokeLayer, home * Ui.clamp(smoke.value), lift);
        show(tiles, home, lift);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        amPmText = Ui.amPm(now);
        dateText = Ui.date(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        // The cross-fade needs frames from its first moment: idle, the loop only ticks once a second, and a fade
        // starting then (the row's delayed background, a wallpaper finishing) jumped straight to its end.
        wake();
        if ((b != null ? b.darkness : 0) != (bgTo != null ? bgTo.darkness : 0)
                || (b != null && b.photo) != (bgTo != null && bgTo.photo)) {
            // Light ⇄ dark wallpaper: the text and glass colors change, so the cached layers are redrawn.
            heroDirty = statusDirty = tilesDirty = drawerDirty = true;
            post(this::invalidateAll);
        }
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    private final int[] neutralFade = new int[Backdrop.FW * Backdrop.FH];

    /** The plain background's gray as a frost image (to fade the glass from or to it). */
    private int[] neutralFrost() {
        java.util.Arrays.fill(neutralFade, blend(0xFFEBEBF0, 0xFF141416, night));
        return neutralFade;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
        smokeLayer.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        // A dark wallpaper keeps the night look's white text and smoked glass by day too.
        return Math.max(night, bgTo != null ? bgTo.darkness : 0);
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        lDate.setColor(tone(L_DIM, 0xFF98989D));
        lClock.setColor(tone(L_INK, WHITE));
        lWeather.setColor(tone(L_DIM, 0xFF98989D));
        lClockQuote.setColor(tone(L_DIM, 0xFF98989D));
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
        if (lHeadClock != null) {
            lHeadClock.setColor(tone(L_INK, WHITE));
            lHeadDate.setColor(tone(L_INK, 0xF2FFFFFF));
            lHeadWx.setColor(tone(L_DIM, 0x9EFFFFFF));
        }
        // White text on a photo: a soft shadow under the clock, date and weather (like the iPhone's lock screen),
        // so they read on any part of the picture. Drawn once into the cached layer.
        boolean shade = bgTo != null && bgTo.photo && dark() > 0.5f;
        if (shade) {
            // The gray of the date and weather fades into a photo: near-white instead.
            lDate.setColor(0xE0FFFFFF);
            lWeather.setColor(0xE0FFFFFF);
            if (lHeadDate != null) {
                lHeadDate.setColor(0xF2FFFFFF);
                lHeadWx.setColor(0xBFFFFFFF);
            }
        }
        if (shade != textShaded) {
            textShaded = shade;
            for (Paint p : new Paint[] {lClock, lDate, lWeather, lHeadClock, lHeadDate, lHeadWx, lTab, lTabOn}) {
                if (p == null) continue;
                if (shade) p.setShadowLayer(p == lClock || p == lHeadClock ? 24 * k : 12 * k, 0, 2 * k, 0x73000000);
                else p.clearShadowLayer();
            }
        }
    }

    private boolean textShaded;

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (scene.flat()) {
            // The shelf's glass shows the blurred background: it cross-fades in step with the background itself
            // (before, it kept the old image for the whole fade and swapped at the end: a visible flicker). From or to
            // the plain background, its own gray. ~20k pixels a frame: cheap.
            int[] from = bgFrom != null && bgFrom.frost != null ? frostOf(bgFrom) : neutralFrost();
            int[] to = bgTo != null && bgTo.frost != null ? frostOf(bgTo) : neutralFrost();
            for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
            scene.setFrost(frostMix);
        } else if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toast != null) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        amount = Math.round(amount * 255) / 255f;
        if (amount == veilAmount && color == veilColor) return veilFilter;
        veilAmount = amount;
        veilColor = color;
        float keep = 1 - amount;
        return veilFilter = new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        tileShaders = new LinearGradient[0];
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
        smokeLayer.invalidate();
    }

    private void layoutShelf() {
        int n = shelf.size();
        float pad = SHELF_PAD * k, gap = TILE_GAP * k;
        // The glass always spans the content edges (aligned with the cards, the drawer rows and the weather card);
        // All Apps, last, sits after a thin divider, like the Dock's.
        boolean divider = n > 1 && shelf.get(n - 1).kind == HomeApps.ALL_APPS;
        float div = divider ? DIVIDER * k : 0;
        float glassL = MARGIN * k, glassR = CONTENT_R * k, inner = glassR - glassL - 2 * pad;
        // Tiles in the TV banners' own 16:9, so an app's art is never stretched; never taller than L_TILE_H. The glass
        // keeps its bottom edge, so with more apps it only gets lower.
        float tw = Math.min(L_TILE_H * 16f / 9 * k, (inner - Math.max(0, n - 1) * gap - div) / Math.max(1, n));
        float th = tw * 9f / 16;
        float width = n * tw + Math.max(0, n - 1) * gap + div, left = glassL + pad + (inner - width) / 2f;
        float top = (L_PLATTER_TOP + L_TILE_H) * k - th;
        platterRect.set(glassL, top, glassR, top + th + 2 * pad);
        float x = left, y = top + pad;
        dividerX = -1;
        for (int i = 0; i < n; i++) {
            if (divider && i == n - 1) {
                dividerX = x - gap / 2 + div / 2;
                x += div;
            }
            tileRects[i].set(x, y, x + tw, y + th);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    private float dividerX = -1;

    /** The shelf's apps with All Apps at the end (it opens the full list, like the Dock's Applications folder). */
    private static List<HomeApps.Item> withAllApps(List<HomeApps.Item> apps) {
        List<HomeApps.Item> out = new ArrayList<>(apps);
        out.add(HomeApps.action(HomeApps.ALL_APPS, "all", Ui.tr("Todos os Apps", "All Apps"), "apps"));
        return out;
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty()) return;
        float radius = (TILE_RADIUS + SHELF_PAD) * k;
        tmp.set(platterRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(tone(0x1F000000, 0x52000000));
        shadows.drawCached(c, tmp, radius, SHADOW_BLUR * k, shadow);   // pre-blurred: redrawn every frame of a drawer slide
        // White glass by day; smoked glass at night (on page 2, smokeLayer covers it).
        scene.liquid(c, platterRect, radius, tone(0xFFFFFFFF, SMOKE), tone(0.42f, 0.12f), tone(0.03f, 0.08f),
                tone(30, 34) * k, tone(22, 28) * k, tone(0.65f, 0.95f), 1);
        if (dividerX > 0 && tileRects.length > 0) {
            // The Dock's separator before All Apps: a short hairline.
            RectF t = tileRects[tileRects.length - 1];
            fill.setShader(null);
            fill.setColor(tone(0x33000000, 0x38FFFFFF));
            float hw = 1.2f * k, inset = t.height() * 0.18f;
            c.drawRoundRect(dividerX - hw, t.top + inset, dividerX + hw, t.bottom - inset, hw, hw, fill);
        }
    }

    /** The shelf's solid smoked glass for page 2, with the shelf's hairline before All Apps. */
    private void drawSmoke(Canvas c) {
        if (shelf.isEmpty()) return;
        float radius = (TILE_RADIUS + SHELF_PAD) * k;
        fill.setShader(null);
        fill.setColor(Ui.alpha(tone(0xFFEDEDF0, 0xFF1C1C1F), 0.94f));
        c.drawRoundRect(platterRect, radius, radius, fill);
        rim.setShader(null);
        rim.setColor(Ui.alpha(tone(0xFF000000, 0xFFFFFFFF), tone(0.06f, 0.12f)));
        rim.setStrokeWidth(1.2f * k);
        c.drawRoundRect(platterRect, radius, radius, rim);
        if (dividerX > 0 && tileRects.length > 0) {
            RectF t = tileRects[tileRects.length - 1];
            fill.setColor(tone(0x33000000, 0x38FFFFFF));
            float hw = 1.2f * k, inset = t.height() * 0.18f;
            c.drawRoundRect(dividerX - hw, t.top + inset, dividerX + hw, t.bottom - inset, hw, hw, fill);
        }
    }

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    private Quotes quotes;

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar. */
    private void drawQuotes(Canvas c) {
        boolean clock = !picksOn();
        float right = platterRect.isEmpty() ? CONTENT_R * k : platterRect.right - SHELF_PAD * k;
        applyTone();
        Paint text = clock ? lClockQuote : lQuote;
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        float fade = 1;
        for (int i = NT; i < NT + lightButtons.size() && !clock; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        int n = HomeData.coinCount();
        if (n == 0) { text.setColor(textColor); return; }
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        int[] coins = HomeData.active();
        Quotes q = quotes;
        if (q == null || !q.matches(coins, data.prices, text.getTextSize())) {
            q = quotes = new Quotes(coins, data.prices, text);
        }
        // On the tab row, at the right.
        float base = clock ? L_QUOTES_BASE * k : (L_TABBAR_TOP + L_TABBAR_H / 2) * k + q.halfCap;
        float cap = q.cap, gap = (clock ? 8 : 6) * k, spacing = (clock ? 32 : 24) * k;
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) total += q.glyphs[i] + gap + q.widths[i];
        float x = clock ? MARGIN * k : right - total;
        int coinColor = clock ? text.getColor() : Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade);
        for (int i = 0; i < n; i++) {
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap, coinColor);
            x += q.glyphs[i] + gap;
            c.drawText(q.values[i], x, base, text);
            x += q.widths[i] + spacing;
        }
        text.setColor(textColor);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (stale.isEmpty()) return;
        if (clock) c.drawText(stale, x - spacing + 24 * k + staleQuote.measureText(stale), base, staleQuote);
        else c.drawText(stale, right - total - 24 * k, base, staleQuote);
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                float angle = i == liftIndex ? wiggle * moveLift.value : 0;
                if (dx == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        float l = i == liftIndex ? Math.max(0, moveLift.value) : 0;
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f + LIFT_GROW * l;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f + 6 * l) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc + 0.14f * Ui.clamp(l)));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            if (tileShaders.length != shelf.size() || tileShaderH != bh) {
                tileShaders = new LinearGradient[shelf.size()];
                tileShaderH = bh;
                tileGlow = new LinearGradient(0, -bh / 2, 0, 0, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            if (tileShaders[i] == null) {
                tileShaders[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, style[0], style[1], Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(tileShaders[i]);
            tileFill.setAlpha(255);
            c.drawRoundRect(tmp, r0, r0, tileFill);
            if (fc > 0.01f) {
                tileFill.setShader(tileGlow);
                tileFill.setAlpha(Math.round(255 * fc));
                c.drawRoundRect(tmp, r0, r0, tileFill);
                tileFill.setAlpha(255);
            }
            tileFill.setShader(null);
            c.restore();
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else if (it.kind == HomeApps.ALL_APPS) {
            // All Apps: a dark tile like the black app tiles, with the grid symbol and its name.
            tileFill.setShader(null);
            tileFill.setColor(blend(0xFF1C1C1F, 0xFF2A2A2E, fc));
            c.drawRoundRect(cell, r, r, tileFill);
            float ih = bh * s;
            Symbols.draw(getContext(), c, "apps", cx, cy - ih * 0.12f, ih * 0.3f, 0xF2FFFFFF);
            if (allAppsLabel == null) {
                allAppsLabel = Ui.text(Ui.medium, 21 * k, 0xD9FFFFFF);
                allAppsLabel.setTextAlign(Paint.Align.CENTER);
            }
            allAppsLabel.setTextSize(Math.min(21 * k, ih * 0.17f));
            c.drawText(it.label, cx, cy + ih * 0.3f, allAppsLabel);
        } else {
            if (it.tile != null) roundImage(c, it.tile, cell, r);
        }
        if (fc > 0.01f && l < 0.01f) {
            // The focus ring: a faint hairline just outside the tile, barely there (the lift and shadow lead).
            float gapOut = 4 * k * s, rr = r + gapOut;
            tmp.set(cell.left - gapOut, cell.top - gapOut, cell.right + gapOut, cell.bottom + gapOut);
            focusRing.setStrokeWidth(1.2f * k);
            focusRing.setColor(Ui.alpha(0xFFFFFFFF, 0.3f * fc));
            c.drawRoundRect(tmp, rr, rr, focusRing);
        }
    }

    private final Paint focusRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    { focusRing.setStyle(Paint.Style.STROKE); }
    private Paint allAppsLabel;
    private final Paint tabRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    { tabRim.setStyle(Paint.Style.STROKE); }

    /**
     * `image` stretched over `cell`, with corners of radius `r`. Android 12+ clips to the rounded shape; on Android 9 to
     * 11 that anti-aliased clip costs the GPU a mask on every frame of a focus animation (measured on a Mi Box 3: most of
     * a 49 ms frame), so there the image is the shader of a rounded rectangle instead, which looks the same.
     */
    private void roundImage(Canvas c, Bitmap image, RectF cell, float r) {
        if (!Ui.LEGACY) {
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(image, null, cell, bitmapPaint);
            c.restore();
            return;
        }
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        imageMatrix.setScale(cell.width() / image.getWidth(), cell.height() / image.getHeight());
        imageMatrix.postTranslate(cell.left, cell.top);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size and to the resting one: focused or at
     * rest it is drawn pixel for pixel, without resampling (on a 720p UI, shrinking at draw time made it look jagged).
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        boolean rest = s < 1 + FOCUS_GROW / 2;
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null, rest);
        if (logo == null) return;
        if (mode == 3) logo = onBrand(logo);
        float scale = rest ? s : s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (rest ? Math.abs(scale - 1) < 0.002f : scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    /**
     * The logo in white for a brand-colored tile, with what sits inside its colored badge cut out (YouTube: the white
     * play triangle shows the red tile through it, the wordmark turns white). Made once per size.
     */
    private Bitmap onBrand(Bitmap src) {
        String key = "brand@" + System.identityHashCode(src);
        Bitmap done = crispLogos.get(key);
        if (done != null) return done;
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int x0 = w, y0 = h, x1 = -1, y1 = -1;   // the colored badge's box
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = px[y * w + x], a = p >>> 24, r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                int hi = Math.max(r, Math.max(g, b)), lo = Math.min(r, Math.min(g, b));
                if (a > 128 && hi > 110 && hi - lo > 90) {   // a strongly colored pixel: YouTube's red, Stremio's purple
                    x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
                }
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x, p = px[i], a = p >>> 24;
                int lo = Math.min((p >> 16) & 255, Math.min((p >> 8) & 255, p & 255));
                boolean inBadge = x >= x0 && x <= x1 && y >= y0 && y <= y1;
                // Inside the badge, light pixels become holes (soft at the edges); everything else is white.
                float keep = inBadge ? 1 - Ui.clamp((lo - 110) / 120f) : 1;
                px[i] = (Math.round(a * keep) << 24) | 0xFFFFFF;
            }
        }
        Bitmap out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        crispLogos.put(key, out);
        return out;
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark, boolean rest) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width()) + (rest ? "@rest" : "");
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        if (rest) scale /= 1 + FOCUS_GROW;
        Bitmap out = downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    /** Halves the size while it can (a 2×2 average, no aliasing), then finishes at the exact size. */
    private static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts());
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        // Centered: the risen shelf, Continue Watching, the dock and the weather as one block, equal room above and
        // below it (and the content edges' equal room left and right).
        boolean cards = Prefs.weather() || (Prefs.quotes() && HomeData.active().length > 0);
        float gap = PAGE_GAP * k, titleRoom = 40 * k;
        float block = platterRect.height() + gap + (cwItems.isEmpty() ? 0 : titleRoom + CW_H * k + gap) + DOCK_H * k
                + (cards ? gap + CARDS_H * k : 0);
        float pageTop = Math.max(24 * k, (h - block) / 2);
        drawerShift = platterRect.top - pageTop;
        motionDirty = true;
        float y = pageTop + platterRect.height() + gap;
        if (!cwItems.isEmpty()) {
            cwTitleY = y + 26 * k;
            y += titleRoom;
        }
        for (int i = 0; i < cwRects.length; i++) {
            float cx = left + i * (CW_W + CW_GAP) * k;
            cwRects[i].set(cx, y, cx + CW_W * k, y + CW_H * k);
        }
        if (!cwItems.isEmpty()) y += CW_H * k + gap;
        // The dock: inputs, a hairline, shortcuts, in one row like the app shelf.
        float dockTop = y;
        dockRect.set(left, dockTop, right, dockTop + DOCK_H * k);
        float pad = DOCK_PAD * k, itemGap = 10 * k, groupGap = 34 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = (dockRect.width() - 2 * pad - Math.max(0, n - groups) * itemGap - (groups - 1) * groupGap) / Math.max(1, n);
        float x = left + pad, top = dockTop + pad;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - itemGap;
            drawerRects[i].set(x, top, x + tw, dockRect.bottom - pad);
            x += tw + itemGap;
        }
        float cardsTop = dockRect.bottom + gap, split = left + (right - left) * 0.585f;
        boolean wx = Prefs.weather(), qt = Prefs.quotes() && HomeData.active().length > 0;
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, qt ? split - 16 * k : right, cardsTop + CARDS_H * k);
        quotesCard.set(wx ? split + 16 * k : left, cardsTop, right, cardsTop + CARDS_H * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        cont.refresh();   // Stremio's progress, when it's older than 5 minutes
        click.drawer(true);
        smoke.snap(1);      // the shelf turns to smoked glass now, before it moves
        smokeLayer.invalidate();
        motionDirty = true;
        float x = shelf.isEmpty() ? w / 2f : tileRects[shelfIndex].centerX();
        drawerIndex = nearest(drawerRects, x);
        if (cwItems.isEmpty()) {
            zone = Z_DRAWER;
        } else {
            // First stop: Continue Watching, the card under the app (among the ones on screen).
            zone = Z_CONT;
            cwIndex = Math.min(cwItems.size() - 1, nearest(java.util.Arrays.copyOf(cwRects, Math.min(5, cwItems.size())), x));
        }
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(zone == Z_DRAWER && i == drawerIndex ? 1 : 0);
        for (int i = 0; i < cwFocus.length; i++) cwFocus[i].snap(zone == Z_CONT && i == cwIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        float from = zone == Z_CONT && cwIndex < cwRects.length ? cwRects[cwIndex].centerX() - cwSlide.target * k
                : drawerIndex < drawerRects.length ? drawerRects[drawerIndex].centerX() : -1;
        drawerOpen = false;
        drawer.target = 0;
        click.drawer(false);
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        for (Spring f : cwFocus) f.snap(0);
        if (!shelf.isEmpty() && from >= 0) shelfIndex = nearest(tileRects, from);
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Surface for a page-2 card or the dock: light by day, dark at night, with a soft shadow. */
    private void drawerGlass(Canvas c, RectF r, float radius, int shadowColor, float drop) {
        // Flat surfaces, not glass: measured on the Vu, moving the focus on page 2 took 40 ms a frame with the glass
        // and 25 ms without it.
        tmp.set(r);
        tmp.offset(0, drop);
        shadow.setColor(tone(shadowColor, 0x33000000));
        shadows.drawCached(c, tmp, radius, SHADOW_BLUR * k, shadow);
        fill.setShader(null);
        fill.setColor(tone(0xF2F4F4F6, 0xF0242427));
        c.drawRoundRect(r, radius, radius, fill);
        rim.setShader(null);
        rim.setColor(tone(0x12000000, 0x17FFFFFF));
        rim.setStrokeWidth(1.2f * k);
        c.drawRoundRect(r, radius, radius, rim);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        pricePaint.setColor(drawerInk(0.95f));
        if (!cwItems.isEmpty()) drawContinue(c);
        drawerGlass(c, dockRect, (TILE_RADIUS + DOCK_PAD) * k, 0x1A000000, 10 * k);
        if (drawerInputs > 0 && drawerInputs < drawerItems.size()) {
            // The hairline between inputs and shortcuts, like the shelf's before All Apps.
            float dx = (drawerRects[drawerInputs - 1].right + drawerRects[drawerInputs].left) / 2, hw = 1.2f * k;
            float inset = dockRect.height() * 0.24f;
            fill.setShader(null);
            fill.setColor(tone(0x26000000, 0x33FFFFFF));
            c.drawRoundRect(dx - hw, dockRect.top + inset, dx + hw, dockRect.bottom - inset, hw, hw, fill);
        }
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (Prefs.weather()) drawWeatherCard(c, weatherCard);
        if (Prefs.quotes() && HomeData.active().length > 0) drawQuotesCard(c, quotesCard);
    }

    /** Continue Watching: the image with its fade, the title and what's next (or the app), Stremio's progress. */
    private void drawContinue(Canvas c) {
        float slide = cwSlide.value * k;
        c.drawText(Ui.tr("Continuar assistindo", "Continue Watching"), cwRects[0].left + 4 * k, cwTitleY, rowTitle);
        int n = Math.min(cwItems.size(), cwRects.length), top = -1;
        for (int i = 0; i < n; i++) if (cwFocus[i].value > 0.02f && (top < 0 || cwFocus[i].value > cwFocus[top].value)) top = i;
        for (int i = 0; i < n; i++) if (i != top) drawContinueCard(c, i, slide);
        if (top >= 0) drawContinueCard(c, top, slide);
    }

    private void drawContinueCard(Canvas c, int i, float slide) {
        RectF base = cwRects[i];
        float left = base.left - slide;
        if (left > w || left + base.width() < 0) return;
        Continue.Entry e = cwItems.get(i);
        float f = Ui.clamp(cwFocus[i].value), s = 1 + 0.08f * f;
        float cx = left + base.width() / 2, cy = base.centerY();
        cwCell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 16 * k * s;
        if (f > 0.01f) {
            tmp.set(cwCell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.drawCached(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        Bitmap img = cont.image(e);
        if (img != null) {
            cropImage(c, img, cwCell, r);
        } else {
            drawerGlass(c, cwCell, r, 0x14000000, 6 * k);
        }
        float pad = 16 * k * s, bottom = cwCell.bottom - pad;
        if (e.progress >= 0) {
            // The progress line, like Apple TV's: white over a see-through track.
            float y = cwCell.bottom - 10 * k * s, x0 = cwCell.left + pad, x1 = cwCell.right - pad, th = 4 * k * s;
            fill.setShader(null);
            fill.setColor(0x59FFFFFF);
            tmp.set(x0, y - th, x1, y);
            c.drawRoundRect(tmp, th / 2, th / 2, fill);
            fill.setColor(0xFFFFFFFF);
            tmp.set(x0, y - th, x0 + (x1 - x0) * Math.max(0.03f, e.progress), y);
            c.drawRoundRect(tmp, th / 2, th / 2, fill);
            bottom = y - th - 10 * k * s;
        }
        float room = cwCell.width() - 2 * pad;
        cwSub.setTextSize(19 * k * s);
        cwTitle.setTextSize(23 * k * s);
        fitPaint.set(cwSub);
        String sub = TextUtils.ellipsize(e.sub == null ? "" : e.sub, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(sub, cwCell.left + pad, bottom, cwSub);
        fitPaint.set(cwTitle);
        String title = TextUtils.ellipsize(e.title == null ? "" : e.title, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(title, cwCell.left + pad, bottom - Ui.cap(cwSub) - 9 * k * s, cwTitle);
        if (removing && i == cwIndex) {
            // Asking to remove: the card dims under a "Remove" label.
            fill.setShader(null);
            fill.setColor(0xB3000000);
            c.drawRoundRect(cwCell, r, r, fill);
            String ask = Ui.tr("✕  Remover", "✕  Remove");
            cwTitle.setTextSize(26 * k * s);
            c.drawText(ask, cwCell.centerX() - cwTitle.measureText(ask) / 2, cwCell.centerY() + Ui.halfCap(cwTitle), cwTitle);
        }
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.85f * f));
            rim.setStrokeWidth(3 * k);
            c.drawRoundRect(cwCell, r, r, rim);
        }
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 16 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 8 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.35f * f));
            shadows.drawCached(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.37f, 28 * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(20 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(16 * k * s, 20 * k * s * room / wide));
        fitPaint.set(drawerLabel);
        String label = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(20 * k);
    }

    /** Weather for your location: now (large), the next 6 hours, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle, x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("Sem dados do clima agora.", "No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        c.drawText(nowTemp, x + sw + 18 * k - Ui.leftBearing(bigTemp, nowTemp), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(highLow, x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k, hw = (r.right - 30 * k - hx) / 6;
        for (int i = 0; i < wx.hours.size() && i < 6; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(hourText[i], hc, r.top + 96 * k, hourPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
            c.drawText(hourTempText[i], hc, r.top + 196 * k, hourTemp);
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", sunriseText, sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", sunsetText, sx, base);
    }

    private void refreshDrawerText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        if (wx != textWeather || settings) {
            textWeather = wx;
            weatherTitle = Prefs.city().isEmpty() ? Ui.tr("Clima", "Weather") : Prefs.city();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = Ui.ru ? "Макс. " + HomeData.degrees(wx.max) + "   Мин. " + HomeData.degrees(wx.min)
                    : Ui.en ? "H:" + HomeData.degrees(wx.max) + "   L:" + HomeData.degrees(wx.min)
                    : Ui.de ? "H:" + HomeData.degrees(wx.max) + "   T:" + HomeData.degrees(wx.min)
                    : "Máx. " + HomeData.degrees(wx.max) + "   Mín. " + HomeData.degrees(wx.min);
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                hourText[i] = HomeData.hour(wx.hours.get(i));
                hourTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
            }
            sunriseText = Ui.tr("Nascer ", "Sunrise ") + Ui.time(wx.sunrise);
            sunsetText = Ui.tr("Pôr do sol ", "Sunset ") + Ui.time(wx.sunset);
        }
        long minute = System.currentTimeMillis() / 60_000;
        if (data.pricesAt != textPricesAt || minute != textMinute || settings) {
            textPricesAt = data.pricesAt;
            textMinute = minute;
            String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
            staleText = stale.isEmpty() ? Ui.tr("variação em 24 h", "24h change") : Ui.tr("de ", "", "de ") + stale;
            for (int i = 0; i < HomeData.COINS.length; i++) {
                priceText[i] = HomeData.brl(data.prices[i]);
                changeText[i] = HomeData.change(data.changes[i]);
            }
        }
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /** Prices in the configured currency with the 24 h change (green/red, like Apple's Stocks app). */
    private void drawQuotesCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) quotesSnapshot.draw(c, r, drawerCardsKey(), cc -> drawPrices(cc, r));
        else drawPrices(c, r);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + data.pricesAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 9 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot(), quotesSnapshot = new Snapshot();

    private void drawPrices(Canvas c, RectF r) {
        float x = r.left + 34 * k, right = r.right - 34 * k;
        refreshDrawerText();
        c.drawText(Ui.tr("Cotações", "Crypto"), x, r.top + 54 * k, cardTitle);
        c.drawText(staleText, right, r.top + 54 * k, cardDimRight);
        float cap = Ui.cap(cardText);
        int[] coins = HomeData.active();
        for (int j = 0; j < coins.length; j++) {
            int i = coins[j];
            float base = r.top + (110 + 52 * j) * k, mid = base - cap / 2;
            float gw = Glyphs.width(getContext(), HomeData.COINS[i], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[i], x + 14 * k, mid, cap, tone(L_DIM, 0xCCFFFFFF));
            c.drawText(HomeData.coinName(i), x + Math.max(gw, 28 * k) / 2 + 30 * k, base, cardText);
            c.drawText(priceText[i], right - 128 * k, base, pricePaint);
            double change = data.changes[i];
            if (Double.isNaN(change)) continue;
            int color = change >= 0 ? tone(0xFF248A3D, UP_COLOR) : tone(0xFFD70015, DOWN_COLOR);
            tmp.set(right - 108 * k, mid - 17 * k, right, mid + 17 * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(color, 0.2f));
            c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
            changePaint.setColor(color);
            c.drawText(changeText[i], tmp.centerX(), mid + Ui.halfCap(changePaint), changePaint);
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        drawerChanged |= aim(cwFocus, !sheetOpen && zone == Z_CONT ? cwIndex : -1, snap);
        cwSlide.target = Math.max(0, cwIndex - 4) * (CW_W + CW_GAP);   // the focused card stays on screen
        if (snap) {
            cwSlide.snap(cwSlide.target);
        }
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        heroChanged |= aim(cardFocus, !sheetOpen && zone == Z_CARDS ? focusSlot : -1, snap) | topChanged;
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        spoken.focusMoved();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    private void openSheet() {
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        c.drawColor(0xA8000000);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(Ui.tr("Todos os Apps", "All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            drawCard(c, others.get(i).tile, gridCell, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (image != null) roundImage(c, image, cell, r);
        if (f > 0.01f) {
            float bh = base.height();
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = new LinearGradient(0, -bh / 2, 0, 0, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                cardSheenH = bh;
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2);
            sheen.setShader(cardSheen);
            sheen.setAlpha(Math.round(255 * fc));
            c.drawRoundRect(tmp, radius, radius, sheen);
            sheen.setAlpha(255);
            c.restore();
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Wallpaper picker (corner button): the background changes live as you browse

    private boolean pickingWall;
    private String wallOriginal;
    private List<String> wallIds = new ArrayList<>();
    private int wallIndex;
    private StaticLayout wallName, wallHint;

    private void startWallpaperPicker() {
        if (drawerOpen) {   // opened from the drawer's Shortcuts: the drawer goes down so the whole screen previews
            closeDrawer();
            retarget(false);
        }
        wallIds = Wallpapers.ids(getContext());
        wallOriginal = Prefs.wallpaper();
        wallIndex = Math.max(0, wallIds.indexOf(wallOriginal));
        pickingWall = true;
        toast = null;   // the picker uses the toast's place
        Wallpapers.setPreview(wallOriginal);
        wallLabels();
        announceForAccessibility(Ui.tr("Papel de parede: ", "Wallpaper: ") + Wallpapers.label(wallIds.get(wallIndex)));
        toastLayer.invalidate();
    }

    /** Keys while picking: ← → browse (live), OK keeps it, Back puts the old one back. Nothing else gets them. */
    private boolean wallKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && !wallIds.isEmpty()) {
                    int dir = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                    wallIndex = (wallIndex + dir + wallIds.size()) % wallIds.size();
                    Wallpapers.setPreview(wallIds.get(wallIndex));
                    wallLabels();
                    click.move();
                    announceForAccessibility(Wallpapers.label(wallIds.get(wallIndex)));
                    updateCards();
                    toastLayer.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that opened the picker is still held: only a new press keeps the wallpaper.
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    endWallpaperPicker(true);
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) endWallpaperPicker(false);
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void endWallpaperPicker(boolean keep) {
        if (!pickingWall) return;
        pickingWall = false;
        String chosen = wallIds.isEmpty() ? wallOriginal : wallIds.get(wallIndex);
        if (keep && !chosen.equals(wallOriginal)) Prefs.setWallpaper(chosen);
        Wallpapers.setPreview(null);
        if (keep) click.open(); else click.move();
        updateCards();
        toastLayer.invalidate();
    }

    /** A wallpaper finished building: show it (with the cross-fade, so frames keep coming). */
    /** Not now: away from the home screen (an app, the screensaver), something open, or nothing to move to. */
    private boolean autoPaused() {
        return !running || !hasWindowFocus() || cards.size() < 2 || !picksOn() || drawerOpen || drawer.value > 0.01f
                || sheetOpen || pickingWall || reordering;
    }

    /** 0 to 1: how far into this suggestion's 10 s we are (0 while paused). */
    private float autoProgress() {
        if (autoStopped || autoPaused()) return 0;
        return Ui.clamp((SystemClock.uptimeMillis() - autoFrom) / (float) AUTO_MS);
    }

    /** 10 times a second: the dots' line grows (only that small layer redraws); at 10 s, the row moves on. */
    private void autoTick() {
        handler.removeCallbacks(autoTick);
        if (!running) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastKeyAt > AUTO_IDLE_MS) {
            autoStopped = true;      // no more ticks until a key
            dotsLayer.invalidate();
            return;
        }
        if (autoPaused()) {
            autoFrom = now;
            dotsLayer.invalidate();
            handler.postDelayed(autoTick, 500);
            return;
        }
        if (now - autoFrom >= AUTO_MS) {
            autoFrom = now;
            advanceRow();
        }
        dotsLayer.invalidate();
        handler.postDelayed(autoTick, 100);   // 10 a second: the line grows ~0.6 px a step, enough to look continuous
    }

    /** The next suggestion, as → would (without the click), wherever the focus is. */
    private void advanceRow() {
        int n = cards.size();
        if (n < 2) return;
        if (!rowLoops()) focusSlot = (focusSlot + 1) % n;
        else if (focusSlot + 1 > 2) rowStart++;
        else focusSlot++;
        rowMoved();
        retarget(false);
        wake();
    }

    private void wallpaperReady() {
        updateCards();
        wake();
    }

    private void wallLabels() {
        String name = wallIds.isEmpty() ? "" : Wallpapers.label(wallIds.get(wallIndex));
        String hint = (wallIndex + 1) + " / " + wallIds.size() + "   ·   "
                + Ui.tr("OK para manter · Voltar para cancelar", "OK to keep · Back to cancel");
        wallName = StaticLayout.Builder.obtain(name, 0, name.length(), wallNamePaint(), Math.round(640 * k))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).build();
        wallHint = StaticLayout.Builder.obtain(hint, 0, hint.length(), toastPaint, Math.round(640 * k))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(1).build();
    }

    private TextPaint wallNamePaint;

    private TextPaint wallNamePaint() {
        if (wallNamePaint == null) wallNamePaint = new TextPaint(Ui.text(Ui.semibold, 34 * k, 0xFFFFFFFF));
        return wallNamePaint;
    }

    /** At the top center: ‹ name › on smoked glass, with the position and what OK and Back do below. */
    private void drawWallpaperPicker(Canvas c) {
        if (wallName == null) return;
        float padX = 44 * k, padY = 20 * k, gap = 8 * k, chev = 30 * k;
        float tw = 640 * k, th = wallName.getHeight() + gap + wallHint.getHeight();
        float top = 48 * k;
        tmp.set((w - tw) / 2f - padX, top, (w + tw) / 2f + padX, top + th + 2 * padY);
        scene.glass(c, tmp, 36 * k, SMOKE, 0.55f, 0, 0, 0.02f, 1);
        float cy = tmp.top + padY + wallName.getHeight() / 2f;
        Symbols.draw(getContext(), c, "chevron_right", tmp.right - padX / 2 - 6 * k, cy, chev, 0xD9FFFFFF);
        c.save();
        c.rotate(180, tmp.left + padX / 2 + 6 * k, cy);
        Symbols.draw(getContext(), c, "chevron_right", tmp.left + padX / 2 + 6 * k, cy, chev, 0xD9FFFFFF);
        c.restore();
        c.save();
        c.translate((w - tw) / 2f, tmp.top + padY);
        wallName.draw(c);
        c.translate(0, wallName.getHeight() + gap);
        wallHint.draw(c);
        c.restore();
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (pickingWall) {
            drawWallpaperPicker(c);
            return;
        }
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = HomeApps.shelf(c, true);
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = HomeApps.others(c);
            handler.post(() -> {
                setShelf(withAllApps(s));
                setStatusItems(st);
                setDrawerItems(d);
                if (!sheetOpen) setOthers(o);
            });
        });
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        wake();
        autoFrom = lastKeyAt = SystemClock.uptimeMillis();   // any key: the current suggestion gets its full 10 s again
        if (autoStopped && running) {
            autoStopped = false;
            handler.post(autoTick);
        }
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (reordering) return reorderKey(e, down);
        if (removing) return removeKey(e, down);
        if (pickingWall) return wallKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;      // another key while OK is held: no move mode
            handler.removeCallbacks(holdStart);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held on a shelf app (~0.5 s): move mode. A timer, since not every remote repeats a held key, or the
                // long-press repeat, whichever comes first; the release after a hold opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = canReorder() || canRemove();
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        startHold();
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void back() {
        if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER || zone == WIDGETS || zone == Z_CONT) {
            closeDrawer();
            retarget(false);
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            case Z_CONT:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(cwItems.size() - 1, cwIndex + dx));
                    if (next == cwIndex) return;
                    cwIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else {
                    zone = Z_DRAWER;     // down to the dock, the item under the card
                    drawerIndex = nearest(drawerRects, cwRects[cwIndex].centerX() - cwSlide.target * k);
                }
                break;
            case WIDGETS: {
                List<Integer> on = widgets();
                int at = on.indexOf(widgetIndex);
                if (dy < 0 || at < 0) {
                    zone = Z_DRAWER;
                } else if (dx != 0 && at + dx >= 0 && at + dx < on.size()) {
                    widgetIndex = on.get(at + dx);
                } else {
                    return;
                }
                break;
            }
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0 && !cwItems.isEmpty()) {
                    // Up to Continue Watching: the card above, among the ones on screen.
                    zone = Z_CONT;
                    float x = drawerRects[drawerIndex].centerX() + cwSlide.target * k;
                    cwIndex = Math.min(cwItems.size() - 1, nearest(java.util.Arrays.copyOf(cwRects, cwItems.size()), x));
                } else if (dy < 0) {
                    closeDrawer();
                } else if (talkBack() && !widgets().isEmpty()) {
                    // Only with TalkBack: the widgets are there to be read, and this is how it gets to them.
                    zone = WIDGETS;
                    widgetIndex = widgets().get(0);
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        wake();
        if (reordering) {   // TalkBack's click while moving an app: drop it
            dropReorder();
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (!sheetOpen && zone == WIDGETS) return;   // a widget is only read
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = heroButton;
            cont.opened(p);
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_CONT) {
            if (cwIndex >= cwItems.size()) return;
            Continue.Entry e = cwItems.get(cwIndex);
            it = HomeApps.app(e.pkg, e.app, e.link);
            r = cwRects[cwIndex];
            cont.reopened(e);
        } else if (zone == Z_TOP) {
            if (topIndex < NT) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - NT);
            r = topRects[topIndex];
        } else if (zone == Z_CARDS) {
            Recs.Pick p = cardAt(rowStart + focusSlot);
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = cardRects[focusSlot];
            cont.opened(p);
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (it.kind == HomeApps.WALLPAPER) {
            startWallpaperPicker();
            return;
        }
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + it.label + ".");
    }

    // MARK: - Removing from Continue Watching

    private void startHold() {
        if (canRemove()) startRemove();
        else startReorder();
    }

    private boolean canRemove() {
        return !sheetOpen && zone == Z_CONT && cwIndex < cwItems.size();
    }

    private void startRemove() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (!canRemove()) return;
        removing = okHeld = true;
        removeArmed = false;     // the release of this hold isn't the answer
        click.open();
        showToast(Ui.tr("OK para remover · Voltar para manter", "OK to remove · Back to keep"));
        drawerDirty = true;
        wake();
    }

    private boolean removeKey(KeyEvent e, boolean down) {
        int code = e.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (ok) {
            if (down && e.getRepeatCount() == 0) removeArmed = true;
            if (!down && removeArmed) {
                Continue.Entry gone = cwIndex < cwItems.size() ? cwItems.get(cwIndex) : null;
                endRemove();
                if (gone != null) {
                    cont.remove(gone);
                    showToast(Ui.tr("Removido de Continuar assistindo", "Removed from Continue Watching"));
                }
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_HOME) {
            endRemove();
            return false;
        }
        if (!down) endRemove();   // Back or an arrow: kept
        return true;
    }

    private void endRemove() {
        removing = removeArmed = okHeld = false;
        okDown = false;
        drawerDirty = true;
        wake();
    }

    // MARK: - Moving apps on the shelf

    private boolean canReorder() {
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 2) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (reordering || !canReorder()) return;
        wake();
        reordering = reorderAnim = okHeld = true;
        reorderOrigin = new ArrayList<>(shelf);
        liftIndex = shelfIndex;
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        click.open();
        showToast(Ui.tr("← → para mover · OK para soltar · Voltar para cancelar",
                "← → to move · OK to drop · Back to cancel"));
        retarget(false);    // TalkBack reads "Moving <app>, position i of n"
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that lifted the app is still held: its repeats and release do nothing; a new press drops it.
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    dropReorder();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        cancelReorder(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
    private void shiftReorder(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size() || shelf.get(j).kind != HomeApps.APP) return;   // All Apps stays last
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = tileRects[i].left - tileRects[j].left;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        if (tileShaders.length == shelf.size()) {
            LinearGradient g = tileShaders[i];
            tileShaders[i] = tileShaders[j];
            tileShaders[j] = g;
        }
        shelfIndex = liftIndex = j;
        reorderAnim = true;
        wake();
        retarget(false);
        click.move();
    }

    /** OK: drops the app where it is and saves the shelf's new order. */
    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        moveLift.target = 0;
        boolean changed = false;
        for (int i = 0; i < shelf.size() && !changed; i++) changed = shelf.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget(false);
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) if (it.pkg != null) order.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            // The saved list may hold more than the shelf shows (apps not installed, past the limit): the shown ones
            // take their new order in the same slots, the others stay where they were.
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> Prefs.setShelf(save));
        });
    }

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    private void cancelReorder(boolean snap) {
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            moveLift.target = 0;
            int n = shelf.size();
            if (reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                LinearGradient[] shaders = tileShaders.length == n ? new LinearGradient[n] : tileShaders;
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                    if (shaders != tileShaders) shaders[a] = tileShaders[b];
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                tileShaders = shaders;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderOrigin = null;
            retarget(snap);
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        return moving;
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** The drawer's weather (0) and quotes (1) cards: read by TalkBack, never focused by the remote. */
    private static final int GRID = 9, WIDGETS = 8;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        for (int i = picksOn() ? 0 : NT; i < NT + lightButtons.size(); i++) if (tabShown(i)) out.add(a11yId(Z_TOP, i));
        for (int i = 0; i < Math.min(3, cards.size()); i++) out.add(a11yId(Z_CARDS, i));
        for (int i = 0; i < shelf.size(); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
        if (drawerOpen && Prefs.weather()) out.add(a11yId(WIDGETS, 0));
        if (drawerOpen && Prefs.quotes() && HomeData.active().length > 0) out.add(a11yId(WIDGETS, 1));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + (i + 1) + Ui.tr(" de ", " of ") + others.size();
            case Z_TOP:
                if (i >= NT) return label(lightButtons, i - NT);
                return tabs()[i] + Ui.tr(", aba ", ", tab ") + (i + 1) + Ui.tr(" de ", " of ") + tabCount();
            case Z_CARDS: return cardAt(rowStart + i) != null ? pickText(cardAt(rowStart + i)) : "";
            case Z_SHELF:
                if (reordering && i == shelfIndex) {
                    return Ui.tr("Movendo ", "Moving ", "Moviendo ") + label(shelf, i)
                            + Ui.tr(", posição ", ", position ", ", posición ") + (i + 1) + Ui.tr(" de ", " of ") + shelf.size();
                }
                return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case WIDGETS: return i == 0 ? weatherText() : quotesText();
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= NT + lightButtons.size() || !tabShown(i)) return false;
                a11yRect.set(topRects[i]);
                dy = (i < NT ? heroLayer : status).getTranslationY();
                break;
            case Z_CARDS:
                if (i >= Math.min(3, cards.size())) return false;
                a11yRect.set(cardRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            case WIDGETS:
                if (!drawerOpen) return false;
                a11yRect.set(i == 0 ? weatherCard : quotesCard);
                dy = 0;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cards.isEmpty() ? -1 : a11yId(Z_CARDS, focusSlot);
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            case WIDGETS: return a11yId(WIDGETS, widgetIndex);
            default: return -1;
        }
    }

    /** The drawer's widgets that are on (0 weather, 1 quotes), with the drawer open. */
    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (!drawerOpen) return out;
        if (Prefs.weather()) out.add(0);
        if (Prefs.quotes() && HomeData.active().length > 0) out.add(1);
        return out;
    }

    /** A spoken-feedback service (TalkBack) is on. */
    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (sheetOpen || reordering) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case WIDGETS:
                if (!widgets().contains(i)) return;
                widgetIndex = i;
                break;
            case Z_TOP:
                if (i >= NT + lightButtons.size() || (i < NT && !picksOn()) || !tabShown(i)) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < NT) selectTab(i);
                break;
            case Z_CARDS:
                if (i >= Math.min(3, cards.size())) return;
                if (drawerOpen) closeDrawer();
                focusSlot = i;
                rowMoved();
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    /** The weather card as TalkBack reads it: what it shows, in the same words and units. */
    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? Ui.tr("Clima", "Weather") : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return s.append(". ").append(Ui.tr("Sem dados do clima agora.", "No weather data right now.")).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay))
                .append(Ui.tr(". Máxima ", ". High ")).append(HomeData.degrees(wx.max))
                .append(Ui.tr(", mínima ", ", low ")).append(HomeData.degrees(wx.min)).append('.');
        int hours = Math.min(6, wx.hours.size());
        if (hours > 0) s.append(Ui.tr(" Próximas horas: ", " Next hours: "));
        for (int i = 0; i < hours; i++) {
            s.append(HomeData.hour(wx.hours.get(i))).append(' ').append(HomeData.degrees(wx.hourTemp.get(i))).append(i < hours - 1 ? ", " : ".");
        }
        if (wx.sunrise != 0) s.append(Ui.tr(" Nascer do sol ", " Sunrise ")).append(Ui.time(wx.sunrise)).append('.');
        if (wx.sunset != 0) s.append(Ui.tr(" Pôr do sol ", " Sunset ")).append(Ui.time(wx.sunset)).append('.');
        return s.toString();
    }

    /** The quotes card as TalkBack reads it: each coin with its price and 24 h change. */
    private String quotesText() {
        StringBuilder s = new StringBuilder(Ui.tr("Cotações", "Crypto"));
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        s.append(", ").append(stale.isEmpty() ? Ui.tr("variação em 24 h", "24h change") : Ui.tr("de ", "", "de ") + stale).append('.');
        for (int i : HomeData.active()) {
            s.append(' ').append(HomeData.coinName(i)).append(' ').append(HomeData.brl(data.prices[i]));
            if (!Double.isNaN(data.changes[i])) s.append(", ").append(HomeData.change(data.changes[i]));
            s.append('.');
        }
        return s.toString();
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        // Slim pills (44 px) centered on the old 68 px bar, 18 px past the text.
        float pad = 18 * k, mid = (L_TABBAR_TOP + L_TABBAR_H / 2) * k, tabTop = mid - 22 * k, tabBottom = mid + 22 * k;
        float x = MARGIN * k - pad;
        for (int i = 0; i < NT; i++) {
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom);
            x = topRects[i].right + 4 * k;
        }
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[NT + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + L_BUTTON_GAP * k;
        }
        settleRow();
        heroDirty = statusDirty = true;
    }

    // MARK: - Suggestion row

    private float wideW() {
        return (CONTENT_R - MARGIN - 2 * L_TALL_W - 2 * L_CARD_GAP) * k;
    }

    /** Width at slot coordinate u: wide where the wide card is (wideAt), tall elsewhere, blending in between. */
    private float rowW(float u, float wideSlot) {
        float t = L_TALL_W * k;
        return t + (wideW() - t) * Math.max(0, 1 - Math.abs(u - wideSlot));
    }

    /** Left edge of integer slot j (0 = first visible; negative and 3+ are off screen). */
    private float slotX(int j, float wideSlot) {
        float x = MARGIN * k, g = L_CARD_GAP * k;
        if (j >= 0) for (int i = 0; i < j; i++) x += rowW(i, wideSlot) + g;
        else for (int i = -1; i >= j; i--) x -= rowW(i, wideSlot) + g;
        return x;
    }

    /** The 3 slots at rest for the current focus (for navigation, accessibility and the shelf's "up"). */
    private void settleRow() {
        float top = L_CARDS_TOP * k, ch = L_CARD_H * k;
        for (int j = 0; j < 3; j++) {
            float x = slotX(j, focusSlot);
            cardRects[j].set(x, top, x + rowW(j, focusSlot), top + ch);
        }
    }

    /** Positions are unbounded with 4+ cards (the row loops); with 3 or fewer the row doesn't move. */
    private boolean rowLoops() {
        return cards.size() > 3;
    }

    private Recs.Pick cardAt(int position) {
        int n = cards.size();
        if (n == 0 || (!rowLoops() && (position < 0 || position >= n))) return null;
        return cards.get(Math.floorMod(position, n));
    }

    /** After the focus moved in the row: which card it is, the slots at rest, images for what's around, the background. */
    private void rowMoved() {
        int n = cards.size();
        if (n == 0) return;
        if (!rowLoops()) {
            rowStart = 0;
            focusSlot = Math.max(0, Math.min(n - 1, focusSlot));
        }
        rowScroll.target = rowStart;
        wideAt.target = focusSlot;
        cardIndex = Math.floorMod(rowStart + focusSlot, n);
        settleRow();
        prefetchRow();
        // The background follows the focused card once the focus rests: a cross-fade on every step made sliding stutter.
        handler.removeCallbacks(rowBackdrop);
        handler.postDelayed(rowBackdrop, 600);
        heroDirty = true;
    }

    private final Runnable rowBackdrop = this::applyRowBackdrop;

    private void applyRowBackdrop() {
        if (cards.isEmpty() || Wallpapers.get(getContext(), this::wallpaperReady) != null) return;
        Backdrop b = recs.backdrop(cards.get(Math.min(cardIndex, cards.size() - 1)));
        if (b != null) showBackdrop(b);
    }

    /** Builds the images of the visible cards and one more on each side (not all 27: they don't fit in memory). */
    private void prefetchRow() {
        // The three on screen first, then the ones next to come in from either side.
        for (int q : new int[] {rowStart + focusSlot, rowStart, rowStart + 1, rowStart + 2, rowStart + 3, rowStart - 1}) {
            Recs.Pick p = cardAt(q);
            if (p != null) recs.backdrop(p);
        }
    }

    /** The visible cards (and the ones sliding in or out), the most focused one last so it grows over its neighbors. */
    private void drawRow(Canvas c) {
        if (cards.isEmpty()) return;
        float scroll = rowScroll.value, wide = wideAt.value;
        int first = (int) Math.floor(scroll) - 1;
        c.save();
        // Cards sliding in or out stop just past the content edges (room for the focused card's growth).
        c.clipRect(MARGIN * k - 34 * k, 0, CONTENT_R * k + 34 * k, h);
        int topQ = Integer.MIN_VALUE;
        float topF = 0.02f;
        for (int pass = 0; pass < 2; pass++) {
            for (int q = first; q <= first + 4; q++) {
                float u = q - scroll;
                if (u <= -1 || u >= 3) continue;
                Recs.Pick p = cardAt(q);
                if (p == null) continue;
                float f = 0;
                for (int j = 0; j < 3; j++) f += cardFocus[j].value * Math.max(0, 1 - Math.abs(u - j));
                if (pass == 0) {
                    if (f > topF) { topF = f; topQ = q; }
                    continue;
                }
                if (q == topQ) continue;
                drawRowCard(c, p, u, wide, f);
            }
        }
        if (topQ != Integer.MIN_VALUE) drawRowCard(c, cardAt(topQ), topQ - scroll, wide, topF);
        c.restore();
    }

    /**
     * A suggestion card at slot coordinate u: the photo (center-cropped, so it never stretches while it changes width),
     * rounded corners and a soft shadow; inside, at the bottom over a dark fade, the title (up to two lines) and
     * genre · runtime · service. Focused: it grows 5%, rises and lights up its border.
     */
    private void drawRowCard(Canvas c, Recs.Pick p, float u, float wide, float v) {
        int j = (int) Math.floor(u);
        float t = u - j;
        float x = slotX(j, wide) + (slotX(j + 1, wide) - slotX(j, wide)) * t;
        float bw = rowW(u, wide), bh = L_CARD_H * k, top = L_CARDS_TOP * k;
        float f = Ui.clamp(v), s = 1 + 0.05f * v, r0 = 18 * k;
        float cx = x + bw / 2, cy = top + bh / 2 - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        if (f > 0.02f) {
            // Only the focused card casts a shadow (on the row's dark background the others' barely showed, and each
            // cost the GPU ~1 ms a frame).
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tmp.offset(0, (10 + 14 * f) * k);
            shadowDeep.setColor(Ui.alpha(0xFF000000, (tone(0.14f, 0.32f) + tone(0.2f, 0.28f)) * f));
            shadows.drawCached(c, tmp, r0, DEEP_BLUR * k, shadowDeep);   // its width changes on every frame of a slide
            c.restore();
        }
        boolean asWide = bw > (wideW() + L_TALL_W * k) / 2;
        Backdrop b = recs.backdrop(p);
        Bitmap img = b == null ? null : (asWide ? b.wide : b.tall);
        boolean poster = !asWide && b != null && b.poster;   // the official poster: its title is on it already
        if (img != null) {
            cropImage(c, img, cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        // The text inside the card, over the dark fade painted into the card's image (Backdrop.card).
        float pad = 26 * k, textW = (asWide ? wideW() : L_TALL_W * k) - 2 * pad;
        float baseY = bh / 2 - pad;
        String meta = p.meta == null ? "" : p.meta;
        // The details show only on the selected (wide) card: they fade in, and the title rises, as it widens.
        float tall = L_TALL_W * k, open = Ui.clamp((bw - tall) / (wideW() - tall) * 1.4f - 0.4f);
        if (!poster && !meta.isEmpty() && open > 0.01f) {
            lOnMeta.setAlpha(Math.round(0xD9 * open));
            String mk = Math.round(textW) + "|" + meta;
            String fitted = metaFits.get(mk);
            if (fitted == null) {
                if (metaFits.size() > 60) metaFits.clear();
                fitPaint.set(lOnMeta);
                fitted = TextUtils.ellipsize(meta, fitPaint, textW, TextUtils.TruncateAt.END).toString();
                metaFits.put(mk, fitted);
            }
            c.drawText(fitted, -bw / 2 + pad, baseY, lOnMeta);
            lOnMeta.setAlpha(0xD9);
            baseY -= (Ui.cap(lOnMeta) + 14 * k) * open;
        }
        Bitmap logo = asWide && b != null ? b.logo : null;
        if (logo != null) {
            // The title's own logo, like Apple TV: it fits the logo box (sized for the focused card, hence / 1.05).
            float fit = Math.min(1, Math.min(textW / logo.getWidth(), 1.05f * Backdrop.LOGO_H / logo.getHeight())) / 1.05f;
            float lw = logo.getWidth() * fit, lh = logo.getHeight() * fit, lx = -bw / 2 + pad, ly = baseY + 4 * k - lh;
            logoDst.set(lx, ly, lx + lw, ly + lh);
            logoPaint.setAlpha(Math.round(255 * Ui.clamp(open * 1.6f)));
            c.drawBitmap(logo, null, logoDst, logoPaint);
        } else if (!poster) {
            StaticLayout title = titleLayout(p.title == null ? "" : p.title, Math.round(textW));
            c.save();
            c.translate(-bw / 2 + pad, baseY - title.getLineBaseline(title.getLineCount() - 1));
            title.draw(c);
            c.restore();
        }
        if (f > 0.01f) {
            int alpha = Math.round(255 * f);
            unitMatrix.setScale(bw, bh);
            cardSheenShader.setLocalMatrix(unitMatrix);
            sheen.setShader(cardSheenShader);
            sheen.setAlpha(alpha);
            c.drawRoundRect(tmp, r0, r0, sheen);
            sheen.setAlpha(255);
            unitMatrix.setScale(1, bh);
            cardRimShader.setLocalMatrix(unitMatrix);
            rim.setShader(cardRimShader);
            rim.setAlpha(alpha);
            rim.setStrokeWidth(2.5f * k / s);
            c.drawRoundRect(tmp, r0, r0, rim);
            rim.setStrokeWidth(2.5f * k);
            rim.setAlpha(255);
        }
        c.restore();
    }

    /** The title in up to two lines (ellipsized only past the second), cached per text and width. */
    private StaticLayout titleLayout(String text, int width) {
        String key = width + "|" + text;
        StaticLayout l = titleLayouts.get(key);
        if (l == null) {
            if (titleLayouts.size() > 60) titleLayouts.clear();
            l = StaticLayout.Builder.obtain(text, 0, text.length(), lOnTitle, Math.max(1, width))
                    .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 0.96f).setIncludePad(false).build();
            titleLayouts.put(key, l);
        }
        return l;
    }

    /** `image` center-cropped to `cell`'s shape (it never stretches while a card changes width), rounded corners. */
    private void cropImage(Canvas c, Bitmap image, RectF cell, float r) {
        float iw = image.getWidth(), ih = image.getHeight(), cellAspect = cell.width() / cell.height();
        if (iw / ih > cellAspect) {
            int cw = Math.round(ih * cellAspect);
            srcRect.set(Math.round((iw - cw) / 2), 0, Math.round((iw + cw) / 2), (int) ih);
        } else {
            int chh = Math.round(iw / cellAspect);
            srcRect.set(0, Math.round((ih - chh) / 2), (int) iw, Math.round((ih + chh) / 2));
        }
        // The image as the shader of a rounded rectangle (no anti-aliased clip mask on every frame of a slide).
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        srcF.set(srcRect);
        imageMatrix.setRectToRect(srcF, cell, android.graphics.Matrix.ScaleToFit.FILL);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
    }

    private final RectF srcF = new RectF();

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the first one. */
    private void updateCards() {
        if (tab >= tabCount()) tab = 0;   // Stremio gone (uninstalled): back to Explore
        List<Recs.Pick> list = recs.now(TAB_KIND[tab]);
        if (!samePicks(list, cards)) {
            cards = list;
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        if (!cards.isEmpty()) {
            if (!rowLoops()) rowStart = 0;
            focusSlot = Math.max(0, Math.min(Math.min(3, cards.size()) - 1, focusSlot));
            cardIndex = Math.floorMod(rowStart + focusSlot, cards.size());
            settleRow();
            prefetchRow();   // the visible cards and their neighbors only
        }
        Backdrop b = cards.isEmpty() ? null : recs.backdrop(cards.get(cardIndex));
        // A wallpaper chosen in Home Settings takes the picks' place (it arrives a moment later the first time).
        Backdrop wall = Wallpapers.get(getContext(), this::wallpaperReady);
        if (wall != null) b = wall;
        if (cards.isEmpty() || b != null) showBackdrop(b);
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    /** 4 with the Stremio tab, else 3. */
    private int tabCount() {
        return recs.stremioOn() ? NT : NT - 1;
    }

    /** Whether top-row position i is there: every button, and the tabs up to tabCount. */
    private boolean tabShown(int i) {
        return i >= NT || i < tabCount();
    }

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        // A new category starts at its first card.
        rowStart = 0;
        focusSlot = 0;
        rowScroll.snap(0);
        wideAt.snap(0);
        updateCards();
        heroDirty = true;
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * Ui.clamp(drawer.value), blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = new LinearGradient(0, 0, 0, h, 0xFFF5F5F7, 0xFFE2E2E7, Shader.TileMode.CLAMP);
            if (nightGradient == null) nightGradient = new LinearGradient(0, 0, 0, h, 0xFF1C1C1E, 0xFF0B0B0D, Shader.TileMode.CLAMP);
            fill.setColorFilter(v);
            fill.setColor(0xFF000000);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (b.sharp != null) {
            // A wallpaper: as it is, dimmed a little at night.
            drawImage(c, b.sharp, alpha);
            if (b.photo && b.darkness == 0 && night > 0.001f) {
                // A bright photo at night gets white text: a soft shadow on the left keeps the clock readable.
                if (photoScrim == null) photoScrim = new LinearGradient(0, 0, w * 0.6f, 0,
                        new int[] {0xA6000000, 0x66000000, 0}, new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
                fill.setShader(photoScrim);
                fill.setAlpha(Math.round(255 * night * alpha));
                c.drawRect(screen, fill);
                fill.setShader(null);
                fill.setAlpha(255);
            }
            if (night > 0.001f) {
                fill.setShader(null);
                // Photos already carry scrims behind the text: a lighter night dim keeps their colors.
                fill.setColor(Ui.alpha(0xFF000000, (b.photo ? 0.2f : 0.38f) * night * alpha));
                c.drawRect(screen, fill);
            }
            return;
        }
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    private void drawLightTop(Canvas c) {
        applyTone();
        if (!picksOn()) {
            drawClock(c);
            if (HomeData.coinCount() > 0) drawQuotes(c);
            return;
        }
        drawHeaderClock(c);
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // a slim frosted pill (see-through white with a hairline edge), growing a touch, its text staying light.
        float focus = Ui.clamp(topFocus[tab].value);
        for (int i = 0; i < tabCount(); i++) {
            RectF r = topRects[i];
            if (i == tab && focus > 0.01f) {
                float s = 1 + 0.03f * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                fill.setShader(null);
                fill.setColor(Ui.alpha(tone(0xFF000000, 0xFFFFFFFF), tone(0.07f, 0.17f) * focus));
                c.drawRoundRect(cell, rr, rr, fill);
                tabRim.setColor(Ui.alpha(tone(0xFF000000, 0xFFFFFFFF), tone(0.12f, 0.32f) * focus));
                tabRim.setStrokeWidth(1.2f * k);
                c.drawRoundRect(cell, rr, rr, tabRim);
            }
            Paint p = i == tab ? lTabOn : lTab;
            if (i == tab) lTabOn.setColor(tone(L_INK, WHITE));
            c.drawText(tabs()[i], r.centerX() - p.measureText(tabs()[i]) / 2, r.centerY() + Ui.halfCap(p), p);
        }
        drawRow(c);
        if (HomeData.coinCount() > 0) drawQuotes(c);
    }

    /**
     * Where you are in the row: one dot per suggestion, the current one a longer pill (iOS-style), sliding to the next
     * as the row moves and wrapping from the last to the first. Centered between the cards and the shelf.
     */
    private void drawRowDots(Canvas c) {
        int n = cards.size();
        if (n < 2 || !picksOn()) return;
        float pos = rowScroll.value + wideAt.value;
        int at = (int) Math.floor(pos);
        float t = pos - at;
        int a = Math.floorMod(at, n), b = Math.floorMod(at + 1, n);
        float dot = 8 * k, pill = 56 * k, gap = 10 * k;
        float total = (n - 1) * (dot + gap) + pill;
        float cy = DOTS_CY * k, x = (w - total) / 2;
        // Apple-style: the current one is a longer dash whose line fills over the 10 s before the row moves on.
        int on = tone(0xD91D1D1F, 0xEBFFFFFF), off = tone(0x381D1D1F, 0x47FFFFFF), track = tone(0x4D1D1D1F, 0x5CFFFFFF);
        int current = t < 0.5f ? a : b;
        float progress = autoProgress();
        fill.setShader(null);
        for (int i = 0; i < n; i++) {
            float grow = (i == a ? 1 - t : 0) + (i == b ? t : 0);
            float dw = dot + (pill - dot) * grow;
            fill.setColor(blend(off, track, grow));
            c.drawRoundRect(x, cy - dot / 2, x + dw, cy + dot / 2, dot / 2, dot / 2, fill);
            if (i == current && grow > 0.5f && progress > 0) {
                fill.setColor(on);
                c.drawRoundRect(x, cy - dot / 2, x + Math.max(dot, dw * progress), cy + dot / 2, dot / 2, dot / 2, fill);
            }
            x += dw + gap;
        }
    }

    /**
     * With suggestions: the clock where "For You" was; beside it, after a hairline, two calm lines centered on the
     * digits: the date, then the weather (symbol, temperature, condition) in a softer tone.
     */
    private void drawHeaderClock(Canvas c) {
        float x = MARGIN * k, base = L_TITLE_BASE * k;
        c.drawText(clockText, x - Ui.leftBearing(lHeadClock, clockText), base, lHeadClock);
        float cap = Ui.cap(lHeadClock), mid = base - cap / 2;
        float clockEnd = x + lHeadClock.measureText(clockText);
        if (!amPmText.isEmpty()) {
            // A small AM/PM on the clock's baseline, a little dimmer, as on Apple's clocks.
            lAmPm.setColor(Ui.alpha(lHeadClock.getColor(), 0.8f));
            c.drawText(amPmText, clockEnd + 10 * k, base, lAmPm);
            clockEnd += 10 * k + lAmPm.measureText(amPmText);
        }
        float lineX = clockEnd + 30 * k, tx = lineX + 30 * k;
        fill.setShader(null);
        fill.setColor(tone(0x26000000, 0x38FFFFFF));
        c.drawRect(lineX, base - cap + 8 * k, lineX + 1.5f * k, base - 2 * k, fill);
        HomeData.Weather wx = data.weather;
        boolean weather = Prefs.weather() && !Double.isNaN(wx.temp);
        float dateBase = weather ? mid - 7 * k : mid + Ui.halfCap(lHeadDate);
        c.drawText(dateText, tx, dateBase, lHeadDate);
        if (!weather) return;
        String line = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay);
        float size = 27 * k, wb = mid + 7 * k + Ui.cap(lHeadWx) + 4 * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, tx + sw / 2, wb - Ui.halfCap(lHeadWx), size, lHeadWx.getColor());
        c.drawText(line, tx + sw + 10 * k, wb, lHeadWx);
    }

    private boolean picksOn() {
        return Recs.hasKey(getContext());
    }

    private void drawClock(Canvas c) {
        float x = MARGIN * k;
        c.drawText(dateText, x - Ui.leftBearing(lDate, dateText), L_DATE_BASE * k, lDate);
        c.drawText(clockText, x - Ui.leftBearing(lClock, clockText), L_CLOCK_BASE * k, lClock);
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) return;
        if (data.weatherAt != weatherLineAt || Prefs.version != weatherLinePrefs || !Ui.language.equals(weatherLineLanguage)) {
            weatherLineAt = data.weatherAt;
            weatherLinePrefs = Prefs.version;
            weatherLineLanguage = Ui.language;
            weatherLine = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "   "
                    + (Ui.ru ? "Макс. " + HomeData.degrees(wx.max) + "  Мин. " + HomeData.degrees(wx.min)
                    : Ui.en ? "H:" + HomeData.degrees(wx.max) + "  L:" + HomeData.degrees(wx.min)
                    : Ui.de ? "H:" + HomeData.degrees(wx.max) + "  T:" + HomeData.degrees(wx.min)
                    : "Máx. " + HomeData.degrees(wx.max) + "  Mín. " + HomeData.degrees(wx.min));
        }
        float size = 40 * k, base = L_WEATHER_BASE * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(lWeather), size, lWeather.getColor());
        c.drawText(weatherLine, x + sw + 16 * k, base, lWeather);
    }

    /**
     * Live TV, HDMI Inputs and Settings: icon-only glass circles (light by day, smoked at night); the focused one turns
     * white with a dark icon (the tvOS focus) and its name appears below it.
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        for (int i = 0; i < lightButtons.size(); i++) {
            RectF r = topRects[NT + i];
            float f = Ui.clamp(topFocus[NT + i].value), s = 1 + 0.1f * f, rad = r.width() / 2 * s;
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                    tone(0.6f, 0.45f), 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
            }
            Symbols.draw(getContext(), c, lightButtons.get(i).symbol, cell.centerX(), cell.centerY(), 27 * k * s,
                    blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
            if (f > 0.01f) {
                String label = lightButtons.get(i).label;
                float half = lTip.measureText(label) / 2;
                float x = Math.min(r.centerX(), CONTENT_R * k - half);
                lTip.setAlpha(Math.round(255 * f));
                c.drawText(label, x, r.bottom + (40 + 4 * f) * k, lTip);
            }
        }
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                // Up from an empty shelf too: move()'s older zones (the hero, the corner capsule) aren't drawn any more.
                if (dy >= 0) return false;
                if (cards.isEmpty()) {
                    zone = Z_TOP;
                    topIndex = picksOn() ? tab : NT;
                } else {
                    zone = Z_CARDS;
                    focusSlot = nearest(cardRects, Math.min(3, cards.size()), shelf.isEmpty() ? w / 2f : tileRects[shelfIndex].centerX());
                    rowMoved();
                }
                break;
            case Z_CARDS:
                if (dx != 0) {
                    int n = cards.size(), slot = focusSlot + dx;
                    if (!rowLoops()) {
                        slot = Math.max(0, Math.min(n - 1, slot));
                        if (slot == focusSlot) return true;
                        focusSlot = slot;
                    } else if (slot > 2) {
                        rowStart++;     // past the right end: the row slides left by one (and loops after the last)
                    } else if (slot < 0) {
                        rowStart--;
                    } else {
                        focusSlot = slot;
                    }
                    rowMoved();
                } else if (dy < 0) {
                    zone = Z_TOP;
                    // Up goes to what is above: the tabs from the first card, the corner buttons from the others.
                    if (focusSlot == 0 || lightButtons.isEmpty()) {
                        topIndex = tab;     // as on Apple TV: moving up to the tabs focuses the selected tab
                    } else {
                        float x = cardRects[focusSlot].centerX(), best = Float.MAX_VALUE;
                        topIndex = NT;
                        for (int i = 0; i < lightButtons.size(); i++) {
                            float d = Math.abs(topRects[NT + i].centerX() - x);
                            if (d < best) { best = d; topIndex = NT + i; }
                        }
                    }
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, cardRects[focusSlot].centerX());
                }
                break;
            case Z_TOP:
                if (dx != 0) {
                    int next = topIndex + dx;
                    if (!tabShown(next)) next += dx;    // no Stremio tab: from Shows straight to the buttons
                    next = Math.max(picksOn() ? 0 : NT, Math.min(NT - 1 + lightButtons.size(), next));
                    if (next == topIndex || !tabShown(next)) return true;
                    topIndex = next;
                    if (topIndex < NT) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (cards.isEmpty()) {
                        zone = Z_SHELF;
                    } else {
                        zone = Z_CARDS;
                        // From the tabs, the first card; from a corner button, the card below it.
                        focusSlot = topIndex < NT ? 0 : nearest(cardRects, Math.min(3, cards.size()), topRects[topIndex].centerX());
                        rowMoved();
                    }
                } else {
                    return true;
                }
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
