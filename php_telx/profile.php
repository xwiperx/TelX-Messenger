<?php
/** TelX — profile.php (POST action=profile|wallpaper|privacy|darkmode) */
require __DIR__ . '/config.php';
$me = require_auth();
$b = body();
$action = $b['action'] ?? '';

if ($action === 'profile') {
    $sets = []; $params = [];
    if (!empty($b['firstName'])) { $sets[] = 'first_name = ?'; $params[] = trim($b['firstName']); }
    if (isset($b['lastName']))   { $sets[] = 'last_name = ?';  $params[] = trim($b['lastName']); }
    if (isset($b['bio']))        { $sets[] = 'bio = ?';        $params[] = mb_substr(trim($b['bio']), 0, 200); }
    if (isset($b['avatarUrl']))  { $sets[] = 'avatar_url = ?'; $params[] = $b['avatarUrl']; }
    if (isset($b['username']) && strtolower(trim($b['username'])) !== $me['username']) {
        $username = strtolower(trim($b['username']));
        if (!preg_match('/^[a-zA-Z][a-zA-Z0-9_]{3,24}$/', $username)) jerr('نام کاربری باید ۴ تا ۲۵ کاراکتر انگلیسی باشد و با حرف شروع شود');
        if (one("SELECT id FROM tx_users WHERE username = ? AND id != ?", [$username, $me['id']])) jerr('این نام کاربری قبلاً گرفته شده است');
        $sets[] = 'username = ?'; $params[] = $username;
    }
    if ($sets) {
        $params[] = $me['id'];
        q_exec("UPDATE tx_users SET " . implode(', ', $sets) . " WHERE id = ?", $params);
    }
    $u = one("SELECT * FROM tx_users WHERE id = ?", [$me['id']]);
    jok(['me' => me_user($u), 'message' => 'پروفایل به‌روزرسانی شد']);
}

if ($action === 'wallpaper') {
    q_exec("UPDATE tx_users SET wallpaper = ? WHERE id = ?", [preg_replace('/[^a-z0-9_]/i', '', $b['wallpaper'] ?? 'pattern1'), $me['id']]);
    jok(['me' => me_user(one("SELECT * FROM tx_users WHERE id = ?", [$me['id']]))]);
}

if ($action === 'privacy') {
    q_exec("UPDATE tx_users SET privacy_lock = ? WHERE id = ?", [!empty($b['privacyLock']) ? 1 : 0, $me['id']]);
    $u = one("SELECT * FROM tx_users WHERE id = ?", [$me['id']]);
    jok(['me' => me_user($u), 'message' => !empty($b['privacyLock']) ? 'قفل پیوی فعال شد' : 'قفل پیوی غیرفعال شد']);
}

if ($action === 'darkmode') {
    q_exec("UPDATE tx_users SET dark_mode = ? WHERE id = ?", [!empty($b['darkMode']) ? 1 : 0, $me['id']]);
    jok(['me' => me_user(one("SELECT * FROM tx_users WHERE id = ?", [$me['id']]))]);
}

jerr('عملیات نامعتبر');
