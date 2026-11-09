package com.yallagoal.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * واجهة «الصوت الإضافي» داخل المشغّل المدمج (مبنية برمجياً كبقية واجهات المشغّل):
 * <ul>
 *   <li>زر سريع في الشريط العلوي.</li>
 *   <li>صف في إعدادات التشغيل يفتح صفحة الصوت الإضافي: إضافة/استبدال (رابط، ملف، محطة راديو)،
 *       الأوضاع (معاً / الإضافي فقط / الأصلي فقط)، مستوى كل صوت مع كتم مستقل، تحديث، حذف، حفظ.</li>
 *   <li>لوحة مزامنة عائمة لا تحجب الصورة ولا تُظلّلها (قابلة للسحب والطيّ): ترجيع/تقديم بخطوات متعددة
 *       (10 / 5 / 1 / 0.5 / 0.1 ثانية مع تكرار بالضغط المطوّل)، مقبض ضبط دقيق، إدخال قيمة رقمية،
 *       إيقاف مؤقت للصوت الإضافي، تصفير/عودة للمباشر، وتحديث.</li>
 * </ul>
 * كل المنطق الفعلي في {@link ExtraAudioPlayer}؛ هذه الطبقة تعرض الحالة وتمرّر أوامر المستخدم فقط.
 */
final class ExtraAudioUi implements ExtraAudioPlayer.Listener {

    /** ما تحتاجه الواجهة من النشاط المضيف. */
    interface Host {
        void pickAudioFile();

        void toast(String text);

        /** المستخدم يتفاعل: أعد ضبط مؤقت إخفاء أزرار المشغّل. */
        void touch();

        /** افتح إعدادات التشغيل على صفحة الصوت الإضافي. */
        void openPage();

        /** أغلق لوحة الإعدادات (لتظهر الصورة بلا تظليل أثناء المزامنة). */
        void closeSettings();

        boolean isLiveContent();
    }

    private static final int EXTRA = YgUi.EMBER;
    private static final int EXTRA_SOFT = YgUi.EMBER_2;
    private static final int ORIG = YgUi.COBALT_2;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Activity act;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExtraAudioPlayer audio;
    private final List<Dialog> dialogs = new ArrayList<>();

    // زر سريع
    private FrameLayout quickBtn;
    private YgIconView quickIcon;

    // صف الإعدادات
    private TextView entrySub;

    // الصفحة
    private LinearLayout pageEmpty;
    private LinearLayout pageSourceCard;
    private YgIconView srcIcon;
    private TextView srcName;
    private TextView srcStatus;
    private TextView btnReload;
    private TextView btnDelete;
    private TextView btnLink;
    private TextView btnFile;
    private TextView btnRadio;
    private TextView addLabelView;
    private LinearLayout pageMix;
    private final TextView[] mixChips = new TextView[3];
    private ValueSlider origSlider;
    private ValueSlider extraSlider;
    private TextView origPct;
    private TextView extraPct;
    private FrameLayout origMuteBtn;
    private YgIconView origMuteIcon;
    private FrameLayout extraMuteBtn;
    private YgIconView extraMuteIcon;
    private LinearLayout pageSync;
    private TextView pageSyncSummary;
    private LinearLayout pageSave;
    private TextView saveBtn;
    private TextView saveHint;
    private boolean deleteArmed = false;
    private final Runnable disarmDelete = new Runnable() {
        @Override
        public void run() {
            deleteArmed = false;
            refreshPage();
        }
    };

    // لوحة المزامنة
    private LinearLayout syncPanel;
    private LinearLayout syncBody;
    private TextView syncValue;
    private TextView syncCaption;
    private TextView syncStatus;
    private YgIconView syncCollapseIcon;
    private FrameLayout syncPauseBtn;
    private YgIconView syncPauseIcon;
    private FrameLayout syncResetBtn;
    private YgIconView syncResetIcon;
    private ValueSlider jog;
    private final List<TextView> forwardChips = new ArrayList<>();
    private boolean syncCollapsed = false;

    ExtraAudioUi(Activity activity, Host host) {
        this.act = activity;
        this.host = host;
    }

    /** يربط الواجهة بالمشغّل بعد إنشائه (الواجهة تُبنى قبل المحرّك). */
    void bind(ExtraAudioPlayer player) {
        this.audio = player;
        player.setListener(this);
        refreshAll();
    }

    // =====================================================================================
    //  استدعاءات المشغّل (خيط الواجهة)
    // =====================================================================================

    @Override
    public void onExtraAudioChanged() {
        refreshAll();
    }

    @Override
    public void onExtraAudioMessage(String message) {
        host.toast(message);
    }

    void dismissAll() {
        ui.removeCallbacksAndMessages(null);
        for (Dialog d : new ArrayList<>(dialogs)) {
            try {
                d.dismiss();
            } catch (Throwable ignored) {
                // النافذة مغلقة أصلاً
            }
        }
        dialogs.clear();
    }

    // =====================================================================================
    //  زر الشريط العلوي
    // =====================================================================================

    View buildQuickButton() {
        FrameLayout b = new FrameLayout(act);
        b.setBackground(YgUi.glass(act, 0, true));
        quickIcon = new YgIconView(act, "audio-lines", Color.WHITE);
        b.addView(quickIcon, fl(dp(22), dp(22), Gravity.CENTER));
        b.setContentDescription("الصوت الإضافي");
        YgUi.pressable(b);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.touch();
                if (audio != null && audio.hasSource()) {
                    toggleSyncPanel();
                } else {
                    host.openPage();
                }
            }
        });
        b.setVisibility(View.GONE);
        quickBtn = b;
        return b;
    }

    private void refreshQuick() {
        if (quickIcon == null) {
            return;
        }
        int color = Color.WHITE;
        if (audio != null && audio.hasSource()) {
            color = audio.getStatus() == ExtraAudioPlayer.Status.ERROR ? YgUi.BAD : EXTRA;
        }
        quickIcon.setColor(color);
        if (quickBtn != null) {
            quickBtn.setVisibility(audio != null && audio.hasSource() ? View.VISIBLE : View.GONE);
            quickBtn.setBackground(audio != null && audio.hasSource()
                    ? tint(YgUi.withAlpha(EXTRA, 0x30), YgUi.withAlpha(EXTRA, 0x99), true, 0)
                    : YgUi.glass(act, 0, true));
        }
    }

    // =====================================================================================
    //  صف الإعدادات (يفتح الصفحة)
    // =====================================================================================

    View buildSettingsEntry(final Runnable open) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(tint(YgUi.withAlpha(EXTRA, 0x1C), YgUi.withAlpha(EXTRA, 0x66), false, dp(16)));

        YgIconView chev = new YgIconView(act, "chevron-left", YgUi.MIST);
        row.addView(chev, ll(dp(20), dp(20)));

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.RIGHT);
        TextView t = label("صوت إضافي", 14.5f, Color.WHITE, YgUi.bold(act));
        t.setGravity(Gravity.RIGHT);
        col.addView(t, ll(MATCH, WRAP));
        entrySub = label("", 11.5f, YgUi.MIST, YgUi.text(act));
        entrySub.setGravity(Gravity.RIGHT);
        entrySub.setMaxLines(1);
        entrySub.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sp = ll(MATCH, WRAP);
        sp.topMargin = dp(2);
        col.addView(entrySub, sp);
        LinearLayout.LayoutParams cp = ll(0, WRAP, 1f);
        cp.leftMargin = dp(10);
        cp.rightMargin = dp(10);
        row.addView(col, cp);

        FrameLayout badge = new FrameLayout(act);
        badge.setBackground(YgUi.rect(YgUi.withAlpha(EXTRA, 0x38), dp(12)));
        badge.addView(new YgIconView(act, "audio-lines", EXTRA_SOFT), fl(dp(22), dp(22), Gravity.CENTER));
        row.addView(badge, ll(dp(40), dp(40)));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open.run();
            }
        });
        YgUi.pressable(row);
        refreshEntry();
        return row;
    }

    private void refreshEntry() {
        if (entrySub == null) {
            return;
        }
        if (audio == null || !audio.hasSource()) {
            entrySub.setText("أضف رابطاً أو ملفاً صوتياً وزامنه مع الصورة");
            entrySub.setTextColor(YgUi.MIST);
        } else {
            entrySub.setText(audio.getSourceName() + " • " + statusText());
            entrySub.setTextColor(statusColor());
        }
    }

    // =====================================================================================
    //  الصفحة (داخل لوحة الإعدادات)
    // =====================================================================================

    /** @return صفحة كاملة: رأس + محتوى قابل للتمرير. */
    View buildPage(final Runnable onBack, final Runnable onClose) {
        LinearLayout page = new LinearLayout(act);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View close = iconButton("x", 38, 18, "إغلاق");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onClose.run();
            }
        });
        head.addView(close, ll(dp(38), dp(38)));
        TextView ht = label("الصوت الإضافي", 21, Color.WHITE, YgUi.display(act));
        ht.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams htp = ll(0, WRAP, 1f);
        htp.rightMargin = dp(10);
        head.addView(ht, htp);
        View back = iconButton("arrow-right", 38, 18, "رجوع");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBack.run();
            }
        });
        head.addView(back, ll(dp(38), dp(38)));
        page.addView(head, ll(MATCH, WRAP));

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(8), 0, dp(18));

        // ---- حالة فارغة
        pageEmpty = new LinearLayout(act);
        pageEmpty.setOrientation(LinearLayout.VERTICAL);
        pageEmpty.setGravity(Gravity.CENTER_HORIZONTAL);
        pageEmpty.setPadding(dp(14), dp(16), dp(14), dp(14));
        pageEmpty.setBackground(YgUi.glass(act, dp(18), false));
        FrameLayout emptyBadge = new FrameLayout(act);
        emptyBadge.setBackground(YgUi.oval(YgUi.withAlpha(EXTRA, 0x33)));
        emptyBadge.addView(new YgIconView(act, "audio-lines", EXTRA_SOFT), fl(dp(30), dp(30), Gravity.CENTER));
        pageEmpty.addView(emptyBadge, ll(dp(60), dp(60)));
        TextView et = label("أضف صوتاً لهذا المحتوى", 16, Color.WHITE, YgUi.bold(act));
        et.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams etp = ll(MATCH, WRAP);
        etp.topMargin = dp(10);
        pageEmpty.addView(et, etp);
        TextView ed = label("ضع رابط بثّ صوتي (راديو/تعليق) أو ملفاً صوتياً ليعمل فوق الصورة، مع التحكم بمستوى كل صوت "
                + "ومزامنته بالترجيع والتقديم بدقة.", 12, YgUi.MIST, YgUi.text(act));
        ed.setGravity(Gravity.CENTER);
        ed.setLineSpacing(0f, 1.35f);
        LinearLayout.LayoutParams edp = ll(MATCH, WRAP);
        edp.topMargin = dp(6);
        pageEmpty.addView(ed, edp);
        body.addView(pageEmpty, ll(MATCH, WRAP));

        // ---- بطاقة المصدر الحالي
        pageSourceCard = new LinearLayout(act);
        pageSourceCard.setOrientation(LinearLayout.VERTICAL);
        pageSourceCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        pageSourceCard.setBackground(tint(YgUi.withAlpha(EXTRA, 0x16), YgUi.withAlpha(EXTRA, 0x55), false, dp(18)));
        LinearLayout srcRow = new LinearLayout(act);
        srcRow.setOrientation(LinearLayout.HORIZONTAL);
        srcRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout srcCol = new LinearLayout(act);
        srcCol.setOrientation(LinearLayout.VERTICAL);
        srcName = label("", 14, Color.WHITE, YgUi.bold(act));
        srcName.setGravity(Gravity.RIGHT);
        srcName.setMaxLines(1);
        srcName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        srcCol.addView(srcName, ll(MATCH, WRAP));
        srcStatus = label("", 12, YgUi.MIST, YgUi.text(act));
        srcStatus.setGravity(Gravity.RIGHT);
        srcStatus.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams ssp = ll(MATCH, WRAP);
        ssp.topMargin = dp(3);
        srcCol.addView(srcStatus, ssp);
        LinearLayout.LayoutParams scp = ll(0, WRAP, 1f);
        scp.rightMargin = dp(10);
        srcRow.addView(srcCol, scp);
        FrameLayout srcBadge = new FrameLayout(act);
        srcBadge.setBackground(YgUi.rect(YgUi.withAlpha(EXTRA, 0x38), dp(12)));
        srcIcon = new YgIconView(act, "link", EXTRA_SOFT);
        srcBadge.addView(srcIcon, fl(dp(22), dp(22), Gravity.CENTER));
        srcRow.addView(srcBadge, ll(dp(42), dp(42)));
        pageSourceCard.addView(srcRow, ll(MATCH, WRAP));

        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        btnDelete = actionChip("حذف");
        btnDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onDeleteClicked();
            }
        });
        btnReload = actionChip("تحديث");
        btnReload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (audio != null) {
                    audio.reload();
                }
            }
        });
        LinearLayout.LayoutParams a1 = ll(0, dp(40), 1f);
        a1.rightMargin = dp(4);
        actions.addView(btnDelete, a1);
        LinearLayout.LayoutParams a2 = ll(0, dp(40), 1f);
        a2.leftMargin = dp(4);
        actions.addView(btnReload, a2);
        LinearLayout.LayoutParams alp = ll(MATCH, WRAP);
        alp.topMargin = dp(12);
        pageSourceCard.addView(actions, alp);
        LinearLayout.LayoutParams scardp = ll(MATCH, WRAP);
        body.addView(pageSourceCard, scardp);

        // ---- أزرار الإضافة/الاستبدال
        addLabelView = sectionLabel("");
        body.addView(addLabelView, ll(MATCH, WRAP));
        LinearLayout addRow = new LinearLayout(act);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRadio = bigChip("راديو", "radio");
        btnFile = bigChip("ملف", "folder-open");
        btnLink = bigChip("رابط", "link");
        btnLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddLinkDialog(null);
            }
        });
        btnFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.pickAudioFile();
            }
        });
        btnRadio.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRadioDialog();
            }
        });
        LinearLayout.LayoutParams r1 = ll(0, dp(78), 1f);
        r1.rightMargin = dp(3);
        LinearLayout.LayoutParams r2 = ll(0, dp(78), 1f);
        r2.leftMargin = dp(3);
        r2.rightMargin = dp(3);
        LinearLayout.LayoutParams r3 = ll(0, dp(78), 1f);
        r3.leftMargin = dp(3);
        addRow.addView(btnRadio, r1);
        addRow.addView(btnFile, r2);
        addRow.addView(btnLink, r3);
        body.addView(addRow, ll(MATCH, WRAP));

        // ---- الأوضاع + المستويات
        pageMix = new LinearLayout(act);
        pageMix.setOrientation(LinearLayout.VERTICAL);
        pageMix.addView(sectionLabel("ما الذي تسمعه؟"), ll(MATCH, WRAP));
        LinearLayout mixRow = new LinearLayout(act);
        mixRow.setOrientation(LinearLayout.HORIZONTAL);
        final String[] mixNames = {"الاثنان معاً", "الإضافي فقط", "الأصلي فقط"};
        final int[] mixModes = {ExtraAudioPlayer.MIX_BOTH, ExtraAudioPlayer.MIX_EXTRA_ONLY, ExtraAudioPlayer.MIX_ORIGINAL_ONLY};
        for (int i = 0; i < 3; i++) {
            final int mode = mixModes[i];
            TextView c = choiceChip(mixNames[i]);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (audio != null) {
                        audio.setMixMode(mode);
                    }
                }
            });
            mixChips[i] = c;
            LinearLayout.LayoutParams cp = ll(0, dp(42), 1f);
            cp.leftMargin = dp(3);
            cp.rightMargin = dp(3);
            mixRow.addView(c, cp);
        }
        pageMix.addView(mixRow, ll(MATCH, WRAP));

        pageMix.addView(sectionLabel("مستوى كل صوت"), ll(MATCH, WRAP));
        // الأصلي
        origPct = label("100%", 12, YgUi.MIST, YgUi.bold(act));
        origSlider = new ValueSlider(act, false, ORIG);
        origSlider.setContentDescription("مستوى الصوت الأصلي");
        origMuteBtn = muteButton(false);
        origMuteIcon = (YgIconView) origMuteBtn.getChildAt(0);
        pageMix.addView(volumeBlock("الصوت الأصلي", origPct, origSlider, origMuteBtn), ll(MATCH, WRAP));
        origSlider.setListener(new ValueSlider.Listener() {
            @Override
            public void onStart() {
                host.touch();
            }

            @Override
            public void onChange(float v, boolean fromUser) {
                if (audio != null && fromUser) {
                    audio.setOriginalVolumePct(Math.round(v * 100f));
                }
                origPct.setText(Math.round(v * 100f) + "%");
            }

            @Override
            public void onEnd(float v) {
                refreshPage();
            }
        });
        origMuteBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (audio != null) {
                    audio.setOriginalMuted(!audio.isOriginalMuted());
                }
            }
        });
        // الإضافي
        extraPct = label("100%", 12, YgUi.MIST, YgUi.bold(act));
        extraSlider = new ValueSlider(act, false, EXTRA);
        extraSlider.setContentDescription("مستوى الصوت الإضافي");
        extraMuteBtn = muteButton(true);
        extraMuteIcon = (YgIconView) extraMuteBtn.getChildAt(0);
        LinearLayout extraBlock = volumeBlock("الصوت الإضافي", extraPct, extraSlider, extraMuteBtn);
        LinearLayout.LayoutParams ebp = ll(MATCH, WRAP);
        ebp.topMargin = dp(8);
        pageMix.addView(extraBlock, ebp);
        extraSlider.setListener(new ValueSlider.Listener() {
            @Override
            public void onStart() {
                host.touch();
            }

            @Override
            public void onChange(float v, boolean fromUser) {
                if (audio != null && fromUser) {
                    audio.setExtraVolumePct(Math.round(v * 100f));
                }
                extraPct.setText(Math.round(v * 100f) + "%");
            }

            @Override
            public void onEnd(float v) {
                refreshPage();
            }
        });
        extraMuteBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (audio != null) {
                    audio.setExtraMuted(!audio.isExtraMuted());
                }
            }
        });
        body.addView(pageMix, ll(MATCH, WRAP));

        // ---- المزامنة
        pageSync = new LinearLayout(act);
        pageSync.setOrientation(LinearLayout.VERTICAL);
        pageSync.addView(sectionLabel("المزامنة مع الصورة"), ll(MATCH, WRAP));
        LinearLayout syncCard = new LinearLayout(act);
        syncCard.setOrientation(LinearLayout.VERTICAL);
        syncCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        syncCard.setBackground(YgUi.glass(act, dp(18), false));
        pageSyncSummary = label("", 12.5f, YgUi.MIST, YgUi.text(act));
        pageSyncSummary.setGravity(Gravity.RIGHT);
        pageSyncSummary.setLineSpacing(0f, 1.35f);
        syncCard.addView(pageSyncSummary, ll(MATCH, WRAP));
        LinearLayout openSync = new LinearLayout(act);
        openSync.setOrientation(LinearLayout.HORIZONTAL);
        openSync.setGravity(Gravity.CENTER);
        openSync.setBackground(emberGradient(dp(14)));
        TextView ost = label("فتح أدوات الترجيع والتقديم", 13.5f, Color.WHITE, YgUi.bold(act));
        openSync.addView(ost, ll(WRAP, WRAP));
        LinearLayout.LayoutParams oip = ll(dp(20), dp(20));
        oip.leftMargin = dp(8);
        openSync.addView(new YgIconView(act, "sliders-horizontal", Color.WHITE), oip);
        openSync.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.closeSettings();
                showSyncPanel();
            }
        });
        YgUi.pressable(openSync);
        LinearLayout.LayoutParams osp = ll(MATCH, dp(46));
        osp.topMargin = dp(10);
        syncCard.addView(openSync, osp);
        pageSync.addView(syncCard, ll(MATCH, WRAP));
        body.addView(pageSync, ll(MATCH, WRAP));

        // ---- الحفظ
        pageSave = new LinearLayout(act);
        pageSave.setOrientation(LinearLayout.VERTICAL);
        pageSave.addView(sectionLabel("الحفظ"), ll(MATCH, WRAP));
        LinearLayout saveCard = new LinearLayout(act);
        saveCard.setOrientation(LinearLayout.HORIZONTAL);
        saveCard.setGravity(Gravity.CENTER_VERTICAL);
        saveCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        saveCard.setBackground(YgUi.glass(act, dp(18), false));
        saveBtn = label("حفظ", 13, Color.WHITE, YgUi.bold(act));
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setPadding(dp(16), 0, dp(16), 0);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (audio == null) {
                    return;
                }
                final boolean on = !audio.isSaved();
                audio.setSaved(on);
                host.toast(on ? "تم الحفظ — سيُستعاد الصوت وإعداداته عند العودة"
                        : "أُلغي الحفظ لهذا المحتوى");
            }
        });
        YgUi.pressable(saveBtn);
        saveCard.addView(saveBtn, ll(WRAP, dp(40)));
        saveHint = label("", 11.5f, YgUi.MIST, YgUi.text(act));
        saveHint.setGravity(Gravity.RIGHT);
        saveHint.setLineSpacing(0f, 1.3f);
        LinearLayout.LayoutParams shp = ll(0, WRAP, 1f);
        shp.leftMargin = dp(10);
        saveCard.addView(saveHint, shp);
        pageSave.addView(saveCard, ll(MATCH, WRAP));
        body.addView(pageSave, ll(MATCH, WRAP));

        TextView foot = label("الصيغ المدعومة: روابط البث المباشر (Icecast / Shoutcast / راديو)، mp3 وaac وm4a وogg وmp4، "
                + "وm3u8، وقوائم pls/m3u. أو ملف من جهازك.", 11, YgUi.FOG, YgUi.text(act));
        foot.setGravity(Gravity.RIGHT);
        foot.setLineSpacing(0f, 1.35f);
        LinearLayout.LayoutParams fp = ll(MATCH, WRAP);
        fp.topMargin = dp(16);
        body.addView(foot, fp);

        scroll.addView(body, new FrameLayout.LayoutParams(MATCH, WRAP));
        page.addView(scroll, ll(MATCH, 0, 1f));
        refreshPage();
        return page;
    }

    private void onDeleteClicked() {
        if (audio == null || !audio.hasSource()) {
            return;
        }
        if (!deleteArmed) {
            deleteArmed = true;
            refreshPage();
            ui.removeCallbacks(disarmDelete);
            ui.postDelayed(disarmDelete, 3500);
            return;
        }
        deleteArmed = false;
        ui.removeCallbacks(disarmDelete);
        audio.clear();
        host.toast("تم حذف الصوت الإضافي — عاد صوت القناة الأصلي");
    }

    private void refreshPage() {
        if (pageEmpty == null) {
            return;
        }
        final boolean has = audio != null && audio.hasSource();
        pageEmpty.setVisibility(has ? View.GONE : View.VISIBLE);
        pageSourceCard.setVisibility(has ? View.VISIBLE : View.GONE);
        pageMix.setVisibility(has ? View.VISIBLE : View.GONE);
        pageSync.setVisibility(has ? View.VISIBLE : View.GONE);
        pageSave.setVisibility(has ? View.VISIBLE : View.GONE);

        // عنوان قسم الإضافة
        if (addLabelView != null) {
            addLabelView.setText(has ? "استبدال المصدر" : "اختر المصدر");
        }
        setChipText(btnLink, has ? "رابط جديد" : "رابط");
        setChipText(btnFile, has ? "ملف جديد" : "ملف");
        setChipText(btnRadio, "راديو");

        if (!has) {
            return;
        }
        srcIcon.setIcon(audio.isFileSource() ? "folder-open" : "link");
        srcName.setText(audio.getSourceName());
        srcStatus.setText(statusText());
        srcStatus.setTextColor(statusColor());

        btnDelete.setText(deleteArmed ? "اضغط للتأكيد" : "حذف");
        btnDelete.setTextColor(deleteArmed ? Color.WHITE : YgUi.CHALK);
        btnDelete.setBackground(deleteArmed ? YgUi.rect(YgUi.withAlpha(YgUi.BAD, 0xCC), dp(14))
                : YgUi.glass(act, dp(14), false));

        final int mode = audio.getMixMode();
        final int[] modes = {ExtraAudioPlayer.MIX_BOTH, ExtraAudioPlayer.MIX_EXTRA_ONLY, ExtraAudioPlayer.MIX_ORIGINAL_ONLY};
        for (int i = 0; i < 3; i++) {
            styleChoice(mixChips[i], mode == modes[i]);
        }

        if (!origSlider.isDragging()) {
            origSlider.setValue(audio.getOriginalVolumePct() / 100f);
        }
        if (!extraSlider.isDragging()) {
            extraSlider.setValue(audio.getExtraVolumePct() / 100f);
        }
        origPct.setText(audio.getOriginalVolumePct() + "%");
        extraPct.setText(audio.getExtraVolumePct() + "%");
        styleMute(origMuteBtn, origMuteIcon, audio.isOriginalMuted(), ORIG);
        styleMute(extraMuteBtn, extraMuteIcon, audio.isExtraMuted(), EXTRA);
        origSlider.setAlphaDim(audio.isOriginalMuted());
        extraSlider.setAlphaDim(audio.isExtraMuted());

        pageSyncSummary.setText(syncCaptionText() + "\n" + syncHintText());

        final boolean saved = audio.isSaved();
        final String what = host.isLiveContent() ? "هذه القناة" : "هذا الفيلم/الحلقة";
        saveBtn.setText(saved ? "محفوظ ✓" : "حفظ");
        saveBtn.setBackground(saved ? YgUi.rect(YgUi.withAlpha(YgUi.OK, 0xCC), dp(14)) : emberGradient(dp(14)));
        saveHint.setText(saved
                ? "محفوظ لـ" + what + ": يُستعاد الصوت وآخر إعدادات المستوى والمزامنة تلقائياً، وتتحدّث مع كل تعديل."
                : "احفظ ليُستعاد الصوت وإعداداته عند العودة إلى " + what + " (حتى بعد إغلاق التطبيق).");
    }

    // =====================================================================================
    //  لوحة المزامنة العائمة
    // =====================================================================================

    /** اللوحة العائمة: تُضاف مرة واحدة إلى جذر النشاط، مخفية افتراضياً. */
    View buildSyncPanel() {
        final LinearLayout panel = new SyncPanelLayout(act, dp(520));
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        panel.setPadding(dp(10), dp(6), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE80E1634, 0xE8070D24});
        bg.setCornerRadius(dp(20));
        bg.setStroke(Math.max(1, dp(1)), YgUi.withAlpha(EXTRA, 0x88));
        panel.setBackground(bg);
        panel.setVisibility(View.GONE);
        syncPanel = panel;

        // ---- الرأس: [إغلاق] [طيّ] ............ [العنوان]   (قابل للسحب)
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View close = iconButton("x", 34, 16, "إغلاق");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.touch();
                hideSyncPanel();
            }
        });
        head.addView(close, ll(dp(34), dp(34)));
        FrameLayout collapse = new FrameLayout(act);
        collapse.setBackground(YgUi.glass(act, 0, true));
        syncCollapseIcon = new YgIconView(act, "chevron-right", Color.WHITE);
        collapse.addView(syncCollapseIcon, fl(dp(16), dp(16), Gravity.CENTER));
        collapse.setContentDescription("طيّ/فتح اللوحة");
        YgUi.pressable(collapse);
        collapse.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSyncCollapsed(!syncCollapsed);
            }
        });
        LinearLayout.LayoutParams colp = ll(dp(34), dp(34));
        colp.leftMargin = dp(6);
        head.addView(collapse, colp);
        TextView title = label("مزامنة الصوت الإضافي", 14, Color.WHITE, YgUi.display(act));
        title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        title.setMaxLines(1);
        LinearLayout.LayoutParams tp = ll(0, WRAP, 1f);
        tp.leftMargin = dp(8);
        head.addView(title, tp);
        installDrag(head, panel);
        panel.addView(head, ll(MATCH, dp(38)));

        // ---- القيمة الكبيرة (الإزاحة) + شرحها
        LinearLayout readout = new LinearLayout(act);
        readout.setOrientation(LinearLayout.VERTICAL);
        readout.setGravity(Gravity.CENTER_HORIZONTAL);
        syncValue = label("0.0 ث", 26, Color.WHITE, YgUi.display(act));
        syncValue.setGravity(Gravity.CENTER);
        syncValue.setIncludeFontPadding(false);
        syncValue.setPadding(dp(18), dp(2), dp(18), dp(2));
        syncValue.setBackground(YgUi.rect(YgUi.withAlpha(Color.WHITE, 0x14), dp(14)));
        syncValue.setContentDescription("إزاحة الصوت. اضغط لإدخال قيمة، واضغط مطوّلاً للتصفير");
        syncValue.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.touch();
                showOffsetDialog();
            }
        });
        syncValue.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (audio != null) {
                    audio.resetOffset();
                    host.toast(audio.isLiveLike() ? "عاد الصوت إلى أحدث نقطة في البث" : "تم تصفير الإزاحة");
                }
                return true;
            }
        });
        YgUi.pressable(syncValue);
        readout.addView(syncValue, ll(WRAP, WRAP));
        syncCaption = label("", 11.5f, YgUi.MIST, YgUi.text(act));
        syncCaption.setGravity(Gravity.CENTER);
        syncCaption.setMaxLines(2);
        syncCaption.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams scp = ll(MATCH, WRAP);
        scp.topMargin = dp(4);
        readout.addView(syncCaption, scp);
        panel.addView(readout, ll(MATCH, WRAP));

        // ---- الجسم القابل للطيّ
        syncBody = new LinearLayout(act);
        syncBody.setOrientation(LinearLayout.VERTICAL);

        // صف الخطوات: يسار = ترجيع (سالب) ، يمين = تقديم (موجب)
        LinearLayout steps = new LinearLayout(act);
        steps.setOrientation(LinearLayout.HORIZONTAL);
        steps.setGravity(Gravity.CENTER_VERTICAL);
        final long[] mags = {10_000, 5_000, 1_000, 500, 100};
        final String[] names = {"10", "5", "1", "0.5", "0.1"};
        for (int i = 0; i < 5; i++) {
            TextView c = stepChip(-mags[i], "−" + names[i], mags[i] <= 1000, false);
            LinearLayout.LayoutParams lp = ll(0, dp(42), 1f);
            lp.leftMargin = dp(1);
            lp.rightMargin = dp(1);
            steps.addView(c, lp);
        }
        View divider = new View(act);
        divider.setBackgroundColor(YgUi.withAlpha(Color.WHITE, 0x33));
        LinearLayout.LayoutParams dlp = ll(dp(2), dp(30));
        dlp.leftMargin = dp(3);
        dlp.rightMargin = dp(3);
        steps.addView(divider, dlp);
        forwardChips.clear();
        for (int i = 4; i >= 0; i--) {
            TextView c = stepChip(mags[i], "+" + names[i], mags[i] <= 1000, true);
            LinearLayout.LayoutParams lp = ll(0, dp(42), 1f);
            lp.leftMargin = dp(1);
            lp.rightMargin = dp(1);
            steps.addView(c, lp);
            forwardChips.add(c);
        }
        LinearLayout.LayoutParams stp = ll(MATCH, WRAP);
        stp.topMargin = dp(8);
        syncBody.addView(steps, stp);

        LinearLayout caps = new LinearLayout(act);
        caps.setOrientation(LinearLayout.HORIZONTAL);
        TextView capL = label("ترجيع الصوت (يتأخر)", 10.5f, YgUi.FOG, YgUi.text(act));
        capL.setGravity(Gravity.LEFT);
        TextView capR = label("تقديم الصوت (يسبق)", 10.5f, YgUi.FOG, YgUi.text(act));
        capR.setGravity(Gravity.RIGHT);
        caps.addView(capL, ll(0, WRAP, 1f));
        caps.addView(capR, ll(0, WRAP, 1f));
        LinearLayout.LayoutParams cpl = ll(MATCH, WRAP);
        cpl.topMargin = dp(2);
        syncBody.addView(caps, cpl);

        // صف الأدوات: [إيقاف مؤقت] [تصفير/مباشر] [تحديث] + مقبض الضبط الدقيق
        LinearLayout tools = new LinearLayout(act);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        syncPauseBtn = toolButton("pause", "إيقاف مؤقت للصوت الإضافي");
        syncPauseIcon = (YgIconView) syncPauseBtn.getChildAt(0);
        syncPauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.touch();
                if (audio != null) {
                    final boolean to = !audio.isUserPaused();
                    audio.setUserPaused(to);
                    host.toast(to ? "تم إيقاف الصوت الإضافي مؤقتاً — الفيديو يعمل" : "تم استئناف الصوت الإضافي");
                }
            }
        });
        syncResetBtn = toolButton("rotate-ccw", "تصفير");
        syncResetIcon = (YgIconView) syncResetBtn.getChildAt(0);
        syncResetBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.touch();
                if (audio != null) {
                    audio.resetOffset();
                    host.toast(audio.isLiveLike() ? "عاد الصوت إلى أحدث نقطة في البث" : "تم تصفير الإزاحة");
                }
            }
        });
        FrameLayout reload = toolButton("refresh-cw", "تحديث الصوت الإضافي");
        reload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.touch();
                if (audio != null) {
                    audio.reload();
                }
            }
        });
        tools.addView(syncPauseBtn, ll(dp(40), dp(40)));
        LinearLayout.LayoutParams t2 = ll(dp(40), dp(40));
        t2.leftMargin = dp(6);
        tools.addView(syncResetBtn, t2);
        LinearLayout.LayoutParams t3 = ll(dp(40), dp(40));
        t3.leftMargin = dp(6);
        tools.addView(reload, t3);

        jog = new ValueSlider(act, true, EXTRA);
        jog.setContentDescription("مقبض الضبط الدقيق: اسحب يميناً للتقديم ويساراً للترجيع");
        jog.setListener(new ValueSlider.Listener() {
            @Override
            public void onStart() {
                host.touch();
            }

            @Override
            public void onChange(float v, boolean fromUser) {
                final long d = jogDeltaMs(v);
                syncCaption.setText(d == 0 ? "اسحب للضبط الدقيق — يُطبَّق عند الإفلات"
                        : "سيُطبَّق " + formatSeconds(d, true) + " ث عند الإفلات");
            }

            @Override
            public void onEnd(float v) {
                final long d = jogDeltaMs(v);
                jog.setValue(0.5f);
                if (audio != null && d != 0) {
                    audio.nudge(d);
                } else {
                    refreshSync();
                }
            }
        });
        LinearLayout.LayoutParams jp = ll(0, dp(40), 1f);
        jp.leftMargin = dp(10);
        tools.addView(jog, jp);
        LinearLayout.LayoutParams toolsLp = ll(MATCH, WRAP);
        toolsLp.topMargin = dp(8);
        syncBody.addView(tools, toolsLp);

        syncStatus = label("", 11, YgUi.FOG, YgUi.text(act));
        syncStatus.setGravity(Gravity.CENTER);
        syncStatus.setMaxLines(2);
        syncStatus.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ssp = ll(MATCH, WRAP);
        ssp.topMargin = dp(4);
        syncBody.addView(syncStatus, ssp);

        panel.addView(syncBody, ll(MATCH, WRAP));
        refreshSync();
        return panel;
    }

    /** عرض اللوحة يُضبط ذاتياً (حتى 520dp) فيتكيّف مع تدوير الشاشة دون إعادة بناء. */
    FrameLayout.LayoutParams syncPanelParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        lp.bottomMargin = dp(10);
        return lp;
    }

    boolean isSyncPanelVisible() {
        return syncPanel != null && syncPanel.getVisibility() == View.VISIBLE;
    }

    void toggleSyncPanel() {
        if (isSyncPanelVisible()) {
            hideSyncPanel();
        } else {
            showSyncPanel();
        }
    }

    void showSyncPanel() {
        if (syncPanel == null) {
            return;
        }
        if (audio == null || !audio.hasSource()) {
            host.openPage();
            return;
        }
        refreshSync();
        syncPanel.setAlpha(0f);
        syncPanel.setVisibility(View.VISIBLE);
        syncPanel.animate().alpha(1f).setDuration(160).start();
    }

    void hideSyncPanel() {
        if (syncPanel == null || !isSyncPanelVisible()) {
            return;
        }
        syncPanel.animate().alpha(0f).setDuration(140).withEndAction(new Runnable() {
            @Override
            public void run() {
                syncPanel.setVisibility(View.GONE);
            }
        }).start();
    }

    private void setSyncCollapsed(boolean collapsed) {
        syncCollapsed = collapsed;
        syncBody.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        syncCollapseIcon.setIcon(collapsed ? "chevron-left" : "chevron-right");
    }

    @SuppressWarnings("ClickableViewAccessibility")
    private void installDrag(View handle, final View panel) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downX;
            float downY;
            float startTx;
            float startTy;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startTx = panel.getTranslationX();
                        startTy = panel.getTranslationY();
                        host.touch();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        final View parent = (View) panel.getParent();
                        float tx = startTx + (e.getRawX() - downX);
                        float ty = startTy + (e.getRawY() - downY);
                        if (parent != null) {
                            // نبقي اللوحة داخل الشاشة: لا تتجاوز حدود الأب.
                            final float maxX = (parent.getWidth() - panel.getWidth()) / 2f;
                            tx = Math.max(-maxX, Math.min(maxX, tx));
                            final float minY = -(parent.getHeight() - panel.getHeight() - dp(10));
                            ty = Math.max(minY, Math.min(0f, ty));
                        }
                        panel.setTranslationX(tx);
                        panel.setTranslationY(ty);
                        return true;
                    }
                    default:
                        return true;
                }
            }
        });
    }

    private void refreshSync() {
        if (syncPanel == null) {
            return;
        }
        if (audio == null || !audio.hasSource()) {
            if (isSyncPanelVisible()) {
                syncPanel.setVisibility(View.GONE);
            }
            return;
        }
        final long off = audio.getOffsetMs();
        syncValue.setText(formatSeconds(off, true) + " ث");
        syncValue.setTextColor(off == 0L ? Color.WHITE : EXTRA_SOFT);
        if (!jog.isDragging()) {
            syncCaption.setText(syncCaptionText());
            syncCaption.setTextColor(audio.getStatus() == ExtraAudioPlayer.Status.ERROR ? YgUi.BAD : YgUi.MIST);
        }
        syncStatus.setText(audio.getSourceName() + " • " + statusText());
        syncStatus.setTextColor(statusColor());

        syncPauseIcon.setIcon(audio.isUserPaused() ? "play" : "pause");
        syncPauseIcon.setFilled(audio.isUserPaused());
        syncPauseBtn.setBackground(audio.isUserPaused()
                ? tint(YgUi.withAlpha(EXTRA, 0x66), EXTRA, true, 0) : YgUi.glass(act, 0, true));
        syncPauseBtn.setContentDescription(audio.isUserPaused() ? "استئناف الصوت الإضافي" : "إيقاف مؤقت للصوت الإضافي");
        syncResetIcon.setIcon(audio.isLiveLike() ? "signal-high" : "rotate-ccw");
        syncResetBtn.setContentDescription(audio.isLiveLike() ? "العودة لأحدث نقطة في البث" : "تصفير الإزاحة");

        // التقديم غير ممكن عند الحافة الحية للبث غير القابل للسَّعي: نُعتّم أزراره.
        final boolean cannotForward = audio.getSyncKind() == ExtraAudioPlayer.SyncKind.LIVE_DELAY && off >= 0L;
        for (TextView c : forwardChips) {
            c.setAlpha(cannotForward ? 0.4f : 1f);
        }
    }

    private void refreshAll() {
        refreshQuick();
        refreshEntry();
        refreshPage();
        refreshSync();
    }

    // =====================================================================================
    //  الحوارات
    // =====================================================================================

    void showAddLinkDialog(String prefill) {
        final LinearLayout card = YgDialog.card(act);
        card.addView(YgDialog.badge(act, "link", YgDialog.TONE_BRAND), ll(WRAP, WRAP));
        LinearLayout.LayoutParams tp = ll(MATCH, WRAP);
        tp.topMargin = dp(12);
        card.addView(YgDialog.title(act, "إضافة رابط صوتي"), tp);
        LinearLayout.LayoutParams mp = ll(MATCH, WRAP);
        mp.topMargin = dp(6);
        card.addView(YgDialog.message(act, "الصق رابط بثّ صوتي مباشر أو ملف (mp3 / aac / m4a / mp4 / m3u8 / pls …)"), mp);

        final EditText input = new EditText(act);
        input.setSingleLine(true);
        input.setHint("https://example.com/stream");
        input.setHintTextColor(YgUi.FOG);
        input.setTextColor(Color.WHITE);
        input.setTextSize(14);
        input.setTypeface(YgUi.text(act));
        input.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setBackground(YgUi.glass(act, dp(14), false));
        if (prefill != null) {
            input.setText(prefill);
        }
        LinearLayout.LayoutParams ip = ll(MATCH, dp(48));
        ip.topMargin = dp(14);
        card.addView(input, ip);

        final TextView error = label("", 12, YgUi.BAD, YgUi.text(act));
        error.setGravity(Gravity.RIGHT);
        error.setVisibility(View.GONE);
        LinearLayout.LayoutParams errp = ll(MATCH, WRAP);
        errp.topMargin = dp(6);
        card.addView(error, errp);

        final Dialog[] holder = new Dialog[1];
        final Runnable submit = new Runnable() {
            @Override
            public void run() {
                if (audio == null) {
                    return;
                }
                final String err = audio.setSourceUrl(input.getText().toString());
                if (err != null) {
                    error.setText(err);
                    error.setVisibility(View.VISIBLE);
                    return;
                }
                holder[0].dismiss();
                host.toast("تمت إضافة الصوت");
            }
        };
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submit.run();
                    return true;
                }
                return false;
            }
        });

        LinearLayout buttons = new LinearLayout(act);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        View cancel = YgDialog.ghostButton(act, "إلغاء", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                holder[0].dismiss();
            }
        });
        View paste = YgDialog.ghostButton(act, "لصق", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String clip = readClipboard();
                if (clip == null || clip.isEmpty()) {
                    error.setText("الحافظة فارغة");
                    error.setVisibility(View.VISIBLE);
                } else {
                    input.setText(clip.trim());
                    input.setSelection(input.getText().length());
                    error.setVisibility(View.GONE);
                }
            }
        });
        View add = YgDialog.primaryButton(act, "إضافة", "plus", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit.run();
            }
        });
        LinearLayout.LayoutParams b1 = ll(0, dp(48), 1f);
        b1.rightMargin = dp(4);
        LinearLayout.LayoutParams b2 = ll(0, dp(48), 1f);
        b2.leftMargin = dp(4);
        b2.rightMargin = dp(4);
        LinearLayout.LayoutParams b3 = ll(0, dp(48), 1.4f);
        b3.leftMargin = dp(4);
        buttons.addView(cancel, b1);
        buttons.addView(paste, b2);
        buttons.addView(add, b3);
        LinearLayout.LayoutParams bp = ll(MATCH, WRAP);
        bp.topMargin = dp(16);
        card.addView(buttons, bp);

        final Dialog d = YgDialog.create(act, card, true);
        holder[0] = d;
        d.setCanceledOnTouchOutside(true);
        showDialog(d, input);
    }

    void showOffsetDialog() {
        if (audio == null || !audio.hasSource()) {
            return;
        }
        final LinearLayout card = YgDialog.card(act);
        card.addView(YgDialog.badge(act, "pencil", YgDialog.TONE_BRAND), ll(WRAP, WRAP));
        LinearLayout.LayoutParams tp = ll(MATCH, WRAP);
        tp.topMargin = dp(12);
        card.addView(YgDialog.title(act, "ضبط المزامنة بالأرقام"), tp);
        LinearLayout.LayoutParams mp = ll(MATCH, WRAP);
        mp.topMargin = dp(6);
        card.addView(YgDialog.message(act, "اكتب عدد الثواني: القيمة السالبة ترجّع الصوت (يتأخر عن الصورة)، "
                + "والموجبة تقدّمه (يسبق الصورة). مثال: -2.5 أو 1.2"), mp);

        final EditText input = new EditText(act);
        input.setSingleLine(true);
        input.setHint("0.0");
        input.setHintTextColor(YgUi.FOG);
        input.setTextColor(Color.WHITE);
        input.setTextSize(18);
        input.setTypeface(YgUi.bold(act));
        input.setGravity(Gravity.CENTER);
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setBackground(YgUi.glass(act, dp(14), false));
        input.setText(formatSeconds(audio.getOffsetMs(), false));
        input.setSelection(input.getText().length());
        LinearLayout.LayoutParams ip = ll(MATCH, dp(52));
        ip.topMargin = dp(14);
        card.addView(input, ip);

        // بعض لوحات المفاتيح الرقمية بلا علامة (−): نوفّر زرّين يضبطان الإشارة مباشرة.
        LinearLayout signRow = new LinearLayout(act);
        signRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView neg = choiceChip("− ترجيع (يتأخر)");
        TextView pos = choiceChip("+ تقديم (يسبق)");
        neg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSign(input, true);
            }
        });
        pos.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSign(input, false);
            }
        });
        LinearLayout.LayoutParams sg1 = ll(0, dp(40), 1f);
        sg1.rightMargin = dp(3);
        LinearLayout.LayoutParams sg2 = ll(0, dp(40), 1f);
        sg2.leftMargin = dp(3);
        signRow.addView(neg, sg1);
        signRow.addView(pos, sg2);
        LinearLayout.LayoutParams sgp = ll(MATCH, WRAP);
        sgp.topMargin = dp(8);
        card.addView(signRow, sgp);

        final TextView error = label("", 12, YgUi.BAD, YgUi.text(act));
        error.setGravity(Gravity.RIGHT);
        error.setVisibility(View.GONE);
        LinearLayout.LayoutParams errp = ll(MATCH, WRAP);
        errp.topMargin = dp(6);
        card.addView(error, errp);

        final Dialog[] holder = new Dialog[1];
        final Runnable apply = new Runnable() {
            @Override
            public void run() {
                final long ms = parseSecondsToMs(input.getText().toString());
                if (ms == Long.MIN_VALUE) {
                    error.setText("اكتب رقماً صحيحاً مثل -2.5 أو 1.2");
                    error.setVisibility(View.VISIBLE);
                    return;
                }
                holder[0].dismiss();
                if (audio != null) {
                    audio.setOffsetMs(ms, true);
                }
            }
        };
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    apply.run();
                    return true;
                }
                return false;
            }
        });

        LinearLayout buttons = new LinearLayout(act);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        View cancel = YgDialog.ghostButton(act, "إلغاء", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                holder[0].dismiss();
            }
        });
        View ok = YgDialog.primaryButton(act, "تطبيق", "check", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                apply.run();
            }
        });
        LinearLayout.LayoutParams b1 = ll(0, dp(48), 1f);
        b1.rightMargin = dp(4);
        LinearLayout.LayoutParams b2 = ll(0, dp(48), 1.4f);
        b2.leftMargin = dp(4);
        buttons.addView(cancel, b1);
        buttons.addView(ok, b2);
        LinearLayout.LayoutParams bp = ll(MATCH, WRAP);
        bp.topMargin = dp(16);
        card.addView(buttons, bp);

        final Dialog d = YgDialog.create(act, card, true);
        holder[0] = d;
        d.setCanceledOnTouchOutside(true);
        showDialog(d, input);
    }

    void showRadioDialog() {
        final LinearLayout card = YgDialog.card(act);
        card.addView(YgDialog.badge(act, "radio", YgDialog.TONE_BRAND), ll(WRAP, WRAP));
        LinearLayout.LayoutParams tp = ll(MATCH, WRAP);
        tp.topMargin = dp(12);
        card.addView(YgDialog.title(act, "اختر محطة راديو"), tp);
        final TextView msg = YgDialog.message(act, "جارٍ تحميل المحطات…");
        LinearLayout.LayoutParams mp = ll(MATCH, WRAP);
        mp.topMargin = dp(6);
        card.addView(msg, mp);

        final MaxHeightScroll scroll = new MaxHeightScroll(act, dp(300));
        scroll.setVerticalScrollBarEnabled(false);
        final LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new FrameLayout.LayoutParams(MATCH, WRAP));
        LinearLayout.LayoutParams sp = ll(MATCH, WRAP);
        sp.topMargin = dp(12);
        card.addView(scroll, sp);

        final Dialog[] holder = new Dialog[1];
        View cancel = YgDialog.ghostButton(act, "إغلاق", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                holder[0].dismiss();
            }
        });
        LinearLayout.LayoutParams cp = ll(MATCH, dp(46));
        cp.topMargin = dp(14);
        card.addView(cancel, cp);

        final Dialog d = YgDialog.create(act, card, true);
        holder[0] = d;
        d.setCanceledOnTouchOutside(true);
        showDialog(d, null);

        RadioDirectory.load(act, new RadioDirectory.Callback() {
            @Override
            public void onLoaded(List<RadioDirectory.Station> stations, boolean fromCache) {
                if (!d.isShowing()) {
                    return;
                }
                msg.setText(fromCache ? "تعذّر الاتصال — هذه آخر قائمة محفوظة" : "اضغط على المحطة لإضافتها كصوت إضافي");
                list.removeAllViews();
                for (final RadioDirectory.Station st : stations) {
                    TextView row = label(st.name, 14, Color.WHITE, YgUi.bold(act));
                    row.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(14), 0, dp(14), 0);
                    row.setMaxLines(1);
                    row.setEllipsize(TextUtils.TruncateAt.END);
                    row.setBackground(YgUi.glass(act, dp(14), false));
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (audio == null) {
                                return;
                            }
                            final String err = audio.setSourceUrl(st.url, st.name);
                            if (err != null) {
                                msg.setText(err);
                                return;
                            }
                            holder[0].dismiss();
                            host.toast("تمت إضافة «" + st.name + "»");
                        }
                    });
                    YgUi.pressable(row);
                    LinearLayout.LayoutParams rp = ll(MATCH, dp(46));
                    rp.bottomMargin = dp(6);
                    list.addView(row, rp);
                }
            }

            @Override
            public void onFailed(String message) {
                if (d.isShowing()) {
                    msg.setText(message);
                }
            }
        });
    }

    private static void setSign(EditText e, boolean negative) {
        String t = e.getText().toString().trim();
        if (t.startsWith("-") || t.startsWith("+") || t.startsWith("\u2212")) {
            t = t.substring(1);
        }
        if (t.isEmpty()) {
            t = "0";
        }
        e.setText(negative ? "-" + t : t);
        e.setSelection(e.getText().length());
    }

    private String readClipboard() {
        try {
            final ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                return null;
            }
            final ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) {
                return null;
            }
            final CharSequence t = cd.getItemAt(0).coerceToText(act);
            return t == null ? null : t.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** يعرض الحوار دون أن تظهر أشرطة النظام فوق المشغّل، ويفتح لوحة المفاتيح عند وجود حقل إدخال. */
    private void showDialog(final Dialog d, final EditText focus) {
        dialogs.add(d);
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface dialog) {
                dialogs.remove(d);
            }
        });
        final Window w = d.getWindow();
        if (w != null) {
            w.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        d.show();
        if (w != null) {
            try {
                final WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
                if (c != null) {
                    c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    c.hide(WindowInsetsCompat.Type.systemBars());
                }
            } catch (Throwable ignored) {
                // الشكل فقط
            }
            w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        if (focus != null) {
            focus.requestFocus();
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        final InputMethodManager imm =
                                (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null && focus.isAttachedToWindow()) {
                            imm.showSoftInput(focus, InputMethodManager.SHOW_IMPLICIT);
                        }
                    } catch (Throwable ignored) {
                        // لا يهم
                    }
                }
            }, 180);
        }
    }

    // =====================================================================================
    //  نصوص الحالة والتنسيق
    // =====================================================================================

    private String statusText() {
        if (audio == null) {
            return "";
        }
        switch (audio.getStatus()) {
            case LOADING:
                return audio.getStatusMessage().isEmpty() ? "جارٍ التحميل…" : audio.getStatusMessage();
            case PLAYING:
                return "يعمل";
            case WAITING:
                return "في انتظار تشغيل الفيديو";
            case PAUSED:
                return "متوقف مؤقتاً";
            case RECONNECTING:
                return audio.getStatusMessage();
            case ERROR:
                return audio.getStatusMessage();
            case ENDED:
                return "انتهى الصوت";
            default:
                return "";
        }
    }

    private int statusColor() {
        if (audio == null) {
            return YgUi.MIST;
        }
        switch (audio.getStatus()) {
            case PLAYING:
                return YgUi.OK;
            case LOADING:
            case RECONNECTING:
                return YgUi.GOLD;
            case ERROR:
                return YgUi.BAD;
            default:
                return YgUi.MIST;
        }
    }

    private String syncCaptionText() {
        if (audio == null || !audio.hasSource()) {
            return "";
        }
        if (audio.getStatus() == ExtraAudioPlayer.Status.ERROR) {
            return audio.getStatusMessage();
        }
        final long off = audio.getOffsetMs();
        if (off == 0L) {
            return audio.isLiveLike() ? "الصوت على أحدث نقطة في البث (بدون ترجيع)" : "الصوت متزامن مع الصورة (بدون إزاحة)";
        }
        final String amount = formatSeconds(Math.abs(off), false) + " ث";
        return off < 0 ? "الصوت متأخر عن الصورة بمقدار " + amount : "الصوت متقدّم على الصورة بمقدار " + amount;
    }

    private String syncHintText() {
        if (audio != null && audio.getSyncKind() == ExtraAudioPlayer.SyncKind.LIVE_DELAY) {
            return "الصوت يسبق الصورة؟ اضغط «ترجيع». الصوت متأخر؟ أرجِع الصورة (الفيديو) للخلف أو قلّل الترجيع.";
        }
        return "الصوت يسبق الصورة؟ اضغط «ترجيع». الصوت متأخر عنها؟ اضغط «تقديم».";
    }

    /** 2.4 → "+2.4" ، −0.05 → "−0.05" ؛ withSign=false لعرض القيمة المطلقة/إدخال نصي. */
    static String formatSeconds(long ms, boolean withSign) {
        final double s = ms / 1000.0;
        String t = String.format(Locale.US, "%.2f", Math.abs(s));
        if (t.endsWith("0")) {
            t = t.substring(0, t.length() - 1);
        }
        if (ms == 0L) {
            return "0.0";
        }
        final String sign = ms < 0 ? (withSign ? "\u2212" : "-") : (withSign ? "+" : "");
        return sign + t;
    }

    /** يحوّل نصاً مثل "-2.5" أو "٢٫٥" إلى ميلي ثانية. Long.MIN_VALUE عند الفشل. */
    static long parseSecondsToMs(String raw) {
        if (raw == null) {
            return Long.MIN_VALUE;
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch >= '\u0660' && ch <= '\u0669') {
                ch = (char) ('0' + (ch - '\u0660'));
            } else if (ch == '\u066B' || ch == ',') {
                ch = '.';
            } else if (ch == '\u2212' || ch == '\u2013') {
                ch = '-';
            } else if (Character.isWhitespace(ch)) {
                continue;
            }
            sb.append(ch);
        }
        final String s = sb.toString();
        if (s.isEmpty() || s.equals("-") || s.equals("+") || s.equals(".")) {
            return Long.MIN_VALUE;
        }
        try {
            final double v = Double.parseDouble(s);
            if (Double.isNaN(v) || Double.isInfinite(v) || Math.abs(v) > 100000d) {
                return Long.MIN_VALUE;
            }
            return Math.round(v * 1000d);
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    /** مقبض الضبط الدقيق: غير خطي (دقيق قرب المنتصف) حتى ±10 ثوانٍ، وبدقة 10ms. */
    static long jogDeltaMs(float v) {
        final float x = (v - 0.5f) * 2f;
        final float m = Math.abs(x);
        final long raw = Math.round(m * m * 10_000f);
        final long snapped = (raw / 10L) * 10L;
        if (snapped < 20L) {
            return 0L;
        }
        return x < 0 ? -snapped : snapped;
    }

    // =====================================================================================
    //  مكوّنات واجهة صغيرة
    // =====================================================================================

    private TextView stepChip(final long deltaMs, String text, final boolean repeatable, final boolean forward) {
        final TextView c = label(text, 11.5f, Color.WHITE, YgUi.bold(act));
        c.setGravity(Gravity.CENTER);
        c.setIncludeFontPadding(false);
        c.setSingleLine(true);
        c.setMinWidth(0);
        c.setPadding(0, 0, 0, 0);
        c.setBackground(YgUi.glass(act, dp(12), false));
        c.setContentDescription((forward ? "تقديم الصوت " : "ترجيع الصوت ") + (Math.abs(deltaMs) / 1000.0) + " ثانية");
        // للمساعد الصوتي (TalkBack): النقر العادي ينفّذ خطوة واحدة؛ اللمس المباشر تعالجه المستمِعة أدناه.
        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (audio != null) {
                    audio.nudge(deltaMs);
                }
            }
        });
        c.setOnTouchListener(new View.OnTouchListener() {
            private int count;
            private final Runnable repeater = new Runnable() {
                @Override
                public void run() {
                    fire(c);
                    count++;
                    ui.postDelayed(this, count < 6 ? 150L : (count < 16 ? 90L : 55L));
                }
            };

            private void fire(View v) {
                if (audio != null) {
                    audio.nudge(deltaMs);
                }
                try {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                } catch (Throwable ignored) {
                    // اختياري
                }
            }

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        host.touch();
                        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).start();
                        v.setBackground(tint(YgUi.withAlpha(EXTRA, 0x55), EXTRA, false, dp(12)));
                        fire(v);
                        if (repeatable) {
                            count = 0;
                            ui.removeCallbacks(repeater);
                            ui.postDelayed(repeater, 420L);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        ui.removeCallbacks(repeater);
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                        v.setBackground(YgUi.glass(act, dp(12), false));
                        return true;
                    default:
                        return true;
                }
            }
        });
        return c;
    }

    private FrameLayout toolButton(String icon, String description) {
        FrameLayout b = new FrameLayout(act);
        b.setBackground(YgUi.glass(act, 0, true));
        b.addView(new YgIconView(act, icon, Color.WHITE), fl(dp(20), dp(20), Gravity.CENTER));
        b.setContentDescription(description);
        YgUi.pressable(b);
        return b;
    }

    private View iconButton(String icon, int sizeDp, int iconDp, String description) {
        FrameLayout b = new FrameLayout(act);
        b.setBackground(YgUi.glass(act, 0, true));
        b.addView(new YgIconView(act, icon, Color.WHITE), fl(dp(iconDp), dp(iconDp), Gravity.CENTER));
        b.setContentDescription(description);
        YgUi.pressable(b);
        return b;
    }

    private FrameLayout muteButton(boolean extra) {
        FrameLayout b = new FrameLayout(act);
        b.setBackground(YgUi.glass(act, 0, true));
        b.addView(new YgIconView(act, "volume-2", Color.WHITE), fl(dp(20), dp(20), Gravity.CENTER));
        b.setContentDescription(extra ? "كتم الصوت الإضافي" : "كتم الصوت الأصلي");
        YgUi.pressable(b);
        return b;
    }

    private void styleMute(FrameLayout btn, YgIconView icon, boolean muted, int accent) {
        icon.setIcon(muted ? "volume-x" : "volume-2");
        icon.setColor(muted ? YgUi.BAD : Color.WHITE);
        btn.setBackground(muted ? tint(YgUi.withAlpha(YgUi.BAD, 0x33), YgUi.withAlpha(YgUi.BAD, 0xAA), true, 0)
                : YgUi.glass(act, 0, true));
    }

    private LinearLayout volumeBlock(String name, TextView pct, ValueSlider slider, FrameLayout mute) {
        LinearLayout block = new LinearLayout(act);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(12), dp(10), dp(10), dp(8));
        block.setBackground(YgUi.glass(act, dp(16), false));
        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        pct.setGravity(Gravity.LEFT);
        top.addView(pct, ll(WRAP, WRAP));
        TextView nm = label(name, 13, Color.WHITE, YgUi.bold(act));
        nm.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams nlp = ll(0, WRAP, 1f);
        nlp.leftMargin = dp(8);
        top.addView(nm, nlp);
        block.addView(top, ll(MATCH, WRAP));
        LinearLayout line = new LinearLayout(act);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(slider, ll(0, dp(40), 1f));
        LinearLayout.LayoutParams mlp = ll(dp(38), dp(38));
        mlp.leftMargin = dp(6);
        line.addView(mute, mlp);
        block.addView(line, ll(MATCH, WRAP));
        return block;
    }

    private TextView sectionLabel(String text) {
        TextView t = label(text, 12, YgUi.COBALT_3, YgUi.bold(act));
        t.setGravity(Gravity.RIGHT);
        t.setPadding(dp(2), dp(14), dp(2), dp(8));
        return t;
    }

    private TextView actionChip(String text) {
        TextView t = label(text, 13, YgUi.CHALK, YgUi.bold(act));
        t.setGravity(Gravity.CENTER);
        t.setBackground(YgUi.glass(act, dp(14), false));
        YgUi.pressable(t);
        return t;
    }

    /** زر كبير بأيقونة فوق نص (إضافة رابط/ملف/راديو). */
    private TextView bigChip(String text, String icon) {
        final TextView t = new TextView(act);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13);
        t.setTypeface(YgUi.bold(act));
        t.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        t.setIncludeFontPadding(false);
        t.setPadding(dp(4), dp(10), dp(4), dp(10));
        t.setBackground(new IconChipDrawable(act, icon));
        YgUi.pressable(t);
        return t;
    }

    private void setChipText(TextView t, String text) {
        if (t != null) {
            t.setText(text);
        }
    }

    private TextView choiceChip(String text) {
        TextView t = label(text, 11.5f, Color.WHITE, YgUi.bold(act));
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setPadding(dp(4), 0, dp(4), 0);
        t.setMaxLines(2);
        YgUi.pressable(t);
        styleChoice(t, false);
        return t;
    }

    private void styleChoice(TextView t, boolean selected) {
        if (t == null) {
            return;
        }
        t.setBackground(selected ? emberGradient(dp(14)) : YgUi.glass(act, dp(14), false));
        t.setTextColor(selected ? Color.WHITE : YgUi.MIST);
    }

    private GradientDrawable emberGradient(float radiusPx) {
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFFFA23A, 0xFFEA6A12, 0xFFB8470A});
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(radiusPx);
        return gd;
    }

    private GradientDrawable tint(int fill, int stroke, boolean circle, float radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(circle ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        gd.setColor(fill);
        gd.setStroke(Math.max(1, dp(1)), stroke);
        if (!circle) {
            gd.setCornerRadius(radiusPx);
        }
        return gd;
    }

    private TextView label(String text, float sp, int color, Typeface tf) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (tf != null) {
            t.setTypeface(tf);
        }
        return t;
    }

    private int dp(float v) {
        return YgUi.dp(act, v);
    }

    private static FrameLayout.LayoutParams fl(int w, int h, int gravity) {
        return new FrameLayout.LayoutParams(w, h, gravity);
    }

    private static LinearLayout.LayoutParams ll(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private static LinearLayout.LayoutParams ll(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    // =====================================================================================
    //  أصناف مساعدة
    // =====================================================================================

    /** خلفية زر كبير: زجاج + أيقونة في الأعلى (تُرسم بنفس مسارات YgIcons). */
    private static final class IconChipDrawable extends android.graphics.drawable.Drawable {
        private final GradientDrawable base;
        private final android.graphics.Path path;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        IconChipDrawable(Context c, String icon) {
            density = c.getResources().getDisplayMetrics().density;
            base = YgUi.glass(c, YgUi.dp(c, 16), false);
            path = YgIconView.parse(YgIcons.data(icon));
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(YgUi.EMBER_2);
        }

        @Override
        public void draw(Canvas canvas) {
            final android.graphics.Rect b = getBounds();
            base.setBounds(b);
            base.draw(canvas);
            final float size = 26f * density;
            final float scale = size / 24f;
            canvas.save();
            canvas.translate(b.centerX() - size / 2f, b.top + 12f * density);
            canvas.scale(scale, scale);
            stroke.setStrokeWidth(2f);
            canvas.drawPath(path, stroke);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            base.setAlpha(alpha);
            stroke.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            stroke.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /** حاوية لوحة المزامنة: تحدّ عرضها الأقصى وتُبقي موضعها (بعد السحب) داخل الشاشة عند تغيّر الأبعاد. */
    private static final class SyncPanelLayout extends LinearLayout {
        private final int maxWidthPx;

        SyncPanelLayout(Context c, int maxWidthPx) {
            super(c);
            this.maxWidthPx = maxWidthPx;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int size = View.MeasureSpec.getSize(widthMeasureSpec);
            final int w = size > 0 ? Math.min(size, maxWidthPx) : maxWidthPx;
            super.onMeasure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), heightMeasureSpec);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (changed && getParent() instanceof View) {
                final View parent = (View) getParent();
                final float maxX = Math.max(0f, (parent.getWidth() - getWidth()) / 2f);
                final float minY = -Math.max(0f, parent.getHeight() - getHeight());
                setTranslationX(Math.max(-maxX, Math.min(maxX, getTranslationX())));
                setTranslationY(Math.max(minY, Math.min(0f, getTranslationY())));
            }
        }
    }

    /** ScrollView بحدّ أقصى للارتفاع (لقائمة المحطات). */
    private static final class MaxHeightScroll extends ScrollView {
        private final int maxHeightPx;

        MaxHeightScroll(Context c, int maxHeightPx) {
            super(c);
            this.maxHeightPx = maxHeightPx;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST));
        }
    }

    /** شريط قيمة مخصّص (أفقي): عادي (يملأ من اليسار) أو مركزي (يملأ من المنتصف — لمقبض الضبط الدقيق). */
    static final class ValueSlider extends View {
        interface Listener {
            void onStart();

            void onChange(float value, boolean fromUser);

            void onEnd(float value);
        }

        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final boolean centered;
        private final int accent;
        private final float d;
        private float value;
        private boolean dragging;
        private boolean dim;
        private Listener listener;

        ValueSlider(Context c, boolean centered, int accent) {
            super(c);
            this.centered = centered;
            this.accent = accent;
            this.d = c.getResources().getDisplayMetrics().density;
            this.value = centered ? 0.5f : 1f;
            track.setColor(YgUi.withAlpha(Color.WHITE, 0x2A));
            fill.setColor(accent);
            thumb.setColor(Color.WHITE);
            thumbRing.setColor(accent);
            thumbRing.setStyle(Paint.Style.STROKE);
            thumbRing.setStrokeWidth(3f * d);
            tick.setColor(YgUi.withAlpha(Color.WHITE, 0x66));
        }

        void setListener(Listener l) {
            this.listener = l;
        }

        boolean isDragging() {
            return dragging;
        }

        void setValue(float v) {
            if (dragging) {
                return;
            }
            value = v < 0f ? 0f : (v > 1f ? 1f : v);
            invalidate();
        }

        void setAlphaDim(boolean dimmed) {
            if (dim != dimmed) {
                dim = dimmed;
                setAlpha(dimmed ? 0.45f : 1f);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int w = resolveSize((int) (160 * d), widthMeasureSpec);
            final int h = resolveSize((int) (40 * d), heightMeasureSpec);
            setMeasuredDimension(w, h);
        }

        private float pad() {
            return 14f * d;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float pad = pad();
            final float w = getWidth() - pad * 2f;
            final float cy = getHeight() / 2f;
            final float th = 6f * d;
            rect.set(pad, cy - th / 2f, pad + w, cy + th / 2f);
            canvas.drawRoundRect(rect, th, th, track);
            final float x = pad + w * value;
            if (centered) {
                final float cx = pad + w * 0.5f;
                rect.set(Math.min(cx, x), cy - th / 2f, Math.max(cx, x), cy + th / 2f);
                canvas.drawRoundRect(rect, th, th, fill);
                canvas.drawRect(cx - 1f * d, cy - 8f * d, cx + 1f * d, cy + 8f * d, tick);
            } else {
                rect.set(pad, cy - th / 2f, x, cy + th / 2f);
                canvas.drawRoundRect(rect, th, th, fill);
            }
            canvas.drawCircle(x, cy, 10f * d, thumb);
            canvas.drawCircle(x, cy, 10f * d, thumbRing);
        }

        private void updateFrom(float touchX) {
            final float pad = pad();
            final float w = Math.max(1f, getWidth() - pad * 2f);
            float v = (touchX - pad) / w;
            v = v < 0f ? 0f : (v > 1f ? 1f : v);
            if (centered && Math.abs(v - 0.5f) < 0.02f) {
                v = 0.5f; // مغناطيس عند الصفر
            }
            value = v;
            invalidate();
            if (listener != null) {
                listener.onChange(value, true);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (listener != null) {
                        listener.onStart();
                    }
                    updateFrom(e.getX());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) {
                        updateFrom(e.getX());
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        dragging = false;
                        if (listener != null) {
                            listener.onEnd(value);
                        }
                        invalidate();
                    }
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }
    }
}
