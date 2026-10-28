<?php
/**
 * TelX — longpoll.php
 *  GET ?chatId=&changed=0        → holds request up to LONGPOLL_SECONDS,
 *                                  returns {changed:true} as soon as anything in this chat changes
 *  POST {chatId, afterId?}       → fetch new messages + current typing users
 */
require __DIR__ . '/config.php';
require __DIR__ . '/lib_messages.php';
$me = require_auth();
$method = $_SERVER['REQUEST_METHOD'];

if ($method === 'GET') {
    $chatId = $_GET['chatId'] ?? '';
    if (!is_member($chatId, $me['id'])) jerr('چت یافت نشد', 404);
    @set_time_limit(LONGPOLL_SECONDS + 10);
    ignore_user_abort(false);

    $start = time();
    while ((time() - $start) < LONGPOLL_SECONDS) {
        $row = one(
            "SELECT
               (SELECT COUNT(*) FROM tx_messages WHERE chat_id = ? AND is_deleted = 0) AS msg_count,
               (SELECT COUNT(*) FROM tx_message_reads mr JOIN tx_messages m ON m.id = mr.message_id
                  WHERE m.chat_id = ? AND mr.user_id != ?) AS read_count,
               (SELECT COUNT(*) FROM tx_reactions r JOIN tx_messages m ON m.id = r.message_id
                  WHERE m.chat_id = ?) AS reaction_count,
               (SELECT COUNT(*) FROM tx_typing_states t WHERE t.chat_id = ? AND t.user_id != ? AND t.updated_at > DATE_SUB(NOW(), INTERVAL 4 SECOND)) AS typing_count",
            [$chatId, $chatId, $me['id'], $chatId, $chatId, $me['id']]
        );
        $sig = (int)$row['msg_count'] . ':' . (int)$row['read_count'] . ':' . (int)$row['reaction_count'] . ':' . (int)$row['typing_count'];
        if (($_GET['changed'] ?? '0') !== $sig) {
            jok(['changed' => true, 'signature' => $sig]);
        }
        usleep(600000); // 0.6s — light on the Alwaysdata shared MySQL
        if (connection_aborted()) exit;
    }
    jok(['timeout' => true, 'signature' => $sig ?? '']);
}

if ($method === 'POST') {
    $b = body();
    $chatId = $b['chatId'] ?? '';
    if (!is_member($chatId, $me['id'])) jerr('چت یافت نشد', 404);

    $after = null;
    if (!empty($b['afterId'])) $after = one("SELECT created_at FROM tx_messages WHERE id = ?", [$b['afterId']]);

    $params = [$chatId];
    $where = 'chat_id = ? AND is_deleted = 0';
    if ($after) { $where .= ' AND created_at > ?'; $params[] = $after['created_at']; }
    $rows = all("SELECT * FROM tx_messages WHERE $where ORDER BY created_at ASC LIMIT 100", $params);

    $typing = array_map(
        fn($t) => $t['first_name'],
        all("SELECT u.first_name FROM tx_typing_states t JOIN tx_users u ON u.id = t.user_id
             WHERE t.chat_id = ? AND t.user_id != ? AND t.updated_at > DATE_SUB(NOW(), INTERVAL 4 SECOND)",
            [$chatId, $me['id']])
    );

    jok([
        'messages' => array_map(fn($m) => serialize_message($m, $me['id']), $rows),
        'typing' => $typing,
    ]);
}

jerr('متد پشتیبانی نمی‌شود', 405);
