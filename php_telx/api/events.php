<?php
/* TelX API — events.php : long-polling gateway (instant updates) */
define('TELX_API', 1);
require __DIR__ . '/../config.php';

@set_time_limit(0);
require_auth();
flush_due();

$uid = me_id();
$since = max(0, (int)(p('since_id') ?? '0'));

// trim old events for this user (keep table small)
if (random_int(1, 20) === 1) {
    db()->prepare('DELETE FROM events WHERE user_id = ? AND created_at < UTC_TIMESTAMP() - INTERVAL 2 DAY')->execute([$uid]);
}

$deadline = time() + LONGPOLL_TIMEOUT;
$events = [];
$lastDelivered = 0;

while (true) {
    $st = db()->prepare('SELECT * FROM events WHERE user_id = ? AND id > ? ORDER BY id LIMIT 60');
    $st->execute([$uid, $since]);
    $rows = $st->fetchAll();
    if ($rows) {
        foreach ($rows as $r) {
            $since = max($since, (int)$r['id']);
            $events[] = [
                'id' => (int)$r['id'],
                'type' => $r['type'],
                'chat_id' => $r['chat_id'] !== null ? (int)$r['chat_id'] : null,
                'from_user' => $r['from_user'] !== null ? (int)$r['from_user'] : null,
                'data' => $r['payload'] ? json_decode($r['payload'], true) : null,
                'ts' => ts($r['created_at']),
            ];
        }
        // delete delivered events for this user (no re-delivery)
        db()->prepare('DELETE FROM events WHERE user_id = ? AND id <= ?')->execute([$uid, $since]);
        break;
    }
    if (time() >= $deadline) break;
    // keep presence fresh while polling
    touch_presence($uid);
    flush_due();
    usleep(700000); // 0.7s
}

$totalUnread = db()->prepare('SELECT COALESCE(SUM(unread_count),0) t FROM chat_members WHERE user_id = ?');
$totalUnread->execute([$uid]);

ok(['events' => $events, 'since_id' => $since, 'unread_total' => (int)$totalUnread->fetch()['t'], 'server_time' => time()]);
