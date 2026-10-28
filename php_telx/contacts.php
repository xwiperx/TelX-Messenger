<?php
/**
 * TelX — contacts.php
 *  GET    → my contacts
 *  POST   {userId|username} → add contact
 *  DELETE ?contactId=       → remove contact
 */
require __DIR__ . '/config.php';
$me = require_auth();
$method = $_SERVER['REQUEST_METHOD'];

if ($method === 'GET') {
    $rows = all(
        "SELECT u.* FROM tx_contacts c JOIN tx_users u ON u.id = c.contact_id WHERE c.user_id = ? ORDER BY c.created_at DESC",
        [$me['id']]
    );
    jok(['contacts' => array_map(fn($u) => public_user($u, $me['id']), $rows)]);
}

if ($method === 'POST') {
    $b = body();
    $target = !empty($b['userId'])
        ? one("SELECT * FROM tx_users WHERE id = ?", [$b['userId']])
        : one("SELECT * FROM tx_users WHERE username = ?", [strtolower(trim($b['username'] ?? ''))]);
    if (!$target) jerr('کاربر یافت نشد', 404);
    if ($target['id'] === $me['id']) jerr('نمی‌توانید خودتان را اضافه کنید');
    if (one("SELECT id FROM tx_contacts WHERE user_id = ? AND contact_id = ?", [$me['id'], $target['id']]))
        jok(['contact' => public_user($target, $me['id']), 'message' => 'این کاربر قبلاً در مخاطبین است']);
    q_exec("INSERT INTO tx_contacts (id, user_id, contact_id) VALUES (?,?,?)", [cuid(), $me['id'], $target['id']]);
    jok(['contact' => public_user($target, $me['id']), 'message' => 'به مخاطبین اضافه شد']);
}

if ($method === 'DELETE') {
    $contactId = $_GET['contactId'] ?? '';
    if (!$contactId) jerr('شناسه مخاطب لازم است');
    q_exec("DELETE FROM tx_contacts WHERE user_id = ? AND contact_id = ?", [$me['id'], $contactId]);
    jok(['message' => 'مخاطب حذف شد']);
}

jerr('متد پشتیبانی نمی‌شود', 405);
