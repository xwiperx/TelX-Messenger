<?php
/**
 * TelX — chats.php
 *  GET  → list my chats (with last message, unread, online)
 *  POST {userId?} → open (or reuse) private chat; empty body → Saved Messages
 */
require __DIR__ . '/config.php';
require __DIR__ . '/lib_messages.php';
$me = require_auth();
$method = $_SERVER['REQUEST_METHOD'];

if ($method === 'GET') {
    $memberships = all("SELECT cm.*, c.type AS chat_type, c.created_at AS chat_created
                        FROM tx_chat_members cm JOIN tx_chats c ON c.id = cm.chat_id
                        WHERE cm.user_id = ?", [$me['id']]);
    $chats = [];
    foreach ($memberships as $m) {
        $others = all("SELECT u.* FROM tx_chat_members cm JOIN tx_users u ON u.id = cm.user_id
                       WHERE cm.chat_id = ? AND cm.user_id != ?", [$m['chat_id'], $me['id']]);
        $other = ($m['chat_type'] === 'saved') ? null : ($others[0] ?? null);
        $last = one("SELECT * FROM tx_messages WHERE chat_id = ? AND is_deleted = 0 ORDER BY created_at DESC LIMIT 1",
            [$m['chat_id']]);
        $chats[] = [
            'id' => $m['chat_id'],
            'type' => $m['chat_type'],
            'unreadCount' => (int)$m['unread_count'],
            'muted' => (bool)$m['muted'],
            'updatedAt' => $last ? $last['created_at'] : $m['chat_created'],
            'other' => $other ? public_user($other, $me['id']) : null,
            'lastMessage' => $last ? [
                'id' => $last['id'],
                'text' => (string)$last['text'],
                'type' => $last['type'],
                'createdAt' => $last['created_at'],
                'mine' => $last['sender_id'] === $me['id'],
                'senderName' => $last['sender_id'] === $me['id'] ? 'شما' : (public_user(one("SELECT * FROM tx_users WHERE id=?", [$last['sender_id']])))['firstName'],
            ] : null,
        ];
    }
    usort($chats, fn($a, $b) => strcmp($b['updatedAt'], $a['updatedAt']));
    jok(['chats' => $chats]);
}

if ($method === 'POST') {
    $b = body();
    $targetId = $b['userId'] ?? $me['id'];
    $target = one("SELECT * FROM tx_users WHERE id = ?", [$targetId]);
    if (!$target) jerr('کاربر یافت نشد', 404);
    $type = ($targetId === $me['id']) ? 'saved' : 'private';

    // reuse existing chat
    foreach (all("SELECT chat_id FROM tx_chat_members WHERE user_id = ?", [$me['id']]) as $mine) {
        foreach (all("SELECT chat_id FROM tx_chat_members WHERE user_id = ?", [$targetId]) as $theirs) {
            if ($mine['chat_id'] === $theirs['chat_id']) {
                $c = one("SELECT type FROM tx_chats WHERE id = ?", [$mine['chat_id']]);
                if ($c && $c['type'] === $type) jok(['chatId' => $mine['chat_id']]);
            }
        }
    }

    $chatId = cuid();
    q_exec("INSERT INTO tx_chats (id, type) VALUES (?,?)", [$chatId, $type]);
    q_exec("INSERT INTO tx_chat_members (id, chat_id, user_id) VALUES (?,?,?)", [cuid(), $chatId, $me['id']]);
    if ($targetId !== $me['id'])
        q_exec("INSERT INTO tx_chat_members (id, chat_id, user_id) VALUES (?,?,?)", [cuid(), $chatId, $targetId]);
    jok(['chatId' => $chatId]);
}

jerr('متد پشتیبانی نمی‌شود', 405);
