<?php
/** TelX — typing.php (POST {chatId}) */
require __DIR__ . '/config.php';
require __DIR__ . '/lib_messages.php';
$me = require_auth();
$b = body();
$chatId = $b['chatId'] ?? '';
if (!is_member($chatId, $me['id'])) jerr('چت یافت نشد', 404);

$existing = one("SELECT id FROM tx_typing_states WHERE chat_id = ? AND user_id = ?", [$chatId, $me['id']]);
if ($existing) {
    q_exec("UPDATE tx_typing_states SET updated_at = NOW() WHERE id = ?", [$existing['id']]);
} else {
    q_exec("INSERT INTO tx_typing_states (id, chat_id, user_id) VALUES (?,?,?)", [cuid(), $chatId, $me['id']]);
}
jok();
