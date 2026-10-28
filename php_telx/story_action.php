<?php
/**
 * TelX — story_action.php
 *  POST   {storyId}            → mark viewed
 *  GET    ?storyId=            → viewers list (own stories only)
 *  DELETE ?storyId=            → delete own story
 */
require __DIR__ . '/config.php';
$me = require_auth();
$method = $_SERVER['REQUEST_METHOD'];

$storyId = $method === 'GET' || $method === 'DELETE' ? ($_GET['storyId'] ?? '') : (body()['storyId'] ?? '');
$story = one("SELECT * FROM tx_stories WHERE id = ?", [$storyId]);
if (!$story) jerr('استوری یافت نشد', 404);

if ($method === 'POST') {
    q_exec("INSERT INTO tx_story_views (id, story_id, user_id) VALUES (?,?,?) ON DUPLICATE KEY UPDATE viewed_at = NOW()",
        [cuid(), $storyId, $me['id']]);
    jok();
}

if ($method === 'GET') {
    if ($story['user_id'] !== $me['id']) jerr('فقط استوری خودتان قابل مشاهده بازدیدکنندگان است', 403);
    $rows = all(
        "SELECT u.*, v.viewed_at FROM tx_story_views v JOIN tx_users u ON u.id = v.user_id WHERE v.story_id = ? ORDER BY v.viewed_at DESC",
        [$storyId]
    );
    jok(['viewers' => array_map(fn($u) => array_merge(public_user($u, $me['id']), ['viewedAt' => $u['viewed_at']]), $rows)]);
}

if ($method === 'DELETE') {
    if ($story['user_id'] !== $me['id']) jerr('فقط استوری خودتان قابل حذف است', 403);
    q_exec("DELETE FROM tx_stories WHERE id = ?", [$storyId]);
    jok(['message' => 'استوری حذف شد']);
}

jerr('متد پشتیبانی نمی‌شود', 405);
