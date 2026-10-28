<?php
/**
 * TelX — messages.php
 *  GET  ?chatId= [&before=ISO]      → history (50, newest-first paging)
 *  POST {chatId, type, text, mediaUrl?, fileName?, fileSize?, duration?, replyToId?}
 */
require __DIR__ . '/config.php';
require __DIR__ . '/lib_messages.php';
$me = require_auth();
$method = $_SERVER['REQUEST_METHOD'];

if ($method === 'GET') {
    $chatId = $_GET['chatId'] ?? '';
    if (!is_member($chatId, $me['id'])) jerr('چت یافت نشد', 404);
    $params = [$chatId];
    $where = 'chat_id = ? AND is_deleted = 0';
    if (!empty($_GET['before'])) { $where .= ' AND created_at < ?'; $params[] = $_GET['before']; }
    $rows = array_reverse(all("SELECT * FROM tx_messages WHERE $where ORDER BY created_at DESC LIMIT 50", $params));
    jok([
        'messages' => array_map(fn($m) => serialize_message($m, $me['id']), $rows),
        'hasMore' => count($rows) === 50,
    ]);
}

if ($method === 'POST') {
    $b = body();
    $chatId = $b['chatId'] ?? '';
    $member = is_member($chatId, $me['id']);
    if (!$member) jerr('چت یافت نشد', 404);

    $text = trim($b['text'] ?? '');
    $type = $b['type'] ?? 'text';
    $mediaUrl = $b['mediaUrl'] ?? null;
    if ($text === '' && !$mediaUrl) jerr('پیام خالی است');
    if ($type === 'text' && mb_strlen($text) > 4000) jerr('متن پیام خیلی طولانی است');

    // reply target must belong to the same chat
    $replyToId = $b['replyToId'] ?? null;
    if ($replyToId && !one("SELECT id FROM tx_messages WHERE id = ? AND chat_id = ?", [$replyToId, $chatId])) $replyToId = null;

    // link preview
    $pTitle = $pImage = $pUrl = $pDesc = null;
    $url = ($type === 'text') ? extract_url($text) : null;
    if ($url) {
        $p = fetch_preview($url);
        if ($p) {
            $pTitle = $p['title']; $pImage = $p['image']; $pUrl = $p['url']; $pDesc = $p['description'];
        }
    }

    $id = cuid();
    q_exec(
        "INSERT INTO tx_messages (id, chat_id, sender_id, text, type, media_url, file_name, file_size, duration, reply_to_id, preview_title, preview_image, preview_url, preview_desc)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        [$id, $chatId, $me['id'], $text, $type, $mediaUrl,
         $b['fileName'] ?? null, isset($b['fileSize']) ? (int)$b['fileSize'] : null,
         isset($b['duration']) ? (int)$b['duration'] : null,
         $replyToId, $pTitle, $pImage, $pUrl, $pDesc]
    );

    // unread counts + notifications
    foreach (all("SELECT * FROM tx_chat_members WHERE chat_id = ?", [$chatId]) as $m) {
        if ($m['user_id'] === $me['id']) {
            q_exec("UPDATE tx_chat_members SET unread_count = 0, last_read_message_id = ? WHERE id = ?", [$id, $m['id']]);
        } else {
            q_exec("UPDATE tx_chat_members SET unread_count = unread_count + 1 WHERE id = ?", [$m['id']]);
            if ((int)$m['muted'] === 0) {
                $kind = $type === 'image' ? '🖼 عکس' : ($type === 'voice' ? '🎤 پیام صوتی' : ($type === 'file' ? '📎 فایل' : mb_substr($text, 0, 120)));
                q_exec("INSERT INTO tx_notifications (id, user_id, title, body, chat_id) VALUES (?,?,?,?,?)",
                    [cuid(), $m['user_id'], $me['first_name'] . ' ' . $me['last_name'], $kind, $chatId]);
            }
        }
    }
    q_exec("INSERT IGNORE INTO tx_message_reads (id, message_id, user_id) VALUES (?,?,?)", [cuid(), $id, $me['id']]);

    $msg = one("SELECT * FROM tx_messages WHERE id = ?", [$id]);
    $isSaved = $member && one("SELECT type FROM tx_chats WHERE id = ?", [$chatId])['type'] === 'saved';
    jok(['message' => serialize_message($msg, $me['id']), 'read' => $isSaved ? true : null]);
}

jerr('متد پشتیبانی نمی‌شود', 405);
