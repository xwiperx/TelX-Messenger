<?php
/* TelX API — communities.php : Telegram-style communities.
   A community is a chat of type 'community' that links related
   groups and channels under one umbrella. Members of the community
   are auto-joined to the linked chats. */
define('TELX_API', 1);
require __DIR__ . '/../config.php';

$action = p('action') ?? '';

/** community row for a chat id (or null). */
function community_of_chat(int $chat_id): ?array {
    $st = db()->prepare('SELECT * FROM communities WHERE chat_id = ?');
    $st->execute([$chat_id]);
    return $st->fetch() ?: null;
}

/** is $chat_id linked into any community that $uid belongs to? */
function linked_community_chat(int $chat_id, int $uid): ?array {
    $st = db()->prepare('SELECT c.* FROM community_chats cc
                         JOIN communities c ON c.id = cc.community_id
                         JOIN chat_members m ON m.chat_id = c.chat_id AND m.user_id = ?
                         WHERE cc.chat_id = ? LIMIT 1');
    $st->execute([$uid, $chat_id]);
    return $st->fetch() ?: null;
}

/** auto-join a community member to a linked chat (best-effort). */
function autojoin_linked(int $chat_id, int $uid): void {
    if (my_membership($chat_id, $uid)) return;
    $chat = chat_of($chat_id);
    if (!$chat) return;
    if (!in_array($chat['type'], ['group', 'channel'], true)) return;
    // banned users stay banned
    db()->prepare("INSERT IGNORE INTO chat_members (chat_id, user_id, role, joined_at) VALUES (?,?,'member',UTC_TIMESTAMP())")
        ->execute([$chat_id, $uid]);
}

switch ($action) {

case 'create': {
    $me = me();
    $title = trim((string)(p('title') ?? ''));
    $desc = trim((string)(p('description') ?? ''));
    $username = trim((string)(p('username') ?? ''));
    if (mb_strlen($title) < 3) fail('invalid', 'Community name must be at least 3 characters');
    if ($username !== '' && !valid_username($username)) fail('invalid', 'Bad public link name');
    if ($username !== '' && username_taken($username)) fail('username_taken', 'This link is already taken');
    db()->prepare("INSERT INTO chats (type, title, username, description, created_at, last_message_at)
                   VALUES ('community', ?, ?, ?, UTC_TIMESTAMP(), UTC_TIMESTAMP())")
        ->execute([$title, $username !== '' ? $username : null, $desc !== '' ? $desc : null]);
    $cid = (int)db()->lastInsertId();
    db()->prepare("INSERT INTO chat_members (chat_id, user_id, role, joined_at) VALUES (?,?,?,UTC_TIMESTAMP())")
        ->execute([$cid, $me['uid'], 'admin']);
    db()->prepare('INSERT INTO communities (chat_id, owner_id) VALUES (?,?)')->execute([$cid, $me['uid']]);
    emit(contact_ids_of((int)$me['uid']), 'chat_created', ['chat_id' => $cid], $cid, (int)$me['uid']);
    ok(['chat_id' => $cid]);
}

case 'link_chat': {
    $me = me();
    $communityChatId = pint('community_id');
    $targetChatId = pint('chat_id');
    $com = community_of_chat($communityChatId);
    if (!$com) fail('not_found', 'Community not found');
    if ((int)$com['owner_id'] !== (int)$me['uid'] && (string)(my_membership($communityChatId, (int)$me['uid'])['role'] ?? '') !== 'admin')
        fail('forbidden', 'Only community admins can link chats');
    $target = chat_of($targetChatId);
    if (!$target || !in_array($target['type'], ['group', 'channel'], true))
        fail('invalid', 'Only groups and channels can be linked');
    $tm = my_membership($targetChatId, (int)$me['uid']);
    if (!$tm || (string)$tm['role'] !== 'admin') fail('forbidden', 'You must be an admin of that chat too');
    db()->prepare('INSERT IGNORE INTO community_chats (community_id, chat_id) VALUES (?,?)')
        ->execute([(int)$com['id'], $targetChatId]);
    // auto-join every existing community member to the linked chat
    foreach (chat_member_ids($communityChatId) as $uid2) autojoin_linked($targetChatId, $uid2);
    emit(chat_member_ids($communityChatId), 'community_updated', ['chat_id' => $communityChatId], $communityChatId, (int)$me['uid']);
    ok(['linked' => $targetChatId]);
}

case 'unlink_chat': {
    $me = me();
    $communityChatId = pint('community_id');
    $targetChatId = pint('chat_id');
    $com = community_of_chat($communityChatId);
    if (!$com) fail('not_found', 'Community not found');
    if ((int)$com['owner_id'] !== (int)$me['uid'] && (string)(my_membership($communityChatId, (int)$me['uid'])['role'] ?? '') !== 'admin')
        fail('forbidden', 'Only community admins can unlink chats');
    db()->prepare('DELETE FROM community_chats WHERE community_id = ? AND chat_id = ?')
        ->execute([(int)$com['id'], $targetChatId]);
    emit(chat_member_ids($communityChatId), 'community_updated', ['chat_id' => $communityChatId], $communityChatId, (int)$me['uid']);
    ok(['unlinked' => $targetChatId]);
}

case 'chats': {
    // linked chats of a community (visible to any community member)
    $me = me();
    $communityChatId = pint('community_id');
    require_member($communityChatId);
    $com = community_of_chat($communityChatId);
    if (!$com) fail('not_found', 'Community not found');
    $st = db()->prepare('SELECT chat_id FROM community_chats WHERE community_id = ? ORDER BY sort, id');
    $st->execute([(int)$com['id']]);
    $out = [];
    foreach ($st->fetchAll() as $r) {
        $cid = (int)$r['chat_id'];
        // auto-join the requester so the chat opens without friction
        autojoin_linked($cid, (int)$me['uid']);
        $chat = chat_of($cid);
        $m = my_membership($cid, (int)$me['uid']);
        if ($chat && $m) $out[] = chat_public($chat, $m, (int)$me['uid']);
    }
    ok(['chats' => $out, 'community_id' => $communityChatId]);
}

case 'join': {
    // join a public community by id or @username
    $me = me();
    $cid = pint('chat_id');
    $username = trim((string)(p('username') ?? ''));
    if ($cid <= 0 && $username === '') fail('invalid', 'chat_id or username required');
    if ($cid <= 0) {
        $st = db()->prepare("SELECT id FROM chats WHERE username = ? AND type = 'community'");
        $st->execute([ltrim($username, '@')]);
        $r = $st->fetch();
        if (!$r) fail('not_found', 'Community not found');
        $cid = (int)$r['id'];
    }
    $chat = chat_of($cid);
    if (!$chat || $chat['type'] !== 'community') fail('not_found', 'Community not found');
    if (!my_membership($cid, (int)$me['uid'])) {
        db()->prepare("INSERT INTO chat_members (chat_id, user_id, role, joined_at) VALUES (?,?,'member',UTC_TIMESTAMP())")
            ->execute([$cid, $me['uid']]);
        $com = community_of_chat($cid);
        if ($com) {
            $st = db()->prepare('SELECT chat_id FROM community_chats WHERE community_id = ?');
            $st->execute([(int)$com['id']]);
            foreach ($st->fetchAll() as $r2) autojoin_linked((int)$r2['chat_id'], (int)$me['uid']);
        }
        emit(chat_member_ids($cid, (int)$me['uid']), 'chat_created', ['chat_id' => $cid], $cid, (int)$me['uid']);
    }
    ok(['chat_id' => $cid]);
}

default: fail('unknown_action', 'Unknown communities action');
}
