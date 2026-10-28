<?php
/** TelX — auth/twofactor.php (POST {enabled:bool}) — enable/disable 2FA */
require __DIR__ . '/../config.php';
$me = require_auth();
$b = body();
$enabled = !empty($b['enabled']);

if (!$enabled) {
    q_exec("UPDATE tx_users SET two_factor_enabled = 0, two_factor_secret = NULL WHERE id = ?", [$me['id']]);
    jok(['twoFactorEnabled' => false, 'message' => 'ورود دو مرحله‌ای غیرفعال شد']);
}

$code = gen_code();
q_exec("UPDATE tx_users SET two_factor_enabled = 1, two_factor_secret = ? WHERE id = ?", [$code, $me['id']]);
send_mail($me['email'], 'TelX — ورود دو مرحله‌ای فعال شد',
    '<h2>ورود دو مرحله‌ای فعال شد</h2><p>کد فعلی برای تست: <b>' . $code . '</b></p><p>در هر ورود، کد جدید به این ایمیل ارسال می‌شود.</p>');
$extra = TELX_DEBUG_CODES ? ['simulatedCode' => $code] : [];
jok(array_merge(['twoFactorEnabled' => true, 'message' => 'ورود دو مرحله‌ای فعال شد. کد به ایمیل شما ارسال شد', 'emailHint' => $me['email']], $extra));
