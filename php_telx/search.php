<?php
/** TelX — search.php (GET ?q=) — global search: users + my messages */
require __DIR__ . '/config.php';
$me = require_auth();
$q = trim($_GET['q'] ?? '');
if (!$q) jok(['users' => [], 'messages' => []]);

$users = all(
    "SELECT * FROM tx_users WHERE id != ? AND (username LIKE ? OR first_name LIKE ? OR last_name LIKE ?) LIMIT 10",
    [$me['id'], '%' . strtolower($q) . '%', '%' . $q . '%', '%' . $q . '%']
);

$myChats = array_map(fn($r) => $r['chat_id'], all("SELECT chat_id FROM tx_chat_members WHERE user_id = ?", [$me['id']]));
$messages = [];
if ($myChats) {
    $in = implode(',', array_fill(0, count($myChats), '?'));
    $params = array_merge($myChats, ['%' . $q . '%']);
    $rows = all(
        "SELECT m.*, c.type AS chat_type FROM tx_messages m JOIN tx_chats c ON c.id = m.chat_id
         WHERE m.chat_id IN ($in) AND m.is_deleted = 0 AND m.text LIKE ? ORDER BY m.created_at DESC LIMIT 20",
        $params
    );
    foreach ($rows as $m) {
        $other = one(
            "SELECT u.* FROM tx_chat_members cm JOIN tx_users u ON u.id = cm.user_id WHERE cm.chat_id = ? AND cm.user_id != ? LIMIT 1",
            [$m['chat_id'], $me['id']]
        );
        $messages[] = [
            'id' => $m['id'],
            'chatId' => $m['chat_id'],
            'chatName' => $m['chat_type'] === 'saved' ? 'پیام‌های ذخیره شده' : ($other ? trim($other['first_name'] . ' ' . $other['last_name']) : 'چت'),
            'text' => (string)$m['text'],
            'createdAt' => $m['created_at'],
            'mine' => $m['sender_id'] === $me['id'],
        ];
    }
}

jok([
    'users' => array_map(fn($u) => public_user($u, $me['id']), $users),
    'messages' => $messages,
]);
