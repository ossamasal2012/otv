package com.yallagoal.app;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.Locale;

/**
 * المشغّل الداخلي الجديد — واجهة واحدة موحّدة تعمل فوق أحد محرّكين قابلين للتبديل عبر
 * PlaybackEngine: ExoPlaybackEngine (AndroidX Media3، الافتراضي) أو VlcPlaybackEngine (LibVLC).
 *
 * الواجهة بالكامل مبنية برمجياً هنا (بدون أي ملف layout أو drawable من res/) عمداً: أي ملف
 * مورد جديد يحتاج إضافته يدوياً بمساره الصحيح بمشروع قائم عرضة للنسيان عند الدمج، بينما ملف
 * Java واحد ذاتي الاكتفاء لا يعتمد على وجود أي شيء خارجه — نفس المبدأ المُتّبع سابقاً في
 * UpdateManager لحوار التقدّم.
 */
public class InternalPlayerActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "extra_url";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_IS_LIVE = "extra_is_live";
    public static final String EXTRA_RESUME_KEY = "extra_resume_key";
    public static final String EXTRA_ENGINE = "extra_engine"; // "vlc" أو غير ذلك (بما فيها فارغ) = ExoPlayer

    private static final long CONTROLS_AUTO_HIDE_MS = 4000L;
    private static final long TICK_INTERVAL_MS = 500L;
    private static final int POSITION_SAVE_EVERY_N_TICKS = 10; // كل ~5 ثوانٍ (10 × 500ms)
    private static final long SEEK_STEP_MS = 10_000L;

    private static final int COLOR_ACCENT = 0xFF10B981;
    private static final int COLOR_ACCENT_DARK_TEXT = 0xFF052E21;
    private static final int COLOR_TRACK_BG = 0x4DFFFFFF;

    private PlaybackEngine engine;
    private String contentUrl;
    private String resumeKey;
    private boolean isLive;

    private FrameLayout videoContainer;
    private FrameLayout loadingOverlay;
    private FrameLayout errorOverlay;
    private TextView errorText;
    private FrameLayout controlsOverlay;
    private TextView titleText;
    private TextView liveBadge;
    private View playPauseBtn;
    private View playGlyph;
    private View pauseGlyph;
    private LinearLayout bottomBar;
    private View rewindBtn;
    private View forwardBtn;
    private TextView currentTimeText;
    private TextView durationText;
    private SeekBar seekBar;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean userSeeking = false;
    private long knownDurationMs = 0L;
    private boolean controlsVisible = true;
    private int tickCounter = 0;

    private final Runnable hideControlsRunnable = new Runnable() {
        @Override
        public void run() {
            hideControls();
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
                    if (!isLive && durationMs > 0) durationText.setText(formatTime(durationMs));
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
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(buildUi());
        hideSystemBars();

        contentUrl = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        isLive = getIntent().getBooleanExtra(EXTRA_IS_LIVE, false);
        resumeKey = getIntent().getStringExtra(EXTRA_RESUME_KEY);
        String engineName = getIntent().getStringExtra(EXTRA_ENGINE);

        if (contentUrl == null || contentUrl.isEmpty()) {
            finish();
            return;
        }

        titleText.setText(title != null ? title : "");
        liveBadge.setVisibility(isLive ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(isLive ? View.GONE : View.VISIBLE);
        rewindBtn.setVisibility(isLive ? View.GONE : View.VISIBLE);
        forwardBtn.setVisibility(isLive ? View.GONE : View.VISIBLE);

        engine = "vlc".equals(engineName) ? new VlcPlaybackEngine() : new ExoPlaybackEngine();
        engine.initialize(getApplicationContext());
        engine.attachTo(videoContainer);
        engine.setListener(engineListener);

        startPlayback();
    }

    // ==================== بناء الواجهة برمجياً بالكامل ====================

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        videoContainer = new FrameLayout(this);
        root.addView(videoContainer, matchParent());

        View tapCatcher = new View(this);
        tapCatcher.setBackgroundColor(Color.TRANSPARENT);
        tapCatcher.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleControls();
            }
        });
        root.addView(tapCatcher, matchParent());

        loadingOverlay = buildLoadingOverlay();
        root.addView(loadingOverlay, matchParent());

        errorOverlay = buildErrorOverlay();
        errorOverlay.setVisibility(View.GONE);
        root.addView(errorOverlay, matchParent());

        controlsOverlay = buildControlsOverlay();
        root.addView(controlsOverlay, matchParent());

        return root;
    }

    private FrameLayout buildLoadingOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x4D000000);

        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(COLOR_ACCENT));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(52), dp(52));
        sp.gravity = Gravity.CENTER;
        overlay.addView(spinner, sp);
        return overlay;
    }

    private FrameLayout buildErrorOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xCC000000);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(dp(32), dp(32), dp(32), dp(32));

        errorText = new TextView(this);
        errorText.setText("تعذر تشغيل هذا المحتوى.");
        errorText.setTextColor(Color.WHITE);
        errorText.setTextSize(15);
        errorText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams etp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etp.bottomMargin = dp(20);
        col.addView(errorText, etp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button retryBtn = new Button(this);
        retryBtn.setText("إعادة المحاولة");
        retryBtn.setAllCaps(false);
        retryBtn.setTextColor(COLOR_ACCENT_DARK_TEXT);
        retryBtn.setBackgroundTintList(ColorStateList.valueOf(COLOR_ACCENT));
        retryBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideError();
                startPlayback();
            }
        });
        LinearLayout.LayoutParams rbp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rbp.setMargins(dp(6), 0, dp(6), 0);
        row.addView(retryBtn, rbp);

        Button backBtnErr = new Button(this);
        backBtnErr.setText("رجوع");
        backBtnErr.setAllCaps(false);
        backBtnErr.setTextColor(Color.WHITE);
        backBtnErr.setBackgroundTintList(ColorStateList.valueOf(0xFF1E293B));
        backBtnErr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishAndSave();
            }
        });
        LinearLayout.LayoutParams bbp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bbp.setMargins(dp(6), 0, dp(6), 0);
        row.addView(backBtnErr, bbp);

        col.addView(row);
        FrameLayout.LayoutParams colParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        colParams.gravity = Gravity.CENTER;
        overlay.addView(col, colParams);
        return overlay;
    }

    private FrameLayout buildControlsOverlay() {
        FrameLayout overlay = new FrameLayout(this);

        // ----- الشريط العلوي: رجوع + العنوان + شارة مباشر -----
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackground(scrimDrawable(true));
        topBar.setPadding(dp(6), dp(10), dp(16), dp(26));

        TextView backBtn = new TextView(this);
        backBtn.setText("\u2190"); // ←
        backBtn.setTextColor(Color.WHITE);
        backBtn.setTextSize(22);
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishAndSave();
            }
        });
        topBar.addView(backBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        titleText = new TextView(this);
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(15);
        titleText.setTypeface(Typeface.DEFAULT_BOLD);
        titleText.setMaxLines(1);
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ttp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ttp.leftMargin = dp(6);
        ttp.rightMargin = dp(6);
        topBar.addView(titleText, ttp);

        liveBadge = new TextView(this);
        liveBadge.setText("\uD83D\uDD34 مباشر"); // 🔴 مباشر
        liveBadge.setTextColor(Color.WHITE);
        liveBadge.setTextSize(11);
        liveBadge.setTypeface(Typeface.DEFAULT_BOLD);
        liveBadge.setBackground(roundedDrawable(0xFFDC2626, 999));
        liveBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        liveBadge.setVisibility(View.GONE);
        topBar.addView(liveBadge, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams topBarParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        topBarParams.gravity = Gravity.TOP;
        overlay.addView(topBar, topBarParams);

        // ----- منتصف الشاشة: ترجيع 10 ثوانٍ / تشغيل-إيقاف / تقديم 10 ثوانٍ -----
        LinearLayout centerRow = new LinearLayout(this);
        centerRow.setOrientation(LinearLayout.HORIZONTAL);
        centerRow.setGravity(Gravity.CENTER_VERTICAL);

        rewindBtn = buildCircleTextButton("-10", 58, 15);
        rewindBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekRelative(-SEEK_STEP_MS);
                resetAutoHideTimer();
            }
        });
        centerRow.addView(rewindBtn, new LinearLayout.LayoutParams(dp(58), dp(58)));

        playPauseBtn = buildPlayPauseButton();
        playPauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (engine == null) return;
                if (engine.isPlaying()) {
                    engine.pause();
                } else {
                    engine.play();
                }
                resetAutoHideTimer();
            }
        });
        LinearLayout.LayoutParams ppp = new LinearLayout.LayoutParams(dp(76), dp(76));
        ppp.leftMargin = dp(22);
        ppp.rightMargin = dp(22);
        centerRow.addView(playPauseBtn, ppp);

        forwardBtn = buildCircleTextButton("+10", 58, 15);
        forwardBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekRelative(SEEK_STEP_MS);
                resetAutoHideTimer();
            }
        });
        centerRow.addView(forwardBtn, new LinearLayout.LayoutParams(dp(58), dp(58)));

        FrameLayout.LayoutParams centerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        centerParams.gravity = Gravity.CENTER;
        overlay.addView(centerRow, centerParams);

        // ----- الشريط السفلي: الوقت الحالي + شريط التقدّم + المدة الكلية -----
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackground(scrimDrawable(false));
        bottomBar.setPadding(dp(16), dp(26), dp(16), dp(14));

        currentTimeText = new TextView(this);
        currentTimeText.setText("00:00");
        currentTimeText.setTextColor(Color.WHITE);
        currentTimeText.setTextSize(12);
        currentTimeText.setMinWidth(dp(42));
        bottomBar.addView(currentTimeText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setProgressTintList(ColorStateList.valueOf(COLOR_ACCENT));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(COLOR_TRACK_BG));
        seekBar.setThumbTintList(ColorStateList.valueOf(COLOR_ACCENT));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && knownDurationMs > 0) {
                    long pos = (long) ((progress / 1000.0) * knownDurationMs);
                    currentTimeText.setText(formatTime(pos));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                userSeeking = true;
                cancelAutoHideTimer();
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                if (knownDurationMs > 0 && engine != null) {
                    long pos = (long) ((sb.getProgress() / 1000.0) * knownDurationMs);
                    engine.seekTo(pos);
                }
                userSeeking = false;
                resetAutoHideTimer();
            }
        });
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        sbp.leftMargin = dp(8);
        sbp.rightMargin = dp(8);
        bottomBar.addView(seekBar, sbp);

        durationText = new TextView(this);
        durationText.setText("00:00");
        durationText.setTextColor(0xFF94A3B8);
        durationText.setTextSize(12);
        durationText.setMinWidth(dp(42));
        bottomBar.addView(durationText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        bottomParams.gravity = Gravity.BOTTOM;
        overlay.addView(bottomBar, bottomParams);

        return overlay;
    }

    /** زر دائري بسيط بنص فقط (بدون أي رمز/صورة) — يُستخدم للترجيع/التقديم. */
    private View buildCircleTextButton(String label, int sizeDp, float textSizeSp) {
        FrameLayout btn = new FrameLayout(this);
        btn.setBackground(circleDrawable(0x33FFFFFF));

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(Color.WHITE);
        text.setTextSize(textSizeSp);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        tp.gravity = Gravity.CENTER;
        btn.addView(text, tp);
        return btn;
    }

    /** زر التشغيل/الإيقاف: طبقتان متراكبتان (مثلث تشغيل نصّي، وشريطا إيقاف مؤقت)، تظهر إحداهما فقط. */
    private View buildPlayPauseButton() {
        FrameLayout btn = new FrameLayout(this);
        btn.setBackground(circleDrawable(0x33FFFFFF));

        TextView play = new TextView(this);
        play.setText("\u25B6"); // ▶
        play.setTextColor(Color.WHITE);
        play.setTextSize(26);
        FrameLayout.LayoutParams playParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        playParams.gravity = Gravity.CENTER;
        // إزاحة بصرية بسيطة لأن شكل المثلث نفسه غير متمركز بصرياً كباقي الحروف
        playParams.leftMargin = dp(3);
        btn.addView(play, playParams);
        playGlyph = play;

        LinearLayout pause = new LinearLayout(this);
        pause.setOrientation(LinearLayout.HORIZONTAL);
        pause.setGravity(Gravity.CENTER);
        View bar1 = new View(this);
        bar1.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams bar1p = new LinearLayout.LayoutParams(dp(6), dp(24));
        bar1p.rightMargin = dp(5);
        pause.addView(bar1, bar1p);
        View bar2 = new View(this);
        bar2.setBackgroundColor(Color.WHITE);
        pause.addView(bar2, new LinearLayout.LayoutParams(dp(6), dp(24)));
        FrameLayout.LayoutParams pauseParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        pauseParams.gravity = Gravity.CENTER;
        pause.setVisibility(View.GONE);
        btn.addView(pause, pauseParams);
        pauseGlyph = pause;

        return btn;
    }

    // ==================== أدوات مساعدة للرسم برمجياً ====================

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private GradientDrawable circleDrawable(int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        return gd;
    }

    private GradientDrawable roundedDrawable(int color, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(color);
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    /** تدرّج شفاف خفيف لضمان وضوح عناصر التحكّم فوق الفيديو (أعلى الشاشة أو أسفلها). */
    private GradientDrawable scrimDrawable(boolean darkAtTop) {
        int[] colors = darkAtTop
                ? new int[]{0xB3000000, 0x00000000}
                : new int[]{0x00000000, 0xB3000000};
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
    }

    // ==================== منطق التشغيل ====================

    private void startPlayback() {
        showLoading();
        long startPos = 0L;
        if (!isLive && resumeKey != null && !resumeKey.isEmpty()) {
            startPos = PlaybackPositionStore.getPosition(getApplicationContext(), resumeKey);
        }
        engine.playUrl(contentUrl, startPos);
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

    private void updateTimeUi(long positionMs, long durationMs) {
        currentTimeText.setText(formatTime(positionMs));
        if (durationMs > 0) {
            int progress = (int) Math.min(1000L, Math.max(0L, (positionMs * 1000L) / durationMs));
            seekBar.setProgress(progress);
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
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void updatePlayPauseIcon(boolean isPlaying) {
        playGlyph.setVisibility(isPlaying ? View.GONE : View.VISIBLE);
        pauseGlyph.setVisibility(isPlaying ? View.VISIBLE : View.GONE);
    }

    private void showLoading() {
        loadingOverlay.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        loadingOverlay.setVisibility(View.GONE);
    }

    private void showError(String message) {
        hideLoading();
        errorText.setText(message != null && !message.isEmpty() ? message : "تعذر تشغيل هذا المحتوى.");
        errorOverlay.setVisibility(View.VISIBLE);
        cancelAutoHideTimer();
    }

    private void hideError() {
        errorOverlay.setVisibility(View.GONE);
    }

    private void toggleControls() {
        if (controlsVisible) {
            hideControls();
        } else {
            showControls();
        }
    }

    private void showControls() {
        controlsVisible = true;
        controlsOverlay.setVisibility(View.VISIBLE);
        resetAutoHideTimer();
    }

    private void hideControls() {
        controlsVisible = false;
        controlsOverlay.setVisibility(View.GONE);
        cancelAutoHideTimer();
    }

    private void resetAutoHideTimer() {
        cancelAutoHideTimer();
        mainHandler.postDelayed(hideControlsRunnable, CONTROLS_AUTO_HIDE_MS);
    }

    private void cancelAutoHideTimer() {
        mainHandler.removeCallbacks(hideControlsRunnable);
    }

    private void savePositionNow(long positionMs) {
        if (isLive || resumeKey == null || resumeKey.isEmpty()) return;
        PlaybackPositionStore.savePosition(getApplicationContext(), resumeKey, positionMs, knownDurationMs);
    }

    private void finishAndSave() {
        if (engine != null) {
            savePositionNow(engine.getCurrentPosition());
        }
        finish();
    }

    @Override
    public void onBackPressed() {
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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        if (engine != null) {
            engine.release();
            engine = null;
        }
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
}
