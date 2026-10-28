<?php
/** TelX — forward.php (POST {messageIds:[], chatId}) — respects privacy lock */
require __DIR__ . '/config.php';
require __DIR__ . '/lib_messages.php';
$me = require_auth();

$b = body();
$ids = $b['messageIds'] ?? [];
$chatId = $b['chatId'] ?? '';
if (!is_array($ids) || count($ids) === 0 || !$chatId) jerr('پیام‌ها و چت مقصد الزامی است');
if (!is_member($chatId, $me['id'])) jerr('چت مقصد یافت نشد', 404);

// privacy lock of target chat members (except me)
foreach (all("SELECT u.* FROM tx_chat_members cm JOIN tx_users u ON u.id = cm.user_id WHERE cm.chat_id = ? AND cm.user_id != ?", [$chatId, $me['id']]) as $u) {
    if ((int)$u['privacy_lock'] === 1) jerr("قفل پیوی {$u['first_name']} فعال است؛ فوروارد مجاز نیست");
}

$count = 0;
foreach ($ids as $mid) {
    $src = one("SELECT * FROM tx_messages WHERE id = ? AND is_deleted = 0", [$mid]);
    if (!$src) continue;
    $srcSender = one("SELECT * FROM tx_users WHERE id = ?", [$src['sender_id']]);
    if ($srcSender && (int)$srcSender['privacy_lock'] === 1 && $src['chat_id'] !== $chatId)
        jerr("قفل پیوی {$srcSender['first_name']} فعال است؛ فوروارد این پیام مجاز نیست");

    q_exec(
        "INSERT INTO tx_messages (id, chat_id, sender_id, text, type, media_url, file_name, file_size, duration, forwarded_from, forwarded_by_name, preview_title, preview_image, preview_url, preview_desc)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        [cuid(), $chatId, $me['id'], $src['text'], $src['type'], $src['media_url'], $src['file_name'],
         $src['file_size'], $src['duration'], $src['chat_id'],
         $srcSender ? trim($srcSender['first_name'] . ' ' . $srcSender['last_name']) : 'ناشناس',
         $src['preview_title'], $src['preview_image'], $src['preview_url'], $src['preview_desc']]
    );
    $count++;
}

foreach (all("SELECT * FROM tx_chat_members WHERE chat_id = ?", [$chatId]) as $m) {
    if ($m['user_id'] === $me['id']) q_exec("UPDATE tx_chat_members SET unread_count = 0 WHERE id = ?", [$m['id']]);
    else q_exec("UPDATE tx_chat_members SET unread_count = unread_count + ? WHERE id = ?", [$count, $m['id']]);
}

jok(['count' => $count]);
