<?php
/**
 * TelX — init_db.php
 * Creates all tables (idempotent). Open once after uploading:
 *   https://mybackup-test.alwaysdata.net/telx/init_db.php
 */
require __DIR__ . '/config.php';

$tables = [
    "CREATE TABLE IF NOT EXISTS tx_users (
        id VARCHAR(40) PRIMARY KEY,
        first_name VARCHAR(80) NOT NULL,
        last_name VARCHAR(80) NOT NULL DEFAULT '',
        username VARCHAR(32) NOT NULL UNIQUE,
        email VARCHAR(190) NOT NULL UNIQUE,
        password_hash VARCHAR(64) NOT NULL,
        bio VARCHAR(255) NOT NULL DEFAULT '',
        avatar_url VARCHAR(255) NULL,
        privacy_lock TINYINT(1) NOT NULL DEFAULT 0,
        two_factor_enabled TINYINT(1) NOT NULL DEFAULT 0,
        two_factor_secret VARCHAR(8) NULL,
        is_online TINYINT(1) NOT NULL DEFAULT 0,
        last_seen DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        wallpaper VARCHAR(24) NOT NULL DEFAULT 'pattern1',
        dark_mode TINYINT(1) NOT NULL DEFAULT 0,
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

    "CREATE TABLE IF NOT EXISTS tx_sessions (
        id VARCHAR(40) PRIMARY KEY,
        token VARCHAR(64) NOT NULL UNIQUE,
        user_id VARCHAR(40) NOT NULL,
        device VARCHAR(120) NOT NULL DEFAULT 'Unknown Device',
        ip VARCHAR(64) NOT NULL DEFAULT '',
        current TINYINT(1) NOT NULL DEFAULT 0,
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        last_active DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        INDEX (user_id),
        FOREIGN KEY (user_id) REFERENCES tx_users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

    "CREATE TABLE IF NOT EXISTS tx_chats (
        id VARCHAR(40) PRIMARY KEY,
        type VARCHAR(16) NOT NULL DEFAULT 'private',
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

    "CREATE TABLE IF NOT EXISTS tx_chat_members (
        id VARCHAR(40) PRIMARY KEY,
        chat_id VARCHAR(40) NOT NULL,
        user_id VARCHAR(40) NOT NULL,
        unread_count INT NOT NULL DEFAULT 0,
        muted TINYINT(1) NOT NULL DEFAULT 0,
        last_read_message_id VARCHAR(40) NULL,
        UNIQUE KEY uq_chat_user (chat_id, user_id),
        INDEX (user_id),
        FOREIGN KEY (chat_id) REFERENCES tx_chats(id) ON DELETE CASCADE,
        FOREIGN KEY (user_id) REFERENCES tx_users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

    "CREATE TABLE IF NOT EXISTS tx_messages (
        id VARCHAR(40) PRIMARY KEY,
        chat_id VARCHAR(40) NOT NULL,
        sender_id VARCHAR(40) NOT NULL,
        text TEXT,
        type VARCHAR(16) NOT NULL DEFAULT 'text',
        media_url VARCHAR(255) NULL,
        file_name VARCHAR(190) NULL,
        file_size INT NULL,
        duration INT NULL,
        reply_to_id VARCHAR(40) NULL,
        forwarded_from VARCHAR(40) NULL,
        forwarded_by_name VARCHAR(160) NULL,
        preview_title VARCHAR(190) NULL,
        preview_image VARCHAR(255) NULL,
        preview_url VARCHAR(255) NULL,
        preview_desc VARCHAR(255) NULL,
        is_edited TINYINT(1) NOT NULL DEFAULT 0,
        is_deleted TINYINT(1) NOT NULL DEFAULT 0,
        pinned TINYINT(1) NOT NULL DEFAULT 0,
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        edited_at DATETIME NULL,
        INDEX (chat_id, created_at),
        FOREIGN KEY (chat_id) REFERENCES tx_chats(id) ON DELETE CASCADE,
        FOREIGN KEY (sender_id) REFERENCES tx_users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

    "CREATE TABLE IF NOT EXISTS tx_reactions (
        id VARCHAR(40) PRIMARY KEY,
        message_id VARCHAR(40) NOT NULL,
        user_id VARCHAR(40) NOT NULL,
        emoji VARCHAR(16) NOT NULL,
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        UNIQUE KEY uq_reaction (message_id, user_id, emoji),
        FOREIGN KEY (message_id) REFERENCES tx_messages(id) ON DELETE CASCADE,
        FOREIGN KEY (user_id) REFERENCES tx_users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

    "CREATE TABLE IF NOT EXISTS tx_message_reads (
        id VARCHAR(40) PRIMARY KEY,
        message_id VARCHAR(40) NOT NULL,
        user_id VARCHAR(40) NOT NULL,
        read_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        UNIQUE KEY uq_read (message_id, user_id),
        INDEX (message_id),
        FOREIGN KEY (message_id) REFERENCES tx_messages(id) ON DELETE CASCADE,
        FOREIGN KEY (user_id) REFERENCES tx_users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

    "CREATE TABLE IF NOT EXISTS tx_contacts (
        id VARCHAR(40) PRIMARY KEY,
        user_id VARCHAR(40) NOT NULL,
        contact_id VARCHAR(40) NOT NULL,
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        UNIQUE KEY uq_contact (user_id, contact_id),
        FOREIGN KEY (user_id) REFERENCES tx_users(id) ON DELETE CASCADE,
        FOREIGN KEY (contact_id) REFERENCES tx_users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

    "CREATE TABLE IF NOT EXISTS tx_stories (
        id VARCHAR(40) PRIMARY KEY,
        user_id VARCHAR(40) NOT NULL,
        media_url VARCHAR(255) NOT NULL,
        media_type VARCHAR(8) NOT NULL DEFAULT 'image',
        duration INT NOT NULL DEFAULT 15,
        caption VARCHAR(220) NOT NULL DEFAULT '',
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        expires_at DATETIME NOT NULL,
        INDEX (user_id, created_at),
        FOREIGN KEY (user_id) REFERENCES tx_users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

    "CREATE TABLE IF NOT EXISTS tx_story_views (
        id VARCHAR(40) PRIMARY KEY,
        story_id VARCHAR(40) NOT NULL,
        user_id VARCHAR(40) NOT NULL,
        viewed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        UNIQUE KEY uq_story_view (story_id, user_id),
        FOREIGN KEY (story_id) REFERENCES tx_stories(id) ON DELETE CASCADE,
        FOREIGN KEY (user_id) REFERENCES tx_users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

    "CREATE TABLE IF NOT EXISTS tx_typing_states (
        id VARCHAR(40) PRIMARY KEY,
        chat_id VARCHAR(40) NOT NULL,
        user_id VARCHAR(40) NOT NULL,
        updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        UNIQUE KEY uq_typing (chat_id, user_id),
        FOREIGN KEY (user_id) REFERENCES tx_users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

    "CREATE TABLE IF NOT EXISTS tx_password_resets (
        id VARCHAR(40) PRIMARY KEY,
        user_id VARCHAR(40) NOT NULL,
        code VARCHAR(8) NOT NULL,
        used TINYINT(1) NOT NULL DEFAULT 0,
        expires_at DATETIME NOT NULL,
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        INDEX (user_id),
        FOREIGN KEY (user_id) REFERENCES tx_users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

    "CREATE TABLE IF NOT EXISTS tx_notifications (
        id VARCHAR(40) PRIMARY KEY,
        user_id VARCHAR(40) NOT NULL,
        title VARCHAR(160) NOT NULL,
        body VARCHAR(255) NOT NULL DEFAULT '',
        chat_id VARCHAR(40) NULL,
        `read` TINYINT(1) NOT NULL DEFAULT 0,
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        INDEX (user_id, `read`),
        FOREIGN KEY (user_id) REFERENCES tx_users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
];

$created = 0; $failed = [];
foreach ($tables as $sql) {
    try { db()->query($sql); $created++; }
    catch (Throwable $e) { $failed[] = $e->getMessage(); }
}

ensure_upload_dir();

header('Content-Type: application/json; charset=utf-8');
echo json_encode([
    'ok'      => count($failed) === 0,
    'message' => 'TelX database initialized — ' . $created . ' tables ready',
    'tables'  => ['users','sessions','chats','chat_members','messages','reactions','message_reads','contacts','stories','story_views','typing_states','password_resets','notifications'],
    'errors'  => $failed,
], JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT);
