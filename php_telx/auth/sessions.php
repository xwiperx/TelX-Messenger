<?php
/** TelX — auth/sessions.php (GET list | DELETE ?id=|all) */
require __DIR__ . '/../config.php';
$me = require_auth();
$method = $_SERVER['REQUEST_METHOD'];

if ($method === 'GET') {
    $rows = all("SELECT id, device, ip, current, created_at, last_active FROM tx_sessions WHERE user_id = ? ORDER BY last_active DESC", [$me['id']]);
    $rows = array_map(function ($r) {
        $r['current'] = (bool)$r['current'];
        return $r;
    }, $rows);
    jok(['sessions' => $rows]);
}

if ($method === 'DELETE') {
    $id = $_GET['id'] ?? '';
    if ($id === 'all') {
        q_exec("DELETE FROM tx_sessions WHERE user_id = ? AND `current` = 0", [$me['id']]);
        jok(['message' => 'همه دستگاه‌های دیگر خارج شدند']);
    }
    if (!$id) jerr('شناسه سشن لازم است');
    $s = one("SELECT * FROM tx_sessions WHERE id = ? AND user_id = ?", [$id, $me['id']]);
    if (!$s) jerr('سشن یافت نشد', 404);
    if ((int)$s['current'] === 1) jerr('نمی‌توانید سشن فعلی را حذف کنید. از خروج استفاده کنید');
    q_exec("DELETE FROM tx_sessions WHERE id = ?", [$id]);
    jok(['message' => 'دستگاه خارج شد']);
}

jerr('متد پشتیبانی نمی‌شود', 405);
