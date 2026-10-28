<?php
/* TelX API — messages.php : list/send/edit/delete/forward/pin/read/typing/react/search */
define('TELX_API', 1);
require __DIR__ . '/../config.php';

$action = p('action') ?? '';

switch ($action) {

case 'list': {
    $me = me();
    $cid = pint('chat_id');
    require_member($cid);
    flush_due();
    $beforeId = pint('before_id');
    $limit = max(1, min(60, pint('limit', 40)));
    $sql = 'SELECT * FROM messages WHERE chat_id = ? AND deleted = 0';
    $args = [$cid];
    if ($beforeId > 0) { $sql .= ' AND id < ?'; $args[] = $beforeId; }
    $sql .= ' ORDER BY id DESC LIMIT ' . $limit;
    $st = db()->prepare($sql); $st->execute($args);
    $msgs = array_reverse(array_map(fn($m) => msg_public($m, (int)$me['uid']), $st->fetchAll()));
    // mark delivered up to newest
    $newest = count($msgs) ? end($msgs)['id'] : 0;
    $mine = my_membership($cid, (int)$me['uid']);
    if ($newest > (int)$mine['last_delivered_message_id']) {
        db()->prepare('UPDATE chat_members SET last_delivered_message_id = ? WHERE chat_id = ? AND user_id = ?')
            ->execute([$newest, $cid, $me['uid']]);
        $senders = [];
        foreach ($msgs as $msg2)
            if ($msg2['sender_id'] && (int)$msg2['sender_id'] !== (int)$me['uid'])
                $senders[(int)$msg2['sender_id']] = true;
        if ($senders) {
            $otherIds = chat_member_ids($cid, (int)$me['uid']);
            $recipients = array_values(array_unique(array_merge($otherIds, array_map(fn($x) => (int)$x, array_keys($senders)))));
            emit($recipients, 'message_delivered', ['chat_id' => $cid, 'last_delivered_id' => $newest], $cid);
        }
    }
    ok(['messages' => $msgs, 'chat_id' => $cid]);
}

case 'send': {
    $me = me();
    $cid = pint('chat_id');
    $membership = require_member($cid);
    $chat = chat_of($cid);
    if (!$chat) fail('not_found', 'Chat not found');
    // channels are broadcast-only: admins post, subscribers read
    if ($chat['type'] === 'channel' && (string)$membership['role'] !== 'admin')
        fail('forbidden', 'Only channel admins can post here');
    $type = p('type') ?? 'text';
    if (!in_array($type, ['text','photo','file','voice','video','sticker','gif'], true)) fail('invalid', 'Bad message type');
    $text = (string)(p('text') ?? '');
    $media = p('media_rel');
    $thumb = p('thumb_rel');
    $enc = pint('encrypted') === 1 && (int)$chat['secret'] === 1;
    if ($type === 'text' && $text === '' && !$enc) fail('invalid', 'Empty message');
    if (in_array($type, ['photo','file','voice','video','gif'], true) && !$media) fail('invalid', 'media required');
    if ($type === 'sticker' && !(p('sticker'))) fail('invalid', 'sticker id required');
    if ($chat['type'] === 'private') {
        $peer = peer_of_private($cid, (int)$me['uid']);
        if ($peer && is_blocked((int)$peer['uid'], (int)$me['uid'])) fail('blocked', 'You cannot message this user');
    }
    $replyTo = pint('reply_to');
    if ($replyTo) {
        $rm = message_by_id($replyTo);
        if (!$rm || (int)$rm['chat_id'] !== $cid) fail('invalid', 'Bad reply target');
    }
    $scheduledTs = pint('scheduled_ts');
    $isScheduled = $scheduledTs > time() + 30;
    $linkJson = null;
    if ($type === 'text' && !$enc) {
        $prev = fetch_link_preview($text);
        if ($prev) $linkJson = json_encode($prev, JSON_UNESCAPED_UNICODE);
    }
    $autoDeleteAt = null;
    if (!$isScheduled && (int)$chat['auto_delete_secs'] > 0)
        $autoDeleteAt = sqlts(time() + (int)$chat['auto_delete_secs']);
    $senderName = trim($me['first_name'] . ' ' . $me['last_name']);
    $schedSql = $isScheduled ? sqlts($scheduledTs) : null;
    db()->prepare("INSERT INTO messages (chat_id, sender_id, sender_name, type, text, media, thumb,
                    media_name, media_size, media_duration, sticker, link_json, reply_to, encrypted,
                    scheduled_at, auto_delete_at, created_at)
                    VALUES (:chat_id, :sender_id, :sender_name, :type, :text, :media, :thumb,
                            :media_name, :media_size, :media_duration, :sticker, :link_json, :reply_to,
                            :encrypted, :scheduled_at, :auto_delete_at, UTC_TIMESTAMP())")
        ->execute(['chat_id' => $cid, 'sender_id' => $me['uid'], 'sender_name' => $senderName, 'type' => $type,
                   'text' => $enc ? $text : ($text !== '' ? $text : null),
                   'media' => $media, 'thumb' => $thumb,
                   'media_name' => (string)(p('media_name') ?? ''), 'media_size' => pint('media_size'),
                   'media_duration' => pint('media_duration'), 'sticker' => p('sticker'), 'link_json' => $linkJson,
                   'reply_to' => $replyTo ?: null,
                   'encrypted' => $enc ? 1 : 0, 'scheduled_at' => $schedSql, 'auto_delete_at' => $autoDeleteAt]);
    $mid = (int)db()->lastInsertId();
    $m = message_by_id($mid);
    if (!$isScheduled) {
        db()->prepare('UPDATE chats SET last_message_id = ?, last_message_at = UTC_TIMESTAMP() WHERE id = ? AND (last_message_id IS NULL OR last_message_id < ?)')
            ->execute([$mid, $cid, $mid]);
        $others = chat_member_ids($cid, (int)$me['uid']);
        $up = db()->prepare('UPDATE chat_members SET unread_count = unread_count + 1 WHERE chat_id = ? AND user_id = ?');
        foreach ($others as $uid2) $up->execute([$cid, $uid2]);
        foreach ($others as $uid2)
            emit($uid2, 'message_new', ['chat_id' => $cid, 'message' => msg_public($m, $uid2)], $cid, (int)$me['uid']);
        db()->prepare('UPDATE chat_members SET unread_count = 0, last_read_message_id = ?, last_delivered_message_id = ? WHERE chat_id = ? AND user_id = ?')
            ->execute([$mid, $mid, $cid, $me['uid']]);
    }
    ok(['message' => msg_public($m, (int)$me['uid']), 'scheduled' => $isScheduled]);
}

case 'edit': {
    $me = me();
    $mid = pint('message_id');
    $m = message_by_id($mid);
    if (!$m || (int)$m['deleted'] === 1) fail('not_found', 'Message not found');
    if ((int)$m['sender_id'] !== (int)$me['uid']) fail('forbidden', 'You can edit only your own messages');
    if (time() - (int)ts($m['created_at']) > 48 * 3600) fail('too_late', 'Editing allowed within 48 hours');
    $text = (string)(p('text') ?? '');
    if ($text === '') fail('invalid', 'Empty text');
    db()->prepare('UPDATE messages SET text = ?, edited_at = UTC_TIMESTAMP() WHERE id = ?')->execute([$text, $mid]);
    $m = message_by_id($mid);
    emit(chat_member_ids((int)$m['chat_id']), 'message_edited',
        ['chat_id' => (int)$m['chat_id'], 'message' => msg_public($m, 0)], (int)$m['chat_id'], (int)$me['uid']);
    ok(['message' => msg_public($m, (int)$me['uid'])]);
}

case 'delete': {
    $me = me();
    $mid = pint('message_id');
    $m = message_by_id($mid);
    if (!$m || (int)$m['deleted'] === 1) fail('not_found', 'Message not found');
    $chat = chat_of((int)$m['chat_id']);
    $membership = my_membership((int)$m['chat_id'], (int)$me['uid']);
    if (!$membership) fail('forbidden', 'Not your chat');
    $isOwn = (int)($m['sender_id'] ?? 0) === (int)$me['uid'];
    $isAdmin = $chat && ($chat['type'] === 'group' || $chat['type'] === 'channel') && $membership['role'] === 'admin';
    if (!$isOwn && !$isAdmin) fail('forbidden', 'Not your message');
    db()->prepare('UPDATE messages SET deleted = 1 WHERE id = ?')->execute([$mid]);
    db()->prepare('DELETE FROM reactions WHERE message_id = ?')->execute([$mid]);
    if ($chat && (int)$chat['pinned_message_id'] === $mid)
        db()->prepare('UPDATE chats SET pinned_message_id = NULL WHERE id = ?')->execute([$chat['id']]);
    emit(chat_member_ids((int)$m['chat_id']), 'message_deleted',
        ['chat_id' => (int)$m['chat_id'], 'message_id' => $mid], (int)$m['chat_id'], (int)$me['uid']);
    ok(['deleted' => $mid]);
}

case 'forward': {
    $me = me();
    $ids = array_map('intval', parr('message_ids'));
    $toChat = pint('to_chat_id');
    $membership = require_member($toChat);
    $target = chat_of($toChat);
    if (!$target) fail('not_found', 'Target chat not found');
    $copied = [];
    foreach ($ids as $mid) {
        $m = message_by_id($mid);
        if (!$m || (int)$m['deleted'] === 1) continue;
        $srcChat = chat_of((int)$m['chat_id']);
        if ($srcChat && (int)$srcChat['secret'] === 1) fail('forbidden', 'Cannot forward from secret chat');
        if ((int)$m['encrypted'] === 1) fail('forbidden', 'Cannot forward encrypted message');
        $srcSender = (int)($m['sender_id'] ?? 0);
        if ($srcSender && $srcSender !== (int)$me['uid']) {
            $srcUser = user_by_id($srcSender);
            if ($srcUser && (int)$srcUser['privacy_lock'] === 1)
                fail('privacy_locked', 'This user locked their messages');
        }
        if ($target['type'] === 'private') {
            $peer = peer_of_private($toChat, (int)$me['uid']);
            if ($peer && is_blocked((int)$peer['uid'], (int)$me['uid'])) fail('blocked', 'Cannot forward there');
        }
        $senderName = trim($me['first_name'] . ' ' . $me['last_name']);
        db()->prepare("INSERT INTO messages (chat_id, sender_id, sender_name, type, text, media, thumb,
                        media_name, media_size, media_duration, sticker, link_json, fwd_from_user_id, fwd_name, created_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP())")
            ->execute([$toChat, $me['uid'], $senderName, $m['type'], $m['text'], $m['media'], $m['thumb'],
                       $m['media_name'], (int)$m['media_size'], (int)$m['media_duration'], $m['sticker'],
                       $m['link_json'], $srcSender ?: null, (string)$m['sender_name']]);
        $newId = (int)db()->lastInsertId();
        $nm = message_by_id($newId);
        db()->prepare('UPDATE chats SET last_message_id = ?, last_message_at = UTC_TIMESTAMP() WHERE id = ? AND (last_message_id IS NULL OR last_message_id < ?)')
            ->execute([$newId, $toChat, $newId]);
        $others = chat_member_ids($toChat, (int)$me['uid']);
        $up = db()->prepare('UPDATE chat_members SET unread_count = unread_count + 1 WHERE chat_id = ? AND user_id = ?');
        foreach ($others as $uid2) $up->execute([$toChat, $uid2]);
        foreach ($others as $uid2)
            emit($uid2, 'message_new', ['chat_id' => $toChat, 'message' => msg_public($nm, $uid2)], $toChat, (int)$me['uid']);
        $copied[] = $newId;
    }
    ok(['forwarded' => $copied]);
}

case 'pin': {
    $me = me();
    $cid = pint('chat_id');
    require_member($cid);
    $mid = pint('message_id');
    if ($mid > 0) {
        $m = message_by_id($mid);
        if (!$m || (int)$m['chat_id'] !== $cid || (int)$m['deleted'] === 1) fail('not_found', 'Message not found');
        db()->prepare('UPDATE chats SET pinned_message_id = ? WHERE id = ?')->execute([$mid, $cid]);
        emit(chat_member_ids($cid), 'message_pinned', ['chat_id' => $cid, 'message_id' => $mid, 'pinned' => true], $cid, (int)$me['uid']);
        ok(['pinned' => $mid]);
    } else {
        db()->prepare('UPDATE chats SET pinned_message_id = NULL WHERE id = ?')->execute([$cid]);
        emit(chat_member_ids($cid), 'message_pinned', ['chat_id' => $cid, 'message_id' => null, 'pinned' => false], $cid, (int)$me['uid']);
        ok(['pinned' => null]);
    }
}

case 'read': {
    $me = me();
    $cid = pint('chat_id');
    require_member($cid);
    $lastId = pint('last_read_id');
    db()->prepare('UPDATE chat_members SET last_read_message_id = ?, unread_count = 0 WHERE chat_id = ? AND user_id = ?')
        ->execute([$lastId, $cid, $me['uid']]);
    $members = chat_member_ids($cid, 0);
    emit($members, 'message_read', ['chat_id' => $cid, 'by_user' => (int)$me['uid'], 'last_read_id' => $lastId], $cid, (int)$me['uid']);
    ok(['read' => $lastId]);
}

case 'delivered': {
    $me = me();
    $cid = pint('chat_id');
    require_member($cid);
    $lastId = pint('last_delivered_id');
    db()->prepare('UPDATE chat_members SET last_delivered_message_id = ? WHERE chat_id = ? AND user_id = ?')
        ->execute([$lastId, $cid, $me['uid']]);
    $senders = chat_member_ids($cid, (int)$me['uid']);
    emit($senders, 'message_delivered', ['chat_id' => $cid, 'last_delivered_id' => $lastId], $cid, (int)$me['uid']);
    ok();
}

case 'typing': {
    $me = me();
    $cid = pint('chat_id');
    require_member($cid);
    db()->prepare('UPDATE chat_members SET typing_at = UTC_TIMESTAMP() WHERE chat_id = ? AND user_id = ?')
        ->execute([$cid, $me['uid']]);
    ok();
}

case 'react': {
    $me = me();
    $mid = pint('message_id');
    $emoji = p('emoji');
    $m = message_by_id($mid);
    if (!$m || (int)$m['deleted'] === 1) fail('not_found', 'Message not found');
    require_member((int)$m['chat_id']);
    $srcChat = chat_of((int)$m['chat_id']);
    if ($srcChat && (int)$srcChat['secret'] === 1) fail('forbidden', 'No reactions in secret chats');
    if ($emoji === null || $emoji === '') {
        db()->prepare('DELETE FROM reactions WHERE message_id = ? AND user_id = ?')->execute([$mid, $me['uid']]);
    } else {
        if (mb_strlen($emoji) > 8) fail('invalid', 'Bad emoji');
        db()->prepare('INSERT INTO reactions (message_id, user_id, emoji, created_at) VALUES (?,?,?,UTC_TIMESTAMP())
                       ON DUPLICATE KEY UPDATE emoji = VALUES(emoji)')
            ->execute([$mid, $me['uid'], $emoji]);
    }
    $st = db()->prepare('SELECT emoji, COUNT(*) c, SUM(user_id = ?) mine FROM reactions WHERE message_id = ? GROUP BY emoji');
    $st->execute([(int)$me['uid'], $mid]);
    $reactions = array_map(fn($r) => ['emoji' => $r['emoji'], 'count' => (int)$r['c'], 'mine' => (int)$r['mine'] > 0], $st->fetchAll());
    emit(chat_member_ids((int)$m['chat_id']), 'message_reacted',
        ['chat_id' => (int)$m['chat_id'], 'message_id' => $mid, 'reactions' => $reactions], (int)$m['chat_id'], (int)$me['uid']);
    ok(['reactions' => $reactions]);
}

case 'search': {
    $me = me();
    $cid = pint('chat_id');
    require_member($cid);
    $q = trim(p('q') ?? '');
    if ($q === '') ok(['messages' => []]);
    $st = db()->prepare("SELECT * FROM messages WHERE chat_id = ? AND deleted = 0 AND text LIKE ? ORDER BY id DESC LIMIT 50");
    $st->execute([$cid, "%$q%"]);
    ok(['messages' => array_map(fn($m) => msg_public($m, (int)$me['uid']), $st->fetchAll())]);
}

case 'search_global': {
    $me = me();
    $q = trim(p('q') ?? '');
    if ($q === '') ok(['users' => [], 'messages' => []]);
    $like = "%$q%";
    // users
    $st = db()->prepare("SELECT *, id AS uid FROM users WHERE LOWER(username) LIKE LOWER(?) OR CONCAT(first_name,' ',last_name) LIKE ? LIMIT 20");
    $st->execute([$like, $like]);
    $users = array_map(fn($u) => user_public($u), $st->fetchAll());
    // my chats messages
    $st = db()->prepare("SELECT m.* FROM messages m
                          JOIN chat_members cm ON cm.chat_id = m.chat_id AND cm.user_id = ?
                          WHERE m.deleted = 0 AND m.text LIKE ? ORDER BY m.id DESC LIMIT 50");
    $st->execute([$me['uid'], $like]);
    $msgs = [];
    foreach ($st->fetchAll() as $m) {
        $mp = msg_public($m, (int)$me['uid']);
        unset($mp['reactions']);
        $msgs[] = $mp;
    }
    ok(['users' => $users, 'messages' => $msgs]);
}

case 'scheduled': {
    $me = me();
    $st = db()->prepare("SELECT * FROM messages m JOIN chat_members cm ON cm.chat_id = m.chat_id AND cm.user_id = ?
                          WHERE m.sender_id = ? AND m.scheduled_at IS NOT NULL AND m.deleted = 0
                          ORDER BY m.scheduled_at LIMIT 100");
    $st->execute([$me['uid'], $me['uid']]);
    $out = [];
    foreach ($st->fetchAll() as $row) {
        $mp = msg_public($row, (int)$me['uid']);
        $out[] = $mp;
    }
    ok(['messages' => $out]);
}

default: fail('unknown_action', 'Unknown messages action');
}
