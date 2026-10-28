<?php
/** TelX — auth/login.php  (POST {login,password}) — supports 2FA */
require __DIR__ . '/../config.php';

$b = body();
$login = strtolower(trim($b['login'] ?? ''));
$password = $b['password'] ?? '';
if (!$login || !$password) jerr('نام کاربری و رمز عبور الزامی است');

$user = one("SELECT * FROM tx_users WHERE username = ? OR email = ?", [$login, $login]);
if (!$user || $user['password_hash'] !== hash_password($password)) jerr('نام کاربری یا رمز عبور اشتباه است');

if ((int)$user['two_factor_enabled'] === 1) {
    $code = gen_code();
    q_exec("UPDATE tx_users SET two_factor_secret = ? WHERE id = ?", [$code, $user['id']]);
    send_mail($user['email'], 'TelX — کد ورود دو مرحله‌ای',
        '<h2>کد ورود شما</h2><p style="font-size:28px;font-weight:bold;letter-spacing:6px">' . $code . '</p><p>این کد تا ۵ دقیقه اعتبار دارد.</p>');
    $extra = TELX_DEBUG_CODES ? ['simulatedCode' => $code] : [];
    jok(array_merge(['need2fa' => true, 'userId' => $user['id'], 'emailHint' => $user['email']], $extra));
}

$token = gen_token();
$device = device_name($_SERVER['HTTP_USER_AGENT'] ?? '');
q_exec("INSERT INTO tx_sessions (id, token, user_id, device, ip, current) VALUES (?,?,?,?,?,1)",
    [cuid(), $token, $user['id'], $device, $_SERVER['REMOTE_ADDR'] ?? '']);
q_exec("UPDATE tx_users SET is_online = 1, last_seen = NOW() WHERE id = ?", [$user['id']]);

jok(['me' => me_user($user), 'token' => $token]);
