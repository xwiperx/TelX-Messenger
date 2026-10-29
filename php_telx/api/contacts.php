<?php
/* TelX API — contacts.php : list, add, remove, block, global user search */
define('TELX_API', 1);
require __DIR__ . '/../config.php';

$action = p('action') ?? '';

switch ($action) {

case 'list': {
    $me = me();
    $st = db()->prepare('SELECT u.*, c.id AS cid, u.id AS uid FROM contacts c JOIN users u ON u.id = c.contact_id
                         WHERE c.user_id = ? ORDER BY u.first_name, u.last_name');
    $st->execute([$me['uid']]);
    $out = [];
    foreach ($st->fetchAll() as $u) {
        $pub = user_public($u);
        $pub['is_contact'] = true;
        $out[] = $pub;
    }
    ok(['contacts' => $out]);
}

case 'add': {
    $me = me();
    $uid = pint('user_id');
    $username = trim(p('username') ?? '');
    $phone = preg_replace('/[^0-9+]/', '', (string)(p('phone') ?? ''));
    $target = null;
    if ($uid) $target = user_by_id($uid);
    else if ($username !== '') {
        $st = db()->prepare('SELECT *, id AS uid FROM users WHERE LOWER(username) = LOWER(?) LIMIT 1');
        $st->execute([ltrim($username, '@')]);
        $target = $st->fetch();
    } else if ($phone !== '') {
        $st = db()->prepare('SELECT *, id AS uid FROM users WHERE phone = ? LIMIT 1');
        $st->execute([$phone]);
        $target = $st->fetch();
        if (!$target) fail('not_found', 'No TelX user with this phone number');
    }
    if (!$target) fail('not_found', 'User not found');
    $tid = (int)$target['uid'];
    if ($tid === (int)$me['uid']) fail('invalid', 'Cannot add yourself');
    if (is_blocked($tid, (int)$me['uid'])) fail('blocked', 'This user blocked you');
    db()->prepare('INSERT IGNORE INTO contacts (user_id, contact_id, created_at) VALUES (?,?,UTC_TIMESTAMP())')
        ->execute([$me['uid'], $tid]);
    emit($tid, 'contact_added', ['user' => user_public(user_by_id((int)$me['uid']))], null, (int)$me['uid']);
    ok(['user' => user_public($target)]);
}

case 'remove': {
    $me = me();
    $uid = pint('user_id');
    db()->prepare('DELETE FROM contacts WHERE user_id = ? AND contact_id = ?')->execute([$me['uid'], $uid]);
    ok(['removed' => $uid]);
}

case 'block': {
    $me = me();
    $uid = pint('user_id');
    if ($uid === (int)$me['uid']) fail('invalid', 'Cannot block yourself');
    $st = db()->prepare('SELECT id FROM users WHERE id = ?'); $st->execute([$uid]);
    if (!$st->fetch()) fail('not_found', 'User not found');
    db()->prepare('INSERT IGNORE INTO blocks (user_id, blocked_id, created_at) VALUES (?,?,UTC_TIMESTAMP())')
        ->execute([$me['uid'], $uid]);
    db()->prepare('DELETE FROM contacts WHERE user_id = ? AND contact_id = ?')->execute([$me['uid'], $uid]);
    ok(['blocked' => $uid]);
}

case 'unblock': {
    $me = me();
    $uid = pint('user_id');
    db()->prepare('DELETE FROM blocks WHERE user_id = ? AND blocked_id = ?')->execute([$me['uid'], $uid]);
    ok(['unblocked' => $uid]);
}

case 'blocked_list': {
    $me = me();
    $st = db()->prepare('SELECT u.*, u.id AS uid FROM blocks b JOIN users u ON u.id = b.blocked_id WHERE b.user_id = ?');
    $st->execute([$me['uid']]);
    ok(['blocked' => array_map(fn($u) => user_public($u), $st->fetchAll())]);
}

case 'search_users': {
    require_auth();
    $q = trim(p('q') ?? '');
    if ($q === '') ok(['users' => []]);
    $like = '%' . $q . '%';
    $phoneq = preg_replace('/[^0-9+]/', '', $q);
    $st = db()->prepare("SELECT *, id AS uid FROM users
                          WHERE (LOWER(username) LIKE LOWER(?) OR CONCAT(first_name,' ',last_name) LIKE ?" 
                          . ($phoneq !== '' ? ' OR phone LIKE ?' : '') . ")
                          AND id <> ? LIMIT 30");
    $params = [$like, $like];
    if ($phoneq !== '') $params[] = '%' . $phoneq . '%';
    $params[] = me_id();
    $st->execute($params);
    $me_id_ = me_id();
    $out = [];
    foreach ($st->fetchAll() as $u) {
        $pub = user_public($u);
        $c = db()->prepare('SELECT id FROM contacts WHERE user_id = ? AND contact_id = ?');
        $c->execute([$me_id_, (int)$u['uid']]);
        $pub['is_contact'] = (bool)$c->fetch();
        $out[] = $pub;
    }
    ok(['users' => $out]);
}

default: fail('unknown_action', 'Unknown contacts action');
}
