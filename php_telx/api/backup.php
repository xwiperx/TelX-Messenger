<?php
/* TelX API — backup.php : encrypted cloud backup (client-side AES-GCM; server only stores the blob)
   actions: meta | save | get | delete */
define('TELX_API', 1);
require __DIR__ . '/../config.php';

$action = p('action') ?? '';

switch ($action) {

case 'meta': {
    $me = me();
    $st = db()->prepare('SELECT size, created_at FROM backups WHERE user_id = ?');
    $st->execute([(int)$me['uid']]);
    $r = $st->fetch();
    ok(['exists' => (bool)$r,
        'size' => $r ? (int)$r['size'] : 0,
        'created_ts' => $r ? ts($r['created_at']) : null]);
}

case 'save': {
    $me = me();
    $data = p('data') ?? '';
    if ($data === '') fail('invalid', 'Empty backup payload');
    // base64 of an AES-256-GCM blob; cap ~14 MB of ciphertext (MEDIUMTEXT-safe)
    if (strlen($data) > 14 * 1024 * 1024) fail('too_large', 'Backup exceeds 14 MB');
    if (!preg_match('#^[A-Za-z0-9+/=\r\n]+$#', $data)) fail('invalid', 'Payload must be base64');
    $size = (int)strlen($data);
    db()->prepare('INSERT INTO backups (user_id, data, size, created_at) VALUES (?,?,?,UTC_TIMESTAMP())
                   ON DUPLICATE KEY UPDATE data = VALUES(data), size = VALUES(size), created_at = UTC_TIMESTAMP()')
        ->execute([(int)$me['uid'], $data, $size]);
    $st = db()->prepare('SELECT created_at FROM backups WHERE user_id = ?');
    $st->execute([(int)$me['uid']]);
    ok(['saved' => true, 'size' => $size, 'created_ts' => ts($st->fetch()['created_at'])]);
}

case 'get': {
    $me = me();
    $st = db()->prepare('SELECT data, size, created_at FROM backups WHERE user_id = ?');
    $st->execute([(int)$me['uid']]);
    $r = $st->fetch();
    if (!$r) fail('not_found', 'No backup stored');
    ok(['data' => $r['data'], 'size' => (int)$r['size'], 'created_ts' => ts($r['created_at'])]);
}

case 'delete': {
    $me = me();
    db()->prepare('DELETE FROM backups WHERE user_id = ?')->execute([(int)$me['uid']]);
    ok(['deleted' => true]);
}

default: fail('unknown_action', 'Unknown backup action');
}
