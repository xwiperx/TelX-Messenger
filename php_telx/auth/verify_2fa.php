<?php
/** TelX — auth/verify_2fa.php  (POST {userId, code}) */
require __DIR__ . '/../config.php';

$b = body();
$userId = $b['userId'] ?? '';
$code = trim($b['code'] ?? '');
if (!$userId || !$code) jerr('کد الزامی است');

$user = one("SELECT * FROM tx_users WHERE id = ?", [$userId]);
if (!$user || !(int)$user['two_factor_enabled'] || !$user['two_factor_secret']) jerr('درخواست نامعتبر', 400);
if ($user['two_factor_secret'] !== $code) jerr('کد وارد شده اشتباه است');

q_exec("UPDATE tx_users SET two_factor_secret = NULL WHERE id = ?", [$userId]);

$token = gen_token();
$device = device_name($_SERVER['HTTP_USER_AGENT'] ?? '');
q_exec("INSERT INTO tx_sessions (id, token, user_id, device, ip, current) VALUES (?,?,?,?,?,1)",
    [cuid(), $token, $userId, $device, $_SERVER['REMOTE_ADDR'] ?? '']);
q_exec("UPDATE tx_users SET is_online = 1, last_seen = NOW() WHERE id = ?", [$userId]);

jok(['me' => me_user($user), 'token' => $token]);
