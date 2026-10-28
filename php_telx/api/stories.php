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
    $st = db()->prepare('INSERT INTO stories (user_id, media, thumb, media_type, duration, created_at, expires_at)
                         VALUES (?,?,?,?,?, UTC_TIMESTAMP(), UTC_TIMESTAMP() + INTERVAL ? SECOND)');
    $st->execute([$me['uid'], $mediaRel, $thumbRel, $type, $duration, STORY_TTL]);
    $sid = (int)db()->lastInsertId();
    emit(contact_ids_of((int)$me['uid']), 'story_new', ['story_id' => $sid, 'user' => user_public(user_by_id((int)$me['uid']))], null, (int)$me['uid']);
    ok(['story_id' => $sid]);
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
        $out[] = [
            'id' => (int)$s['id'], 'media' => absolute_url($s['media']),
            'thumb' => $s['thumb'] ? absolute_url($s['thumb']) : null,
            'type' => $s['media_type'], 'duration' => (int)$s['duration'],
            'ts' => ts($s['created_at']), 'views' => (int)$cnt->fetch()['c'],
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
