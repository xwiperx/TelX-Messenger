<?php
/* TelX API — reports.php : report spam/users/chats (v6).
   5 distinct reports on a user auto-flags the account as "Scam".
   Verified badges are set server-side by the operator (SQL). */
define('TELX_API', 1);
require __DIR__ . '/../config.php';

$action = p('action') ?? '';

/** admin ids of a chat (defined in chats.php too — guarded for standalone use). */
if (!function_exists('chat_admin_ids')) {
    function chat_admin_ids(int $cid, int $exclude = 0): array {
        $st = db()->prepare("SELECT user_id FROM chat_members WHERE chat_id = ? AND role = 'admin'");
        $st->execute([$cid]);
        $r = [];
        foreach ($st->fetchAll() as $row) { $i = (int)$row['user_id']; if ($i !== $exclude) $r[] = $i; }
        return $r;
    }
}

switch ($action) {

case 'report': {
    $me = me();
    $type = p('target_type');
    if (!in_array($type, ['user', 'chat'], true)) fail('invalid', 'target_type must be user or chat');
    $tid = pint('target_id');
    if ($tid <= 0) fail('invalid', 'target_id required');
    $reason = mb_substr(trim((string)(p('reason') ?? '')), 0, 200);
    if ($reason === '') fail('invalid', 'Pick or type a reason');
    if ($type === 'user' && $tid === (int)$me['uid']) fail('invalid', 'Cannot report yourself');

    db()->prepare('INSERT IGNORE INTO reports (reporter_id, target_type, target_id, reason, created_at)
                   VALUES (?,?,?,?,UTC_TIMESTAMP())')
        ->execute([$me['uid'], $type, $tid, $reason]);

    // auto "Scam" flag after 5 distinct reporters on a user
    if ($type === 'user') {
        $st = db()->prepare('SELECT COUNT(DISTINCT reporter_id) c FROM reports WHERE target_type = ? AND target_id = ?');
        $st->execute(['user', $tid]);
        if ((int)$st->fetch()['c'] >= 5) {
            db()->prepare('UPDATE users SET scam = 1 WHERE id = ? AND verified = 0')->execute([$tid]);
        }
    } else {
        // notify the admins of the reported group/channel
        $chat = chat_of($tid);
        if ($chat) emit(chat_admin_ids($tid), 'chat_reported',
            ['chat_id' => $tid, 'by' => (int)$me['uid'], 'reason' => $reason], $tid, (int)$me['uid']);
    }
    ok(['reported' => true]);
}

default: fail('unknown_action', 'Unknown reports action');
}
