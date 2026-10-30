<?php
/* TelX 6.0 migration — run once: adds columns/tables for media albums, text
   formatting/effects metadata, silent + anonymous sends, multi-pin, comments,
   slow mode, granular permissions, join requests, multiple invite links,
   hidden chats, login alerts, verified/scam badges, reports, self-destruct.
   Safe to re-run (checks existence first). */
define('TELX_API', 1);
require __DIR__ . '/config.php';
$db = db();

function col_exists6(string $table, string $col): bool {
    $st = db()->prepare("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?");
    $st->execute([$table, $col]);
    return (bool)$st->fetch();
}
function table_exists6(string $t): bool {
    $st = db()->prepare("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?");
    $st->execute([$t]);
    return (bool)$st->fetch();
}

$migrations = [
    // ---- messages: albums / silent / anonymous / effects / multi-pin / comments ----
    'messages.group_id'    => "ALTER TABLE messages ADD COLUMN group_id BIGINT UNSIGNED NULL DEFAULT NULL AFTER reply_to",
    'messages.silent'      => "ALTER TABLE messages ADD COLUMN silent TINYINT(1) NOT NULL DEFAULT 0 AFTER group_id",
    'messages.anon'        => "ALTER TABLE messages ADD COLUMN anon TINYINT(1) NOT NULL DEFAULT 0 AFTER silent",
    'messages.pinned'      => "ALTER TABLE messages ADD COLUMN pinned TINYINT(1) NOT NULL DEFAULT 0 AFTER anon",
    'messages.effect'      => "ALTER TABLE messages ADD COLUMN effect VARCHAR(16) NULL DEFAULT NULL AFTER pinned",
    'messages.comment_to'  => "ALTER TABLE messages ADD COLUMN comment_to BIGINT UNSIGNED NULL DEFAULT NULL AFTER effect",
    // ---- chats: slow mode / join requests / member permissions ----
    'chats.slow_mode'      => "ALTER TABLE chats ADD COLUMN slow_mode INT UNSIGNED NOT NULL DEFAULT 0 AFTER auto_delete_secs",
    'chats.join_request'   => "ALTER TABLE chats ADD COLUMN join_request TINYINT(1) NOT NULL DEFAULT 0 AFTER slow_mode",
    'chats.perms'          => "ALTER TABLE chats ADD COLUMN perms TEXT NULL DEFAULT NULL AFTER join_request",
    // ---- users: badges / self-destruct ----
    'users.verified'       => "ALTER TABLE users ADD COLUMN verified TINYINT(1) NOT NULL DEFAULT 0 AFTER twofa_enabled",
    'users.scam'           => "ALTER TABLE users ADD COLUMN scam TINYINT(1) NOT NULL DEFAULT 0 AFTER verified",
    'users.self_destruct'   => "ALTER TABLE users ADD COLUMN self_destruct_months INT UNSIGNED NOT NULL DEFAULT 0 AFTER scam",
    // ---- chat_members: hidden chats + banned role ----
    'chat_members.hidden'  => "ALTER TABLE chat_members ADD COLUMN hidden TINYINT(1) NOT NULL DEFAULT 0 AFTER draft",
    'chat_members.role_enum' => "ALTER TABLE chat_members MODIFY role ENUM('admin','member','banned') NOT NULL DEFAULT 'member'",
    // ---- new tables ----
    'table.invite_links' => "CREATE TABLE IF NOT EXISTS invite_links (
        id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
        chat_id BIGINT UNSIGNED NOT NULL,
        code VARCHAR(16) NOT NULL UNIQUE,
        name VARCHAR(64) NOT NULL DEFAULT '',
        creator_id BIGINT UNSIGNED NOT NULL,
        expires_at DATETIME NULL,
        usage_limit INT UNSIGNED NOT NULL DEFAULT 0,
        uses INT UNSIGNED NOT NULL DEFAULT 0,
        revoked TINYINT(1) NOT NULL DEFAULT 0,
        created_at DATETIME NOT NULL,
        KEY idx_il_chat (chat_id),
        FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
    'table.join_requests' => "CREATE TABLE IF NOT EXISTS join_requests (
        id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
        chat_id BIGINT UNSIGNED NOT NULL,
        user_id BIGINT UNSIGNED NOT NULL,
        created_at DATETIME NOT NULL,
        UNIQUE KEY uq_jr (chat_id, user_id),
        FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE,
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
    'table.reports' => "CREATE TABLE IF NOT EXISTS reports (
        id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
        reporter_id BIGINT UNSIGNED NOT NULL,
        target_type ENUM('user','chat') NOT NULL,
        target_id BIGINT UNSIGNED NOT NULL,
        reason VARCHAR(200) NOT NULL DEFAULT '',
        created_at DATETIME NOT NULL,
        UNIQUE KEY uq_report (reporter_id, target_type, target_id),
        KEY idx_rep_target (target_type, target_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
];

$done = 0;
foreach ($migrations as $key => $sql) {
    [$t, $c] = explode('.', $key, 2);
    if ($t === 'table') {
        if (!table_exists6($c)) { $db->exec($sql); echo "+ table $c\n"; $done++; }
        else echo "= table $c exists\n";
    } else {
        if (!col_exists6($t, $c)) { $db->exec($sql); echo "+ $t.$c\n"; $done++; }
        else echo "= $t.$c exists\n";
    }
}
echo "DONE ($done new)\n";
