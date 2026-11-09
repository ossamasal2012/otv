package com.yallagoal.app;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/**
 * معالج «إزاحة زمنية» للصوت (PCM 16-bit) يعمل داخل مشغّل الصوت الإضافي فقط.
 *
 * <p>المشكلة التي يحلّها: البث الصوتي المباشر غير المنتهي (Icecast/Shoutcast/راديو) لا يقبل
 * السَّعي أصلاً — لا يمكن «ترجيعه» بـ seekTo. وهو في الغالب يسبق صورة قناة IPTV بعدة ثوانٍ، فيحتاج
 * المستخدم لتأخيره بدقة ليتطابق مع الصورة. لذلك نحتفظ بسجلّ دائري لآخر ثوانٍ مرّت من الصوت،
 * ونقرأ منه بتأخير قابل للتغيير لحظياً:
 * <ul>
 *   <li>ترجيع الصوت = زيادة التأخير (نقفز بمؤشر القراءة للخلف داخل السجلّ فنسمع المقطع من جديد).</li>
 *   <li>تقديم الصوت = إنقاص التأخير (نقفز للأمام وصولاً إلى الحافة الحية = تأخير صفر).</li>
 * </ul>
 * كل قفزة تُنفَّذ بمزج متقاطع (Crossfade) قصير ≈ 12ms فلا تُسمع طقطقة.
 *
 * <p>إن طلب المستخدم تأخيراً أكبر مما سُجِّل بعد (مثلاً بعد ثوانٍ من بدء الصوت) فالجزء الناقص صمت،
 * وهو المعنى الصحيح لـ«تأخير الصوت بمقدار X»: تبدأ الأصوات بعد X ثانية.
 *
 * <p>الأبعاد: عدد الإطارات الداخلة = عدد الإطارات الخارجة دائماً (1:1)، فلا يتأثر زمن المشغّل.
 * عند التعطيل (الملفات والفيديو القابل للسَّعي) يمرّر المعالج الصوت كما هو بلا أي نسخ إضافي
 * ولا ذاكرة. الذاكرة: قاعدة 60 ثانية من السجلّ ثم تكبر حتى 120 ثانية عند الطلب فقط.
 *
 * <p>الخيوط: setters تُستدعى من خيط الواجهة (حقول volatile)، والمعالجة على خيط تشغيل ExoPlayer.
 */
@UnstableApi
final class TimeShiftAudioProcessor extends BaseAudioProcessor {

    /** أقصى تأخير ممكن (120 ثانية). */
    static final long MAX_DELAY_MS = 120_000L;
    /** سجلّ أساسي يُسجَّل دائماً أثناء التفعيل ليمكن الترجيع فوراً (60 ثانية ≈ 11MB عند ستيريو 48kHz). */
    private static final long BASE_HISTORY_MS = 60_000L;
    private static final long FADE_MS = 12L;
    private static final short[] ZEROS = new short[4096];

    private volatile boolean enabled = false;
    private volatile long targetDelayMs = 0L;
    private volatile long appliedDelayMs = 0L;
    private volatile boolean memoryFailure = false;

    // ---- حالة المعالجة (خيط التشغيل فقط) ----
    private int channels;
    private int sampleRate;
    private short[] ring = new short[0];
    private int capFrames;
    private long writeHead;
    private long readHead;
    private long fadeHead;
    private int fadeLeft;
    private int fadeTotal;
    private boolean primed;

    /** تفعيل/تعطيل المعالجة. عند التعطيل يمرّر الصوت دون تغيير ويُفرَّغ السجلّ. */
    void setEnabled(boolean on) {
        this.enabled = on;
    }

    boolean isEnabled() {
        return enabled;
    }

    /** التأخير المطلوب بالميلي ثانية (0 = الحافة الحية). يُقيَّد بين 0 و{@link #MAX_DELAY_MS}. */
    void setDelayMs(long ms) {
        long v = ms < 0L ? 0L : (ms > MAX_DELAY_MS ? MAX_DELAY_MS : ms);
        this.targetDelayMs = v;
    }

    long getTargetDelayMs() {
        return targetDelayMs;
    }

    /** التأخير الفعلي الحالي (قد يسبقه المطلوب بجزء من الثانية حتى تصل أول دفعة). */
    long getAppliedDelayMs() {
        return appliedDelayMs;
    }

    /** true إن تعذّر حجز ذاكرة السجلّ (عندها يعمل المعالج كممرّر فقط). */
    boolean hasMemoryFailure() {
        return memoryFailure;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat) {
        // نعمل فقط على PCM 16-bit (ما يصل لمعالجات DefaultAudioSink دائماً تقريباً بعد التحويل)،
        // وأي صيغة أخرى نبقى غير نشطين فيها فيتخطّانا المسار دون أي خطأ.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT
                || inputAudioFormat.channelCount < 1
                || inputAudioFormat.channelCount > 8
                || inputAudioFormat.sampleRate <= 0) {
            return AudioFormat.NOT_SET;
        }
        return inputAudioFormat;
    }

    @Override
    protected void onFlush() {
        channels = inputAudioFormat.channelCount > 0 ? inputAudioFormat.channelCount : 0;
        sampleRate = inputAudioFormat.sampleRate > 0 ? inputAudioFormat.sampleRate : 0;
        fadeTotal = Math.max(32, (int) (sampleRate * FADE_MS / 1000L));
        resetState();
    }

    @Override
    protected void onReset() {
        channels = 0;
        sampleRate = 0;
        ring = new short[0];
        capFrames = 0;
        resetState();
    }

    private void resetState() {
        writeHead = 0L;
        fadeLeft = 0;
        primed = false;
        // البداية بعد flush: أي تأخير مطلوب يظهر كصمت قبل أول صوت.
        readHead = -wantedDelayFrames();
        fadeHead = readHead;
    }

    @Override
    public void queueInput(ByteBuffer input) {
        final int bytes = input.remaining();
        if (bytes == 0) {
            return;
        }
        final ByteBuffer out = replaceOutputBuffer(bytes);

        if (!enabled || memoryFailure || channels <= 0 || sampleRate <= 0) {
            if (primed) {
                // تعطيل أثناء التشغيل: نُسقط السجلّ ليُعاد تأسيسه عند أي تفعيل لاحق.
                primed = false;
                ring = new short[0];
                capFrames = 0;
                appliedDelayMs = 0L;
            }
            out.put(input);
            out.flip();
            return;
        }

        final int ch = channels;
        final int frames = bytes / (2 * ch);
        if (frames == 0) {
            input.position(input.limit());
            out.flip();
            return;
        }

        final long want = wantedDelayFrames();
        if (!ensureCapacity(want + frames + fadeTotal + 16L)) {
            // لا ذاكرة كافية: نتحوّل لتمرير بسيط بدل أي خطأ.
            memoryFailure = true;
            out.put(input);
            out.flip();
            return;
        }

        if (!primed) {
            primed = true;
            readHead = writeHead - want;
            fadeLeft = 0;
        } else if (writeHead - readHead != want) {
            // تغيّر التأخير المطلوب: قفزة بمزج متقاطع بين المؤشر القديم والجديد.
            fadeHead = readHead;
            fadeLeft = fadeTotal;
            readHead = writeHead - want;
        }

        // 1) نكتب الدخل في السجلّ.
        final ShortBuffer inShorts = input.asShortBuffer();
        writeFrames(inShorts, frames);
        input.position(input.limit());

        // 2) نقرأ بنفس عدد الإطارات من مؤشر القراءة.
        final ShortBuffer outShorts = out.asShortBuffer();
        int done = 0;
        if (fadeLeft > 0) {
            final int m = Math.min(fadeLeft, frames);
            mixFade(outShorts, m);
            done = m;
        }
        if (done < frames) {
            copyRun(outShorts, readHead, frames - done);
            readHead += frames - done;
            if (fadeLeft > 0) {
                // لا يحدث عملياً (انتهى المزج داخل mixFade)، حماية فقط.
                fadeLeft = 0;
            }
        }
        out.position(0);
        out.limit(frames * ch * 2);

        appliedDelayMs = (writeHead - readHead) * 1000L / sampleRate;
    }

    // ------------------------------------------------------------------------------------

    private long wantedDelayFrames() {
        if (sampleRate <= 0) {
            return 0L;
        }
        return targetDelayMs * sampleRate / 1000L;
    }

    /** يضمن أن سعة السجلّ لا تقلّ عن {@code neededFrames}. يُرجع false عند نفاد الذاكرة. */
    private boolean ensureCapacity(long neededFrames) {
        if (capFrames >= neededFrames && capFrames > 0) {
            return true;
        }
        final long base = BASE_HISTORY_MS * sampleRate / 1000L;
        final long slack = sampleRate; // ثانية إضافية
        final long hardMax = (MAX_DELAY_MS + 4_000L) * sampleRate / 1000L + 8192L;
        long newCap = Math.max(base, neededFrames + slack);
        if (newCap > hardMax) {
            newCap = hardMax;
        }
        if (newCap < neededFrames) {
            // لا يمكن تلبية الطلب حتى بأقصى سعة (لا يحدث لأن التأخير مقيّد) — نرفضه بأمان.
            return false;
        }
        final int newCapInt = (int) newCap;
        final short[] newRing;
        try {
            newRing = new short[newCapInt * channels];
        } catch (OutOfMemoryError e) {
            return false;
        }
        // ننقل المحتوى الصالح (آخر capFrames إطار) إلى السجلّ الجديد بنفس الإحداثيات الافتراضية.
        if (capFrames > 0 && writeHead > 0) {
            final short[] old = ring;
            final int oldCap = capFrames;
            long p = Math.max(0L, writeHead - oldCap);
            while (p < writeHead) {
                final int oi = (int) (p % oldCap);
                final int ni = (int) (p % newCapInt);
                final int run = (int) Math.min(writeHead - p, Math.min(oldCap - oi, newCapInt - ni));
                System.arraycopy(old, oi * channels, newRing, ni * channels, run * channels);
                p += run;
            }
        }
        ring = newRing;
        capFrames = newCapInt;
        return true;
    }

    private void writeFrames(ShortBuffer in, int frames) {
        final int ch = channels;
        int remaining = frames;
        long pos = writeHead;
        while (remaining > 0) {
            final int idx = (int) (pos % capFrames);
            final int run = Math.min(remaining, capFrames - idx);
            in.get(ring, idx * ch, run * ch);
            pos += run;
            remaining -= run;
        }
        writeHead = pos;
    }

    /** ينسخ n إطاراً بدءاً من الموضع الافتراضي start (الأجزاء خارج السجلّ تصبح صمتاً). */
    private void copyRun(ShortBuffer os, long start, int n) {
        final int ch = channels;
        final long lo = Math.max(0L, writeHead - capFrames);
        final long hi = writeHead;
        int i = 0;
        while (i < n) {
            final long p = start + i;
            if (p < lo) {
                final int z = (int) Math.min(n - i, lo - p);
                putZeros(os, z * ch);
                i += z;
            } else if (p >= hi) {
                putZeros(os, (n - i) * ch);
                i = n;
            } else {
                final int avail = (int) Math.min(n - i, hi - p);
                final int idx = (int) (p % capFrames);
                final int run = Math.min(avail, capFrames - idx);
                os.put(ring, idx * ch, run * ch);
                i += run;
            }
        }
    }

    private static void putZeros(ShortBuffer os, int count) {
        int left = count;
        while (left > 0) {
            final int c = Math.min(left, ZEROS.length);
            os.put(ZEROS, 0, c);
            left -= c;
        }
    }

    private short sampleAt(long frame, int channel) {
        if (frame < 0L || frame >= writeHead || frame < writeHead - capFrames) {
            return 0;
        }
        return ring[(int) (frame % capFrames) * channels + channel];
    }

    /** m إطاراً أولى: مزج خطّي من الدفق القديم (fadeHead) إلى الجديد (readHead). */
    private void mixFade(ShortBuffer os, int m) {
        final int ch = channels;
        for (int j = 0; j < m; j++) {
            final float t = Math.min(1f, (fadeTotal - fadeLeft + j + 1) / (float) fadeTotal);
            for (int c = 0; c < ch; c++) {
                final float a = sampleAt(fadeHead + j, c);
                final float b = sampleAt(readHead + j, c);
                int v = Math.round(a + (b - a) * t);
                if (v > Short.MAX_VALUE) {
                    v = Short.MAX_VALUE;
                } else if (v < Short.MIN_VALUE) {
                    v = Short.MIN_VALUE;
                }
                os.put((short) v);
            }
        }
        readHead += m;
        fadeHead += m;
        fadeLeft -= m;
        if (fadeLeft < 0) {
            fadeLeft = 0;
        }
    }
}
