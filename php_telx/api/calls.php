<?php
/* TelX API — calls.php : WebRTC signaling relay (events-based) */
define('TELX_API', 1);
require __DIR__ . '/../config.php';

$action = p('action') ?? '';

function call_target(int $chat_id): array {
    $me = me();
    $chat = chat_of($chat_id);
    if (!$chat || $chat['type'] !== 'private') fail('invalid', 'Calls work in private chats only');
    $peer = peer_of_private($chat_id, (int)$me['uid']);
    if (!$peer) fail('not_found', 'Peer not found');
    if (is_blocked((int)$peer['uid'], (int)$me['uid'])) fail('blocked', 'This user blocked you');
    return $peer;
}

/* ============ v7: secure group calls (participant registry + events) ============ */

/** any group/channel/community chat where the caller is a member. */
function group_call_target(int $chat_id): array {
    $me = me();
    $chat = chat_of($chat_id);
    if (!$chat || !in_array($chat['type'], ['group', 'channel', 'community'], true))
        fail('invalid', 'Group calls work in groups, channels and communities');
    $m = my_membership($chat_id, (int)$me['uid']);
    if (!$m) fail('forbidden', 'You are not a member of this chat');
    if ((string)$m['role'] === 'banned') fail('banned', 'You are banned in this chat');
    return $chat;
}

switch ($action) {

case 'start': {
    $me = me();
    $cid = pint('chat_id');
    $peer = call_target($cid);
    $callId = random_token(24);
    emit((int)$peer['uid'], 'call_invite',
        ['call_id' => $callId, 'chat_id' => $cid,
         'from' => user_public(user_by_id((int)$me['uid']))], $cid, (int)$me['uid']);
    ok(['call_id' => $callId]);
}

case 'sdp': {
    $me = me();
    $cid = pint('chat_id');
    $peer = call_target($cid);
    $sdp = p('sdp');
    $typ = p('sdp_type');
    if (!$sdp || !$typ) fail('invalid', 'sdp required');
    emit((int)$peer['uid'], 'call_sdp',
        ['call_id' => p('call_id'), 'sdp' => $sdp, 'type' => $typ], $cid, (int)$me['uid']);
    ok();
}

case 'ice': {
    $me = me();
    $cid = pint('chat_id');
    $peer = call_target($cid);
    emit((int)$peer['uid'], 'call_ice',
        ['call_id' => p('call_id'), 'candidate' => p('candidate')], $cid, (int)$me['uid']);
    ok();
}

case 'accept': {
    $me = me();
    $cid = pint('chat_id');
    $peer = call_target($cid);
    emit((int)$peer['uid'], 'call_accept', ['call_id' => p('call_id')], $cid, (int)$me['uid']);
    ok();
}

case 'decline': {
    $me = me();
    $cid = pint('chat_id');
    $peer = call_target($cid);
    emit((int)$peer['uid'], 'call_decline', ['call_id' => p('call_id')], $cid, (int)$me['uid']);
    ok();
}

case 'end': {
    $me = me();
    $cid = pint('chat_id');
    $peer = call_target($cid);
    emit((int)$peer['uid'], 'call_end', ['call_id' => p('call_id')], $cid, (int)$me['uid']);
    ok();
}

case 'group_start': {
    $me = me();
    $cid = pint('chat_id');
    $chat = group_call_target($cid);
    $callId = random_token(24);
    // fresh call → clear stale participants from earlier calls
    db()->prepare('DELETE FROM call_participants WHERE chat_id = ?')->execute([$cid]);
    db()->prepare('INSERT INTO call_participants (chat_id, user_id, call_id, joined_at) VALUES (?,?,?,UTC_TIMESTAMP())')
        ->execute([$cid, $me['uid'], $callId]);
    $title = (string)$chat['title'];
    db()->prepare("INSERT INTO messages (chat_id, sender_id, sender_name, type, text, created_at)
                   VALUES (?,NULL,'','system',?,UTC_TIMESTAMP())")
        ->execute([$cid, '🔒 Secure group call started — tap Join to enter']);
    $mid = (int)db()->lastInsertId();
    emit(chat_member_ids($cid), 'group_call_started',
        ['chat_id' => $cid, 'call_id' => $callId,
         'from' => user_public(user_by_id((int)$me['uid'])), 'title' => $title], $cid, (int)$me['uid']);
    emit(chat_member_ids($cid), 'message_new',
        ['chat_id' => $cid, 'message' => msg_public(message_by_id($mid), 0)], $cid, (int)$me['uid']);
    ok(['call_id' => $callId]);
}

case 'group_join': {
    $me = me();
    $cid = pint('chat_id');
    group_call_target($cid);
    $callId = (string)(p('call_id') ?? random_token(24));
    db()->prepare('INSERT INTO call_participants (chat_id, user_id, call_id, joined_at) VALUES (?,?,?,UTC_TIMESTAMP())
                   ON DUPLICATE KEY UPDATE left_at = NULL, call_id = VALUES(call_id), joined_at = UTC_TIMESTAMP()')
        ->execute([$cid, $me['uid'], $callId]);
    emit(chat_member_ids($cid), 'group_call_updated', ['chat_id' => $cid, 'call_id' => $callId], $cid, (int)$me['uid']);
    ok(['call_id' => $callId]);
}

case 'group_leave': {
    $me = me();
    $cid = pint('chat_id');
    group_call_target($cid);
    db()->prepare('UPDATE call_participants SET left_at = UTC_TIMESTAMP() WHERE chat_id = ? AND user_id = ? AND left_at IS NULL')
        ->execute([$cid, $me['uid']]);
    emit(chat_member_ids($cid), 'group_call_updated', ['chat_id' => $cid], $cid, (int)$me['uid']);
    ok();
}

case 'group_participants': {
    $me = me();
    $cid = pint('chat_id');
    group_call_target($cid);
    $st = db()->prepare('SELECT cp.*, u.*, cp.user_id AS cuid, cp.left_at AS cp_left
                         FROM call_participants cp JOIN users u ON u.id = cp.user_id
                         WHERE cp.chat_id = ? AND cp.left_at IS NULL ORDER BY cp.joined_at');
    $st->execute([$cid]);
    $out = [];
    foreach ($st->fetchAll() as $r) {
        $pub = user_public($r);
        $pub['in_call'] = true;
        $out[] = $pub;
    }
    $live = db()->prepare('SELECT COUNT(*) c FROM call_participants WHERE chat_id = ? AND left_at IS NULL');
    $live->execute([$cid]);
    ok(['participants' => $out, 'live_count' => (int)$live->fetch()['c']]);
}

case 'group_end': {
    $me = me();
    $cid = pint('chat_id');
    $chat = group_call_target($cid);
    db()->prepare('DELETE FROM call_participants WHERE chat_id = ?')->execute([$cid]);
    db()->prepare("INSERT INTO messages (chat_id, sender_id, sender_name, type, text, created_at)
                   VALUES (?,NULL,'','system',?,UTC_TIMESTAMP())")
        ->execute([$cid, 'Group call ended']);
    $mid = (int)db()->lastInsertId();
    emit(chat_member_ids($cid), 'group_call_ended', ['chat_id' => $cid, 'call_id' => p('call_id')], $cid, (int)$me['uid']);
    emit(chat_member_ids($cid), 'message_new',
        ['chat_id' => $cid, 'message' => msg_public(message_by_id($mid), 0)], $cid, (int)$me['uid']);
    ok(['ended' => true]);
}

default: fail('unknown_action', 'Unknown calls action');
}
