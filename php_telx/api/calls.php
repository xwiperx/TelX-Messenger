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

default: fail('unknown_action', 'Unknown calls action');
}
