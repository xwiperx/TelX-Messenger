<?php
/* TelX API — stickers.php : sticker packs CRUD + list */
define('TELX_API', 1);
require __DIR__ . '/../config.php';

$action = p('action') ?? '';

switch ($action) {

case 'list': {
    // returns: builtin pack (shipped with server) + user's own packs with sticker urls
    $me = me();
    $packs = [];
    $st = db()->prepare("SELECT * FROM sticker_packs WHERE user_id = ? ORDER BY id");
    $st->execute([$me['uid']]);
    foreach ($st->fetchAll() as $p) {
        $s2 = db()->prepare('SELECT * FROM stickers WHERE pack_id = ? ORDER BY id LIMIT 120');
        $s2->execute([(int)$p['id']]);
        $items = [];
        foreach ($s2->fetchAll() as $s) $items[] = absolute_url($s['file']);
        if ($items) $packs[] = ['id' => (int)$p['id'], 'title' => $p['title'], 'mine' => true, 'stickers' => $items];
    }
    // builtin pack: serve every gif/png in uploads/stickers/builtin
    $builtin = [];
    $dir = UPLOAD_DIR . '/stickers/builtin';
    if (is_dir($dir)) {
        foreach (scandir($dir) ?: [] as $f) {
            if (str_ends_with($f, '.png') || str_ends_with($f, '.gif') || str_ends_with($f, '.webp') || str_ends_with($f, '.webm'))
                $builtin[] = absolute_url('uploads/stickers/builtin/' . $f);
        }
        sort($builtin);
    }
    ok(['packs' => $packs, 'builtin' => $builtin]);
}

case 'pack_create': {
    $me = me();
    $title = trim((string)(p('title') ?? ''));
    if (mb_strlen($title) < 1) fail('invalid', 'Pack title required');
    if (mb_strlen($title) > 40) $title = mb_substr($title, 0, 40);
    db()->prepare('INSERT INTO sticker_packs (user_id, title) VALUES (?,?)')->execute([$me['uid'], $title]);
    ok(['pack' => ['id' => (int)db()->lastInsertId(), 'title' => $title]]);
}

case 'sticker_add': {
    $me = me();
    $packId = pint('pack_id');
    $rel = p('file_rel');
    if (!$rel) fail('invalid', 'file_rel required');
    $st = db()->prepare('SELECT id FROM sticker_packs WHERE id = ? AND user_id = ?');
    $st->execute([$packId, $me['uid']]);
    if (!$st->fetch()) fail('forbidden', 'Not your pack');
    $count = db()->prepare('SELECT COUNT(*) c FROM stickers WHERE pack_id = ?');
    $count->execute([$packId]);
    if ((int)$count->fetch()['c'] >= 120) fail('limit', 'Pack is full (120 max)');
    if (!str_starts_with($rel, 'uploads/')) fail('invalid', 'Bad file path');
    db()->prepare('INSERT INTO stickers (pack_id, file) VALUES (?,?)')->execute([$packId, $rel]);
    ok(['added' => 1, 'url' => absolute_url($rel)]);
}

case 'sticker_delete': {
    $me = me();
    $packId = pint('pack_id');
    $url = (string)(p('url') ?? '');
    $st = db()->prepare('SELECT id FROM sticker_packs WHERE id = ? AND user_id = ?');
    $st->execute([$packId, $me['uid']]);
    if (!$st->fetch()) fail('forbidden', 'Not your pack');
    $rel = str_replace(BASE_URL, '', $url);
    db()->prepare('DELETE FROM stickers WHERE pack_id = ? AND file = ?')->execute([$packId, $rel]);
    ok(['deleted' => 1]);
}

case 'pack_delete': {
    $me = me();
    $packId = pint('pack_id');
    $st = db()->prepare('SELECT id FROM sticker_packs WHERE id = ? AND user_id = ?');
    $st->execute([$packId, $me['uid']]);
    if (!$st->fetch()) fail('forbidden', 'Not your pack');
    db()->prepare('DELETE FROM stickers WHERE pack_id = ?')->execute([$packId]);
    db()->prepare('DELETE FROM sticker_packs WHERE id = ?')->execute([$packId]);
    ok(['deleted' => 1]);
}

default: fail('unknown_action', 'Unknown stickers action');
}
