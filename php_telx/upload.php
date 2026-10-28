<?php
/** TelX — upload.php (POST multipart file) — saves to uploads/, returns URL */
require __DIR__ . '/config.php';
$me = require_auth();

if (empty($_FILES['file'])) jerr('فایلی ارسال نشد');
$f = $_FILES['file'];
if ($f['error'] !== UPLOAD_ERR_OK) jerr('خطا در دریافت فایل (کد ' . $f['error'] . ')');
if ($f['size'] > MAX_UPLOAD_BYTES) jerr('حجم فایل باید کمتر از ۲۵ مگابایت باشد');

ensure_upload_dir();
$ext = strtolower(pathinfo($f['name'], PATHINFO_EXTENSION));
$ext = preg_replace('/[^a-z0-9]/', '', substr($ext, 0, 8));
$name = bin2hex(random_bytes(16)) . ($ext ? '.' . $ext : '');

$target = UPLOAD_DIR . '/' . $name;
if (!move_uploaded_file($f['tmp_name'], $target)) {
    // fallback for CLI/tests
    if (!@rename($f['tmp_name'], $target) && !@copy($f['tmp_name'], $target)) jerr('ذخیره فایل ناموفق بود', 500);
}
@chmod($target, 0664);

jok([
    'url'  => UPLOAD_URL_BASE . '/' . $name,
    'name' => $f['name'],
    'size' => $f['size'],
    'mime' => $f['type'],
]);
