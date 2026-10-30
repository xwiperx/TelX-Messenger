<?php
/* TelX 7.0 migration — run once: adds columns/tables for communities,
   mighty polls (media, quiz, multiple answers, user suggestions),
   shared to-do lists, interactive message buttons, inline rich media,
   ephemeral messages, recurring scheduled messages, group calls,
   live stories + story albums + story replies, profile music,
   welcome messages. Safe to re-run (checks existence first). */
define('TELX_API', 1);
require __DIR__ . '/config.php';
$db = db();

function col_exists7(string $table, string $col): bool {
    $st = db()->prepare("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?");
    $st->execute([$table, $col]);
    return (bool)$st->fetch();
}
function table_exists7(string $t): bool {
    $st = db()->prepare("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?");
    $st->execute([$t]);
    return (bool)$st->fetch();
}

$migrations = [
    // ---- chats: community type + welcome messages ----
    'chats.type_enum7'  => "ALTER TABLE chats MODIFY type ENUM('private','saved','group','channel','community') NOT NULL DEFAULT 'private'",
    'chats.welcome_msg' => "ALTER TABLE chats ADD COLUMN welcome_msg TEXT NULL AFTER perms",
    // ---- messages: buttons / inline media / ephemeral / polls / todos / repeat ----
    'messages.buttons'     => "ALTER TABLE messages ADD COLUMN buttons TEXT NULL AFTER comment_to",
    'messages.inline_json' => "ALTER TABLE messages ADD COLUMN inline_json MEDIUMTEXT NULL AFTER buttons",
    'messages.ephemeral_to' => "ALTER TABLE messages ADD COLUMN ephemeral_to BIGINT UNSIGNED NULL AFTER inline_json",
    'messages.repeat_every' => "ALTER TABLE messages ADD COLUMN repeat_every INT UNSIGNED NOT NULL DEFAULT 0 AFTER ephemeral_to",
    'messages.type_enum7'  => "ALTER TABLE messages MODIFY type ENUM('text','photo','file','voice','video','sticker','gif','system','video_note','location','contact','poll','todo') NOT NULL DEFAULT 'text'",
    // ---- stories: live + albums ----
    'stories.is_live'  => "ALTER TABLE stories ADD COLUMN is_live TINYINT(1) NOT NULL DEFAULT 0 AFTER duration",
    'stories.live_end' => "ALTER TABLE stories ADD COLUMN live_end DATETIME NULL AFTER is_live",
    'stories.album'    => "ALTER TABLE stories ADD COLUMN album VARCHAR(48) NULL DEFAULT NULL AFTER live_end",
    // ---- users: profile music + default profile tab ----
    'users.profile_music'       => "ALTER TABLE users ADD COLUMN profile_music VARCHAR(255) NULL AFTER self_destruct_months",
    'users.profile_music_title' => "ALTER TABLE users ADD COLUMN profile_music_title VARCHAR(96) NOT NULL DEFAULT '' AFTER profile_music",
    'users.profile_tab'         => "ALTER TABLE users ADD COLUMN profile_tab TINYINT NOT NULL DEFAULT 0 AFTER profile_music_title",
    // ---- new tables ----
    'table.polls' => "CREATE TABLE IF NOT EXISTS polls (
        id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
        message_id BIGINT UNSIGNED NOT NULL UNIQUE,
        question VARCHAR(190) NOT NULL,
        options_json TEXT NOT NULL,
        multiple TINYINT(1) NOT NULL DEFAULT 0,
        quiz TINYINT(1) NOT NULL DEFAULT 0,
        correct_json TEXT NULL,
        closed TINYINT(1) NOT NULL DEFAULT 0,
        media VARCHAR(255) NULL,
        thumb VARCHAR(255) NULL,
        FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
    'table.poll_votes' => "CREATE TABLE IF NOT EXISTS poll_votes (
        id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
        poll_id BIGINT UNSIGNED NOT NULL,
        user_id BIGINT UNSIGNED NOT NULL,
        option_idx INT NOT NULL,
        created_at DATETIME NOT NULL,
        UNIQUE KEY uq_pvote (poll_id, user_id, option_idx),
        FOREIGN KEY (poll_id) REFERENCES polls(id) ON DELETE CASCADE,
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
    'table.poll_suggestions' => "CREATE TABLE IF NOT EXISTS poll_suggestions (
        id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
        poll_id BIGINT UNSIGNED NOT NULL,
        user_id BIGINT UNSIGNED NOT NULL,
        option_text VARCHAR(100) NOT NULL,
        created_at DATETIME NOT NULL,
        UNIQUE KEY uq_psug (poll_id, user_id, option_text),
        FOREIGN KEY (poll_id) REFERENCES polls(id) ON DELETE CASCADE,
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
    'table.communities' => "CREATE TABLE IF NOT EXISTS communities (
        id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
        chat_id BIGINT UNSIGNED NOT NULL UNIQUE,
        owner_id BIGINT UNSIGNED NOT NULL,
        FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE,
        FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
    'table.community_chats' => "CREATE TABLE IF NOT EXISTS community_chats (
        id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
        community_id BIGINT UNSIGNED NOT NULL,
        chat_id BIGINT UNSIGNED NOT NULL,
        sort INT NOT NULL DEFAULT 0,
        UNIQUE KEY uq_cc (community_id, chat_id),
        FOREIGN KEY (community_id) REFERENCES communities(id) ON DELETE CASCADE,
        FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
    'table.call_participants' => "CREATE TABLE IF NOT EXISTS call_participants (
        id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
        chat_id BIGINT UNSIGNED NOT NULL,
        user_id BIGINT UNSIGNED NOT NULL,
        call_id VARCHAR(32) NOT NULL,
        joined_at DATETIME NOT NULL,
        left_at DATETIME NULL,
        UNIQUE KEY uq_callp (chat_id, user_id),
        FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE,
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
    'table.story_replies' => "CREATE TABLE IF NOT EXISTS story_replies (
        id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
        story_id BIGINT UNSIGNED NOT NULL,
        user_id BIGINT UNSIGNED NOT NULL,
        text VARCHAR(500) NOT NULL,
        created_at DATETIME NOT NULL,
        FOREIGN KEY (story_id) REFERENCES stories(id) ON DELETE CASCADE,
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        KEY idx_sr (story_id, id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
];

$done = 0;
foreach ($migrations as $key => $sql) {
    [$t, $c] = explode('.', $key, 2);
    if ($t === 'table') {
        if (!table_exists7($c)) { $db->exec($sql); echo "+ table $c\n"; $done++; }
        else echo "= table $c exists\n";
    } else {
        if (!col_exists7($t, $c)) { $db->exec($sql); echo "+ $t.$c\n"; $done++; }
        else echo "= $t.$c exists\n";
    }
}
echo "DONE ($done new)\n";
