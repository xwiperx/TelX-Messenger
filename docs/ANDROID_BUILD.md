# راهنمای ساخت (کامپایل) APK اندروید TelX

<div dir="rtl">

پروژه اندروید TelX یک اپ‌شل کامل است که شامل:
- **WebView تمام‌صفحه** با جاوااسکریپت، انتخاب فایل، دانلود، Swipe-to-Refresh
- **سرویس اعلان بومی بدون Firebase** (Foreground Service با Long Polling هر ۱۵ ثانیه)
- **پاسخ سریع از داخل اعلان** (Quick Reply با RemoteInput)
- **راه‌اندازی مجدد بعد از ریبوت** (BootReceiver)
- ضبط صدا، دوربین و مدیا برای پیام صوتی/عکس داخل وب‌اپ

---

## ⚙️ تنظیم آدرس سرور

فایل `app/src/main/java/com/telx/messenger/MainActivity.java`:

```java
public static final String BASE_URL = "https://mybackup-test.alwaysdata.net/telx/";
```

فایل `NotificationService.java` به‌صورت خودکار از `BASE_URL + "v2/"` برای اعلان‌ها استفاده می‌کند.

## روش ۱ — GitHub Actions (ساده‌ترین، بدون نصب هیچ‌چیز)

1. سورس را push کنید (پوشه `.github/workflows/android.yml` موجود است).
2. در گیت‌هاب به تب **Actions** بروید.
3. ورک‌فلو **Build TelX APK** را انتخاب و **Run workflow** بزنید.
4. بعد از اتمام (~۳ دقیقه) از بخش **Artifacts** فایل `TelX-debug-apk` یا `TelX-release-apk` را دانلود کنید.
5. فایل zip را باز کنید و `app-debug.apk` را روی گوشی نصب کنید.

> APK نسخه debug نیاز به امضا ندارد و مستقیم نصب می‌شود.

## روش ۲ — Android Studio (روی کامپیوتر)

1. Android Studio را باز کنید → `Open` → پوشه `android_telx` را انتخاب کنید.
2. صبر کنید تا Gradle sync تمام شود.
3. منوی `Build > Build Bundle(s)/APK(s) > Build APK(s)`.
4. خروجی: `app/build/outputs/apk/debug/app-debug.apk`

## روش ۳ — AndroidIDE (روی خود گوشی اندروید)

1. AndroidIDE را نصب کنید (androidide.com).
2. پروژه را در مسیر دلخواه کپی کنید.
3. در AndroidIDE پروژه Gradle را باز کنید و build بزنید.
4. خروجی در `app/build/outputs/apk/` قرار می‌گیرد.

## روش ۴ — خط فرمان (لینوکس/ترموکس)

```bash
cd android_telx
gradle assembleDebug        # یا ./gradlew اگر wrapper دارید
# خروجی: app/build/outputs/apk/debug/app-debug.apk
```

---

## چطور توکن اعلان‌ها کار می‌کند؟

وقتی کاربر داخل WebView لاگین می‌کند، MainActivity به‌صورت خودکار پاسخ API را رصد می‌کند (hook روی `window.fetch`) و توکن سشن را از پاسخ JSON استخراج و در `SharedPreferences` ذخیره می‌کند. سپس `NotificationService` با همان توکن هر ۱۵ ثانیه `notifications.php` را چک می‌کند و برای هر پیام جدید یک اعلان بومی با دکمه «پاسخ» نمایش می‌دهد.

## نکات مهم

- **مجوزها:** اعلان (Android 13+)، ضبط صدا و دوربین در اولین اجرا درخواست می‌شوند.
- **Optimize باتری:** برای اعلان پایدار، اپ را از بهینه‌سازی باتری مستثنا کنید (Settings > Battery > Unrestricted).
- **امضای Release:** برای انتشار عمومی، یک keystone بسازید و `signingConfigs` را به `app/build.gradle` اضافه کنید. APK دیباگ برای استفاده شخصی کافی است.

</div>
