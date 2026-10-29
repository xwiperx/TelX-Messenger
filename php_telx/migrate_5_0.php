<?php
/* TelX 5.0 migration — run once: adds new columns/tables for
   phone/privacy, media_json, message views, sticker packs, chat folders,
   group invite links. Safe to re-run (checks existence first). */
define('TELX_API', 1);
require __DIR__ . '/config.php';
$db = db();

function col_exists(string $table, string $col): bool {
    $st = db()->prepare("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?");
    $st->execute([$table, $col]);
    return (bool)$st->fetch();
}
function table_exists(string $t): bool {
    $st = db()->prepare("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?");
    $st->execute([$t]);
    return (bool)$st->fetch();
}

$migrations = [
    'users.phone' => "ALTER TABLE users ADD COLUMN phone VARCHAR(24) NULL DEFAULT NULL AFTER username",
    'users.privacy_last_seen' => "ALTER TABLE users ADD COLUMN privacy_last_seen TINYINT NOT NULL DEFAULT 0 AFTER privacy_lock",
    'users.privacy_phone' => "ALTER TABLE users ADD COLUMN privacy_phone TINYINT NOT NULL DEFAULT 0 AFTER privacy_last_seen",
    'users.privacy_photo' => "ALTER TABLE users ADD COLUMN privacy_photo TINYINT NOT NULL DEFAULT 0 AFTER privacy_phone",
    'messages.media_json' => "ALTER TABLE messages ADD COLUMN media_json TEXT NULL DEFAULT NULL AFTER link_json",
    'messages.type_enum' => "ALTER TABLE messages MODIFY type ENUM('text','photo','file','voice','video','sticker','gif','system','video_note','location','contact') NOT NULL DEFAULT 'text'",
    'chats.invite_code' => "ALTER TABLE chats ADD COLUMN invite_code VARCHAR(16) NULL DEFAULT NULL AFTER username",
    'table.message_views' => "CREATE TABLE IF NOT EXISTS message_views (
        message_id INT NOT NULL,
        user_id INT NOT NULL,
        viewed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (message_id, user_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
    'table.sticker_packs' => "CREATE TABLE IF NOT EXISTS sticker_packs (
        id INT AUTO_INCREMENT PRIMARY KEY,
        user_id INT NOT NULL,
        title VARCHAR(40) NOT NULL,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
    'table.stickers' => "CREATE TABLE IF NOT EXISTS stickers (
        id INT AUTO_INCREMENT PRIMARY KEY,
        pack_id INT NOT NULL,
        file VARCHAR(255) NOT NULL,
        added_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        KEY idx_pack (pack_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
    'table.chat_folders' => "CREATE TABLE IF NOT EXISTS chat_folders (
        id INT AUTO_INCREMENT PRIMARY KEY,
        user_id INT NOT NULL,
        name VARCHAR(32) NOT NULL,
        emoji VARCHAR(8) NOT NULL DEFAULT '',
        flags INT NOT NULL DEFAULT 0,
        sort INT NOT NULL DEFAULT 0,
        UNIQUE KEY uq_folder (user_id, name),
        KEY idx_user (user_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
    'table.folder_chats' => "CREATE TABLE IF NOT EXISTS folder_chats (
        folder_id INT NOT NULL,
        chat_id INT NOT NULL,
        PRIMARY KEY (folder_id, chat_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
];

$done = 0;
foreach ($migrations as $key => $sql) {
    [$t, $c] = explode('.', $key, 2);
    if ($t === 'table') {
        if (!table_exists($c)) { $db->exec($sql); echo "+ table $c\n"; $done++; }
        else echo "= table $c exists\n";
    } else {
        if (!col_exists($t, $c)) { $db->exec($sql); echo "+ $t.$c\n"; $done++; }
        else echo "= $t.$c exists\n";
    }
}
// index to speed up folder_chats reverse lookup
try { $db->exec("ALTER TABLE folder_chats ADD KEY idx_chat (chat_id)"); } catch (Throwable $e) {}
echo "DONE ($done new)\n";
