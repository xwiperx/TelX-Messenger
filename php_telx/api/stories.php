<?php
/* TelX API — stories.php : feed, upload, view, viewers, delete */
define('TELX_API', 1);
require __DIR__ . '/../config.php';

$action = p('action') ?? '';

switch ($action) {

case 'feed': {
    $me = me();
    flush_due();
    $uid = (int)$me['uid'];
    $ids = array_values(array_unique(array_merge([$uid], contact_ids_of($uid))));
    $in = implode(',', array_fill(0, count($ids), '?'));
    $st = db()->prepare("SELECT s.*, u.first_name, u.last_name, u.username, u.avatar, u.id AS uid
                         FROM stories s JOIN users u ON u.id = s.user_id
                         WHERE s.user_id IN ($in) AND s.expires_at > UTC_TIMESTAMP()
                         ORDER BY s.user_id, s.id");
    $st->execute($ids);
    $byUser = [];
    $viewedMap = [];
    foreach ($st->fetchAll() as $row) {
        $sid = (int)$row['id']; $owner = (int)$row['user_id'];
        if (!isset($viewedMap[$owner])) $viewedMap[$owner] = [];
        $viewedMap[$owner][$sid] = null;
        $byUser[$owner]['user'] = [
            'id' => $owner, 'name' => trim($row['first_name'] . ' ' . $row['last_name']),
            'username' => $row['username'],
            'avatar' => $row['avatar'] ? absolute_url($row['avatar']) : null,
        ];
        $byUser[$owner]['stories'][] = [
            'id' => $sid,
            'media' => absolute_url($row['media']),
            'thumb' => $row['thumb'] ? absolute_url($row['thumb']) : null,
            'type' => $row['media_type'],
            'duration' => (int)$row['duration'],
            'ts' => ts($row['created_at']),
            // v7: live stories + thematic albums
            'is_live' => (int)($row['is_live'] ?? 0) === 1,
            'album' => !empty($row['album']) ? (string)$row['album'] : null,
        ];
    }
    // which ones has viewer seen?
    if ($byUser) {
        $allIds = [];
        foreach ($byUser as $ownerId => $g) foreach ($g['stories'] as $s) $allIds[] = $s['id'];
        $inS = implode(',', array_fill(0, count($allIds), '?'));
        $vs = db()->prepare("SELECT story_id FROM story_views WHERE user_id = ? AND story_id IN ($inS)");
        $vs->execute([$uid, ...$allIds]);
        $seen = [];
        foreach ($vs->fetchAll() as $r) $seen[(int)$r['story_id']] = true;
        $out = [];
        foreach ($byUser as $ownerId => $group) {
            $allSeen = true; $latest = 0;
            foreach ($group['stories'] as $s) {
                if (empty($seen[$s['id']])) $allSeen = false;
                $latest = max($latest, $s['ts']);
            }
            $group['user']['is_me'] = $ownerId === $uid;
            $group['seen'] = $allSeen;
            $group['latest_ts'] = $latest;
            $out[] = $group;
        }
        usort($out, function ($a, $b) use ($uid) {
            if ($a['user']['is_me'] !== $b['user']['is_me']) return $a['user']['is_me'] ? -1 : 1;
            if ($a['seen'] !== $b['seen']) return $a['seen'] ? 1 : -1;
            return $b['latest_ts'] <=> $a['latest_ts'];
        });
        ok(['feed' => $out]);
    }
    ok(['feed' => []]);
}

case 'upload': {
    $me = me();
    $mediaRel = p('media_rel');
    $thumbRel = p('thumb_rel');
    $type = p('media_type') === 'video' ? 'video' : 'photo';
    $duration = max(5, min(60, pint('duration', 15)));
    if (!$mediaRel) fail('invalid', 'media required');
    // v7: live story flag + thematic album name
    $isLive = pint('is_live') === 1 ? 1 : 0;
    $album = trim((string)(p('album') ?? ''));
    if ($album !== '') $album = mb_substr($album, 0, 48);
    else $album = null;
    $ttl = $isLive ? 4 * 3600 : STORY_TTL;   // live stories last up to 4h
    $st = db()->prepare('INSERT INTO stories (user_id, media, thumb, media_type, duration, is_live, album, created_at, expires_at)
                         VALUES (?,?,?,?,?,?,?,?, UTC_TIMESTAMP() + INTERVAL ? SECOND)');
    $st->execute([$me['uid'], $mediaRel, $thumbRel, $type, $duration, $isLive, $album, $ttl]);
    $sid = (int)db()->lastInsertId();
    emit(contact_ids_of((int)$me['uid']), 'story_new', ['story_id' => $sid, 'user' => user_public(user_by_id((int)$me['uid'])), 'is_live' => $isLive], null, (int)$me['uid']);
    ok(['story_id' => $sid, 'is_live' => $isLive]);
}

/* v7: owner ends an ongoing live story. */
case 'end_live': {
    $me = me();
    $sid = pint('story_id');
    $st = db()->prepare('SELECT * FROM stories WHERE id = ?');
    $st->execute([$sid]);
    $s = $st->fetch();
    if (!$s || (int)$s['user_id'] !== (int)$me['uid']) fail('forbidden', 'Not your story');
    db()->prepare('UPDATE stories SET is_live = 0, live_end = UTC_TIMESTAMP(), expires_at = UTC_TIMESTAMP() WHERE id = ?')
        ->execute([$sid]);
    emit(contact_ids_of((int)$me['uid']), 'story_deleted', ['story_id' => $sid], null, (int)$me['uid']);
    ok(['ended' => $sid]);
}

/* v7: live-chat replies to a story (viewers → owner thread). */
case 'reply': {
    $me = me();
    $sid = pint('story_id');
    $text = trim((string)(p('text') ?? ''));
    if ($text === '') fail('invalid', 'Empty reply');
    if (mb_strlen($text) > 500) $text = mb_substr($text, 0, 500);
    $st = db()->prepare('SELECT * FROM stories WHERE id = ? AND expires_at > UTC_TIMESTAMP()');
    $st->execute([$sid]);
    $s = $st->fetch();
    if (!$s) fail('not_found', 'Story not found');
    $owner = (int)$s['user_id'];
    if ($owner !== (int)$me['uid'] && !in_array($owner, contact_ids_of((int)$me['uid']), true))
        fail('forbidden', 'Not a contact');
    db()->prepare('INSERT INTO story_replies (story_id, user_id, text, created_at) VALUES (?,?,?,UTC_TIMESTAMP())')
        ->execute([$sid, $me['uid'], $text]);
    emit($owner, 'story_reply',
        ['story_id' => $sid, 'text' => $text, 'from' => user_public(user_by_id((int)$me['uid'])),
         'is_live' => (int)$s['is_live'] === 1], null, (int)$me['uid']);
    ok(['replied' => $sid]);
}

/* v7: list the replies of my story (owner sees everything live). */
case 'replies': {
    $me = me();
    $sid = pint('story_id');
    $st = db()->prepare('SELECT * FROM stories WHERE id = ?');
    $st->execute([$sid]);
    $s = $st->fetch();
    if (!$s) fail('not_found', 'Story not found');
    $owner = (int)$s['user_id'];
    if ($owner !== (int)$me['uid']) fail('forbidden', 'Only the owner can read replies');
    $rs = db()->prepare('SELECT r.*, u.*, u.id AS uid FROM story_replies r JOIN users u ON u.id = r.user_id WHERE r.story_id = ? ORDER BY r.id');
    $rs->execute([$sid]);
    $out = [];
    foreach ($rs->fetchAll() as $r) {
        $out[] = ['user' => user_public($r), 'text' => (string)$r['text'], 'ts' => ts($r['created_at'])];
    }
    ok(['replies' => $out]);
}

case 'view': {
    $me = me();
    $sid = pint('story_id');
    $st = db()->prepare('SELECT * FROM stories WHERE id = ?');
    $st->execute([$sid]);
    $s = $st->fetch();
    if (!$s) fail('not_found', 'Story not found');
    $owner = (int)$s['user_id'];
    $uid = (int)$me['uid'];
    if ($owner !== $uid && !in_array($owner, contact_ids_of($uid), true)) fail('forbidden', 'Not a contact');
    db()->prepare('INSERT IGNORE INTO story_views (story_id, user_id, viewed_at) VALUES (?,?,UTC_TIMESTAMP())')
        ->execute([$sid, $uid]);
    if ($owner !== $uid) emit($owner, 'story_viewed', ['story_id' => $sid, 'viewer' => user_public(user_by_id($uid))], null, $uid);
    ok();
}

case 'viewers': {
    $me = me();
    $sid = pint('story_id');
    $st = db()->prepare('SELECT * FROM stories WHERE id = ?');
    $st->execute([$sid]);
    $s = $st->fetch();
    if (!$s || (int)$s['user_id'] !== (int)$me['uid']) fail('forbidden', 'Only the owner can see viewers');
    $vs = db()->prepare('SELECT u.*, v.viewed_at, u.id AS uid FROM story_views v JOIN users u ON u.id = v.user_id
                         WHERE v.story_id = ? ORDER BY v.viewed_at DESC');
    $vs->execute([$sid]);
    $out = [];
    foreach ($vs->fetchAll() as $r) {
        $pub = user_public($r);
        $pub['viewed_ts'] = ts($r['viewed_at']);
        $out[] = $pub;
    }
    ok(['viewers' => $out]);
}

case 'mine': {
    $me = me();
    $st = db()->prepare('SELECT * FROM stories WHERE user_id = ? AND expires_at > UTC_TIMESTAMP() ORDER BY id');
    $st->execute([$me['uid']]);
    $out = [];
    foreach ($st->fetchAll() as $s) {
        $cnt = db()->prepare('SELECT COUNT(*) c FROM story_views WHERE story_id = ?');
        $cnt->execute([(int)$s['id']]);
        $rcnt = db()->prepare('SELECT COUNT(*) c FROM story_replies WHERE story_id = ?');
        $rcnt->execute([(int)$s['id']]);
        $out[] = [
            'id' => (int)$s['id'], 'media' => absolute_url($s['media']),
            'thumb' => $s['thumb'] ? absolute_url($s['thumb']) : null,
            'type' => $s['media_type'], 'duration' => (int)$s['duration'],
            'ts' => ts($s['created_at']), 'views' => (int)$cnt->fetch()['c'],
            'replies' => (int)$rcnt->fetch()['c'],
            'is_live' => (int)($s['is_live'] ?? 0) === 1,
            'album' => !empty($s['album']) ? (string)$s['album'] : null,
        ];
    }
    ok(['stories' => $out]);
}

case 'delete': {
    $me = me();
    $sid = pint('story_id');
    $st = db()->prepare('SELECT * FROM stories WHERE id = ?');
    $st->execute([$sid]);
    $s = $st->fetch();
    if (!$s || (int)$s['user_id'] !== (int)$me['uid']) fail('forbidden', 'Not your story');
    $f = UPLOAD_DIR . str_replace('uploads', '', $s['media']);
    if (is_file($f)) @unlink($f);
    db()->prepare('DELETE FROM stories WHERE id = ?')->execute([$sid]);
    emit(contact_ids_of((int)$me['uid']), 'story_deleted', ['story_id' => $sid], null, (int)$me['uid']);
    ok(['deleted' => $sid]);
}

default: fail('unknown_action', 'Unknown stories action');
}
