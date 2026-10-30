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
    // v6 badges + self-destruct timer
    $out['verified'] = (int)($u['verified'] ?? 0) === 1;
    $out['scam'] = (int)($u['scam'] ?? 0) === 1;
    if ($is_me) $out['self_destruct_months'] = (int)($u['self_destruct_months'] ?? 0);
    // v7: profile music + default profile tab
    $out['profile_music'] = !empty($u['profile_music']) ? absolute_url($u['profile_music']) : null;
    $out['profile_music_title'] = (string)($u['profile_music_title'] ?? '');
    $out['profile_tab'] = (int)($u['profile_tab'] ?? 0);
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
    // phone visible only per privacy setting (always visible to self)
    $phoneVis = (int)($u['privacy_phone'] ?? 0);
    $isContact = !empty($out['is_contact']);
    $out['phone'] = ($is_me || $phoneVis === 0 || ($phoneVis === 1 && $isContact)) ? (string)($u['phone'] ?? '') : '';
    if (!$is_me) {
        $out['privacy_last_seen'] = (int)($u['privacy_last_seen'] ?? 0);
        // enforce last-seen / photo privacy server-side
        $pLS = (int)($u['privacy_last_seen'] ?? 0);
        if ($pLS === 2 || ($pLS === 1 && !$isContact)) { $out['online'] = false; $out['last_ts'] = 0; }
        $pPh = (int)($u['privacy_photo'] ?? 0);
        if ($pPh === 2 || ($pPh === 1 && !$isContact)) $out['avatar'] = null;
    }
    ok(['user' => $out]);
}

case 'update': {
    $me = me();
    $first = trim(p('first') ?? ''); $last = trim(p('last') ?? '');
    $bio = trim(p('bio') ?? ''); $username = trim(p('username') ?? '');
    $privacy = pint('privacy_lock');
    $avatarRel = p('avatar_rel');   // null = not provided, '' = remove
    $phone = p('phone');
    $pLastSeen = p('privacy_last_seen');
    $pPhone = p('privacy_phone');
    $pPhoto = p('privacy_photo');
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
    if ($phone !== null) {
        $phone = preg_replace('/[^0-9+]/', '', $phone);
        if ($phone === '') $updates['phone'] = null;
        else {
            if (strlen($phone) < 7 || strlen($phone) > 20) fail('invalid', 'Phone number looks invalid');
            $chk = db()->prepare('SELECT id FROM users WHERE phone = ? AND id <> ?');
            $chk->execute([$phone, $me['uid']]);
            if ($chk->fetch()) fail('phone_taken', 'This phone number is already used');
            $updates['phone'] = $phone;
        }
    }
    if ($pLastSeen !== null) $updates['privacy_last_seen'] = max(0, min(2, (int)$pLastSeen));
    if ($pPhone !== null)    $updates['privacy_phone'] = max(0, min(2, (int)$pPhone));
    if ($pPhoto !== null)     $updates['privacy_photo'] = max(0, min(2, (int)$pPhoto));
    // v7: profile music (audio track on the profile) + default profile tab
    $musicRel = p('profile_music');
    if ($musicRel !== null) $updates['profile_music'] = ($musicRel === '' ? null : $musicRel);
    $musicTitle = p('profile_music_title');
    if ($musicTitle !== null) $updates['profile_music_title'] = mb_substr($musicTitle, 0, 96);
    $pTab = p('profile_tab');
    if ($pTab !== null) $updates['profile_tab'] = max(0, min(1, (int)$pTab));
    $set = implode(',', array_map(fn($k) => "$k = ?", array_keys($updates)));
    $st = db()->prepare("UPDATE users SET $set WHERE id = ?");
    $st->execute([...array_values($updates), $me['uid']]);
    emit(contact_ids_of((int)$me['uid']), 'profile_updated', ['user_id' => (int)$me['uid']], null, (int)$me['uid']);
    $u2 = user_by_id((int)$me['uid']);
    $out2 = user_public($u2, true);
    $out2['profile_music'] = !empty($u2['profile_music']) ? absolute_url($u2['profile_music']) : null;
    $out2['profile_music_title'] = (string)($u2['profile_music_title'] ?? '');
    $out2['profile_tab'] = (int)($u2['profile_tab'] ?? 0);
    ok(['user' => $out2]);
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
