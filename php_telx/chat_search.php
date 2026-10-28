<?php
/** TelX — chat_search.php (GET ?chatId=&q=) — search inside one chat */
require __DIR__ . '/config.php';
require __DIR__ . '/lib_messages.php';
$me = require_auth();
$chatId = $_GET['chatId'] ?? '';
$q = trim($_GET['q'] ?? '');
if (!is_member($chatId, $me['id'])) jerr('چت یافت نشد', 404);
if (!$q) jok(['messages' => []]);

$rows = array_reverse(all(
    "SELECT * FROM tx_messages WHERE chat_id = ? AND is_deleted = 0 AND text LIKE ? ORDER BY created_at DESC LIMIT 30",
    [$chatId, '%' . $q . '%']
));
jok(['messages' => array_map(fn($m) => serialize_message($m, $me['id']), $rows)]);
