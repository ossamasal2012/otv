# 📱 OTV Live TV - دليل البناء والنشر

## هيكل الملفات المطلوب

```
مشروعك/
├── .github/
│   └── workflows/
│       └── main.yml          ← ملف GitHub Actions
├── dist/
│   └── index.html            ← ملف HTML الخاص بك
├── android/                  ← يُنشأ تلقائياً بواسطة Capacitor
├── capacitor.config.json
├── package.json
└── index.html                ← الملف الأصلي
```

---

## 🔑 إنشاء Keystore (التوقيع الرقمي)

نفّذ هذا الأمر مرة واحدة فقط على جهازك:

```bash
keytool -genkeypair \
  -v \
  -keystore otv-keystore.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias otv-key \
  -storepass YOUR_STORE_PASSWORD \
  -keypass YOUR_KEY_PASSWORD \
  -dname "CN=OTV App, OU=Dev, O=OTV, L=Baghdad, S=Baghdad, C=IQ"
```

ثم حوّله إلى Base64:
```bash
base64 -i otv-keystore.jks -o keystore_base64.txt
```

---

## 🔐 إضافة Secrets في GitHub

اذهب إلى: **Settings → Secrets and variables → Actions → New repository secret**

| اسم السر           | القيمة                              |
|--------------------|--------------------------------------|
| `KEYSTORE_BASE64`  | محتوى ملف `keystore_base64.txt`     |
| `KEY_ALIAS`        | `otv-key`                           |
| `KEYSTORE_PASSWORD`| كلمة مرور الـ Keystore              |
| `KEY_PASSWORD`     | كلمة مرور المفتاح                   |

---

## 🖼️ تغيير أيقونة التطبيق

في ملف `main.yml`، ابحث عن هذا السطر وضع رابط أيقونتك:

```yaml
ICON_URL="https://رابط-الأيقونة-هنا.png"
```

**متطلبات الأيقونة:**
- صيغة PNG
- حجم لا يقل عن **1024×1024** بكسل
- خلفية غير شفافة للحصول على أفضل نتيجة

---

## 🚀 خطوات الإعداد الكاملة

```bash
# 1. تثبيت الحزم
npm install

# 2. إضافة منصة Android
npx cap add android

# 3. بناء ملفات الويب
npm run build

# 4. المزامنة مع Android
npx cap sync android
```

---

## ✅ ملاحظات مهمة

- احفظ ملف `otv-keystore.jks` في مكان آمن، ستحتاجه لكل تحديثات مستقبلية
- لا ترفع الـ Keystore على GitHub أبداً
- تأكد أن `android/` مضاف إلى `.gitignore` أو رفعه بالكامل حسب حاجتك
