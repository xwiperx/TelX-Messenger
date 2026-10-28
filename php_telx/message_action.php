<?php
/**
 * TelX — message_action.php
 *  POST {messageId, action:'edit', text}
 *  POST {messageId, action:'react', emoji}
 *  POST {messageId, action:'pin', pinned}
 *  DELETE ?messageId=           → soft delete own message
 */
require __DIR__ . '/config.php';
require __DIR__ . '/lib_messages.php';
$me = require_auth();
$method = $_SERVER['REQUEST_METHOD'];
$b = $method === 'POST' ? body() : $_GET;
$messageId = $b['messageId'] ?? '';

$message = one("SELECT * FROM tx_messages WHERE id = ?", [$messageId]);
if (!$message || (int)$message['is_deleted'] === 1) jerr('پیام یافت نشد', 404);
if (!is_member($message['chat_id'], $me['id'])) jerr('دسترسی ندارید', 403);

if ($method === 'POST') {
    $action = $b['action'] ?? '';

    if ($action === 'edit') {
        if ($message['sender_id'] !== $me['id']) jerr('فقط پیام خودتان قابل ویرایش است');
        $text = trim($b['text'] ?? '');
        if ($text === '') jerr('متن خالی است');
        q_exec("UPDATE tx_messages SET text = ?, is_edited = 1, edited_at = NOW() WHERE id = ?", [$text, $messageId]);
        jok(['message' => serialize_message(one("SELECT * FROM tx_messages WHERE id = ?", [$messageId]), $me['id'])]);
    }

    if ($action === 'react') {
        $emoji = mb_substr($b['emoji'] ?? '', 0, 8);
        if (!$emoji) jerr('ایموجی نامعتبر');
        $existing = one("SELECT id FROM tx_reactions WHERE message_id = ? AND user_id = ? AND emoji = ?", [$messageId, $me['id'], $emoji]);
        if ($existing) q_exec("DELETE FROM tx_reactions WHERE id = ?", [$existing['id']]);
        else q_exec("INSERT INTO tx_reactions (id, message_id, user_id, emoji) VALUES (?,?,?,?)", [cuid(), $messageId, $me['id'], $emoji]);
        jok(['message' => serialize_message(one("SELECT * FROM tx_messages WHERE id = ?", [$messageId]), $me['id'])]);
    }

    if ($action === 'pin') {
        $pinned = !empty($b['pinned']);
        q_exec("UPDATE tx_messages SET pinned = 0 WHERE chat_id = ? AND pinned = 1", [$message['chat_id']]);
        if ($pinned) q_exec("UPDATE tx_messages SET pinned = 1 WHERE id = ?", [$messageId]);
        jok(['pinned' => $pinned]);
    }

    jerr('عملیات نامعتبر');
}

if ($method === 'DELETE') {
    if ($message['sender_id'] !== $me['id']) jerr('فقط پیام خودتان قابل حذف است');
    q_exec("UPDATE tx_messages SET is_deleted = 1, pinned = 0 WHERE id = ?", [$messageId]);
    jok();
}

jerr('متد پشتیبانی نمی‌شود', 405);
