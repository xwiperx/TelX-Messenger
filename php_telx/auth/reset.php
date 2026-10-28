<?php
/** TelX — auth/reset.php (POST {email, code, password}) */
require __DIR__ . '/../config.php';
$b = body();
$email = strtolower(trim($b['email'] ?? ''));
$code = trim($b['code'] ?? '');
$password = $b['password'] ?? '';
if (!$email || !$code || !$password) jerr('همه فیلدها الزامی است');
if (mb_strlen($password) < 6) jerr('رمز عبور باید حداقل ۶ کاراکتر باشد');

$user = one("SELECT * FROM tx_users WHERE email = ?", [$email]);
if (!$user) jerr('کاربر یافت نشد');
$reset = one("SELECT * FROM tx_password_resets WHERE user_id = ? AND code = ? AND used = 0 AND expires_at > NOW() ORDER BY created_at DESC LIMIT 1",
    [$user['id'], $code]);
if (!$reset) jerr('کد نامعتبر یا منقضی شده است');

q_exec("UPDATE tx_users SET password_hash = ? WHERE id = ?", [hash_password($password), $user['id']]);
q_exec("UPDATE tx_password_resets SET used = 1 WHERE id = ?", [$reset['id']]);
jok(['message' => 'رمز عبور با موفقیت تغییر کرد']);
