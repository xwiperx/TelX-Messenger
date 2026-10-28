<?php
/**
 * TelX — stories.php
 *  GET  → story feed (mine + contacts', grouped by user)
 *  POST {mediaUrl, mediaType, duration, caption?}
 */
require __DIR__ . '/config.php';
$me = require_auth();
$method = $_SERVER['REQUEST_METHOD'];

if ($method === 'GET') {
    $contactIds = array_map(fn($r) => $r['contact_id'], all("SELECT contact_id FROM tx_contacts WHERE user_id = ?", [$me['id']]));
    $ids = array_merge([$me['id']], $contactIds);
    $in = implode(',', array_fill(0, count($ids), '?'));
    $rows = all(
        "SELECT s.*, (SELECT COUNT(*) FROM tx_story_views v WHERE v.story_id = s.id) AS views_count,
                (SELECT COUNT(*) FROM tx_story_views v WHERE v.story_id = s.id AND v.user_id = ?) AS seen_by_me
         FROM tx_stories s WHERE s.user_id IN ($in) AND s.expires_at > NOW() ORDER BY s.created_at ASC",
        array_merge([$me['id']], $ids)
    );

    $groups = [];
    $byUser = [];
    foreach ($rows as $s) {
        $uid = $s['user_id'];
        if (!isset($byUser[$uid])) {
            $u = one("SELECT * FROM tx_users WHERE id = ?", [$uid]);
            $g = ['user' => public_user($u, $me['id']), 'isMine' => $uid === $me['id'], 'items' => [], 'hasUnseen' => false];
            $byUser[$uid] = count($groups);
            $groups[] = $g;
        }
        $seen = (int)$s['seen_by_me'] > 0;
        $groups[$byUser[$uid]]['items'][] = [
            'id' => $s['id'],
            'mediaUrl' => $s['media_url'],
            'mediaType' => $s['media_type'],
            'duration' => (int)$s['duration'],
            'caption' => $s['caption'],
            'createdAt' => $s['created_at'],
            'seenByMe' => $seen,
            'viewsCount' => (int)$s['views_count'],
        ];
        if (!$seen) $groups[$byUser[$uid]]['hasUnseen'] = true;
    }
    // my stories first
    usort($groups, fn($a, $b) => $b['isMine'] <=> $a['isMine']);
    jok(['groups' => $groups]);
}

if ($method === 'POST') {
    $b = body();
    if (empty($b['mediaUrl'])) jerr('رسانه استوری الزامی است');
    $dur = in_array((int)($b['duration'] ?? 15), [15, 30, 45, 60]) ? (int)$b['duration'] : 15;
    $id = cuid();
    q_exec(
        "INSERT INTO tx_stories (id, user_id, media_url, media_type, duration, caption, expires_at)
         VALUES (?,?,?,?,?,?, DATE_ADD(NOW(), INTERVAL ? HOUR))",
        [$id, $me['id'], $b['mediaUrl'], $b['mediaType'] === 'video' ? 'video' : 'image',
         $dur, mb_substr($b['caption'] ?? '', 0, 200), STORY_LIFETIME_HOURS]
    );
    jok(['story' => ['id' => $id], 'message' => 'استوری منتشر شد']);
}

jerr('متد پشتیبانی نمی‌شود', 405);
