<?php
/* TelX API — chats.php : list, open private/saved, groups, settings per chat */
define('TELX_API', 1);
require __DIR__ . '/../config.php';

$action = p('action') ?? '';

function chat_public(array $chat, array $my_membership, int $viewer, ?array $peer_override = null): array {
    $cid = (int)$chat['id'];
    $a = [
        'id' => $cid, 'type' => $chat['type'],
        'title' => $chat['title'], 'secret' => (int)$chat['secret'] === 1,
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
        // peer read progress for ✓✓ in chat list
        $peerRow = peer_of_private($cid, $viewer);
        if ($peerRow) {
            $pm = my_membership($cid, (int)$peerRow['uid']);
            if ($pm) $a['peer_last_read'] = (int)$pm['last_read_message_id'];
        }
    } else if ($chat['type'] === 'group') {
        // read = minimum of all other members' last_read ids
        $st = db()->prepare('SELECT MIN(last_read_message_id) m FROM chat_members WHERE chat_id = ? AND user_id <> ?');
        $st->execute([$cid, $viewer]);
        $a['peer_last_read'] = (int)$st->fetch()['m'];
    }
    if (!isset($a['peer_last_read'])) $a['peer_last_read'] = 0;
    return $a;
}

switch ($action) {

case 'list': {
    $me = me();
    flush_due();
    $includeArchived = pint('include_archived') === 1;
    $st = db()->prepare('SELECT c.*, m.unread_count, m.muted, m.pinned, m.archived, m.draft, m.last_read_message_id
                         FROM chat_members m JOIN chats c ON c.id = m.chat_id
                         WHERE m.user_id = ?' . ($includeArchived ? '' : ' AND m.archived = 0') . '
                         ORDER BY m.pinned DESC, COALESCE(c.last_message_at, c.created_at) DESC
                         LIMIT 200');
    $st->execute([$me['uid']]);
    $rows = $st->fetchAll();
    $out = [];
    foreach ($rows as $r) {
        $chat = chat_of((int)$r['id']);
        $peer = $chat['type'] === 'private' ? peer_of_private((int)$r['id'], (int)$me['uid']) : null;
        if ($chat['type'] === 'private' && !$peer) continue;          // ghost private chat
        $cp = chat_public($r, $r, (int)$me['uid'], $peer);
        if ($peer) $cp['peer'] = user_public($peer);
        $out[] = $cp;
    }
    $totalUnread = db()->prepare('SELECT COALESCE(SUM(unread_count),0) t FROM chat_members WHERE user_id = ?');
    $totalUnread->execute([$me['uid']]);
    ok(['chats' => $out, 'total_unread' => (int)$totalUnread->fetch()['t'], 'server_time' => time()]);
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
    db()->prepare("INSERT INTO chats (type, title, created_at, last_message_at) VALUES ('group', ?, UTC_TIMESTAMP(), UTC_TIMESTAMP())")
        ->execute([$title]);
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
    if (!$chat || $chat['type'] !== 'group') fail('invalid', 'Not a group');
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
    emit($others, 'group_updated', ['chat_id' => $cid, 'added' => array_column($added, 'id')], $cid, (int)$me['uid']);
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

default: fail('unknown_action', 'Unknown chats action');
}
