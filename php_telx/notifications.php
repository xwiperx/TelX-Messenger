<?php
/** TelX — notifications.php
 *  GET  → unread notifications
 *  POST {id?} → mark one/all read
 */
require __DIR__ . '/config.php';
$me = require_auth();
$method = $_SERVER['REQUEST_METHOD'];

if ($method === 'GET') {
    $rows = all("SELECT * FROM tx_notifications WHERE user_id = ? AND `read` = 0 ORDER BY created_at DESC LIMIT 30", [$me['id']]);
    jok([
        'count' => count($rows),
        'items' => array_map(fn($n) => [
            'id' => $n['id'], 'title' => $n['title'], 'body' => $n['body'],
            'chatId' => $n['chat_id'], 'createdAt' => $n['created_at'],
        ], $rows),
    ]);
}

if ($method === 'POST') {
    $b = body();
    if (!empty($b['id'])) q_exec("UPDATE tx_notifications SET `read` = 1 WHERE id = ? AND user_id = ?", [$b['id'], $me['id']]);
    else q_exec("UPDATE tx_notifications SET `read` = 1 WHERE user_id = ?", [$me['id']]);
    jok();
}

jerr('متد پشتیبانی نمی‌شود', 405);
