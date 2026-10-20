package com.yallagoal.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.List;
import java.util.Locale;

/**
 * المشغّل الداخلي — واجهة بهوية ليلا گول ("ليلة المباراة") فوق محرّك التشغيل ExoPlaybackEngine
 * (AndroidX Media3) عبر طبقة تجريد PlaybackEngine.
 *
 * الواجهة بالكامل مبنية برمجياً (لا layout ولا drawable من res/): أزرار زجاجية فوق الفيديو،
 * أيقونات متجهية ترسمها {@link YgIconView}، شريط تقدّم مخصّص {@link YgSeekView}، والخطوط من assets/fonts.
 *
 * الميزات:
 *  - لمسة واحدة: إظهار/إخفاء الأزرار. لمستان على الثلث الأيسر/الأيمن: ترجيع/تقديم 10 ثوانٍ
 *    (وتتراكم مع اللمسات المتتالية)، ولمستان في الوسط: تشغيل/إيقاف.
 *  - سحب رأسي على نصف الشاشة الأيسر = السطوع، وعلى الأيمن = الصوت.
 *  - سحب أفقي = تقديم/ترجيع مع معاينة للوقت (للمحتوى القابل للسَّعي).
 *  - لوحة إعدادات جانبية: جودة الفيديو + سرعة التشغيل (للفيديو المسجّل) + نسبة العرض.
 *  - بث مباشر بنافذة DVR: سَعي حقيقي وزر «العودة للمباشر»؛ بلا نافذة: شريط حي دون تقدّم مُلفَّق.
 *
 * منطق التشغيل (الاستئناف، التعافي الآلي، تجديد الروابط، تحرير المحرّك...) كما كان تماماً.
 */
public class InternalPlayerActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "extra_url";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_IS_LIVE = "extra_is_live";
    public static final String EXTRA_RESUME_KEY = "extra_resume_key";
    /** اختياري: رابط API يُجدِّد رابط البث عند انتهاء صلاحيته (قنوات الرابط المتجدد فقط). */
    public static final String EXTRA_REFRESH_URL = "extra_refresh_url";

    private static final long CONTROLS_AUTO_HIDE_MS = 4200L;
    private static final long TICK_INTERVAL_MS = 500L;
    private static final int POSITION_SAVE_EVERY_N_TICKS = 10; // كل ~5 ثوانٍ (10 × 500ms)
    private static final long SEEK_STEP_MS = 10_000L;
    private static final long MULTI_TAP_WINDOW_MS = 320L;
    private static final long LIVE_EDGE_SLACK_MS = 12_000L;
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};

    private static final int MODE_NONE = 0;
    private static final int MODE_BRIGHTNESS = 1;
    private static final int MODE_VOLUME = 2;
    private static final int MODE_SEEK = 3;
    private static final int MODE_IGNORE = 4;

    private static final int MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

    // ----- المحرّك والحالة -----
    private PlaybackEngine engine;
    private String contentUrl;
    private String resumeKey;
    private String titleString = "";
    private boolean isLive;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean userSeeking = false;
    private long knownDurationMs = 0L;
    private boolean controlsVisible = true;
    private int tickCounter = 0;
    private PlaybackEngine.ResizeMode currentResizeMode = PlaybackEngine.ResizeMode.FIT;
    private float currentSpeed = 1f;

    // ----- الواجهة -----
    private FrameLayout root;
    private FrameLayout videoContainer;
    private View gestureLayer;

    private FrameLayout loadingOverlay;
    private TextView loadingTitleText;
    private TextView loadingStatusText;

    private FrameLayout errorOverlay;
    private TextView errorText;

    private FrameLayout controlsOverlay;
    private TextView titleText;
    private LinearLayout liveBadgeTop;
    private TextView speedBadge;
    private YgIconView resizeIcon;
    private TextView resizeLabel;
    private YgIconView playPauseIcon;
    private View rewindBtn;
    private View forwardBtn;
    private LinearLayout centerRow;
    private TextView currentTimeText;
    private TextView durationText;
    private YgSeekView seekView;
    private LinearLayout liveBadgeBottom;
    private TextView liveBadgeBottomText;

    private FrameLayout settingsOverlay;
    private View settingsScrim;
    private LinearLayout settingsPanel;
    private LinearLayout qualityListContainer;
    private LinearLayout speedSection;
    private LinearLayout speedGrid;
    private LinearLayout aspectRow;

    // طبقة المؤشرات العابرة (HUD)
    private LinearLayout brightnessHud;
    private LinearLayout volumeHud;
    private YgMeterView brightnessMeter;
    private YgMeterView volumeMeter;
    private TextView brightnessText;
    private TextView volumeText;
    private YgIconView volumeIcon;
    private FrameLayout seekFeedbackLeft;
    private FrameLayout seekFeedbackRight;
    private TextView seekFeedbackLeftText;
    private TextView seekFeedbackRightText;
    private LinearLayout seekPreview;
    private TextView seekPreviewTime;
    private TextView seekPreviewDelta;
    private LinearLayout centerToast;
    private YgIconView centerToastIcon;
    private TextView centerToastText;

    // ----- الإيماءات -----
    private GestureDetector gestureDetector;
    private int gestureMode = MODE_NONE;
    private boolean touchStartedInEdge = false;
    private float brightness = 0.5f;
    private AudioManager audio;
    private int maxVolume = 15;
    private float volumeFraction = 0.5f;
    private long seekDragStartPos = 0L;
    private long seekDragTarget = 0L;
    private long seekDragRange = 120_000L;
    private float seekDragAccumX = 0f;
    private int tapChain = 0;
    private int tapChainSide = 0; // -1 يسار، 0 وسط، +1 يمين
    private long seekFeedbackAccumMs = 0L;
    private boolean chainActive = false;
    private int liveStateCache = -1;

    private final Runnable hideControlsRunnable = new Runnable() {
        @Override
        public void run() {
            hideControls();
        }
    };

    private final Runnable singleTapRunnable = new Runnable() {
        @Override
        public void run() {
            chainActive = false;
            tapChain = 0;
            toggleControls();
        }
    };

    private final Runnable chainResetRunnable = new Runnable() {
        @Override
        public void run() {
            chainActive = false;
            tapChain = 0;
            seekFeedbackAccumMs = 0L;
        }
    };

    private final Runnable hideHudRunnable = new Runnable() {
        @Override
        public void run() {
            fadeOut(brightnessHud);
            fadeOut(volumeHud);
        }
    };

    private final Runnable hideCenterToastRunnable = new Runnable() {
        @Override
        public void run() {
            fadeOut(centerToast);
        }
    };

    private final Runnable hideSeekFeedbackRunnable = new Runnable() {
        @Override
        public void run() {
            fadeOut(seekFeedbackLeft);
            fadeOut(seekFeedbackRight);
        }
    };

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (engine != null) {
                long pos = engine.getCurrentPosition();
                if (!userSeeking) {
                    updateTimeUi(pos, knownDurationMs);
                }
                updateBufferedUi();
                tickCounter++;
                if (tickCounter % POSITION_SAVE_EVERY_N_TICKS == 0) {
                    savePositionNow(pos);
                }
            }
            mainHandler.postDelayed(this, TICK_INTERVAL_MS);
        }
    };

    private final PlaybackEngine.Listener engineListener = new PlaybackEngine.Listener() {
        @Override
        public void onReady(final long durationMs) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    knownDurationMs = durationMs;
                    hideLoading();
                    hideError();
                    if (durationMs > 0 && !isLive) durationText.setText(formatTime(durationMs));
                    applySeekMode();
                    resetAutoHideTimer();
                }
            });
        }

        @Override
        public void onBuffering(final boolean isBuffering) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (isBuffering) showLoading(); else hideLoading();
                }
            });
        }

        @Override
        public void onError(final String userMessage) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    showError(userMessage);
                }
            });
        }

        @Override
        public void onRecovering(final String statusMessage) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    showRecovering(statusMessage);
                }
            });
        }

        @Override
        public void onEnded() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (!isLive && resumeKey != null && !resumeKey.isEmpty()) {
                        PlaybackPositionStore.clearPosition(getApplicationContext(), resumeKey);
                    }
                    finish();
                }
            });
        }

        @Override
        public void onPlayingStateChanged(final boolean isPlaying) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    updatePlayPauseIcon(isPlaying);
                }
            });
        }

        @Override
        public void onTracksChanged() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (isSettingsVisible()) {
                        refreshQualityListContent();
                    }
                }
            });
        }
    };

    // =====================================================================================
    //  دورة الحياة
    // =====================================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            // الفيديو يمتد تحت الشق (notch) في الوضع الأفقي بدل شريط أسود جانبي
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        contentUrl = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        titleString = title != null ? title : "";
        isLive = getIntent().getBooleanExtra(EXTRA_IS_LIVE, false);
        resumeKey = getIntent().getStringExtra(EXTRA_RESUME_KEY);

        setContentView(buildUi());
        hideSystemBars();

        if (contentUrl == null || contentUrl.isEmpty()) {
            finish();
            return;
        }

        titleText.setText(titleString);
        loadingTitleText.setText(titleString);
        liveBadgeTop.setVisibility(isLive ? View.VISIBLE : View.GONE);
        liveBadgeBottom.setVisibility(isLive ? View.VISIBLE : View.GONE);
        durationText.setVisibility(isLive ? View.GONE : View.VISIBLE);
        applySeekMode();

        initBrightness();
        initVolume();

        // المحرك الوحيد المتاح الآن — لا حاجة لأي فرع اختيار
        engine = new ExoPlaybackEngine();
        engine.initialize(getApplicationContext());
        engine.attachTo(videoContainer);
        engine.setListener(engineListener);
        engine.setResizeMode(currentResizeMode);

        // قنوات الرابط المتجدد: نمنح المحرّك مصدراً موثوقاً لجلب رابط جديد عند انتهاء صلاحية التوكن.
        String refreshUrl = getIntent().getStringExtra(EXTRA_REFRESH_URL);
        if (refreshUrl != null && !refreshUrl.isEmpty()) {
            engine.setUrlRefresher(new ApiUrlRefresher(refreshUrl));
        }

        startPlayback();
    }

    @Override
    public void onBackPressed() {
        if (isSettingsVisible()) {
            hideSettings();
            return;
        }
        finishAndSave();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        mainHandler.post(tickRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(tickRunnable);
        if (engine != null) {
            savePositionNow(engine.getCurrentPosition());
        }
        // أي إنهاء للنشاط (back، انتهاء الفيديو، زر رجوع الخطأ...) يحرّر المشغّل فوراً.
        if (isFinishing()) {
            releaseEngineNow();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        releaseEngineNow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
        }
    }

    // =====================================================================================
    //  بناء الواجهة
    // =====================================================================================

    private View buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        videoContainer = new FrameLayout(this);
        root.addView(videoContainer, fl(MATCH, MATCH));

        gestureLayer = new View(this);
        setupGestures();
        root.addView(gestureLayer, fl(MATCH, MATCH));

        root.addView(buildHudLayer(), fl(MATCH, MATCH));

        loadingOverlay = buildLoadingOverlay();
        root.addView(loadingOverlay, fl(MATCH, MATCH));

        controlsOverlay = buildControlsOverlay();
        root.addView(controlsOverlay, fl(MATCH, MATCH));

        errorOverlay = buildErrorOverlay();
        errorOverlay.setVisibility(View.GONE);
        root.addView(errorOverlay, fl(MATCH, MATCH));

        settingsOverlay = buildSettingsOverlay();
        settingsOverlay.setVisibility(View.GONE);
        root.addView(settingsOverlay, fl(MATCH, MATCH));

        // مساحات الشق/الحواف: نُبعد عناصر التحكّم عن منطقة القص دون المساس بالفيديو نفسه
        ViewCompat.setOnApplyWindowInsetsListener(controlsOverlay, new androidx.core.view.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
                Insets i = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                        | WindowInsetsCompat.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return insets;
            }
        });
        return root;
    }

    // ---------- المؤشرات العابرة (سطوع/صوت/تقديم/إشعار وسطي) ----------

    private FrameLayout buildHudLayer() {
        FrameLayout layer = new FrameLayout(this);

        // سطوع (يسار)
        brightnessHud = new LinearLayout(this);
        brightnessMeter = new YgMeterView(this);
        brightnessText = hudText();
        buildHudCapsule(brightnessHud, new YgIconView(this, "sun-medium", Color.WHITE), brightnessMeter, brightnessText);
        FrameLayout.LayoutParams bp = fl(dp(54), dp(176), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        bp.leftMargin = dp(40);
        layer.addView(brightnessHud, bp);

        // صوت (يمين)
        volumeHud = new LinearLayout(this);
        volumeMeter = new YgMeterView(this);
        volumeText = hudText();
        volumeIcon = new YgIconView(this, "volume-2", Color.WHITE);
        buildHudCapsule(volumeHud, volumeIcon, volumeMeter, volumeText);
        FrameLayout.LayoutParams vp = fl(dp(54), dp(176), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        vp.rightMargin = dp(40);
        layer.addView(volumeHud, vp);

        // أصداء اللمس المزدوج
        seekFeedbackLeft = buildSeekFeedback(false);
        seekFeedbackLeftText = (TextView) ((LinearLayout) seekFeedbackLeft.getChildAt(0)).getChildAt(1);
        FrameLayout.LayoutParams lfp = fl(dp(170), dp(170), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        lfp.leftMargin = -dp(56);
        layer.addView(seekFeedbackLeft, lfp);

        seekFeedbackRight = buildSeekFeedback(true);
        seekFeedbackRightText = (TextView) ((LinearLayout) seekFeedbackRight.getChildAt(0)).getChildAt(1);
        FrameLayout.LayoutParams rfp = fl(dp(170), dp(170), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        rfp.rightMargin = -dp(56);
        layer.addView(seekFeedbackRight, rfp);

        // معاينة السحب الأفقي
        seekPreview = new LinearLayout(this);
        seekPreview.setOrientation(LinearLayout.HORIZONTAL);
        seekPreview.setGravity(Gravity.CENTER);
        seekPreview.setPadding(dp(18), dp(8), dp(18), dp(8));
        seekPreview.setBackground(YgUi.rect(0xB8050A1B, dp(22)));
        seekPreviewTime = label("0:00", 26, Color.WHITE, YgUi.display(this));
        seekPreviewDelta = label("", 15, YgUi.EMBER_2, YgUi.display(this));
        LinearLayout.LayoutParams dlp = ll(WRAP, WRAP);
        dlp.leftMargin = dp(10);
        seekPreview.addView(seekPreviewTime, ll(WRAP, WRAP));
        seekPreview.addView(seekPreviewDelta, dlp);
        seekPreview.setVisibility(View.GONE);
        layer.addView(seekPreview, fl(WRAP, WRAP, Gravity.CENTER_HORIZONTAL | Gravity.TOP));
        ((FrameLayout.LayoutParams) seekPreview.getLayoutParams()).topMargin = dp(46);

        // إشعار وسطي (نسبة العرض، السرعة...)
        centerToast = new LinearLayout(this);
        centerToast.setOrientation(LinearLayout.HORIZONTAL);
        centerToast.setGravity(Gravity.CENTER_VERTICAL);
        centerToast.setPadding(dp(16), 0, dp(18), 0);
        centerToast.setBackground(toastBackground());
        centerToastText = label("", 13, Color.WHITE, YgUi.bold(this));
        centerToastIcon = new YgIconView(this, "scan", YgUi.COBALT_3);
        LinearLayout.LayoutParams iconLp = ll(dp(18), dp(18));
        iconLp.rightMargin = dp(8);
        centerToast.addView(centerToastIcon, iconLp);
        centerToast.addView(centerToastText, ll(WRAP, WRAP));
        centerToast.setVisibility(View.GONE);
        layer.addView(centerToast, fl(WRAP, dp(42), Gravity.CENTER));

        brightnessHud.setVisibility(View.GONE);
        volumeHud.setVisibility(View.GONE);
        seekFeedbackLeft.setVisibility(View.GONE);
        seekFeedbackRight.setVisibility(View.GONE);
        return layer;
    }

    private GradientDrawable toastBackground() {
        GradientDrawable gd = YgUi.rect(0xB8050A1B, dp(21));
        gd.setStroke(Math.max(1, dp(1)), GLASS_EDGE);
        return gd;
    }

    private static final int GLASS_EDGE = 0x33FFFFFF;

    private TextView hudText() {
        return label("0", 14, Color.WHITE, YgUi.display(this));
    }

    private void buildHudCapsule(LinearLayout capsule, YgIconView icon, YgMeterView meter, TextView text) {
        capsule.setOrientation(LinearLayout.VERTICAL);
        capsule.setGravity(Gravity.CENTER_HORIZONTAL);
        capsule.setPadding(0, dp(12), 0, dp(10));
        GradientDrawable bg = YgUi.rect(0xA6050A1B, dp(27));
        bg.setStroke(Math.max(1, dp(1)), GLASS_EDGE);
        capsule.setBackground(bg);
        capsule.addView(icon, ll(dp(20), dp(20)));
        LinearLayout.LayoutParams mp = ll(dp(6), 0, 1f);
        mp.topMargin = dp(8);
        mp.bottomMargin = dp(8);
        capsule.addView(meter, mp);
        capsule.addView(text, ll(WRAP, WRAP));
    }

    /** دائرة شبه شفافة بسهمين و«+10» تظهر عند اللمس المزدوج على جانبي الشاشة. */
    private FrameLayout buildSeekFeedback(boolean forward) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(YgUi.oval(0x2EFFFFFF));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        YgIconView chev = new YgIconView(this, forward ? "chevrons-right" : "chevrons-left", Color.WHITE);
        col.addView(chev, ll(dp(30), dp(30)));
        TextView t = label(forward ? "+10" : "-10", 24, Color.WHITE, YgUi.display(this));
        t.setGravity(Gravity.CENTER);
        col.addView(t, ll(WRAP, WRAP));
        TextView s = label("ثوانٍ", 11, 0xD9FFFFFF, YgUi.text(this));
        s.setGravity(Gravity.CENTER);
        col.addView(s, ll(WRAP, WRAP));
        // نُبعد المحتوى نحو مركز الشاشة (الجزء الظاهر من الدائرة)
        FrameLayout.LayoutParams cp = fl(WRAP, WRAP, Gravity.CENTER);
        cp.leftMargin = forward ? 0 : dp(56);
        cp.rightMargin = forward ? dp(56) : 0;
        f.addView(col, cp);
        return f;
    }

    // ---------- التحميل ----------

    private FrameLayout buildLoadingOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x66020510);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        YgSpinnerView spinner = new YgSpinnerView(this);
        col.addView(spinner, ll(dp(62), dp(62)));

        // اسم القناة/الفيلم تحت الدائرة ليعرف المستخدم ماذا يُحمَّل
        loadingTitleText = label("", 14, Color.WHITE, YgUi.bold(this));
        loadingTitleText.setGravity(Gravity.CENTER);
        loadingTitleText.setMaxLines(1);
        loadingTitleText.setEllipsize(TextUtils.TruncateAt.END);
        loadingTitleText.setShadowLayer(8f, 0f, 1f, Color.BLACK);
        LinearLayout.LayoutParams tp = ll(WRAP, WRAP);
        tp.topMargin = dp(18);
        col.addView(loadingTitleText, tp);

        // نص حالة يظهر فقط أثناء التعافي الآلي (مثل «جارٍ إعادة الاتصال (2/5)…») حتى لا ينتظر
        // المستخدم أمام دائرة صامتة؛ مخفي افتراضياً ويُمسح مع إخفاء التحميل.
        loadingStatusText = label("", 12, YgUi.MIST, YgUi.text(this));
        loadingStatusText.setGravity(Gravity.CENTER);
        loadingStatusText.setShadowLayer(8f, 0f, 1f, Color.BLACK);
        loadingStatusText.setVisibility(View.GONE);
        LinearLayout.LayoutParams sp = ll(WRAP, WRAP);
        sp.topMargin = dp(4);
        col.addView(loadingStatusText, sp);

        FrameLayout.LayoutParams cp = fl(dp(300), WRAP, Gravity.CENTER);
        overlay.addView(col, cp);
        return overlay;
    }

    // ---------- الخطأ ----------

    private FrameLayout buildErrorOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xC7020510);
        overlay.setClickable(true); // يمتص اللمس خلفه

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(22), dp(24), dp(22), dp(20));
        card.setBackground(YgUi.panel(this, dp(26)));

        FrameLayout iconBox = new FrameLayout(this);
        GradientDrawable ib = YgUi.rect(0x24FF5A6E, dp(20));
        ib.setStroke(Math.max(1, dp(1)), 0x59FF5A6E);
        iconBox.setBackground(ib);
        iconBox.addView(new YgIconView(this, "triangle-alert", YgUi.BAD), fl(dp(30), dp(30), Gravity.CENTER));
        card.addView(iconBox, ll(dp(60), dp(60)));

        TextView title = label("تعذّر التشغيل", 22, Color.WHITE, YgUi.display(this));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = ll(WRAP, WRAP);
        tlp.topMargin = dp(14);
        card.addView(title, tlp);

        errorText = label("تعذر تشغيل هذا المحتوى.", 12.5f, YgUi.MIST, YgUi.text(this));
        errorText.setGravity(Gravity.CENTER);
        errorText.setLineSpacing(0f, 1.35f);
        LinearLayout.LayoutParams elp = ll(MATCH, WRAP);
        elp.topMargin = dp(8);
        elp.bottomMargin = dp(18);
        card.addView(errorText, elp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        // «رجوع» على اليسار و«إعادة المحاولة» على اليمين (اتجاه عربي)
        TextView back = label("رجوع", 13.5f, Color.WHITE, YgUi.bold(this));
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(22), 0, dp(22), 0);
        back.setBackground(YgUi.glass(this, dp(15), false));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishAndSave();
            }
        });
        YgUi.pressable(back);
        row.addView(back, ll(WRAP, dp(44)));

        LinearLayout retry = new LinearLayout(this);
        retry.setOrientation(LinearLayout.HORIZONTAL);
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(dp(20), 0, dp(22), 0);
        retry.setBackground(YgUi.primary(dp(15), false));
        TextView retryText = label("إعادة المحاولة", 13.5f, Color.WHITE, YgUi.bold(this));
        retry.addView(retryText, ll(WRAP, WRAP));
        YgIconView retryIcon = new YgIconView(this, "refresh-cw", Color.WHITE);
        LinearLayout.LayoutParams rip = ll(dp(18), dp(18));
        rip.leftMargin = dp(8);
        retry.addView(retryIcon, rip);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideError();
                startPlayback();
            }
        });
        YgUi.pressable(retry);
        LinearLayout.LayoutParams rlp = ll(WRAP, dp(44));
        rlp.leftMargin = dp(10);
        row.addView(retry, rlp);

        card.addView(row, ll(WRAP, WRAP));
        overlay.addView(card, fl(dp(340), WRAP, Gravity.CENTER));
        return overlay;
    }

    // ---------- عناصر التحكّم ----------

    private FrameLayout buildControlsOverlay() {
        FrameLayout overlay = new FrameLayout(this);

        // ===== الشريط العلوي (من اليسار): إعدادات | نسبة العرض | العنوان | رجوع =====
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackground(scrim(true));
        top.setPadding(dp(18), dp(14), dp(18), dp(34));

        View settingsBtn = glassIconButton("sliders-horizontal", 44, 22, "إعدادات التشغيل");
        settingsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
        top.addView(settingsBtn, ll(dp(44), dp(44)));

        LinearLayout resizeChip = new LinearLayout(this);
        resizeChip.setOrientation(LinearLayout.HORIZONTAL);
        resizeChip.setGravity(Gravity.CENTER_VERTICAL);
        resizeChip.setPadding(dp(14), 0, dp(14), 0);
        resizeChip.setBackground(YgUi.glass(this, dp(19), false));
        resizeChip.setContentDescription("نسبة العرض");
        resizeLabel = label("احتواء", 12, Color.WHITE, YgUi.bold(this));
        resizeIcon = new YgIconView(this, "scan", Color.WHITE);
        resizeChip.addView(resizeLabel, ll(WRAP, WRAP));
        LinearLayout.LayoutParams rilp = ll(dp(16), dp(16));
        rilp.leftMargin = dp(7);
        resizeChip.addView(resizeIcon, rilp);
        resizeChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleResizeMode();
                resetAutoHideTimer();
            }
        });
        YgUi.pressable(resizeChip);
        LinearLayout.LayoutParams rcp = ll(WRAP, dp(38));
        rcp.leftMargin = dp(10);
        top.addView(resizeChip, rcp);

        // العنوان + شارات (مباشر/السرعة) — محاذاة لليمين كأي نص عربي
        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setGravity(Gravity.RIGHT);
        titleText = label("", 17, Color.WHITE, YgUi.bold(this));
        titleText.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        titleText.setMaxLines(1);
        titleText.setEllipsize(TextUtils.TruncateAt.END);
        titleText.setShadowLayer(10f, 0f, 1f, 0xCC000000);
        titleBlock.addView(titleText, ll(MATCH, WRAP));

        LinearLayout badges = new LinearLayout(this);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        badges.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        speedBadge = label("", 12, 0xFF2A1200, YgUi.display(this));
        speedBadge.setGravity(Gravity.CENTER);
        speedBadge.setPadding(dp(9), dp(1), dp(9), dp(1));
        speedBadge.setBackground(YgUi.rect(YgUi.EMBER_2, dp(11)));
        speedBadge.setVisibility(View.GONE);
        LinearLayout.LayoutParams sbp = ll(WRAP, dp(22));
        sbp.rightMargin = dp(8);
        badges.addView(speedBadge, sbp);
        liveBadgeTop = buildLivePill(11, 22);
        badges.addView(liveBadgeTop, ll(WRAP, dp(22)));
        LinearLayout.LayoutParams blp = ll(MATCH, WRAP);
        blp.topMargin = dp(4);
        titleBlock.addView(badges, blp);

        LinearLayout.LayoutParams tblp = ll(0, WRAP, 1f);
        tblp.leftMargin = dp(14);
        tblp.rightMargin = dp(14);
        top.addView(titleBlock, tblp);

        View backBtn = glassIconButton("arrow-right", 44, 22, "رجوع");
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishAndSave();
            }
        });
        top.addView(backBtn, ll(dp(44), dp(44)));

        overlay.addView(top, fl(MATCH, WRAP, Gravity.TOP));

        // ===== منتصف الشاشة: ترجيع | تشغيل/إيقاف | تقديم =====
        LinearLayout center = new LinearLayout(this);
        centerRow = center;
        center.setOrientation(LinearLayout.HORIZONTAL);
        center.setGravity(Gravity.CENTER_VERTICAL);

        rewindBtn = buildSkipButton(false);
        rewindBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekRelative(-SEEK_STEP_MS);
                resetAutoHideTimer();
            }
        });
        center.addView(rewindBtn, ll(dp(58), dp(58)));

        FrameLayout playOuter = new FrameLayout(this);
        playOuter.setBackground(YgUi.oval(YgUi.withAlpha(YgUi.COBALT, 0x38)));
        FrameLayout playInner = new FrameLayout(this);
        playInner.setBackground(YgUi.primary(0, true));
        playPauseIcon = new YgIconView(this, "play", Color.WHITE);
        playPauseIcon.setFilled(true);
        playPauseIcon.setStrokeUnits(1.6f);
        playPauseIcon.setTranslationX(dp(1.5f));
        FrameLayout.LayoutParams ipp = fl(dp(36), dp(36), Gravity.CENTER);
        playInner.addView(playPauseIcon, ipp);
        playOuter.addView(playInner, fl(dp(80), dp(80), Gravity.CENTER));
        playOuter.setContentDescription("تشغيل أو إيقاف");
        playOuter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlayPause();
                resetAutoHideTimer();
            }
        });
        YgUi.pressable(playOuter);
        LinearLayout.LayoutParams pp = ll(dp(98), dp(98));
        pp.leftMargin = dp(26);
        pp.rightMargin = dp(26);
        center.addView(playOuter, pp);

        forwardBtn = buildSkipButton(true);
        forwardBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekRelative(SEEK_STEP_MS);
                resetAutoHideTimer();
            }
        });
        center.addView(forwardBtn, ll(dp(58), dp(58)));
        overlay.addView(center, fl(WRAP, WRAP, Gravity.CENTER));

        // ===== الشريط السفلي: الوقت الحالي | شريط التقدّم | المدة أو شارة المباشر =====
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackground(scrim(false));
        bottom.setPadding(dp(18), dp(40), dp(18), dp(14));

        currentTimeText = label("0:00", 17, Color.WHITE, YgUi.display(this));
        currentTimeText.setGravity(Gravity.CENTER);
        currentTimeText.setMinWidth(dp(54));
        currentTimeText.setShadowLayer(8f, 0f, 1f, 0x99000000);
        bottom.addView(currentTimeText, ll(WRAP, WRAP));

        seekView = new YgSeekView(this);
        seekView.setListener(new YgSeekView.Listener() {
            @Override
            public void onSeekStart() {
                userSeeking = true;
                cancelAutoHideTimer();
            }

            @Override
            public void onSeekChange(float fraction) {
                if (knownDurationMs > 0) {
                    currentTimeText.setText(formatTime((long) (fraction * knownDurationMs)));
                }
            }

            @Override
            public void onSeekEnd(float fraction) {
                if (knownDurationMs > 0 && engine != null) {
                    engine.seekTo((long) (fraction * knownDurationMs));
                }
                userSeeking = false;
                resetAutoHideTimer();
            }
        });
        LinearLayout.LayoutParams skp = ll(0, dp(38), 1f);
        skp.leftMargin = dp(4);
        skp.rightMargin = dp(4);
        bottom.addView(seekView, skp);

        FrameLayout endSlot = new FrameLayout(this);
        durationText = label("0:00", 17, 0xB3FFFFFF, YgUi.display(this));
        durationText.setGravity(Gravity.CENTER);
        durationText.setMinWidth(dp(54));
        durationText.setShadowLayer(8f, 0f, 1f, 0x99000000);
        endSlot.addView(durationText, fl(WRAP, WRAP, Gravity.CENTER));
        liveBadgeBottom = buildLivePill(12, 28);
        liveBadgeBottomText = (TextView) liveBadgeBottom.getChildAt(1);
        liveBadgeBottom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goToLiveEdge();
            }
        });
        endSlot.addView(liveBadgeBottom, fl(WRAP, dp(28), Gravity.CENTER));
        bottom.addView(endSlot, ll(WRAP, WRAP));

        overlay.addView(bottom, fl(MATCH, WRAP, Gravity.BOTTOM));
        return overlay;
    }

    /** زر دائري زجاجي بأيقونة متجهية. */
    private View glassIconButton(String icon, int sizeDp, int iconDp, String description) {
        FrameLayout b = new FrameLayout(this);
        b.setBackground(YgUi.glass(this, 0, true));
        b.addView(new YgIconView(this, icon, Color.WHITE), fl(dp(iconDp), dp(iconDp), Gravity.CENTER));
        b.setContentDescription(description);
        YgUi.pressable(b);
        return b;
    }

    /** زر ترجيع/تقديم 10 ثوانٍ: سهم دائري + رقم 10 بخط العناوين. */
    private View buildSkipButton(boolean forward) {
        FrameLayout b = new FrameLayout(this);
        b.setBackground(YgUi.glass(this, 0, true));
        b.addView(new YgIconView(this, forward ? "rotate-cw" : "rotate-ccw", Color.WHITE), fl(dp(32), dp(32), Gravity.CENTER));
        TextView n = label("10", 11, Color.WHITE, YgUi.display(this));
        n.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams np = fl(WRAP, WRAP, Gravity.CENTER);
        np.topMargin = dp(1);
        b.addView(n, np);
        b.setContentDescription(forward ? "تقديم 10 ثوانٍ" : "ترجيع 10 ثوانٍ");
        YgUi.pressable(b);
        return b;
    }

    /** شارة «مباشر»: نقطة نابضة + نص، خلفية حمراء. */
    private LinearLayout buildLivePill(float textSp, int heightDp) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(10), 0, dp(11), 0);
        pill.setBackground(YgUi.rect(YgUi.LIVE, dp(heightDp) / 2f));

        View dot = new View(this);
        dot.setBackground(YgUi.oval(Color.WHITE));
        AlphaAnimation pulse = new AlphaAnimation(1f, 0.3f);
        pulse.setDuration(850);
        pulse.setRepeatMode(Animation.REVERSE);
        pulse.setRepeatCount(Animation.INFINITE);
        dot.startAnimation(pulse);
        LinearLayout.LayoutParams dotLp = ll(dp(7), dp(7));
        dotLp.rightMargin = dp(6);
        pill.addView(dot, dotLp);

        TextView t = label("مباشر", textSp, Color.WHITE, YgUi.bold(this));
        pill.addView(t, ll(WRAP, WRAP));
        return pill;
    }

    // ---------- لوحة الإعدادات الجانبية ----------

    private FrameLayout buildSettingsOverlay() {
        FrameLayout overlay = new FrameLayout(this);

        settingsScrim = new View(this);
        settingsScrim.setBackgroundColor(0x99020510);
        settingsScrim.setClickable(true);
        settingsScrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideSettings();
            }
        });
        overlay.addView(settingsScrim, fl(MATCH, MATCH));

        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setClickable(true);
        GradientDrawable pbg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFA0E1634, 0xFA050A1B});
        float r = dp(26);
        pbg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        pbg.setStroke(Math.max(1, dp(1)), YgUi.LINE);
        settingsPanel.setBackground(pbg);
        settingsPanel.setPadding(dp(16), dp(14), dp(16), dp(10));

        // الرأس: [إغلاق]  ......  [العنوان]
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View close = glassIconButton("x", 38, 18, "إغلاق");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideSettings();
            }
        });
        head.addView(close, ll(dp(38), dp(38)));
        TextView ht = label("إعدادات التشغيل", 21, Color.WHITE, YgUi.display(this));
        ht.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        head.addView(ht, ll(0, WRAP, 1f));
        settingsPanel.addView(head, ll(MATCH, WRAP));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(4), 0, dp(14));

        // جودة الفيديو
        body.addView(sectionLabel("جودة الفيديو"), ll(MATCH, WRAP));
        qualityListContainer = new LinearLayout(this);
        qualityListContainer.setOrientation(LinearLayout.VERTICAL);
        body.addView(qualityListContainer, ll(MATCH, WRAP));

        // سرعة التشغيل (للفيديو المسجّل فقط)
        speedSection = new LinearLayout(this);
        speedSection.setOrientation(LinearLayout.VERTICAL);
        speedSection.addView(sectionLabel("سرعة التشغيل"), ll(MATCH, WRAP));
        speedGrid = new LinearLayout(this);
        speedGrid.setOrientation(LinearLayout.VERTICAL);
        speedSection.addView(speedGrid, ll(MATCH, WRAP));
        body.addView(speedSection, ll(MATCH, WRAP));

        // نسبة العرض
        body.addView(sectionLabel("نسبة العرض"), ll(MATCH, WRAP));
        aspectRow = new LinearLayout(this);
        aspectRow.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(aspectRow, ll(MATCH, WRAP));

        scroll.addView(body, new FrameLayout.LayoutParams(MATCH, WRAP));
        settingsPanel.addView(scroll, ll(MATCH, 0, 1f));

        overlay.addView(settingsPanel, fl(dp(340), MATCH, Gravity.RIGHT));
        return overlay;
    }

    private TextView sectionLabel(String text) {
        TextView t = label(text, 12, YgUi.COBALT_3, YgUi.bold(this));
        t.setGravity(Gravity.RIGHT);
        t.setPadding(dp(2), dp(14), dp(2), dp(8));
        return t;
    }

    private void showSettings() {
        refreshQualityListContent();
        refreshSpeedGrid();
        refreshAspectRow();
        speedSection.setVisibility(isLive ? View.GONE : View.VISIBLE);
        cancelAutoHideTimer();

        int w = Math.min(dp(340), (int) (root.getWidth() * 0.9f));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) settingsPanel.getLayoutParams();
        lp.width = w;
        settingsPanel.setLayoutParams(lp);

        settingsOverlay.setVisibility(View.VISIBLE);
        settingsScrim.setAlpha(0f);
        settingsScrim.animate().alpha(1f).setDuration(200).start();
        settingsPanel.setTranslationX(w);
        settingsPanel.animate().translationX(0f).setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    private void hideSettings() {
        if (!isSettingsVisible()) return;
        int w = settingsPanel.getWidth();
        settingsScrim.animate().alpha(0f).setDuration(180).start();
        settingsPanel.animate().translationX(w).setDuration(200).withEndAction(new Runnable() {
            @Override
            public void run() {
                settingsOverlay.setVisibility(View.GONE);
            }
        }).start();
        resetAutoHideTimer();
    }

    private boolean isSettingsVisible() {
        return settingsOverlay != null && settingsOverlay.getVisibility() == View.VISIBLE;
    }

    private void refreshQualityListContent() {
        if (qualityListContainer == null || engine == null) return;
        qualityListContainer.removeAllViews();

        String selected = engine.getSelectedQualityId();
        if (selected == null || selected.isEmpty()) selected = "auto";

        addQualityRow("auto", "تلقائي", "auto".equals(selected));

        List<PlaybackEngine.QualityOption> options = engine.getAvailableQualities();
        if (options != null) {
            for (PlaybackEngine.QualityOption opt : options) {
                if (opt == null || opt.id == null || opt.id.isEmpty()) continue;
                addQualityRow(opt.id, opt.label, opt.id.equals(selected));
            }
        }
    }

    private void addQualityRow(final String id, String label, boolean isSelected) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, dp(16), 0);
        GradientDrawable bg = YgUi.rect(isSelected ? 0x382F6BFF : 0x0FFFFFFF, dp(13));
        if (isSelected) bg.setStroke(Math.max(1, dp(1.4f)), 0xCC5B8DFF);
        row.setBackground(bg);

        YgIconView check = new YgIconView(this, "check", YgUi.COBALT_3);
        check.setStrokeUnits(2.8f);
        check.setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
        row.addView(check, ll(dp(18), dp(18)));

        TextView t = label(label != null && !label.isEmpty() ? label : id, 14,
                isSelected ? Color.WHITE : YgUi.CHALK,
                isSelected ? YgUi.bold(this) : YgUi.text(this));
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(t, ll(0, WRAP, 1f));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (engine != null) engine.selectQuality(id);
                hideSettings();
            }
        });
        YgUi.pressable(row);
        LinearLayout.LayoutParams lp = ll(MATCH, dp(44));
        lp.bottomMargin = dp(7);
        qualityListContainer.addView(row, lp);
    }

    private void refreshSpeedGrid() {
        speedGrid.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < SPEEDS.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = ll(MATCH, dp(40));
                rlp.bottomMargin = dp(8);
                speedGrid.addView(row, rlp);
            }
            final float s = SPEEDS[i];
            boolean sel = Math.abs(s - currentSpeed) < 0.01f;
            TextView pill = label(formatSpeed(s), 16, Color.WHITE, YgUi.display(this));
            pill.setGravity(Gravity.CENTER);
            pill.setBackground(sel ? YgUi.primary(dp(20), false) : YgUi.rect(0x14FFFFFF, dp(20)));
            pill.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setSpeed(s);
                    refreshSpeedGrid();
                }
            });
            YgUi.pressable(pill);
            LinearLayout.LayoutParams plp = ll(0, MATCH, 1f);
            if (i % 3 != 0) plp.leftMargin = dp(8);
            row.addView(pill, plp);
        }
    }

    private void refreshAspectRow() {
        aspectRow.removeAllViews();
        PlaybackEngine.ResizeMode[] modes = {PlaybackEngine.ResizeMode.FIT,
                PlaybackEngine.ResizeMode.FILL, PlaybackEngine.ResizeMode.ZOOM};
        for (int i = 0; i < modes.length; i++) {
            final PlaybackEngine.ResizeMode m = modes[i];
            boolean sel = (m == currentResizeMode);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            GradientDrawable bg = YgUi.rect(sel ? 0x382F6BFF : 0x0FFFFFFF, dp(14));
            if (sel) bg.setStroke(Math.max(1, dp(1.4f)), 0xCC5B8DFF);
            cell.setBackground(bg);
            cell.addView(new YgIconView(this, resizeIconName(m), sel ? Color.WHITE : YgUi.MIST), ll(dp(20), dp(20)));
            TextView t = label(resizeLabelText(m), 11.5f, sel ? Color.WHITE : YgUi.MIST,
                    sel ? YgUi.bold(this) : YgUi.text(this));
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tlp = ll(WRAP, WRAP);
            tlp.topMargin = dp(5);
            cell.addView(t, tlp);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setResizeMode(m, true);
                    refreshAspectRow();
                }
            });
            YgUi.pressable(cell);
            LinearLayout.LayoutParams clp = ll(0, dp(62), 1f);
            if (i > 0) clp.leftMargin = dp(8);
            aspectRow.addView(cell, clp);
        }
    }

    // =====================================================================================
    //  الإيماءات
    // =====================================================================================

    @SuppressLint("ClickableViewAccessibility")
    private void setupGestures() {
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                gestureMode = MODE_NONE;
                seekDragAccumX = 0f;
                int w = gestureLayer.getWidth();
                int h = gestureLayer.getHeight();
                int edge = dp(26);
                // اللمسات الملاصقة للحواف نتركها لإيماءات النظام (سحب شريط التنقّل/الإشعارات)
                touchStartedInEdge = e.getX() < edge || e.getX() > w - edge
                        || e.getY() < edge || e.getY() > h - edge;
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                handleTap(e.getX());
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (e1 == null) return false;
                int w = Math.max(1, gestureLayer.getWidth());
                int h = Math.max(1, gestureLayer.getHeight());
                if (gestureMode == MODE_NONE) {
                    float totalX = e2.getX() - e1.getX();
                    float totalY = e2.getY() - e1.getY();
                    if (Math.abs(totalX) < slop * 1.6f && Math.abs(totalY) < slop * 1.6f) return false;
                    if (touchStartedInEdge) {
                        gestureMode = MODE_IGNORE;
                        return false;
                    }
                    if (Math.abs(totalY) > Math.abs(totalX)) {
                        gestureMode = e1.getX() < w / 2f ? MODE_BRIGHTNESS : MODE_VOLUME;
                    } else if (canSeek()) {
                        gestureMode = MODE_SEEK;
                        beginSeekDrag();
                    } else {
                        gestureMode = MODE_IGNORE;
                    }
                }
                switch (gestureMode) {
                    case MODE_BRIGHTNESS:
                        applyBrightness(brightness + (dy / h) * 1.15f);
                        break;
                    case MODE_VOLUME:
                        applyVolume(volumeFraction + (dy / h) * 1.15f);
                        break;
                    case MODE_SEEK:
                        seekDragAccumX += -dx;
                        updateSeekDrag(w);
                        break;
                    default:
                        break;
                }
                return true;
            }
        });
        gestureDetector.setIsLongpressEnabled(false);
        gestureDetector.setOnDoubleTapListener(null);

        gestureLayer.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                gestureDetector.onTouchEvent(ev);
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    endGesture();
                }
                return true;
            }
        });
    }

    /** لمسة واحدة = إظهار/إخفاء؛ لمسات متتالية خلال نافذة قصيرة = تقديم/ترجيع متراكم. */
    private void handleTap(float x) {
        if (isSettingsVisible()) return;
        int w = Math.max(1, gestureLayer.getWidth());
        int side = x < w / 3f ? -1 : (x > w * 2f / 3f ? 1 : 0);

        if (chainActive) {
            // لمسة ثانية فأكثر ضمن النافذة
            mainHandler.removeCallbacks(singleTapRunnable);
            mainHandler.removeCallbacks(chainResetRunnable);
            tapChain++;
            if (side == tapChainSide && side != 0) {
                multiTapSeek(side);
            } else if (side == 0 && tapChain == 2 && tapChainSide == 0) {
                togglePlayPause();
                showControlsBriefly();
            } else if (side != tapChainSide) {
                // تغيّر الجانب: نبدأ سلسلة جديدة من هذه اللمسة
                tapChainSide = side;
                tapChain = 1;
                seekFeedbackAccumMs = 0L;
                mainHandler.postDelayed(singleTapRunnable, MULTI_TAP_WINDOW_MS);
                return;
            }
            mainHandler.postDelayed(chainResetRunnable, MULTI_TAP_WINDOW_MS);
            return;
        }
        chainActive = true;
        tapChain = 1;
        tapChainSide = side;
        seekFeedbackAccumMs = 0L;
        mainHandler.removeCallbacks(singleTapRunnable);
        mainHandler.postDelayed(singleTapRunnable, MULTI_TAP_WINDOW_MS);
    }

    private void multiTapSeek(int side) {
        if (!canSeek()) {
            showCenterToast("lock", "لا يمكن التقديم في هذا البث");
            return;
        }
        long delta = side * SEEK_STEP_MS;
        seekRelative(delta);
        seekFeedbackAccumMs += delta;
        long secs = Math.abs(seekFeedbackAccumMs) / 1000L;
        String txt = (side > 0 ? "+" : "-") + secs;
        FrameLayout target = side > 0 ? seekFeedbackRight : seekFeedbackLeft;
        TextView tv = side > 0 ? seekFeedbackRightText : seekFeedbackLeftText;
        FrameLayout other = side > 0 ? seekFeedbackLeft : seekFeedbackRight;
        tv.setText(txt);
        other.setVisibility(View.GONE);
        fadeIn(target);
        mainHandler.removeCallbacks(hideSeekFeedbackRunnable);
        mainHandler.postDelayed(hideSeekFeedbackRunnable, 650);
    }

    private void endGesture() {
        if (gestureMode == MODE_SEEK) {
            commitSeekDrag();
        }
        if (gestureMode == MODE_BRIGHTNESS || gestureMode == MODE_VOLUME) {
            mainHandler.removeCallbacks(hideHudRunnable);
            mainHandler.postDelayed(hideHudRunnable, 700);
        }
        gestureMode = MODE_NONE;
    }

    // ----- السطوع -----

    private void initBrightness() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (lp.screenBrightness >= 0f) {
            brightness = lp.screenBrightness;
        } else {
            try {
                int sys = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
                brightness = sys / 255f;
            } catch (Exception e) {
                brightness = 0.5f;
            }
        }
        brightnessMeter.setValue(brightness);
        brightnessText.setText(String.valueOf(Math.round(brightness * 100)));
    }

    private void applyBrightness(float v) {
        brightness = clamp(v, 0.02f, 1f);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = brightness;
        getWindow().setAttributes(lp);
        brightnessMeter.setValue(brightness);
        brightnessText.setText(String.valueOf(Math.round(brightness * 100)));
        fadeIn(brightnessHud);
        fadeOut(volumeHud);
        mainHandler.removeCallbacks(hideHudRunnable);
    }

    // ----- الصوت -----

    private void initVolume() {
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio != null) {
            maxVolume = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            volumeFraction = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) maxVolume;
        }
        volumeMeter.setValue(volumeFraction);
        volumeText.setText(String.valueOf(Math.round(volumeFraction * 100)));
        updateVolumeIcon();
    }

    private void applyVolume(float v) {
        volumeFraction = clamp(v, 0f, 1f);
        if (audio != null) {
            try {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(volumeFraction * maxVolume), 0);
            } catch (SecurityException ignored) {
                // بعض الأجهزة تمنع تغيير الصوت في أوضاع معيّنة (عدم الإزعاج)
            }
        }
        volumeMeter.setValue(volumeFraction);
        volumeText.setText(String.valueOf(Math.round(volumeFraction * 100)));
        updateVolumeIcon();
        fadeIn(volumeHud);
        fadeOut(brightnessHud);
        mainHandler.removeCallbacks(hideHudRunnable);
    }

    private void updateVolumeIcon() {
        if (volumeIcon == null) return;
        if (volumeFraction <= 0.001f) volumeIcon.setIcon("volume-x");
        else if (volumeFraction < 0.5f) volumeIcon.setIcon("volume-1");
        else volumeIcon.setIcon("volume-2");
    }

    // ----- السحب الأفقي للتقديم -----

    private boolean canSeek() {
        return !isLive || knownDurationMs > 0;
    }

    private void beginSeekDrag() {
        if (engine == null) return;
        seekDragStartPos = engine.getCurrentPosition();
        seekDragTarget = seekDragStartPos;
        seekDragRange = knownDurationMs > 0 ? Math.min(knownDurationMs, 180_000L) : 120_000L;
        userSeeking = true;
        cancelAutoHideTimer();
        seekPreview.setVisibility(View.VISIBLE);
        seekPreview.setAlpha(1f);
    }

    private void updateSeekDrag(int screenWidth) {
        float frac = seekDragAccumX / (float) screenWidth;
        long delta = (long) (frac * seekDragRange);
        long target = seekDragStartPos + delta;
        if (target < 0) target = 0;
        if (knownDurationMs > 0 && target > knownDurationMs) target = knownDurationMs;
        seekDragTarget = target;
        long shown = target - seekDragStartPos;
        seekPreviewTime.setText(formatTime(target));
        seekPreviewDelta.setText((shown >= 0 ? "+" : "-") + formatTime(Math.abs(shown)));
        updateTimeUi(target, knownDurationMs);
    }

    private void commitSeekDrag() {
        seekPreview.setVisibility(View.GONE);
        userSeeking = false;
        if (engine != null && Math.abs(seekDragTarget - seekDragStartPos) > 500) {
            engine.seekTo(seekDragTarget);
        }
        resetAutoHideTimer();
    }

    // =====================================================================================
    //  منطق التشغيل (كما كان) + إضافات الواجهة
    // =====================================================================================

    private void startPlayback() {
        if (engine == null) return;
        showLoading();
        long startPos = 0L;
        if (!isLive && resumeKey != null && !resumeKey.isEmpty()) {
            startPos = PlaybackPositionStore.getPosition(getApplicationContext(), resumeKey);
        }
        engine.playUrl(contentUrl, startPos);
    }

    private void togglePlayPause() {
        if (engine == null) return;
        if (engine.isPlaying()) {
            engine.pause();
        } else {
            engine.play();
        }
    }

    private void seekRelative(long deltaMs) {
        if (engine == null) return;
        long current = engine.getCurrentPosition();
        long target = current + deltaMs;
        if (target < 0) target = 0;
        if (knownDurationMs > 0 && target > knownDurationMs) target = knownDurationMs;
        engine.seekTo(target);
        updateTimeUi(target, knownDurationMs);
    }

    private void goToLiveEdge() {
        if (engine == null || knownDurationMs <= 0) return;
        engine.seekTo(knownDurationMs);
        resetAutoHideTimer();
    }

    /** يضبط شكل شريط التقدّم والأزرار بحسب نوع المحتوى (فيديو / بث بنافذة / بث بلا نافذة). */
    private void applySeekMode() {
        boolean liveNoWindow = isLive && knownDurationMs <= 0;
        seekView.setLiveFull(liveNoWindow);
        seekView.setSeekable(!liveNoWindow);
        float a = liveNoWindow ? 0.38f : 1f;
        rewindBtn.setAlpha(a);
        forwardBtn.setAlpha(a);
        if (!isLive && knownDurationMs > 0) durationText.setText(formatTime(knownDurationMs));
        updateLiveBadgeState(engine != null ? engine.getCurrentPosition() : 0L);
    }

    private void updateTimeUi(long positionMs, long durationMs) {
        currentTimeText.setText(formatTime(positionMs));
        if (durationMs > 0) {
            seekView.setProgress(Math.min(1f, Math.max(0f, positionMs / (float) durationMs)));
        }
        if (isLive) updateLiveBadgeState(positionMs);
    }

    private void updateBufferedUi() {
        if (engine == null || knownDurationMs <= 0) return;
        long b = engine.getBufferedPosition();
        seekView.setBuffered(b / (float) knownDurationMs);
    }

    /** شارة الأسفل: حمراء «مباشر» عند الحافة الحية، ورمادية «العودة للمباشر» عند التأخّر ضمن نافذة DVR. */
    private void updateLiveBadgeState(long positionMs) {
        if (!isLive || liveBadgeBottom == null) return;
        boolean dvr = knownDurationMs > 0;
        boolean atEdge = !dvr || (knownDurationMs - positionMs) <= LIVE_EDGE_SLACK_MS;
        int state = atEdge ? 1 : 0;
        if (state == liveStateCache) return;
        liveStateCache = state;
        liveBadgeBottom.setBackground(YgUi.rect(atEdge ? YgUi.LIVE : 0x33FFFFFF, dp(14)));
        liveBadgeBottomText.setText(atEdge ? "مباشر" : "العودة للمباشر");
        liveBadgeBottom.setClickable(dvr && !atEdge);
    }

    private void updatePlayPauseIcon(boolean isPlaying) {
        playPauseIcon.setIcon(isPlaying ? "pause" : "play");
        playPauseIcon.setFilled(true);
        // مثلث التشغيل يبدو أيسر من مركزه الهندسي — نزيحه قليلاً لتوسيط بصري
        playPauseIcon.setTranslationX(isPlaying ? 0f : dp(1.5f));
        playPauseIcon.setScaleX(0.8f);
        playPauseIcon.setScaleY(0.8f);
        playPauseIcon.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
    }

    private void setSpeed(float s) {
        currentSpeed = s;
        if (engine != null) engine.setPlaybackSpeed(s);
        boolean normal = Math.abs(s - 1f) < 0.01f;
        speedBadge.setVisibility(normal ? View.GONE : View.VISIBLE);
        speedBadge.setText(formatSpeed(s));
        showCenterToast("gauge", "السرعة " + formatSpeed(s));
    }

    private static String formatSpeed(float s) {
        if (Math.abs(s - Math.round(s)) < 0.001f) return ((int) Math.round(s)) + "×";
        String t = String.format(Locale.US, "%.2f", s);
        if (t.endsWith("0")) t = t.substring(0, t.length() - 1);
        return t + "×";
    }

    private void cycleResizeMode() {
        PlaybackEngine.ResizeMode next;
        if (currentResizeMode == PlaybackEngine.ResizeMode.FIT) next = PlaybackEngine.ResizeMode.FILL;
        else if (currentResizeMode == PlaybackEngine.ResizeMode.FILL) next = PlaybackEngine.ResizeMode.ZOOM;
        else next = PlaybackEngine.ResizeMode.FIT;
        setResizeMode(next, true);
    }

    private void setResizeMode(PlaybackEngine.ResizeMode mode, boolean announce) {
        currentResizeMode = mode;
        if (engine != null) engine.setResizeMode(mode);
        resizeIcon.setIcon(resizeIconName(mode));
        resizeLabel.setText(resizeLabelText(mode));
        if (announce) showCenterToast(resizeIconName(mode), resizeLabelText(mode));
    }

    private static String resizeIconName(PlaybackEngine.ResizeMode m) {
        switch (m) {
            case FILL:
                return "maximize-2";
            case ZOOM:
                return "zoom-in";
            case FIT:
            default:
                return "scan";
        }
    }

    private static String resizeLabelText(PlaybackEngine.ResizeMode m) {
        switch (m) {
            case FILL:
                return "ملء";
            case ZOOM:
                return "تكبير";
            case FIT:
            default:
                return "احتواء";
        }
    }

    private String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    // ----- التحميل/الخطأ -----

    private void showLoading() {
        loadingOverlay.setVisibility(View.VISIBLE);
        // دائرة التحميل في مركز الشاشة نفسه الذي يشغله زر التشغيل الكبير (الذي يعلوها في الترتيب)
        // فنُخفيه أثناء التحميل ليظهر مؤشر التحميل بوضوح، ثم يعود فور الجاهزية.
        if (centerRow != null) centerRow.setVisibility(View.INVISIBLE);
    }

    private void hideLoading() {
        loadingOverlay.setVisibility(View.GONE);
        if (centerRow != null) centerRow.setVisibility(View.VISIBLE);
        if (loadingStatusText != null) {
            loadingStatusText.setVisibility(View.GONE);
            loadingStatusText.setText("");
        }
    }

    /** المحرّك يتعافى تلقائياً من فشل مؤقت: دائرة التحميل + نص يوضح ما يجري بدل رسالة خطأ. */
    private void showRecovering(String statusMessage) {
        hideError();
        showLoading();
        if (loadingStatusText != null && statusMessage != null && !statusMessage.isEmpty()) {
            loadingStatusText.setText(statusMessage);
            loadingStatusText.setVisibility(View.VISIBLE);
        }
    }

    private void showError(String message) {
        hideLoading();
        errorText.setText(message != null && !message.isEmpty() ? message : "تعذر تشغيل هذا المحتوى.");
        errorOverlay.setVisibility(View.VISIBLE);
        errorOverlay.setAlpha(0f);
        errorOverlay.animate().alpha(1f).setDuration(220).start();
        cancelAutoHideTimer();
        controlsVisible = false;
        controlsOverlay.animate().cancel();
        controlsOverlay.setAlpha(0f);
        controlsOverlay.setVisibility(View.GONE);
    }

    private void hideError() {
        errorOverlay.setVisibility(View.GONE);
    }

    // ----- إظهار/إخفاء عناصر التحكّم -----

    private void toggleControls() {
        if (isSettingsVisible()) return;
        if (controlsVisible) {
            hideControls();
        } else {
            showControls();
        }
    }

    private void showControls() {
        controlsVisible = true;
        controlsOverlay.animate().cancel();
        controlsOverlay.setVisibility(View.VISIBLE);
        controlsOverlay.animate().alpha(1f).setDuration(180).start();
        resetAutoHideTimer();
    }

    private void showControlsBriefly() {
        if (!controlsVisible) showControls(); else resetAutoHideTimer();
    }

    private void hideControls() {
        controlsVisible = false;
        cancelAutoHideTimer();
        controlsOverlay.animate().cancel();
        controlsOverlay.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (!controlsVisible) controlsOverlay.setVisibility(View.GONE);
            }
        }).start();
    }

    private void resetAutoHideTimer() {
        cancelAutoHideTimer();
        mainHandler.postDelayed(hideControlsRunnable, CONTROLS_AUTO_HIDE_MS);
    }

    private void cancelAutoHideTimer() {
        mainHandler.removeCallbacks(hideControlsRunnable);
    }

    // ----- إشعار وسطي + تلاشي -----

    private void showCenterToast(String icon, String text) {
        centerToastIcon.setIcon(icon);
        centerToastText.setText(text);
        fadeIn(centerToast);
        mainHandler.removeCallbacks(hideCenterToastRunnable);
        mainHandler.postDelayed(hideCenterToastRunnable, 1100);
    }

    private void fadeIn(final View v) {
        if (v == null) return;
        v.animate().cancel();
        if (v.getVisibility() != View.VISIBLE) {
            v.setAlpha(0f);
            v.setVisibility(View.VISIBLE);
        }
        v.animate().alpha(1f).setDuration(120).start();
    }

    private void fadeOut(final View v) {
        if (v == null || v.getVisibility() != View.VISIBLE) return;
        v.animate().cancel();
        v.animate().alpha(0f).setDuration(220).withEndAction(new Runnable() {
            @Override
            public void run() {
                v.setVisibility(View.GONE);
            }
        }).start();
    }

    // ----- الحفظ والإغلاق -----

    private void savePositionNow(long positionMs) {
        if (isLive || resumeKey == null || resumeKey.isEmpty()) return;
        PlaybackPositionStore.savePosition(getApplicationContext(), resumeKey, positionMs, knownDurationMs);
    }

    private void finishAndSave() {
        if (engine != null) {
            savePositionNow(engine.getCurrentPosition());
        }
        // نحرّر المشغّل واتصال البث الآن، لا بعد onDestroy: finish() لا تستدعي onDestroy فوراً (تتأخر
        // حتى ينتهي انتقال الشاشة)، وخلال هذه الفترة كان الاتصال القديم مفتوحاً والصوت ما يزال
        // يعمل بينما قد يضغط المستخدم قناة جديدة — فتصطدم بحدّ الاتصالات المتزامنة في حسابات Xtream.
        releaseEngineNow();
        finish();
    }

    private void releaseEngineNow() {
        if (engine != null) {
            engine.release();
            engine = null;
        }
    }

    // =====================================================================================
    //  أدوات رسم مساعدة
    // =====================================================================================

    private int dp(float v) {
        return YgUi.dp(this, v);
    }

    private FrameLayout.LayoutParams fl(int w, int h) {
        return new FrameLayout.LayoutParams(w, h);
    }

    private FrameLayout.LayoutParams fl(int w, int h, int gravity) {
        return new FrameLayout.LayoutParams(w, h, gravity);
    }

    private LinearLayout.LayoutParams ll(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private LinearLayout.LayoutParams ll(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    private TextView label(String text, float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    private GradientDrawable scrim(boolean darkAtTop) {
        int[] colors = darkAtTop
                ? new int[]{0xDB020510, 0x00020510}
                : new int[]{0x00020510, 0xEB020510};
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
