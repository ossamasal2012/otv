#!/usr/bin/env node
/*
 * ============================================================================================
 *  scripts/protect-assets.js — حماية واجهة التطبيق (index.html) أثناء البناء الرسمي فقط
 * ============================================================================================
 *
 *  يُستدعى من .github/workflows/main.yml (لا حاجة لتشغيله يدوياً). له أمران:
 *
 *  1) minify <ملف.html>
 *       يُصغِّر الـ JavaScript والـ CSS المضمَّنين داخل الملف ويحذف كل التعليقات (تعليقات الشرح
 *       العربية الطويلة تكشف تصميم النظام كله لأي منافس) ويُبدِّل الملف في مكانه.
 *       - أسماء الدوال/المتغيّرات على المستوى الأعلى (top-level) لا تُغيَّر إطلاقاً، لأن الصفحة
 *         تستدعيها بالاسم من أعلى النص (onclick="openChannel(...)") ومن Java عبر evaluateJavascript.
 *       - أي فشل في التصغير أو في فحص صحة النتيجة يوقف البناء بدل نشر ملف معطوب.
 *
 *  2) encrypt --in <html> --out <yg.dat> --vault <AssetVault.java> --keystore <ملف> --alias <اسم>
 *       يشفّر الملف بـ AES-256-GCM بنفس صيغة AssetVault.java تماماً:
 *         المفتاح = HMAC-SHA256( pepper , SHA256(شهادة التوقيع) || "yg-asset-v1" )
 *         pepper  = K1[i] ^ K2[(i*7+3)&31] ^ K3[i]     (تُقرأ المصفوفات من AssetVault.java)
 *         الملف الناتج = "YG1\0" || IV(12) || ciphertext || tag(16)
 *       كلمة سر الـ keystore تُقرأ من متغيّر البيئة KEYSTORE_PASSWORD (لا تُمرَّر بسطر الأوامر).
 *       بعد التشفير يفكّه السكربت فوراً للتحقق من مطابقة النص الأصلي بايتاً ببايت.
 *
 *  المتطلبات: Node 18+ ، وحزمتا terser و clean-css (يثبّتهما الـ workflow بإصدارات محدّدة).
 */
'use strict';

const fs = require('fs');
const vm = require('vm');
const path = require('path');
const crypto = require('crypto');
const { execFileSync } = require('child_process');

const MAGIC = Buffer.from([0x59, 0x47, 0x31, 0x00]); // "YG1\0"
const IV_LEN = 12;
const TAG_LEN = 16;
const KDF_LABEL = Buffer.from('yg-asset-v1', 'utf8');

function fail(msg) {
    console.error('\n❌ protect-assets: ' + msg + '\n');
    process.exit(1);
}

function requireTool(name) {
    try {
        return require(name);
    } catch (e) {
        fail('الحزمة "' + name + '" غير مثبّتة (NODE_PATH=' + (process.env.NODE_PATH || '') + ').');
    }
}

// --------------------------------------------------------------------------------------------
//  1) التصغير
// --------------------------------------------------------------------------------------------

const SCRIPT_RE = /<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi;
const STYLE_RE = /<style\b([^>]*)>([\s\S]*?)<\/style\s*>/gi;

function isPlainJsScript(attrs) {
    if (/\bsrc\s*=/i.test(attrs)) return false; // سكربت خارجي: لا نلمسه
    const m = /\btype\s*=\s*["']?([^"'\s>]+)/i.exec(attrs);
    if (!m) return true;
    const t = m[1].toLowerCase();
    return t === 'text/javascript' || t === 'application/javascript';
}

async function minifyHtml(html) {
    const { minify } = requireTool('terser');
    const CleanCSS = requireTool('clean-css');

    // نحمي كتل script/style بأرقام مؤقتة كي لا تمسّ إزالة تعليقات HTML أي نص بداخلها.
    const stash = [];
    let jsBlocks = 0;
    let cssBlocks = 0;

    async function replaceAsync(str, re, fn) {
        const parts = [];
        let last = 0;
        re.lastIndex = 0;
        let m;
        while ((m = re.exec(str)) !== null) {
            parts.push(str.slice(last, m.index));
            parts.push(await fn(m));
            last = m.index + m[0].length;
        }
        parts.push(str.slice(last));
        return parts.join('');
    }

    let out = await replaceAsync(html, SCRIPT_RE, async (m) => {
        const attrs = m[1];
        const body = m[2];
        let replacement = m[0];
        if (isPlainJsScript(attrs) && body.trim() !== '') {
            const res = await minify(body, {
                compress: { passes: 1 },
                // toplevel:false ⇒ أسماء المستوى الأعلى تبقى كما هي (ضرورية لـ onclick و evaluateJavascript)
                mangle: { toplevel: false },
                format: { comments: false },
                sourceMap: false,
            });
            if (!res || typeof res.code !== 'string' || res.code.length < 100) {
                fail('فشل تصغير JavaScript (ناتج فارغ).');
            }
            try {
                new vm.Script(res.code); // فحص صياغة فقط بدون تشغيل
            } catch (e) {
                fail('ناتج JavaScript المصغَّر غير صالح: ' + e.message);
            }
            replacement = '<script' + attrs + '>' + res.code + '</script>';
            jsBlocks++;
        }
        stash.push(replacement);
        return '\u0000YGBLOCK' + (stash.length - 1) + '\u0000';
    });

    out = await replaceAsync(out, STYLE_RE, async (m) => {
        const attrs = m[1];
        const body = m[2];
        let replacement = m[0];
        if (body.trim() !== '') {
            const res = new CleanCSS({ level: 1, inline: false }).minify(body);
            if (res.errors && res.errors.length) fail('فشل تصغير CSS: ' + res.errors.join(' | '));
            if (!res.styles || res.styles.length < 50) fail('ناتج CSS المصغَّر فارغ.');
            replacement = '<style' + attrs + '>' + res.styles + '</style>';
            cssBlocks++;
        }
        stash.push(replacement);
        return '\u0000YGBLOCK' + (stash.length - 1) + '\u0000';
    });

    // حذف تعليقات HTML (خارج script/style) — بدون المساس بالتعليقات الشرطية.
    out = out.replace(/<!--(?!\[if)[\s\S]*?-->/g, '');
    // إعادة الكتل المحمية.
    out = out.replace(/\u0000YGBLOCK(\d+)\u0000/g, (_, i) => stash[Number(i)]);

    if (jsBlocks < 1) fail('لم يُعثر على كتلة JavaScript مضمَّنة لتصغيرها.');
    if (cssBlocks < 1) fail('لم يُعثر على كتلة CSS مضمَّنة لتصغيرها.');
    return { html: out, jsBlocks, cssBlocks };
}

async function cmdMinify(file) {
    if (!file || !fs.existsSync(file)) fail('ملف غير موجود: ' + file);
    const before = fs.readFileSync(file, 'utf8');
    const { html, jsBlocks, cssBlocks } = await minifyHtml(before);
    if (html.length < 1000) fail('الناتج صغير بشكل مريب.');
    if (!/<\/html>\s*$/i.test(html.trim())) fail('الناتج لا ينتهي بـ </html> — ملف مبتور؟');
    fs.writeFileSync(file, html, 'utf8');
    console.log('✅ minify: ' + path.basename(file) + '  ' + Buffer.byteLength(before) + ' → ' +
        Buffer.byteLength(html) + ' bytes  (JS blocks: ' + jsBlocks + ', CSS blocks: ' + cssBlocks + ')');
}

// --------------------------------------------------------------------------------------------
//  2) التشفير
// --------------------------------------------------------------------------------------------

function readPepperFromJava(vaultFile) {
    const src = fs.readFileSync(vaultFile, 'utf8');
    const begin = src.indexOf('YG-PEPPER-BEGIN');
    const end = src.indexOf('YG-PEPPER-END');
    if (begin < 0 || end < 0 || end < begin) fail('علامتا YG-PEPPER غير موجودتين في ' + vaultFile);
    const block = src.slice(begin, end);
    const arrays = {};
    const re = /int\[\]\s+(K[123])\s*=\s*\{([^}]*)\}/g;
    let m;
    while ((m = re.exec(block)) !== null) {
        const nums = m[2].split(',').map((s) => s.trim()).filter((s) => s !== '').map((s) => {
            if (!/^0x[0-9a-fA-F]{1,2}$/.test(s)) fail('قيمة غير متوقعة في ' + m[1] + ': ' + s);
            return parseInt(s, 16);
        });
        if (nums.length !== 32) fail(m[1] + ' يجب أن تحوي 32 قيمة بالضبط (وُجد ' + nums.length + ').');
        arrays[m[1]] = nums;
    }
    if (!arrays.K1 || !arrays.K2 || !arrays.K3) fail('لم أجد المصفوفات K1/K2/K3 كاملة.');
    const pepper = Buffer.alloc(32);
    for (let i = 0; i < 32; i++) {
        pepper[i] = (arrays.K1[i] ^ arrays.K2[(i * 7 + 3) & 31] ^ arrays.K3[i]) & 0xff;
    }
    return pepper;
}

function deriveKey(pepper, certDigest) {
    return crypto.createHmac('sha256', pepper).update(certDigest).update(KDF_LABEL).digest();
}

function certDigestFromKeystore(keystore, alias) {
    const pass = process.env.KEYSTORE_PASSWORD;
    if (!pass) fail('متغيّر البيئة KEYSTORE_PASSWORD غير مضبوط.');
    if (!fs.existsSync(keystore)) fail('keystore غير موجود: ' + keystore);
    let der;
    try {
        der = execFileSync('keytool', [
            '-exportcert', '-alias', alias, '-keystore', keystore, '-storepass:env', 'KEYSTORE_PASSWORD',
        ], { encoding: 'buffer', stdio: ['ignore', 'pipe', 'pipe'], env: process.env });
    } catch (e) {
        fail('تعذّر تصدير الشهادة عبر keytool (تحقق من KEY_ALIAS وكلمة السر): ' +
            (e.stderr ? e.stderr.toString('utf8').slice(0, 300) : e.message));
    }
    if (!der || der.length < 100) fail('شهادة غير صالحة من keytool.');
    return crypto.createHash('sha256').update(der).digest();
}

function encryptBuffer(plain, key) {
    const iv = crypto.randomBytes(IV_LEN);
    const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
    cipher.setAAD(MAGIC);
    const ct = Buffer.concat([cipher.update(plain), cipher.final()]);
    return Buffer.concat([MAGIC, iv, ct, cipher.getAuthTag()]);
}

function decryptBuffer(blob, key) {
    if (!blob.subarray(0, MAGIC.length).equals(MAGIC)) throw new Error('bad magic');
    const iv = blob.subarray(MAGIC.length, MAGIC.length + IV_LEN);
    const tag = blob.subarray(blob.length - TAG_LEN);
    const ct = blob.subarray(MAGIC.length + IV_LEN, blob.length - TAG_LEN);
    const d = crypto.createDecipheriv('aes-256-gcm', key, iv);
    d.setAAD(MAGIC);
    d.setAuthTag(tag);
    return Buffer.concat([d.update(ct), d.final()]);
}

function parseArgs(argv) {
    const o = {};
    for (let i = 0; i < argv.length; i++) {
        const a = argv[i];
        if (a.startsWith('--')) {
            o[a.slice(2)] = argv[i + 1];
            i++;
        }
    }
    return o;
}

function cmdEncrypt(args) {
    for (const k of ['in', 'out', 'vault']) if (!args[k]) fail('الوسيط --' + k + ' مطلوب.');
    if (!fs.existsSync(args.in)) fail('الملف المصدر غير موجود: ' + args.in);

    let certDigest;
    if (args['cert-sha256']) {
        if (!/^[0-9a-fA-F]{64}$/.test(args['cert-sha256'])) fail('--cert-sha256 يجب أن يكون 64 خانة hex.');
        certDigest = Buffer.from(args['cert-sha256'], 'hex');
    } else {
        if (!args.keystore || !args.alias) fail('مرّر --keystore و --alias (أو --cert-sha256).');
        certDigest = certDigestFromKeystore(args.keystore, args.alias);
    }

    const plain = fs.readFileSync(args.in);
    if (plain.length < 1000) fail('الملف المصدر صغير بشكل مريب (' + plain.length + ' bytes).');

    const key = deriveKey(readPepperFromJava(args.vault), certDigest);
    const blob = encryptBuffer(plain, key);

    // تحقق ذاتي: فكّ النتيجة وقارنها بالأصل.
    let back;
    try {
        back = decryptBuffer(blob, key);
    } catch (e) {
        fail('فشل التحقق الذاتي بعد التشفير: ' + e.message);
    }
    if (!back.equals(plain)) fail('عدم تطابق بعد فك التشفير — البناء موقوف.');

    fs.mkdirSync(path.dirname(args.out), { recursive: true });
    fs.writeFileSync(args.out, blob);
    console.log('✅ encrypt: ' + path.basename(args.in) + ' (' + plain.length + ' B) → ' +
        path.basename(args.out) + ' (' + blob.length + ' B)  cert-sha256=' + certDigest.toString('hex').slice(0, 12) + '…');
}

// --------------------------------------------------------------------------------------------

(async function main() {
    const [cmd, ...rest] = process.argv.slice(2);
    if (cmd === 'minify') return cmdMinify(rest[0]);
    if (cmd === 'encrypt') return cmdEncrypt(parseArgs(rest));
    fail('استخدام: protect-assets.js minify <index.html> | encrypt --in .. --out .. --vault .. (--keystore .. --alias ..)');
})().catch((e) => fail((e && e.stack) || String(e)));
