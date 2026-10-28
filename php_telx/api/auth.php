<?php
/* TelX API — auth.php : register, login, 2FA, forgot/reset, sessions, account deletion */
define('TELX_API', 1);
require __DIR__ . '/../config.php';

$action = p('action') ?? '';

switch ($action) {

case 'check_username': {
    $u = trim(p('username') ?? '');
    if ($u === '') ok(['available' => false, 'reason' => 'empty']);
    if (!valid_username($u)) ok(['available' => false, 'reason' => 'invalid']);
    ok(['available' => !username_taken($u), 'reason' => username_taken($u) ? 'taken' : null]);
}

case 'register': {
    $first = trim(p('first') ?? ''); $last = trim(p('last') ?? '');
    $u  = trim(p('username') ?? ''); $email = trim(p('email') ?? '');
    $pass = (string)(p('password') ?? ''); $device = p('device') ?? 'Android';
    if (mb_strlen($first) < 2 || mb_strlen($first) > 40) fail('invalid', 'First name must be 2-40 chars');
    if (mb_strlen($last) > 40) fail('invalid', 'Last name too long');
    if (!valid_username($u)) fail('invalid', 'Username: 5-32 chars, a-z A-Z 0-9 _ only');
    if (username_taken($u)) fail('username_taken', 'This username is already taken');
    if (!filter_var($email, FILTER_VALIDATE_EMAIL)) fail('invalid', 'Invalid email address');
    $st = db()->prepare('SELECT id FROM users WHERE LOWER(email) = LOWER(?)'); $st->execute([$email]);
    if ($st->fetch()) fail('email_taken', 'This email is already registered');
    if (strlen($pass) < 6) fail('invalid', 'Password must be at least 6 characters');
    db()->prepare('INSERT INTO users (first_name, last_name, username, email, password_hash, last_seen, created_at)
                   VALUES (?,?,?,?,?,UTC_TIMESTAMP(),UTC_TIMESTAMP())')
        ->execute([$first, $last, $u, $email, password_hash($pass, PASSWORD_DEFAULT)]);
    $uid = (int)db()->lastInsertId();
    ensure_saved_chat($uid);
    $s = create_session($uid, $device);
    $user = user_by_id($uid);
    ok(['token' => $s['token'], 'user' => user_public($user, true)]);
}

case 'login': {
    $idfield = trim(p('username') ?? ''); $pass = (string)(p('password') ?? '');
    $device = p('device') ?? 'Android';
    $st = db()->prepare('SELECT *, id AS uid FROM users WHERE LOWER(username) = LOWER(?) OR LOWER(email) = LOWER(?) LIMIT 1');
    $st->execute([$idfield, $idfield]);
    $u = $st->fetch();
    if (!$u || !password_verify($pass, $u['password_hash'])) fail('credentials', 'Wrong username or password');
    if ((int)$u['twofa_enabled'] === 1) {
        $c = store_code((int)$u['uid'], 'twofa', 600);
        $sent = send_mail($u['email'], 'TelX login code',
            "<p>Your TelX verification code is: <b style=\"font-size:20px\">{$c['code']}</b></p><p>It expires in 10 minutes. If you did not try to log in, change your password immediately.</p>");
        if (!$sent) fail('mail_failed', 'Could not send 2FA email. Contact support.');
        ok(['needs2fa' => true]);
    }
    $s = create_session((int)$u['uid'], $device);
    ok(['token' => $s['token'], 'user' => user_public($u, true)]);
}

case 'verify2fa': {
    $idfield = trim(p('username') ?? ''); $pass = (string)(p('password') ?? '');
    $code = trim(p('code') ?? ''); $device = p('device') ?? 'Android';
    $st = db()->prepare('SELECT *, id AS uid FROM users WHERE LOWER(username) = LOWER(?) OR LOWER(email) = LOWER(?) LIMIT 1');
    $st->execute([$idfield, $idfield]);
    $u = $st->fetch();
    if (!$u || !password_verify($pass, $u['password_hash'])) fail('credentials', 'Wrong username or password');
    if ((int)$u['twofa_enabled'] !== 1) fail('invalid', '2FA is not enabled');
    if (!check_code((int)$u['uid'], 'twofa', $code)) fail('code', 'Invalid or expired code');
    $s = create_session((int)$u['uid'], $device);
    ok(['token' => $s['token'], 'user' => user_public($u, true)]);
}

case 'logout': {
    require_auth();
    $token = bearer();
    db()->prepare('DELETE FROM sessions WHERE token_hash = ?')->execute([hash('sha256', $token)]);
    ok();
}

case 'set2fa': {
    $me = me(); $pass = (string)(p('password') ?? ''); $enable = pint('enabled') === 1;
    if (!password_verify($pass, $me['password_hash'])) fail('credentials', 'Wrong password');
    db()->prepare('UPDATE users SET twofa_enabled = ? WHERE id = ?')->execute([$enable ? 1 : 0, $me['uid']]);
    ok(['twofa' => $enable]);
}

case 'forgot': {
    $idfield = trim(p('username') ?? '');
    $st = db()->prepare('SELECT *, id AS uid FROM users WHERE LOWER(username) = LOWER(?) OR LOWER(email) = LOWER(?) LIMIT 1');
    $st->execute([$idfield, $idfield]);
    $u = $st->fetch();
    if ($u) {
        $c = store_code((int)$u['uid'], 'reset', 900);
        send_mail($u['email'], 'TelX password reset',
            "<p>Reset code: <b style=\"font-size:20px\">{$c['code']}</b></p><p>Expires in 15 minutes.</p>");
    }
    ok(['sent' => true]); // never reveal if account exists
}

case 'reset': {
    $idfield = trim(p('username') ?? ''); $code = trim(p('code') ?? '');
    $new = (string)(p('new_password') ?? '');
    if (strlen($new) < 6) fail('invalid', 'Password must be at least 6 characters');
    $st = db()->prepare('SELECT *, id AS uid FROM users WHERE LOWER(username) = LOWER(?) OR LOWER(email) = LOWER(?) LIMIT 1');
    $st->execute([$idfield, $idfield]);
    $u = $st->fetch();
    if (!$u || !check_code((int)$u['uid'], 'reset', $code)) fail('code', 'Invalid or expired code');
    db()->prepare('UPDATE users SET password_hash = ? WHERE id = ?')->execute([password_hash($new, PASSWORD_DEFAULT), $u['uid']]);
    db()->prepare('DELETE FROM sessions WHERE user_id = ?')->execute([$u['uid']]);
    ok(['reset' => true]);
}

case 'change_password': {
    $me = me(); $old = (string)(p('old') ?? ''); $new = (string)(p('new') ?? '');
    if (!password_verify($old, $me['password_hash'])) fail('credentials', 'Wrong current password');
    if (strlen($new) < 6) fail('invalid', 'Password must be at least 6 characters');
    db()->prepare('UPDATE users SET password_hash = ? WHERE id = ?')->execute([password_hash($new, PASSWORD_DEFAULT), $me['uid']]);
    ok();
}

case 'sessions': {
    $me = me();
    $st = db()->prepare('SELECT * FROM sessions WHERE user_id = ? ORDER BY last_active DESC');
    $st->execute([$me['uid']]);
    $token = bearer();
    $cur = hash('sha256', $token);
    $out = [];
    foreach ($st->fetchAll() as $s) {
        $out[] = ['id' => (int)$s['id'], 'device' => $s['device'], 'ip' => $s['ip'],
                  'current' => $s['token_hash'] === $cur,
                  'created_ts' => ts($s['created_at']), 'last_ts' => ts($s['last_active'])];
    }
    ok(['sessions' => $out]);
}

case 'revoke_session': {
    $me = me(); $sid = pint('session_id');
    $st = db()->prepare('SELECT * FROM sessions WHERE id = ? AND user_id = ?');
    $st->execute([$sid, $me['uid']]);
    $s = $st->fetch();
    if (!$s) fail('not_found', 'Session not found');
    if ($s['token_hash'] === hash('sha256', bearer())) fail('invalid', 'Use logout for the current session');
    db()->prepare('DELETE FROM sessions WHERE id = ?')->execute([$sid]);
    ok(['revoked' => $sid]);
}

case 'delete_account': {
    $me = me(); $pass = (string)(p('password') ?? '');
    if (!password_verify($pass, $me['password_hash'])) fail('credentials', 'Wrong password');
    $uid = (int)$me['uid'];
    // snapshot private chats to remove entirely
    $st = db()->prepare("SELECT c.id FROM chats c JOIN chat_members m ON m.chat_id = c.id WHERE m.user_id = ? AND c.type IN ('private','saved')");
    $st->execute([$uid]);
    $privateChats = array_map(fn($r) => (int)$r['id'], $st->fetchAll());
    // notify private partners
    foreach ($privateChats as $cid) {
        $others = chat_member_ids($cid, $uid);
        if ($others) emit($others, 'chat_deleted', ['chat_id' => $cid], $cid, $uid);
    }
    // delete uploaded files (best effort)
    foreach (['uploads/avatar', 'uploads/story'] as $d) { /* cleaned by glob below */ }
    $del = db()->prepare("SELECT media FROM stories WHERE user_id = ? UNION SELECT avatar FROM users WHERE id = ?");
    $del->execute([$uid, $uid]);
    foreach ($del->fetchAll() as $r) {
        if (!empty($r['media'])) { $f = UPLOAD_DIR . str_replace('uploads', '', $r['media']); if (is_file($f)) @unlink($f); }
    }
    // messages: keep history in groups but detach sender
    db()->prepare('UPDATE messages SET sender_id = NULL WHERE sender_id = ?')->execute([$uid]);
    // groups where I'm the only member → delete; groups where I'm admin → transfer admin or drop
    $st = db()->prepare("SELECT chat_id FROM chat_members WHERE user_id = ?");
    $st->execute([$uid]);
    $myChats = array_map(fn($r) => (int)$r['chat_id'], $st->fetchAll());
    foreach ($myChats as $cid) {
        $others = chat_member_ids($cid, $uid);
        if (!$others) { db()->prepare('DELETE FROM chats WHERE id = ?')->execute([$cid]); continue; }
        $chat = chat_of($cid);
        if ($chat && $chat['type'] === 'group') {
            db()->prepare("UPDATE chat_members SET role = 'admin' WHERE chat_id = ? AND user_id = ? ORDER BY id LIMIT 1")->execute([$cid, $others[0]]);
        }
    }
    foreach ($privateChats as $cid) db()->prepare('DELETE FROM chats WHERE id = ?')->execute([$cid]);
    // user row cascades: sessions, contacts, blocks, chat_members, reactions, story_views, secret_keys, verification_codes
    db()->prepare('DELETE FROM stories WHERE user_id = ?')->execute([$uid]);
    db()->prepare('DELETE FROM users WHERE id = ?')->execute([$uid]);
    ok(['deleted' => true]);
}

default: fail('unknown_action', 'Unknown auth action');
}
