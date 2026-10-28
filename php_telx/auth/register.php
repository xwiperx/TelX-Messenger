<?php
/** TelX — auth/register.php  (POST {firstName,lastName,username,email,password}) */
require __DIR__ . '/../config.php';

$b = body();
$firstName = trim($b['firstName'] ?? '');
$lastName  = trim($b['lastName'] ?? '');
$username  = strtolower(trim($b['username'] ?? ''));
$email     = strtolower(trim($b['email'] ?? ''));
$password  = $b['password'] ?? '';

if (!$firstName || !$username || !$email || !$password) jerr('همه فیلدها الزامی است');
if (!preg_match('/^[a-zA-Z][a-zA-Z0-9_]{3,24}$/', $username)) jerr('نام کاربری باید ۴ تا ۲۵ کاراکتر انگلیسی باشد و با حرف شروع شود');
if (!filter_var($email, FILTER_VALIDATE_EMAIL)) jerr('ایمیل معتبر نیست');
if (mb_strlen($password) < 6) jerr('رمز عبور باید حداقل ۶ کاراکتر باشد');
if (one("SELECT id FROM tx_users WHERE username = ?", [$username])) jerr('این نام کاربری قبلاً گرفته شده است');
if (one("SELECT id FROM tx_users WHERE email = ?", [$email])) jerr('این ایمیل قبلاً ثبت شده است');

$id = cuid();
q_exec("INSERT INTO tx_users (id, first_name, last_name, username, email, password_hash) VALUES (?,?,?,?,?,?)",
    [$id, $firstName, $lastName, $username, $email, hash_password($password)]);

// Saved Messages chat
$chatId = cuid();
q_exec("INSERT INTO tx_chats (id, type) VALUES (?, 'saved')", [$chatId]);
q_exec("INSERT INTO tx_chat_members (id, chat_id, user_id) VALUES (?,?,?)", [cuid(), $chatId, $id]);

$token = gen_token();
$device = device_name($_SERVER['HTTP_USER_AGENT'] ?? '');
q_exec("INSERT INTO tx_sessions (id, token, user_id, device, ip, current) VALUES (?,?,?,?,?,1)",
    [cuid(), $token, $id, $device, $_SERVER['REMOTE_ADDR'] ?? '']);

$user = one("SELECT * FROM tx_users WHERE id = ?", [$id]);
jok(['me' => me_user($user), 'token' => $token]);
