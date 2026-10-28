<?php
/* TelX API — profile.php : view/update profile, secret keys, blocks */
define('TELX_API', 1);
require __DIR__ . '/../config.php';

$action = p('action') ?? '';

switch ($action) {

case 'get': {
    require_auth();
    $uid = pint('user_id');
    $u = $uid ? user_by_id($uid) : user_by_id(me_id());
    if (!$u) fail('not_found', 'User not found');
    $is_me = (int)$u['uid'] === me_id();
    $out = user_public($u, $is_me);
    $out['is_me'] = $is_me;
    $st = db()->prepare('SELECT id FROM stories WHERE user_id = ? AND expires_at > UTC_TIMESTAMP() LIMIT 1');
    $st->execute([(int)$u['uid']]);
    $out['has_story'] = (bool)$st->fetch();
    if (!$is_me) {
        $me_id_ = me_id();
        $st = db()->prepare('SELECT id FROM contacts WHERE user_id = ? AND contact_id = ?');
        $st->execute([$me_id_, (int)$u['uid']]);
        $out['is_contact'] = (bool)$st->fetch();
        $out['blocked_by_me'] = is_blocked($me_id_, (int)$u['uid']);
        $out['blocked_me'] = is_blocked((int)$u['uid'], $me_id_);
    }
    ok(['user' => $out]);
}

case 'update': {
    $me = me();
    $first = trim(p('first') ?? ''); $last = trim(p('last') ?? '');
    $bio = trim(p('bio') ?? ''); $username = trim(p('username') ?? '');
    $privacy = pint('privacy_lock');
    $avatarRel = p('avatar_rel');   // null = not provided, '' = remove
    $updates = [];
    if ($first !== '') { if (mb_strlen($first) < 2 || mb_strlen($first) > 40) fail('invalid', 'First name must be 2-40 chars'); $updates['first_name'] = $first; }
    if ($last !== null) { if (mb_strlen($last) > 40) fail('invalid', 'Last name too long'); $updates['last_name'] = $last; }
    if ($bio !== '') { if (mb_strlen($bio) > 200) fail('invalid', 'Bio too long (max 200)'); $updates['bio'] = $bio; }
    if ($username !== '') {
        if (!valid_username($username)) fail('invalid', 'Username: 5-32 chars, a-z A-Z 0-9 _');
        if (mb_strtolower($username) !== mb_strtolower($me['username']) && username_taken($username)) fail('username_taken', 'Username taken');
        $updates['username'] = $username;
    }
    if ($avatarRel !== null) $updates['avatar'] = ($avatarRel === '' ? null : $avatarRel);
    $updates['privacy_lock'] = $privacy ? 1 : 0;
    $set = implode(',', array_map(fn($k) => "$k = ?", array_keys($updates)));
    $st = db()->prepare("UPDATE users SET $set WHERE id = ?");
    $st->execute([...array_values($updates), $me['uid']]);
    emit(contact_ids_of((int)$me['uid']), 'profile_updated', ['user_id' => (int)$me['uid']], null, (int)$me['uid']);
    ok(['user' => user_public(user_by_id((int)$me['uid']), true)]);
}

case 'set_secret_key': {
    $me = me();
    $pub = (string)(p('pubkey') ?? '');
    if (strlen($pub) < 40 || strlen($pub) > 3000) fail('invalid', 'Bad key');
    db()->prepare('INSERT INTO secret_keys (user_id, pubkey, updated_at) VALUES (?,?,UTC_TIMESTAMP())
                   ON DUPLICATE KEY UPDATE pubkey = VALUES(pubkey), updated_at = UTC_TIMESTAMP()')
        ->execute([$me['uid'], $pub]);
    ok();
}

case 'get_secret_key': {
    require_auth();
    $uid = pint('user_id');
    $st = db()->prepare('SELECT pubkey FROM secret_keys WHERE user_id = ?');
    $st->execute([$uid]);
    $r = $st->fetch();
    ok(['pubkey' => $r ? $r['pubkey'] : null]);
}

default: fail('unknown_action', 'Unknown profile action');
}
