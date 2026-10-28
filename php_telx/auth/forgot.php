<?php
/** TelX — auth/forgot.php (POST {email}) — send reset code by email */
require __DIR__ . '/../config.php';
$b = body();
$email = strtolower(trim($b['email'] ?? ''));
if (!$email) jerr('ایمیل الزامی است');
$user = one("SELECT * FROM tx_users WHERE email = ?", [$email]);
if (!$user) jerr('کاربری با این ایمیل یافت نشد');

$code = gen_code();
q_exec("INSERT INTO tx_password_resets (id, user_id, code, expires_at) VALUES (?,?,?, DATE_ADD(NOW(), INTERVAL 5 MINUTE))",
    [cuid(), $user['id'], $code]);
send_mail($email, 'TelX — کد بازیابی رمز عبور',
    '<h2>کد بازیابی رمز عبور</h2><p style="font-size:28px;font-weight:bold;letter-spacing:6px">' . $code . '</p><p>این کد تا ۵ دقیقه اعتبار دارد. اگر شما درخواست نداده‌اید، این ایمیل را نادیده بگیرید.</p>');
$extra = TELX_DEBUG_CODES ? ['simulatedCode' => $code] : [];
jok(array_merge(['message' => 'کد بازیابی به ایمیل شما ارسال شد'], $extra));
