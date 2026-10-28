# TelX Messenger 📱

پیام‌رسان کامل با طراحی کاملاً شبیه تلگرام — شامل **وب‌اپلیکیشن فول‌استک (Next.js)**، **بک‌اند PHP برای هاست Alwaysdata**، و **پروژه اندروید** با اعلان‌های بومی بدون Firebase.

<div dir="rtl">

## ✨ امکانات

| بخش | قابلیت‌ها |
|---|---|
| 👤 حساب کاربری | ثبت‌نام، ورود، سشن دائمی، بررسی لحظه‌ای یوزرنیم (سبز/قرمز)، بازیابی رمز با ایمیل، ورود دو مرحله‌ای (2FA)، مدیریت دستگاه‌ها، حذف دائمی اکانت، بیوگرافی |
| 💬 پیام‌رسانی | پیام متنی لحظه‌ای (Long Polling)، عکس و فایل، پیام صوتی، پیام‌های ذخیره شده، پیش‌نمایش لینک، تیک ✓/✓✓، نشانگر تایپ، وضعیت آنلاین و آخرین بازدید، شمارنده ناخوانده |
| ⚙️ مدیریت پیام | پاسخ (Reply)، فوروارد با قفل پیوی، ویرایش + برچسب ویرایش‌شده، حذف، واکنش‌ها (۱۲ ایموجی)، سنجاق پیام، انتخاب چندگانه |
| 🎨 ظاهر | پالت آبی تلگرام `#3390EC`، نویگیشن شناور پایین، حباب‌های تلگرامی، دارک مود کامل، ۷ پس‌زمینه چت، صفحه پروفایل |
| 📸 استوری | آپلود عکس/ویدیو، مدت ۱۵/۳۰/۴۵/۶۰ ثانیه، نوار پیشرفت متحرک، تپ چپ/راست، نگه‌داشتن = توقف، بازدیدکنندگان، حذف، حلقه رنگی |
| 📇 مخاطبین | لیست مخاطبین، افزودن/حذف، جستجوی کاربران، جستجوی سراسری پیام‌ها، جستجو در چت |
| 🔔 اعلان | اعلان بومی اندروید بدون Firebase (Foreground Service)، پاسخ سریع از اعلان، Badge آیکون، بی‌صدا کردن هر چت |
| 🖥 بک‌اند | PHP + MySQL برای Alwaysdata، ۱۳ جدول با پیشوند `tx_`، Long Polling واقعی (۲۵ ثانیه)، آپلود فایل |

## 🗂 ساختار مخزن

```
TelX/
├── src/                  ← وب‌اپلیکیشن Next.js 16 (فول‌استک، نسخه دمو کامل)
├── prisma/               ← اسکیمای دیتابیس SQLite (نسخه وب)
├── php_telx/             ← بک‌اند PHP مستقل برای Alwaysdata (نسخه production)
│   ├── config.php        ← تنظیمات دیتابیس (از config.sample.php کپی کنید)
│   ├── init_db.php       ← ساخت ۱۳ جدول tx_*
│   ├── auth/             ← register, login, verify_2fa, logout, me, ...
│   ├── chats.php, messages.php, longpoll.php, ...
│   └── upload.php, stories.php, contacts.php, search.php, ...
├── android_telx/         ← پروژه اندروید (WebView + سرویس اعلان بومی)
│   └── app/src/main/java/com/telx/messenger/
│       ├── MainActivity.java        ← WebView کامل + انتخاب فایل + دانلود
│       ├── NotificationService.java ← اعلان بدون Firebase + پاسخ سریع
│       ├── QuickReplyReceiver.java  ← ارسال پاسخ از داخل اعلان
│       └── BootReceiver.java        ← راه‌اندازی مجدد بعد از ریبوت
└── .github/workflows/    ← کامپایل خودکار APK روی GitHub Actions
```

## 🚀 راه‌اندازی بک‌اند PHP روی Alwaysdata

<div dir="rtl">

1. محتوای پوشه `php_telx/` را در `www/telx/v2/` آپلود کنید.
2. `config.sample.php` را به `config.php` کپی و مشخصات دیتابیس خود را وارد کنید.
3. یک‌بار در مرورگر باز کنید: `https://USER.alwaysdata.net/telx/v2/init_db.php` ← ۱۳ جدول ساخته می‌شود.
4. تمام! اندپوینت‌ها از `https://USER.alwaysdata.net/telx/v2/` در دسترس‌اند.

> **نکته امنیتی:** در فایل `config.php` مقدار `TELX_DEBUG_CODES` را در محیط production روی `false` بگذارید تا کدهای 2FA/بازیابی فقط با ایمیل ارسال شوند.

</div>

### لیست اندپوینت‌ها

مستندات کامل: [`docs/API_DOCS.md`](docs/API_DOCS.md)

## 📱 کامپایل APK اندروید

<div dir="rtl">

**روش ۱ — خودکار با GitHub Actions (پیشنهادی):**
بعد از push، به تب **Actions** مخزن بروید → ورک‌فلو *Build TelX APK* را اجرا کنید → فایل APK را از **Artifacts** دانلود کنید.

**روش ۲ — Android Studio (کامپیوتر):**
پوشه `android_telx/` را باز کنید → `Build > Build APK(s)`.

**روش ۳ — AndroidIDE (روی خود گوشی):**
پروژه Gradle را import کنید → build debug.

راهنمای کامل و تنظیم آدرس سرور: [`docs/ANDROID_BUILD.md`](docs/ANDROID_BUILD.md)

</div>

## 🌐 اجرای نسخه وب (Next.js)

```bash
npm install
npx prisma db push
npm run dev
```

## 🔒 امنیت

<div dir="rtl">

- رمزها با SHA-256 + salt هش می‌شوند.
- سشن‌ها با توکن ۶۴ کاراکتری تصادفی و قابلیت لغو از تنظیمات.
- قفل پیوی: جلوگیری از فوروارد/ذخیره محتوا توسط دیگران.
- آپلود فایل با محدودیت حجم و نام تصادفی.
- `config.php` را هرگز در گیت commit نکنید (در `.gitignore` است).

</div>

## 📄 لایسنس

استفاده شخصی/آموزشی.
