<?php
/* TelX API — chats.php : list, open private/saved, groups, settings per chat */
define('TELX_API', 1);
require __DIR__ . '/../config.php';

$action = p('action') ?? '';

function valid_chat_username(string $u): bool { return (bool)preg_match('/^[a-zA-Z][a-zA-Z0-9_]{3,31}$/', $u); }
function chat_username_taken(string $u): bool {
    $st = db()->prepare('SELECT id FROM chats WHERE LOWER(username) = LOWER(?)');
    $st->execute([$u]);
    return (bool)$st->fetch();
}

function chat_public(array $chat, array $my_membership, int $viewer, ?array $peer_override = null, bool $typing = false): array {
    $cid = (int)$chat['id'];
    $a = [
        'id' => $cid, 'type' => $chat['type'],
        'title' => $chat['title'], 'secret' => (int)$chat['secret'] === 1,
        'username' => $chat['username'] ?? null,
        'description' => $chat['description'] ?? null,
        'avatar' => $chat['avatar'] ? absolute_url($chat['avatar']) : null,
        'unread' => (int)$my_membership['unread_count'],
        'muted' => (int)$my_membership['muted'] === 1,
        'pinned' => (int)$my_membership['pinned'] === 1,
        'archived' => (int)$my_membership['archived'] === 1,
        'draft' => $my_membership['draft'] !== null ? (string)$my_membership['draft'] : null,
        'auto_delete_secs' => (int)$chat['auto_delete_secs'],
        'members_count' => 0,
        'last_message' => null, 'last_ts' => null,
    ];
    // v6 fields
    $a['slow_mode'] = (int)($chat['slow_mode'] ?? 0);
    $a['join_request'] = (int)($chat['join_request'] ?? 0) === 1;
    $a['hidden'] = (int)($my_membership['hidden'] ?? 0) === 1;
    $a['perms'] = !empty($chat['perms']) ? json_decode($chat['perms'], true) : null;
    // v7: welcome message text (admins see it in the admin panel)
    if (!empty($chat['welcome_msg'])) $a['welcome'] = (string)$chat['welcome_msg'];
    else $a['welcome'] = null;
    if ($chat['last_message_id']) {
        $m = message_by_id((int)$chat['last_message_id']);
        if ($m && !(int)$m['deleted']) {
            $mp = msg_public($m, $viewer);
            unset($mp['reactions'], $mp['reply']);
            $a['last_message'] = $mp;
            $a['last_ts'] = ts($m['created_at']);
        }
    }
    if (!$a['last_ts']) $a['last_ts'] = ts($chat['created_at']);
    $st = db()->prepare('SELECT COUNT(*) c FROM chat_members WHERE chat_id = ?');
    $st->execute([$cid]);
    $a['members_count'] = (int)$st->fetch()['c'];
    if ($chat['type'] === 'private') {
        $a['peer'] = $peer_override ? user_public($peer_override) : peer_of_private($cid, $viewer);
        // peer read/delivery progress for ✓/✓✓ in chat list
        $peerRow = peer_of_private($cid, $viewer);
        if ($peerRow) {
            $pm = my_membership($cid, (int)$peerRow['uid']);
            if ($pm) {
                $a['peer_last_read'] = (int)$pm['last_read_message_id'];
                $a['peer_last_delivered'] = (int)$pm['last_delivered_message_id'];
            }
        }
    } else if ($chat['type'] === 'group' || $chat['type'] === 'channel') {
        // read = minimum of all other members' last_read ids
        $st = db()->prepare('SELECT MIN(last_read_message_id) m FROM chat_members WHERE chat_id = ? AND user_id <> ?');
        $st->execute([$cid, $viewer]);
        $a['peer_last_read'] = (int)$st->fetch()['m'];
    }
    if (!isset($a['peer_last_read'])) $a['peer_last_read'] = 0;
    if (!isset($a['peer_last_delivered'])) $a['peer_last_delivered'] = 0;
    if ($chat['type'] === 'channel' || $chat['type'] === 'group') $a['my_role'] = (string)$my_membership['role'];
    $a['typing'] = $typing;
    return $a;
}

switch ($action) {

case 'list': {
    $me = me();
    flush_due();
    $includeArchived = pint('include_archived') === 1;
    $st = db()->prepare('SELECT c.*, m.unread_count, m.muted, m.pinned, m.archived, m.hidden, m.draft, m.role, m.last_read_message_id
                         FROM chat_members m JOIN chats c ON c.id = m.chat_id
                         WHERE m.user_id = ? AND m.hidden = 0' . ($includeArchived ? '' : ' AND m.archived = 0') . '
                         ORDER BY m.pinned DESC, COALESCE(c.last_message_at, c.created_at) DESC
                         LIMIT 200');
    $st->execute([$me['uid']]);
    $rows = $st->fetchAll();
    // one batched query: which of my chats currently have someone typing
    $typingMap = [];
    $tt = db()->prepare("SELECT m.chat_id FROM chat_members m
                          JOIN chat_members t ON t.chat_id = m.chat_id AND t.user_id <> m.user_id
                          WHERE m.user_id = ? AND t.typing_at >= UTC_TIMESTAMP() - INTERVAL 5 SECOND");
    $tt->execute([$me['uid']]);
    foreach ($tt->fetchAll() as $r) $typingMap[(int)$r['chat_id']] = true;
    $out = [];
    foreach ($rows as $r) {
        $chat = chat_of((int)$r['id']);
        $peer = $chat['type'] === 'private' ? peer_of_private((int)$r['id'], (int)$me['uid']) : null;
        if ($chat['type'] === 'private' && !$peer) continue;          // ghost private chat
        $cp = chat_public($r, $r, (int)$me['uid'], $peer, !empty($typingMap[(int)$r['id']]));
        if ($peer) $cp['peer'] = user_public($peer);
        $out[] = $cp;
    }
    $totalUnread = db()->prepare('SELECT COALESCE(SUM(unread_count),0) t FROM chat_members WHERE user_id = ?');
    $totalUnread->execute([$me['uid']]);
    $archivedCount = db()->prepare('SELECT COUNT(*) c FROM chat_members WHERE user_id = ? AND archived = 1');
    $archivedCount->execute([$me['uid']]);
    ok(['chats' => $out, 'total_unread' => (int)$totalUnread->fetch()['t'],
        'archived_count' => (int)$archivedCount->fetch()['c'], 'server_time' => time()]);
}

case 'open': {
    $me = me();
    $uid = pint('user_id');
    $other = user_by_id($uid);
    if (!$other) fail('not_found', 'User not found');
    if ($uid === (int)$me['uid']) { $cid = ensure_saved_chat((int)$me['uid']); ok(['chat_id' => $cid, 'type' => 'saved']); }
    if (is_blocked($uid, (int)$me['uid'])) fail('blocked', 'This user blocked you');
    if (is_blocked((int)$me['uid'], $uid)) fail('blocked', 'You blocked this user');
    $st = db()->prepare("SELECT c.id FROM chats c
                          JOIN chat_members a ON a.chat_id = c.id AND a.user_id = ?
                          JOIN chat_members b ON b.chat_id = c.id AND b.user_id = ?
                          WHERE c.type = 'private' AND c.secret = 0 LIMIT 1");
    $st->execute([$me['uid'], $uid]);
    $r = $st->fetch();
    if ($r) ok(['chat_id' => (int)$r['id'], 'type' => 'private']);
    db()->prepare("INSERT INTO chats (type, secret, created_at, last_message_at) VALUES ('private', 0, UTC_TIMESTAMP(), UTC_TIMESTAMP())")->execute();
    $cid = (int)db()->lastInsertId();
    $ins = db()->prepare('INSERT INTO chat_members (chat_id, user_id, joined_at) VALUES (?,?,UTC_TIMESTAMP())');
    $ins->execute([$cid, $me['uid']]); $ins->execute([$cid, $uid]);
    emit($uid, 'chat_created', ['chat_id' => $cid, 'peer' => user_public(user_by_id((int)$me['uid']))], $cid, (int)$me['uid']);
    ok(['chat_id' => $cid, 'type' => 'private']);
}

case 'open_secret': {
    $me = me();
    $uid = pint('user_id');
    $other = user_by_id($uid);
    if (!$other) fail('not_found', 'User not found');
    if ($uid === (int)$me['uid']) fail('invalid', 'Cannot open secret chat with yourself');
    if (is_blocked($uid, (int)$me['uid'])) fail('blocked', 'This user blocked you');
    if (is_blocked((int)$me['uid'], $uid)) fail('blocked', 'You blocked this user');
    $st = db()->prepare('SELECT pubkey FROM secret_keys WHERE user_id = ?');
    $st->execute([$uid]);
    if (!$st->fetch()) fail('no_key', 'User has no encryption key yet');
    $st = db()->prepare("SELECT c.id FROM chats c
                          JOIN chat_members a ON a.chat_id = c.id AND a.user_id = ?
                          JOIN chat_members b ON b.chat_id = c.id AND b.user_id = ?
                          WHERE c.type = 'private' AND c.secret = 1 LIMIT 1");
    $st->execute([$me['uid'], $uid]);
    $r = $st->fetch();
    if ($r) ok(['chat_id' => (int)$r['id'], 'type' => 'private', 'secret' => true]);
    db()->prepare("INSERT INTO chats (type, secret, created_at, last_message_at) VALUES ('private', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP())")->execute();
    $cid = (int)db()->lastInsertId();
    $ins = db()->prepare('INSERT INTO chat_members (chat_id, user_id, joined_at) VALUES (?,?,UTC_TIMESTAMP())');
    $ins->execute([$cid, $me['uid']]); $ins->execute([$cid, $uid]);
    emit($uid, 'chat_created', ['chat_id' => $cid, 'peer' => user_public(user_by_id((int)$me['uid'])), 'secret' => true], $cid, (int)$me['uid']);
    ok(['chat_id' => $cid, 'type' => 'private', 'secret' => true]);
}

case 'open_saved': {
    require_auth();
    ok(['chat_id' => ensure_saved_chat(me_id())]);
}

case 'create_group': {
    $me = me();
    $title = trim(p('title') ?? '');
    if (mb_strlen($title) < 1 || mb_strlen($title) > 128) fail('invalid', 'Group title 1-128 chars');
    $memberIds = array_map('intval', parr('member_ids'));
    $memberIds = array_values(array_unique(array_diff($memberIds, [(int)$me['uid']])));
    if (count($memberIds) < 1) fail('invalid', 'Add at least one member');
    foreach ($memberIds as $mid) {
        $u = user_by_id($mid);
        if (!$u) fail('not_found', 'User not found');
        if (is_blocked($mid, (int)$me['uid'])) fail('blocked', "User $mid blocked you");
    }
    $avatarRel = p('avatar_rel');
    db()->prepare("INSERT INTO chats (type, title, avatar, created_at, last_message_at) VALUES ('group', ?, ?, UTC_TIMESTAMP(), UTC_TIMESTAMP())")
        ->execute([$title, $avatarRel ?: null]);
    $cid = (int)db()->lastInsertId();
    $ins = db()->prepare("INSERT INTO chat_members (chat_id, user_id, role, joined_at) VALUES (?,?,?,UTC_TIMESTAMP())");
    $ins->execute([$cid, $me['uid'], 'admin']);
    foreach ($memberIds as $mid) $ins->execute([$cid, $mid, 'member']);
    $systemText = trim($me['first_name']) . ' created the group';
    db()->prepare("INSERT INTO messages (chat_id, sender_id, sender_name, type, text, created_at)
                   VALUES (?,?,?,?,?,UTC_TIMESTAMP())")->execute([$cid, null, '', 'system', $systemText]);
    emit([$me['uid'], ...$memberIds], 'chat_created', ['chat_id' => $cid], $cid, (int)$me['uid']);
    ok(['chat_id' => $cid]);
}

case 'create_channel': {
    $me = me();
    $title = trim(p('title') ?? '');
    if (mb_strlen($title) < 1 || mb_strlen($title) > 128) fail('invalid', 'Channel title 1-128 chars');
    $username = trim(p('username') ?? '');
    if ($username !== '') {
        if (!valid_chat_username($username)) fail('invalid', 'Username: 4-32 chars, letters/digits/underscore, must start with a letter');
        if (chat_username_taken($username)) fail('taken', 'This link is already taken');
    }
    $desc = trim(p('description') ?? '');
    if (mb_strlen($desc) > 255) fail('invalid', 'Description max 255 chars');
    $avatarRelC = p('avatar_rel');
    $memberIds = array_values(array_unique(array_diff(array_map('intval', parr('member_ids')), [(int)$me['uid']])));
    foreach ($memberIds as $mid) { $u = user_by_id($mid); if (!$u) fail('not_found', "User $mid not found"); }
    db()->prepare("INSERT INTO chats (type, title, username, description, avatar, created_at, last_message_at)
                   VALUES ('channel', ?, ?, ?, ?, UTC_TIMESTAMP(), UTC_TIMESTAMP())")
        ->execute([$title, $username ?: null, $desc ?: null, $avatarRelC ?: null]);
    $cid = (int)db()->lastInsertId();
    $ins = db()->prepare("INSERT INTO chat_members (chat_id, user_id, role, joined_at) VALUES (?,?,?,UTC_TIMESTAMP())");
    $ins->execute([$cid, $me['uid'], 'admin']);
    foreach ($memberIds as $mid) $ins->execute([$cid, $mid, 'member']);
    db()->prepare("INSERT INTO messages (chat_id, sender_id, sender_name, type, text, created_at)
                   VALUES (?,NULL,'','system',?,UTC_TIMESTAMP())")->execute([$cid, 'Channel created']);
    emit([$me['uid'], ...$memberIds], 'chat_created', ['chat_id' => $cid], $cid, (int)$me['uid']);
    ok(['chat_id' => $cid]);
}

case 'join_channel': {
    $me = me();
    $cid = pint('chat_id');
    $username = trim(p('username') ?? '');
    if ($cid > 0) {
        $chat = chat_of($cid);
        if (!$chat || $chat['type'] !== 'channel') fail('not_found', 'Channel not found');
    } elseif ($username !== '') {
        $st = db()->prepare("SELECT * FROM chats WHERE type = 'channel' AND LOWER(username) = LOWER(?) LIMIT 1");
        $st->execute([$username]);
        $chat = $st->fetch();
        if (!$chat) fail('not_found', 'Channel not found');
        $cid = (int)$chat['id'];
    } else fail('invalid', 'chat_id or username required');
    $existing = my_membership($cid, (int)$me['uid']);
    if ($existing && (string)$existing['role'] === 'banned') fail('banned', 'You are banned from this channel');
    if ($existing) ok(['chat_id' => $cid, 'joined' => false]);
    if ((int)($chat['join_request'] ?? 0) === 1) {
        db()->prepare('INSERT IGNORE INTO join_requests (chat_id, user_id, created_at) VALUES (?,?,UTC_TIMESTAMP())')
            ->execute([$cid, $me['uid']]);
        emit(chat_admin_ids($cid), 'join_request',
            ['chat_id' => $cid, 'user' => user_public($me), 'ts' => time()], $cid, (int)$me['uid']);
        ok(['chat_id' => $cid, 'joined' => false, 'requested' => true]);
    }
    db()->prepare("INSERT INTO chat_members (chat_id, user_id, role, joined_at) VALUES (?,?,'member',UTC_TIMESTAMP())")->execute([$cid, $me['uid']]);
    // v7: send the admin's welcome message to the new subscriber (visible only to them)
    deliver_welcome($cid, (int)$me['uid']);
    emit(chat_member_ids($cid, (int)$me['uid']), 'chat_updated', ['chat_id' => $cid], $cid, (int)$me['uid']);
    emit($me['uid'], 'chat_created', ['chat_id' => $cid], $cid);
    ok(['chat_id' => $cid, 'joined' => true]);
}

case 'leave_channel': {
    $me = me();
    $cid = pint('chat_id');
    $m = require_member($cid);
    $chat = chat_of($cid);
    if (!$chat || $chat['type'] !== 'channel') fail('invalid', 'Not a channel');
    db()->prepare('DELETE FROM chat_members WHERE chat_id = ? AND user_id = ?')->execute([$cid, $me['uid']]);
    // hand ownership to another admin/subscriber if the creator leaves
    $st = db()->prepare('SELECT user_id FROM chat_members WHERE chat_id = ? ORDER BY id LIMIT 1');
    $st->execute([$cid]);
    $r = $st->fetch();
    if ($r) db()->prepare("UPDATE chat_members SET role = 'admin' WHERE chat_id = ? AND user_id = ?")->execute([$cid, (int)$r['user_id']]);
    else db()->prepare('DELETE FROM chats WHERE id = ?')->execute([$cid]);
    emit($me['uid'], 'chat_deleted', ['chat_id' => $cid], $cid);
    ok(['left' => true]);
}

case 'update_channel': {
    $me = me();
    $cid = pint('chat_id');
    $m = require_member($cid);
    if ($m['role'] !== 'admin') fail('forbidden', 'Only admins can edit the channel');
    $chat = chat_of($cid);
    if (!$chat || $chat['type'] !== 'channel') fail('invalid', 'Not a channel');
    $title = trim(p('title') ?? '');
    if ($title !== '') {
        if (mb_strlen($title) < 1 || mb_strlen($title) > 128) fail('invalid', 'Title 1-128 chars');
        db()->prepare('UPDATE chats SET title = ? WHERE id = ?')->execute([$title, $cid]);
    }
    $desc = p('description');
    if ($desc !== null) {
        if (mb_strlen($desc) > 255) fail('invalid', 'Description max 255 chars');
        db()->prepare('UPDATE chats SET description = ? WHERE id = ?')->execute([$desc === '' ? null : $desc, $cid]);
    }
    $usernameParam = p('username');
    if ($usernameParam !== null) {
        $username = trim($usernameParam);
        if ($username === '') {
            // empty username => make the channel private (no public link)
            db()->prepare('UPDATE chats SET username = NULL WHERE id = ?')->execute([$cid]);
        } else {
            if (!valid_chat_username($username)) fail('invalid', 'Bad username format');
            $own = strtolower((string)$chat['username']) === strtolower($username);
            if (!$own && chat_username_taken($username)) fail('taken', 'This link is already taken');
            db()->prepare('UPDATE chats SET username = ? WHERE id = ?')->execute([$username, $cid]);
        }
    }
    $avatarRel = p('avatar_rel');
    if ($avatarRel !== null)
        db()->prepare('UPDATE chats SET avatar = ? WHERE id = ?')->execute([$avatarRel === '' ? null : $avatarRel, $cid]);
    emit(chat_member_ids($cid, (int)$me['uid']), 'chat_updated', ['chat_id' => $cid], $cid, (int)$me['uid']);
    ok(['chat' => chat_public(chat_of($cid), $m, (int)$me['uid'])]);
}

case 'delete_channel': {
    $me = me();
    $cid = pint('chat_id');
    $m = require_member($cid);
    if ($m['role'] !== 'admin') fail('forbidden', 'Only channel admins can delete it');
    $chat = chat_of($cid);
    if (!$chat || $chat['type'] !== 'channel') fail('invalid', 'Not a channel');
    $everyone = chat_member_ids($cid);
    db()->prepare('DELETE FROM chats WHERE id = ?')->execute([$cid]);
    emit($everyone, 'chat_deleted', ['chat_id' => $cid], $cid, (int)$me['uid']);
    ok(['deleted' => true]);
}

case 'channel_info': {
    $me = me();
    $cid = pint('chat_id');
    $m = require_member($cid);
    $chat = chat_of($cid);
    if (!$chat || $chat['type'] !== 'channel') fail('invalid', 'Not a channel');
    $resp = ['chat' => chat_public($chat, $m, (int)$me['uid'])];
    $st = db()->prepare('SELECT u.*, m.role, u.id AS uid FROM chat_members m JOIN users u ON u.id = m.user_id
                         WHERE m.chat_id = ? ORDER BY m.role = "admin" DESC, u.first_name LIMIT 200');
    $st->execute([$cid]);
    $subs = [];
    foreach ($st->fetchAll() as $u) {
        $pub = user_public($u);
        $pub['role'] = $u['role'];
        $subs[] = $pub;
    }
    $resp['subscribers'] = $m['role'] === 'admin' ? $subs : [];
    ok($resp);
}

case 'set_channel_admin': {
    $me = me();
    $cid = pint('chat_id');
    $uid2 = pint('user_id');
    $m = require_member($cid);
    if ($m['role'] !== 'admin') fail('forbidden', 'Only admins can change roles');
    $chat = chat_of($cid);
    if (!$chat || $chat['type'] !== 'channel') fail('invalid', 'Not a channel');
    $target = my_membership($cid, $uid2);
    if (!$target) fail('not_found', 'User is not a subscriber');
    $role = pint('admin') === 1 ? 'admin' : 'member';
    db()->prepare('UPDATE chat_members SET role = ? WHERE chat_id = ? AND user_id = ?')->execute([$role, $cid, $uid2]);
    emit(chat_member_ids($cid, (int)$me['uid']), 'chat_updated', ['chat_id' => $cid], $cid, (int)$me['uid']);
    ok(['user_id' => $uid2, 'role' => $role]);
}

case 'search_public': {
    $me = me();
    $q = trim(p('q') ?? '');
    if ($q === '') ok(['channels' => []]);
    $like = "%$q%";
    $st = db()->prepare("SELECT c.*, ? AS viewer FROM chats c
                          WHERE c.type = 'channel' AND (LOWER(c.username) LIKE LOWER(?) OR c.title LIKE ?)
                          ORDER BY (SELECT COUNT(*) FROM chat_members cm WHERE cm.chat_id = c.id) DESC
                          LIMIT 20");
    $st->execute([$me['uid'], $like, $like]);
    $channels = [];
    foreach ($st->fetchAll() as $c) {
        $joined = my_membership((int)$c['id'], (int)$me['uid']) ? true : false;
        $channels[] = [
            'id' => (int)$c['id'], 'title' => $c['title'], 'username' => $c['username'],
            'description' => $c['description'],
            'avatar' => $c['avatar'] ? absolute_url($c['avatar']) : null,
            'members_count' => (int)db()->query('SELECT COUNT(*) c FROM chat_members WHERE chat_id = ' . (int)$c['id'])->fetch()['c'],
            'joined' => $joined,
        ];
    }
    ok(['channels' => $channels]);
}

case 'group_info': {
    $me = me();
    $cid = pint('chat_id');
    require_member($cid);
    $chat = chat_of($cid);
    if (!$chat || $chat['type'] !== 'group') fail('invalid', 'Not a group');
    $st = db()->prepare('SELECT u.*, m.role, m.typing_at, u.id AS uid FROM chat_members m JOIN users u ON u.id = m.user_id
                         WHERE m.chat_id = ? ORDER BY m.role = "admin" DESC, u.first_name');
    $st->execute([$cid]);
    $members = [];
    $typing = [];
    foreach ($st->fetchAll() as $u) {
        $pub = user_public($u);
        $pub['role'] = $u['role'];
        $members[] = $pub;
        if ($u['typing_at'] && time() - (int)ts($u['typing_at']) < 5) $typing[] = $pub['id'];
    }
    ok(['chat' => chat_public($chat, my_membership($cid, (int)$me['uid']), (int)$me['uid']), 'members' => $members, 'typing' => $typing]);
}

case 'update_group': {
    $me = me();
    $cid = pint('chat_id');
    $m = require_member($cid);
    if ($m['role'] !== 'admin') fail('forbidden', 'Only admins can edit the group');
    $chat = chat_of($cid);
    if (!$chat || $chat['type'] !== 'group') fail('invalid', 'Not a group');
    $title = trim(p('title') ?? '');
    $avatarRel = p('avatar_rel');
    if ($title !== '') {
        if (mb_strlen($title) < 1 || mb_strlen($title) > 128) fail('invalid', 'Title 1-128 chars');
        db()->prepare('UPDATE chats SET title = ? WHERE id = ?')->execute([$title, $cid]);
    }
    if ($avatarRel !== null) {
        db()->prepare('UPDATE chats SET avatar = ? WHERE id = ?')->execute([$avatarRel === '' ? null : $avatarRel, $cid]);
    }
    $others = chat_member_ids($cid, (int)$me['uid']);
    emit($others, 'group_updated', ['chat_id' => $cid], $cid, (int)$me['uid']);
    ok(['chat' => chat_public(chat_of($cid), $m, (int)$me['uid'])]);
}

case 'add_members': {
    $me = me();
    $cid = pint('chat_id');
    $m = require_member($cid);
    $chat = chat_of($cid);
    if (!$chat || !in_array($chat['type'], ['group', 'channel'], true)) fail('invalid', 'Not a group or channel');
    $ids = array_map('intval', parr('user_ids'));
    $ins = db()->prepare("INSERT IGNORE INTO chat_members (chat_id, user_id, role, joined_at) VALUES (?,?,'member',UTC_TIMESTAMP())");
    $added = [];
    foreach ($ids as $uid2) {
        $u = user_by_id($uid2);
        if (!$u) continue;
        $ins->execute([$cid, $uid2]);
        $added[] = user_public($u);
        $sysTxt = trim($me['first_name']) . ' added ' . trim($u['first_name']);
        db()->prepare("INSERT INTO messages (chat_id, sender_id, sender_name, type, text, created_at)
                       VALUES (?,?,?,'system',?,UTC_TIMESTAMP())")->execute([$cid, null, '', $sysTxt]);
    }
    $others = chat_member_ids($cid, (int)$me['uid']);
    $evt = $chat['type'] === 'channel' ? 'chat_updated' : 'group_updated';
    emit($others, $evt, ['chat_id' => $cid, 'added' => array_column($added, 'id')], $cid, (int)$me['uid']);
    emit(array_column($added, 'id'), 'chat_created', ['chat_id' => $cid], $cid, (int)$me['uid']);
    ok(['added' => $added]);
}

case 'remove_member': {
    $me = me();
    $cid = pint('chat_id');
    $uid2 = pint('user_id');
    $m = require_member($cid);
    if ($m['role'] !== 'admin') fail('forbidden', 'Only admins can remove members');
    $u = user_by_id($uid2);
    if (!$u) fail('not_found', 'User not found');
    db()->prepare('DELETE FROM chat_members WHERE chat_id = ? AND user_id = ?')->execute([$cid, $uid2]);
    $sysTxt = trim($me['first_name']) . ' removed ' . trim($u['first_name']);
    db()->prepare("INSERT INTO messages (chat_id, sender_id, sender_name, type, text, created_at)
                   VALUES (?,?,?,'system',?,UTC_TIMESTAMP())")->execute([$cid, null, '', $sysTxt]);
    emit(chat_member_ids($cid), 'group_updated', ['chat_id' => $cid, 'removed' => $uid2], $cid, (int)$me['uid']);
    emit($uid2, 'chat_deleted', ['chat_id' => $cid], $cid, (int)$me['uid']);
    ok(['removed' => $uid2]);
}

case 'leave_group': {
    $me = me();
    $cid = pint('chat_id');
    require_member($cid);
    $others = chat_member_ids($cid, (int)$me['uid']);
    db()->prepare('DELETE FROM chat_members WHERE chat_id = ? AND user_id = ?')->execute([$cid, $me['uid']]);
    $sysTxt = trim($me['first_name']) . ' left the group';
    db()->prepare("INSERT INTO messages (chat_id, sender_id, sender_name, type, text, created_at)
                   VALUES (?,?,?,'system',?,UTC_TIMESTAMP())")->execute([$cid, null, '', $sysTxt]);
    if ($others) {
        $chat = chat_of($cid);
        if ($chat && $chat['type'] === 'group') {
            $st = db()->prepare("SELECT user_id FROM chat_members WHERE chat_id = ? AND role='admin' LIMIT 1");
            $st->execute([$cid]);
            if (!$st->fetch() && isset($others[0])) {
                db()->prepare("UPDATE chat_members SET role='admin' WHERE chat_id = ? AND user_id = ?")->execute([$cid, $others[0]]);
            }
        }
        emit($others, 'group_updated', ['chat_id' => $cid], $cid, (int)$me['uid']);
    } else {
        db()->prepare('DELETE FROM chats WHERE id = ?')->execute([$cid]);
    }
    ok(['left' => true]);
}

case 'info': {
    $me = me();
    $cid = pint('chat_id');
    $m = require_member($cid);
    $chat = chat_of($cid);
    $resp = ['chat' => chat_public($chat, $m, (int)$me['uid'])];
    $typing = [];
    if ($chat['type'] === 'private') {
        $peer = peer_of_private($cid, (int)$me['uid']);
        $resp['peer'] = $peer ? user_public($peer) : null;
        $resp['peer']['privacy_lock'] = $peer ? ((int)$peer['privacy_lock'] === 1) : false;
        $resp['blocked_by_me'] = $peer ? is_blocked((int)$me['uid'], (int)$peer['uid']) : false;
        if ($peer) {
            $pm = my_membership($cid, (int)$peer['uid']);
            if ($pm && $pm['typing_at'] && time() - (int)ts($pm['typing_at']) < 5) $typing[] = (int)$peer['uid'];
        }
    } else if ($chat['type'] === 'group') {
        $st = db()->prepare('SELECT user_id, typing_at FROM chat_members WHERE chat_id = ?');
        $st->execute([$cid]);
        foreach ($st->fetchAll() as $r) {
            if ((int)$r['user_id'] !== (int)$me['uid'] && $r['typing_at'] && time() - (int)ts($r['typing_at']) < 5)
                $typing[] = (int)$r['user_id'];
        }
    }
    $resp['typing'] = $typing;
    if ($chat['pinned_message_id']) {
        $pm = message_by_id((int)$chat['pinned_message_id']);
        if ($pm && !(int)$pm['deleted']) {
            $resp['pinned_message'] = msg_public($pm, (int)$me['uid']);
            unset($resp['pinned_message']['reactions']);
        }
    }
    ok($resp);
}

case 'set_mute':      { $me = me(); $cid = pint('chat_id'); require_member($cid); db()->prepare('UPDATE chat_members SET muted = ? WHERE chat_id = ? AND user_id = ?')->execute([pint('muted') === 1 ? 1 : 0, $cid, $me['uid']]); ok(); }
case 'set_archived':  { $me = me(); $cid = pint('chat_id'); require_member($cid); db()->prepare('UPDATE chat_members SET archived = ? WHERE chat_id = ? AND user_id = ?')->execute([pint('archived') === 1 ? 1 : 0, $cid, $me['uid']]); ok(); }
case 'set_pinned':    { $me = me(); $cid = pint('chat_id'); require_member($cid); db()->prepare('UPDATE chat_members SET pinned = ? WHERE chat_id = ? AND user_id = ?')->execute([pint('pinned') === 1 ? 1 : 0, $cid, $me['uid']]); ok(); }
case 'set_draft':     { $me = me(); $cid = pint('chat_id'); require_member($cid); db()->prepare('UPDATE chat_members SET draft = ? WHERE chat_id = ? AND user_id = ?')->execute([p('draft') ?? null, $cid, $me['uid']]); ok(); }

case 'set_auto_delete': {
    $me = me(); $cid = pint('chat_id');
    require_member($cid);
    $secs = pint('seconds');
    if (!in_array($secs, [0, 86400, 604800, 2592000], true)) fail('invalid', 'Allowed: 0, 24h, 7d, 30d');
    db()->prepare('UPDATE chats SET auto_delete_secs = ? WHERE id = ?')->execute([$secs, $cid]);
    $others = chat_member_ids($cid, (int)$me['uid']);
    emit($others, 'chat_updated', ['chat_id' => $cid, 'auto_delete_secs' => $secs], $cid, (int)$me['uid']);
    ok(['auto_delete_secs' => $secs]);
}

case 'invite': {
    // create / read / reset the invite link of a group or channel
    $me = me();
    $cid = pint('chat_id');
    $m = require_member($cid);
    $chat = chat_of($cid);
    if (!$chat || !in_array($chat['type'], ['group', 'channel'], true)) fail('invalid', 'Not a group or channel');
    $reset = pint('reset') === 1;
    if (($reset || empty($chat['invite_code'])) && $m['role'] !== 'admin')
        fail('forbidden', 'Only admins can manage the invite link');
    $code = (string)$chat['invite_code'];
    if ($code === '' || $reset) {
        $code = substr(bin2hex(random_bytes(6)), 0, 10);
        db()->prepare('UPDATE chats SET invite_code = ? WHERE id = ?')->execute([$code, $cid]);
    }
    ok(['invite_code' => $code, 'link' => BASE_URL . 'invite/' . $code]);
}

case 'join_invite': {
    $me = me();
    $code = trim((string)(p('code') ?? ''));
    if ($code === '') fail('invalid', 'Invite code required');
    // accept both the raw code and the full link (…/invite/CODE)
    if (preg_match('#invite/([A-Za-z0-9]+)#', $code, $mm)) $code = $mm[1];
    // v6: multiple named links (invite_links) take priority over the legacy chats.invite_code
    $st = db()->prepare('SELECT * FROM invite_links WHERE code = ? LIMIT 1');
    $st->execute([$code]);
    $linkRow = $st->fetch();
    if ($linkRow) {
        if ((int)$linkRow['revoked'] === 1) fail('not_found', 'This invite link was revoked');
        if ($linkRow['expires_at'] !== null && (int)ts($linkRow['expires_at']) < time()) fail('not_found', 'This invite link has expired');
        if ((int)$linkRow['usage_limit'] > 0 && (int)$linkRow['uses'] >= (int)$linkRow['usage_limit'])
            fail('not_found', 'This invite link has reached its member limit');
        $st = db()->prepare('SELECT * FROM chats WHERE id = ? LIMIT 1');
        $st->execute([(int)$linkRow['chat_id']]);
        $chat = $st->fetch();
        if (!$chat || !in_array($chat['type'], ['group', 'channel'], true)) fail('not_found', 'This invite link is invalid');
        $cid = (int)$chat['id'];
        $existing = my_membership($cid, (int)$me['uid']);
        if ($existing && (string)$existing['role'] === 'banned') fail('banned', 'You are banned from this chat');
        if ($existing) ok(['chat_id' => $cid, 'joined' => false]);
        if ((int)($chat['join_request'] ?? 0) === 1) {
            db()->prepare('INSERT IGNORE INTO join_requests (chat_id, user_id, created_at) VALUES (?,?,UTC_TIMESTAMP())')
                ->execute([$cid, $me['uid']]);
            emit(chat_admin_ids($cid), 'join_request',
                ['chat_id' => $cid, 'user' => user_public($me), 'ts' => time()], $cid, (int)$me['uid']);
            ok(['chat_id' => $cid, 'joined' => false, 'requested' => true]);
        }
        db()->prepare("INSERT INTO chat_members (chat_id, user_id, role, joined_at) VALUES (?,?,'member',UTC_TIMESTAMP())")
            ->execute([$cid, $me['uid']]);
        db()->prepare('UPDATE invite_links SET uses = uses + 1 WHERE id = ?')->execute([(int)$linkRow['id']]);
        if ($chat['type'] === 'group') {
            db()->prepare("INSERT INTO messages (chat_id, sender_id, sender_name, type, text, created_at)
                           VALUES (?,NULL,'','system',?,UTC_TIMESTAMP())")
                ->execute([$cid, trim($me['first_name']) . ' joined via invite link']);
        }
        deliver_welcome($cid, (int)$me['uid']);   // v7
        emit(chat_member_ids($cid, (int)$me['uid']), 'chat_updated', ['chat_id' => $cid], $cid, (int)$me['uid']);
        emit($me['uid'], 'chat_created', ['chat_id' => $cid], $cid);
        ok(['chat_id' => $cid, 'joined' => true]);
    }
    $st = db()->prepare('SELECT * FROM chats WHERE invite_code = ? LIMIT 1');
    $st->execute([$code]);
    $chat = $st->fetch();
    if (!$chat || !in_array($chat['type'], ['group', 'channel'], true)) fail('not_found', 'This invite link is invalid');
    $cid = (int)$chat['id'];
    $existing = my_membership($cid, (int)$me['uid']);
    if ($existing && (string)$existing['role'] === 'banned') fail('banned', 'You are banned from this chat');
    if ($existing) ok(['chat_id' => $cid, 'joined' => false]);
    // v6: admin approval required → create a join request instead of joining
    if ((int)($chat['join_request'] ?? 0) === 1) {
        db()->prepare('INSERT IGNORE INTO join_requests (chat_id, user_id, created_at) VALUES (?,?,UTC_TIMESTAMP())')
            ->execute([$cid, $me['uid']]);
        emit(chat_admin_ids($cid), 'join_request',
            ['chat_id' => $cid, 'user' => user_public($me), 'ts' => time()], $cid, (int)$me['uid']);
        ok(['chat_id' => $cid, 'joined' => false, 'requested' => true]);
    }
    db()->prepare("INSERT INTO chat_members (chat_id, user_id, role, joined_at) VALUES (?,?,'member',UTC_TIMESTAMP())")
        ->execute([$cid, $me['uid']]);
    if ($chat['type'] === 'group') {
        db()->prepare("INSERT INTO messages (chat_id, sender_id, sender_name, type, text, created_at)
                       VALUES (?,NULL,'','system',?,UTC_TIMESTAMP())")
            ->execute([$cid, trim($me['first_name']) . ' joined via invite link']);
    }
    deliver_welcome($cid, (int)$me['uid']);   // v7
    emit(chat_member_ids($cid, (int)$me['uid']), 'chat_updated', ['chat_id' => $cid], $cid, (int)$me['uid']);
    emit($me['uid'], 'chat_created', ['chat_id' => $cid], $cid);
    ok(['chat_id' => $cid, 'joined' => true]);
}

case 'folders': {
    // list all folders of me with their chat ids
    $me = me();
    $st = db()->prepare('SELECT * FROM chat_folders WHERE user_id = ? ORDER BY sort, id');
    $st->execute([$me['uid']]);
    $out = [];
    foreach ($st->fetchAll() as $f) {
        $s2 = db()->prepare('SELECT chat_id FROM folder_chats WHERE folder_id = ?');
        $s2->execute([(int)$f['id']]);
        $out[] = ['id' => (int)$f['id'], 'name' => $f['name'], 'emoji' => $f['emoji'],
                  'flags' => (int)$f['flags'], 'chats' => array_map(fn($r) => (int)$r['chat_id'], $s2->fetchAll())];
    }
    ok(['folders' => $out]);
}

case 'folder_save': {
    $me = me();
    $fid = pint('folder_id');
    $name = trim((string)(p('name') ?? ''));
    $emoji = mb_substr(trim((string)(p('emoji') ?? '')), 0, 8);
    $flags = pint('flags');
    if ($name === '') fail('invalid', 'Folder name required');
    if (mb_strlen($name) > 32) fail('invalid', 'Folder name too long');
    if ($fid > 0) {
        $st = db()->prepare('SELECT id FROM chat_folders WHERE id = ? AND user_id = ?');
        $st->execute([$fid, $me['uid']]);
        if (!$st->fetch()) fail('not_found', 'Folder not found');
        db()->prepare('UPDATE chat_folders SET name = ?, emoji = ?, flags = ? WHERE id = ?')
            ->execute([$name, $emoji, $flags, $fid]);
        ok(['folder_id' => $fid]);
    }
    try {
        db()->prepare('INSERT INTO chat_folders (user_id, name, emoji, flags) VALUES (?,?,?,?)')
            ->execute([$me['uid'], $name, $emoji, $flags]);
        ok(['folder_id' => (int)db()->lastInsertId()]);
    } catch (Throwable $e) {
        fail('invalid', 'A folder with this name already exists');
    }
}

case 'folder_delete': {
    $me = me();
    $fid = pint('folder_id');
    $st = db()->prepare('SELECT id FROM chat_folders WHERE id = ? AND user_id = ?');
    $st->execute([$fid, $me['uid']]);
    if (!$st->fetch()) fail('not_found', 'Folder not found');
    db()->prepare('DELETE FROM folder_chats WHERE folder_id = ?')->execute([$fid]);
    db()->prepare('DELETE FROM chat_folders WHERE id = ?')->execute([$fid]);
    ok(['deleted' => 1]);
}

case 'folder_assign': {
    $me = me();
    $fid = pint('folder_id');
    $cid = pint('chat_id');
    $inside = pint('inside') === 1;
    $st = db()->prepare('SELECT id FROM chat_folders WHERE id = ? AND user_id = ?');
    $st->execute([$fid, $me['uid']]);
    if (!$st->fetch()) fail('not_found', 'Folder not found');
    require_member($cid);
    if ($inside) db()->prepare('INSERT IGNORE INTO folder_chats (folder_id, chat_id) VALUES (?,?)')->execute([$fid, $cid]);
    else db()->prepare('DELETE FROM folder_chats WHERE folder_id = ? AND chat_id = ?')->execute([$fid, $cid]);
    ok(['folder_id' => $fid, 'chat_id' => $cid, 'inside' => $inside]);
}

case 'clear_history': {
    $me = me(); $cid = pint('chat_id');
    require_member($cid);
    db()->prepare('UPDATE messages SET deleted = 1 WHERE chat_id = ?')->execute([$cid]);
    db()->prepare('UPDATE chats SET pinned_message_id = NULL WHERE id = ?')->execute([$cid]);
    db()->prepare('UPDATE chat_members SET unread_count = 0, last_read_message_id = 0, last_delivered_message_id = 0 WHERE chat_id = ?')
        ->execute([$cid]);
    emit(chat_member_ids($cid), 'chat_cleared', ['chat_id' => $cid], $cid, (int)$me['uid']);
    ok(['cleared' => true]);
}

case 'set_slow_mode': {
    // Telegram-style slow mode for groups (0 = off, else seconds between messages)
    $me = me(); $cid = pint('chat_id'); $m = require_member($cid); $chat = chat_of($cid);
    if (!$chat || $chat['type'] !== 'group') fail('invalid', 'Not a group');
    if ($m['role'] !== 'admin') fail('forbidden', 'Only admins can set slow mode');
    $secs = max(0, min(86400, pint('seconds')));
    db()->prepare('UPDATE chats SET slow_mode = ? WHERE id = ?')->execute([$secs, $cid]);
    emit(chat_member_ids($cid), 'chat_updated', ['chat_id' => $cid, 'slow_mode' => $secs], $cid, (int)$me['uid']);
    ok(['slow_mode' => $secs]);
}

case 'set_join_request': {
    // admin approval required before new members can join
    $me = me(); $cid = pint('chat_id'); $m = require_member($cid); $chat = chat_of($cid);
    if (!$chat || !in_array($chat['type'], ['group','channel'], true)) fail('invalid', 'Not a group or channel');
    if ($m['role'] !== 'admin') fail('forbidden', 'Only admins can change this');
    $on = pint('enabled') === 1 ? 1 : 0;
    db()->prepare('UPDATE chats SET join_request = ? WHERE id = ?')->execute([$on, $cid]);
    ok(['join_request' => $on === 1]);
}

case 'set_perms': {
    // granular member permissions: {send_media, send_links, send_stickers, add_members}
    $me = me(); $cid = pint('chat_id'); $m = require_member($cid); $chat = chat_of($cid);
    if (!$chat || !in_array($chat['type'], ['group','channel'], true)) fail('invalid', 'Not a group or channel');
    if ($m['role'] !== 'admin') fail('forbidden', 'Only admins can change permissions');
    $p = json_decode((string)(p('perms') ?? '{}'), true);
    if (!is_array($p)) fail('invalid', 'Bad perms JSON');
    $clean = [];
    foreach (['send_media','send_links','send_stickers','add_members'] as $k)
        $clean[$k] = (int)($p[$k] ?? 1) === 1 ? 1 : 0;
    db()->prepare('UPDATE chats SET perms = ? WHERE id = ?')->execute([json_encode($clean), $cid]);
    ok(['perms' => $clean]);
}

case 'ban_member': {
    $me = me(); $cid = pint('chat_id'); $m = require_member($cid); $chat = chat_of($cid);
    $uid = pint('user_id');
    if (!$chat || !in_array($chat['type'], ['group','channel'], true)) fail('invalid', 'Not a group or channel');
    if ($m['role'] !== 'admin') fail('forbidden', 'Only admins can ban');
    if ($uid === (int)$me['uid']) fail('invalid', 'Cannot ban yourself');
    $tm = my_membership($cid, $uid);
    if (!$tm) fail('not_found', 'Not a member');
    if ($tm['role'] === 'admin') fail('forbidden', 'Cannot ban another admin');
    $ban = pint('banned') === 1;
    db()->prepare("UPDATE chat_members SET role = ? WHERE chat_id = ? AND user_id = ?")
        ->execute([$ban ? 'banned' : 'member', $cid, $uid]);
    $u = user_by_id($uid);
    if ($chat['type'] === 'group') {
        db()->prepare("INSERT INTO messages (chat_id, sender_id, sender_name, type, text, created_at)
                       VALUES (?,NULL,'','system',?,UTC_TIMESTAMP())")
            ->execute([$cid, ($u ? trim($u['first_name']) : 'Member') . ($ban ? ' was banned' : ' was unbanned')]);
    }
    emit($uid, 'chat_updated', ['chat_id' => $cid, 'banned' => $ban], $cid, (int)$me['uid']);
    ok(['banned' => $ban]);
}

case 'banned_list': {
    $me = me(); $cid = pint('chat_id'); $m = require_member($cid); $chat = chat_of($cid);
    if (!$chat) fail('not_found', 'Chat not found');
    if ($m['role'] !== 'admin') fail('forbidden', 'Only admins can see this');
    $st = db()->prepare("SELECT u.*, u.id AS uid FROM chat_members cm JOIN users u ON u.id = cm.user_id
                          WHERE cm.chat_id = ? AND cm.role = 'banned'");
    $st->execute([$cid]);
    ok(['banned' => array_map(fn($u) => user_public($u), $st->fetchAll())]);
}

case 'links_list': {
    // multiple invite links with usage/expiry, Telegram-style
    $me = me(); $cid = pint('chat_id'); $m = require_member($cid); $chat = chat_of($cid);
    if (!$chat || !in_array($chat['type'], ['group','channel'], true)) fail('invalid', 'Not a group or channel');
    $st = db()->prepare('SELECT * FROM invite_links WHERE chat_id = ? ORDER BY id DESC LIMIT 50');
    $st->execute([$cid]);
    $out = [];
    foreach ($st->fetchAll() as $l) {
        $creator = user_by_id((int)$l['creator_id']);
        $out[] = [
            'id' => (int)$l['id'], 'code' => (string)$l['code'], 'name' => (string)$l['name'],
            'link' => BASE_URL . 'invite/' . $l['code'],
            'creator' => $creator ? trim($creator['first_name'] . ' ' . $creator['last_name']) : '',
            'expires_ts' => ts($l['expires_at']), 'usage_limit' => (int)$l['usage_limit'],
            'uses' => (int)$l['uses'], 'revoked' => (int)$l['revoked'] === 1,
            'ts' => ts($l['created_at']),
        ];
    }
    ok(['links' => $out]);
}

case 'link_create': {
    $me = me(); $cid = pint('chat_id'); $m = require_member($cid); $chat = chat_of($cid);
    if (!$chat || !in_array($chat['type'], ['group','channel'], true)) fail('invalid', 'Not a group or channel');
    if ($m['role'] !== 'admin') fail('forbidden', 'Only admins can create links');
    $name = mb_substr(trim((string)(p('name') ?? '')), 0, 64);
    $days = max(0, min(365, pint('expires_days')));
    $limit = max(0, min(100000, pint('usage_limit')));
    $code = substr(bin2hex(random_bytes(6)), 0, 10);
    db()->prepare('INSERT INTO invite_links (chat_id, code, name, creator_id, expires_at, usage_limit, created_at)
                   VALUES (?,?,?,?,?,?,UTC_TIMESTAMP())')
        ->execute([$cid, $code, $name, $me['uid'],
                   $days > 0 ? sqlts(time() + $days * 86400) : null, $limit]);
    ok(['code' => $code, 'link' => BASE_URL . 'invite/' . $code]);
}

case 'link_revoke': {
    $me = me(); $cid = pint('chat_id'); $m = require_member($cid);
    if ($m['role'] !== 'admin') fail('forbidden', 'Only admins can revoke links');
    $lid = pint('link_id');
    db()->prepare('UPDATE invite_links SET revoked = 1 WHERE id = ? AND chat_id = ?')->execute([$lid, $cid]);
    ok(['revoked' => $lid]);
}

case 'requests_list': {
    $me = me(); $cid = pint('chat_id'); $m = require_member($cid);
    if ($m['role'] !== 'admin') fail('forbidden', 'Only admins can review requests');
    $st = db()->prepare('SELECT u.*, u.id AS uid, jr.created_at AS req_at FROM join_requests jr
                          JOIN users u ON u.id = jr.user_id WHERE jr.chat_id = ? ORDER BY jr.id DESC LIMIT 100');
    $st->execute([$cid]);
    $out = [];
    foreach ($st->fetchAll() as $r) {
        $u = user_public($r);
        $u['req_ts'] = ts($r['req_at']);
        $out[] = $u;
    }
    ok(['requests' => $out]);
}

case 'request_decide': {
    $me = me(); $cid = pint('chat_id'); $m = require_member($cid); $chat = chat_of($cid);
    if (!$chat) fail('not_found', 'Chat not found');
    if ($m['role'] !== 'admin') fail('forbidden', 'Only admins can decide');
    $uid = pint('user_id');
    $approve = pint('approve') === 1;
    db()->prepare('DELETE FROM join_requests WHERE chat_id = ? AND user_id = ?')->execute([$cid, $uid]);
    if ($approve) {
        db()->prepare("INSERT INTO chat_members (chat_id, user_id, role, joined_at) VALUES (?,?,'member',UTC_TIMESTAMP())")
            ->execute([$cid, $uid]);
        $u = user_by_id($uid);
        if ($chat['type'] === 'group') {
            db()->prepare("INSERT INTO messages (chat_id, sender_id, sender_name, type, text, created_at)
                           VALUES (?,NULL,'','system',?,UTC_TIMESTAMP())")
                ->execute([$cid, ($u ? trim($u['first_name']) : 'New member') . ' joined the group']);
        }
        emit(chat_member_ids($cid), 'chat_updated', ['chat_id' => $cid], $cid, (int)$me['uid']);
        emit($uid, 'chat_created', ['chat_id' => $cid], $cid);
        deliver_welcome($cid, $uid);   // v7
    }
    emit($uid, 'join_request_result', ['chat_id' => $cid, 'approved' => $approve, 'chat_title' => (string)$chat['title']], $cid, (int)$me['uid']);
    ok(['approved' => $approve]);
}

case 'set_hidden': {
    // hide a chat from the main list (see it again via Settings → Hidden chats)
    $me = me(); $cid = pint('chat_id'); require_member($cid);
    db()->prepare('UPDATE chat_members SET hidden = ? WHERE chat_id = ? AND user_id = ?')
        ->execute([pint('hidden') === 1 ? 1 : 0, $cid, $me['uid']]);
    ok(['hidden' => pint('hidden') === 1]);
}

case 'hidden_list': {
    $me = me();
    $st = db()->prepare('SELECT c.* FROM chat_members m JOIN chats c ON c.id = m.chat_id
                          WHERE m.user_id = ? AND m.hidden = 1 ORDER BY COALESCE(c.last_message_at, c.created_at) DESC');
    $st->execute([$me['uid']]);
    $out = [];
    foreach ($st->fetchAll() as $c) {
        $mm = my_membership((int)$c['id'], (int)$me['uid']);
        if ($mm) $out[] = chat_public($c, $mm, (int)$me['uid']);
    }
    ok(['chats' => $out]);
}

case 'chat_stats': {
    // group/channel statistics: member growth, message activity, top posters, channel views
    $me = me(); $cid = pint('chat_id'); $m = require_member($cid); $chat = chat_of($cid);
    if (!$chat || !in_array($chat['type'], ['group','channel'], true)) fail('invalid', 'Not a group or channel');
    if ($m['role'] !== 'admin') fail('forbidden', 'Only admins can see statistics');
    $growth = [];
    $st = db()->prepare("SELECT DATE(joined_at) d, COUNT(*) c FROM chat_members
                          WHERE chat_id = ? AND joined_at >= UTC_TIMESTAMP() - INTERVAL 30 DAY GROUP BY DATE(joined_at) ORDER BY d");
    $st->execute([$cid]);
    foreach ($st->fetchAll() as $r) $growth[] = ['day' => $r['d'], 'count' => (int)$r['c']];
    $activity = [];
    $st = db()->prepare("SELECT DATE(created_at) d, COUNT(*) c FROM messages
                          WHERE chat_id = ? AND deleted = 0 AND created_at >= UTC_TIMESTAMP() - INTERVAL 30 DAY GROUP BY DATE(created_at) ORDER BY d");
    $st->execute([$cid]);
    foreach ($st->fetchAll() as $r) $activity[] = ['day' => $r['d'], 'count' => (int)$r['c']];
    $top = [];
    $st = db()->prepare('SELECT sender_name n, COUNT(*) c FROM messages
                          WHERE chat_id = ? AND deleted = 0 AND sender_id IS NOT NULL
                          GROUP BY sender_id, sender_name ORDER BY c DESC LIMIT 10');
    $st->execute([$cid]);
    foreach ($st->fetchAll() as $r) $top[] = ['name' => (string)$r['n'], 'count' => (int)$r['c']];
    $views = 0;
    if ($chat['type'] === 'channel') {
        $st = db()->prepare('SELECT COALESCE(SUM(v.c),0) t FROM (SELECT COUNT(*) c FROM message_views
                              WHERE message_id IN (SELECT id FROM messages WHERE chat_id = ?)) v');
        $st->execute([$cid]);
        $views = (int)$st->fetch()['t'];
    }
    $st = db()->prepare('SELECT COUNT(*) c FROM chat_members WHERE chat_id = ?');
    $st->execute([$cid]);
    $members = (int)$st->fetch()['c'];
    $st = db()->prepare('SELECT COUNT(*) c FROM messages WHERE chat_id = ? AND deleted = 0');
    $st->execute([$cid]);
    ok(['stats' => ['members' => $members, 'messages' => (int)$st->fetch()['c'], 'views' => $views,
                    'growth' => $growth, 'activity' => $activity, 'top' => $top,
                    'type' => $chat['type']]]);
}

/* ================= v7: welcome messages + read-all ================= */

case 'set_welcome': {
    $me = me();
    $cid = pint('chat_id');
    $m = require_member($cid);
    if ((string)$m['role'] !== 'admin') fail('forbidden', 'Only admins can set the welcome message');
    $text = trim((string)(p('text') ?? ''));
    if (mb_strlen($text) > 500) $text = mb_substr($text, 0, 500);
    db()->prepare('UPDATE chats SET welcome_msg = ? WHERE id = ?')
        ->execute([$text !== '' ? $text : null, $cid]);
    ok(['welcome' => $text !== '' ? $text : null]);
}

case 'mark_all_read': {
    $me = me();
    $st = db()->prepare('SELECT chat_id, MAX(id) as maxid FROM messages WHERE chat_id IN
                         (SELECT chat_id FROM chat_members WHERE user_id = ?) AND deleted = 0
                         GROUP BY chat_id');
    $st->execute([$me['uid']]);
    $upd = db()->prepare('UPDATE chat_members SET unread_count = 0, last_read_message_id = ?, last_delivered_message_id = ? WHERE chat_id = ? AND user_id = ?');
    $affected = [];
    foreach ($st->fetchAll() as $r) {
        $max = (int)$r['maxid'];
        $upd->execute([$max, $max, (int)$r['chat_id'], $me['uid']]);
        $affected[(int)$r['chat_id']] = $max;
    }
    // tell peers their messages were read
    foreach ($affected as $cid => $max) {
        $chat = chat_of($cid);
        if ($chat && ($chat['type'] === 'private' || $chat['type'] === 'group' || $chat['type'] === 'channel')) {
            $targets = $chat['type'] === 'private'
                ? array_map('intval', array_column(array_filter([peer_of_private($cid, (int)$me['uid'])], fn($x) => $x !== null), 'uid'))
                : chat_member_ids($cid, (int)$me['uid']);
            if ($targets) emit($targets, 'message_read', ['chat_id' => $cid, 'last_read_id' => $max], $cid, (int)$me['uid']);
        }
    }
    ok(['chats' => count($affected)]);
}

default: fail('unknown_action', 'Unknown chats action');
}
