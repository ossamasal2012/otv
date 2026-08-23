package com.yallagoal.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.Locale;

/**
 * المشغّل الداخلي الجديد — واجهة واحدة موحّدة ومصمَّمة بالكامل يدوياً (لا عناصر تحكّم جاهزة
 * من أي مكتبة)، تعمل فوق أحد محرّكين قابلين للتبديل عبر PlaybackEngine: ExoPlaybackEngine
 * (AndroidX Media3، الافتراضي) أو VlcPlaybackEngine (LibVLC، بديل).
 *
 * يُفتح حصرياً من WebAppInterface.playInternal(...) عندما يكون "المشغل الداخلي" هو خيار
 * التشغيل المُفعَّل بالإعدادات؛ لا علاقة له إطلاقاً بمسار المشغّلات الخارجية (playExternal)
 * الذي يبقى يعمل تماماً كما كان.
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

    private PlaybackEngine engine;
    private String contentUrl;
    private String resumeKey;
    private boolean isLive;

    private FrameLayout videoContainer;
    private View tapCatcher;
    private View loadingOverlay;
    private View errorOverlay;
    private TextView errorText;
    private FrameLayout controlsOverlay;
    private ImageButton backBtn;
    private TextView titleText;
    private TextView liveBadge;
    private FrameLayout rewindBtn;
    private FrameLayout playPauseBtn;
    private ImageView playPauseIcon;
    private FrameLayout forwardBtn;
    private View bottomBar;
    private TextView currentTimeText;
    private SeekBar seekBar;
    private TextView durationText;

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

        setContentView(R.layout.activity_internal_player);
        hideSystemBars();
        bindViews();

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

        setupControlListeners();
        startPlayback();
    }

    private void bindViews() {
        videoContainer = findViewById(R.id.videoContainer);
        tapCatcher = findViewById(R.id.tapCatcher);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        errorOverlay = findViewById(R.id.errorOverlay);
        errorText = findViewById(R.id.errorText);
        controlsOverlay = findViewById(R.id.controlsOverlay);
        backBtn = findViewById(R.id.backBtn);
        titleText = findViewById(R.id.titleText);
        liveBadge = findViewById(R.id.liveBadge);
        rewindBtn = findViewById(R.id.rewindBtn);
        playPauseBtn = findViewById(R.id.playPauseBtn);
        playPauseIcon = findViewById(R.id.playPauseIcon);
        forwardBtn = findViewById(R.id.forwardBtn);
        bottomBar = findViewById(R.id.bottomBar);
        currentTimeText = findViewById(R.id.currentTimeText);
        seekBar = findViewById(R.id.seekBar);
        durationText = findViewById(R.id.durationText);

        Button errorRetryBtn = findViewById(R.id.errorRetryBtn);
        Button errorBackBtn = findViewById(R.id.errorBackBtn);
        errorRetryBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideError();
                startPlayback();
            }
        });
        errorBackBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishAndSave();
            }
        });
    }

    private void setupControlListeners() {
        tapCatcher.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleControls();
            }
        });

        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishAndSave();
            }
        });

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

        rewindBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekRelative(-SEEK_STEP_MS);
                resetAutoHideTimer();
            }
        });

        forwardBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekRelative(SEEK_STEP_MS);
                resetAutoHideTimer();
            }
        });

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
    }

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
        playPauseIcon.setImageResource(isPlaying ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
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
