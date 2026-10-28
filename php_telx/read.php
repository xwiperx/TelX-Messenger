<?php
/** TelX — read.php (POST {chatId, lastMessageId?}) — mark chat read */
require __DIR__ . '/config.php';
require __DIR__ . '/lib_messages.php';
$me = require_auth();

$b = body();
$chatId = $b['chatId'] ?? '';
$member = is_member($chatId, $me['id']);
if (!$member) jerr('چت یافت نشد', 404);

$last = !empty($b['lastMessageId'])
    ? one("SELECT * FROM tx_messages WHERE id = ?", [$b['lastMessageId']])
    : one("SELECT * FROM tx_messages WHERE chat_id = ? AND is_deleted = 0 ORDER BY created_at DESC LIMIT 1", [$chatId]);
if (!$last) jok(['marked' => 0]);

$unread = all(
    "SELECT * FROM tx_messages WHERE chat_id = ? AND sender_id != ? AND is_deleted = 0 AND created_at <= ?",
    [$chatId, $me['id'], $last['created_at']]
);

$marked = 0;
$senders = [];
foreach ($unread as $m) {
    $exists = one("SELECT id FROM tx_message_reads WHERE message_id = ? AND user_id = ?", [$m['id'], $me['id']]);
    if (!$exists) {
        q_exec("INSERT INTO tx_message_reads (id, message_id, user_id) VALUES (?,?,?)", [cuid(), $m['id'], $me['id']]);
        $marked++;
        $senders[$m['sender_id']] = true;
    }
}
q_exec("UPDATE tx_chat_members SET unread_count = 0, last_read_message_id = ? WHERE id = ?", [$last['id'], $member['id']]);
jok(['marked' => $marked]);
