<?php
/* TelX — init_db.php : creates / refreshes the full database schema (13 tables) */
define('TELX_API', 1);
require __DIR__ . '/config.php';

$fresh = !(isset($_GET['keep']) && $_GET['keep'] === '1');

$tables = [
"users" => "CREATE TABLE IF NOT EXISTS users (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  first_name VARCHAR(64) NOT NULL,
  last_name VARCHAR(64) NOT NULL DEFAULT '',
  username VARCHAR(32) NOT NULL UNIQUE,
  phone VARCHAR(24) NULL DEFAULT NULL,
  email VARCHAR(190) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  avatar VARCHAR(255) NULL,
  bio VARCHAR(200) NOT NULL DEFAULT '',
  privacy_lock TINYINT(1) NOT NULL DEFAULT 0,
  privacy_last_seen TINYINT NOT NULL DEFAULT 0,
  privacy_phone TINYINT NOT NULL DEFAULT 0,
  privacy_photo TINYINT NOT NULL DEFAULT 0,
  twofa_enabled TINYINT(1) NOT NULL DEFAULT 0,
  last_seen DATETIME NULL,
  created_at DATETIME NOT NULL,
  INDEX idx_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

"sessions" => "CREATE TABLE IF NOT EXISTS sessions (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  token_hash CHAR(64) NOT NULL UNIQUE,
  device VARCHAR(190) NOT NULL DEFAULT '',
  ip VARCHAR(64) NOT NULL DEFAULT '',
  created_at DATETIME NOT NULL,
  last_active DATETIME NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  INDEX idx_sessions_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

"verification_codes" => "CREATE TABLE IF NOT EXISTS verification_codes (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  purpose ENUM('twofa','reset') NOT NULL,
  code_hash CHAR(64) NOT NULL,
  used TINYINT(1) NOT NULL DEFAULT 0,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  INDEX idx_vc_user (user_id, purpose)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

"contacts" => "CREATE TABLE IF NOT EXISTS contacts (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  contact_id BIGINT UNSIGNED NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uq_contact (user_id, contact_id),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (contact_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

"blocks" => "CREATE TABLE IF NOT EXISTS blocks (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  blocked_id BIGINT UNSIGNED NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uq_block (user_id, blocked_id),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (blocked_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

"chats" => "CREATE TABLE IF NOT EXISTS chats (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  type ENUM('private','saved','group','channel') NOT NULL DEFAULT 'private',
  title VARCHAR(128) NULL,
  username VARCHAR(32) NULL,
  description VARCHAR(255) NULL,
  avatar VARCHAR(255) NULL,
  secret TINYINT(1) NOT NULL DEFAULT 0,
  pinned_message_id BIGINT UNSIGNED NULL,
  last_message_id BIGINT UNSIGNED NULL,
  last_message_at DATETIME NULL,
  auto_delete_secs INT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  INDEX idx_chats_last (last_message_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

"chat_members" => "CREATE TABLE IF NOT EXISTS chat_members (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  chat_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  role ENUM('admin','member') NOT NULL DEFAULT 'member',
  muted TINYINT(1) NOT NULL DEFAULT 0,
  pinned TINYINT(1) NOT NULL DEFAULT 0,
  archived TINYINT(1) NOT NULL DEFAULT 0,
  unread_count INT UNSIGNED NOT NULL DEFAULT 0,
  last_read_message_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  last_delivered_message_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  draft TEXT NULL,
  typing_at DATETIME NULL,
  joined_at DATETIME NOT NULL,
  UNIQUE KEY uq_member (chat_id, user_id),
  FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  INDEX idx_cm_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

"messages" => "CREATE TABLE IF NOT EXISTS messages (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  chat_id BIGINT UNSIGNED NOT NULL,
  sender_id BIGINT UNSIGNED NULL,
  sender_name VARCHAR(96) NOT NULL DEFAULT '',
  type ENUM('text','photo','file','voice','video','sticker','gif','system','video_note','location','contact') NOT NULL DEFAULT 'text',
  text MEDIUMTEXT NULL,
  media VARCHAR(255) NULL,
  thumb VARCHAR(255) NULL,
  media_name VARCHAR(255) NOT NULL DEFAULT '',
  media_size BIGINT UNSIGNED NOT NULL DEFAULT 0,
  media_duration INT UNSIGNED NOT NULL DEFAULT 0,
  sticker VARCHAR(64) NULL,
  link_json TEXT NULL,
  media_json TEXT NULL,
  reply_to BIGINT UNSIGNED NULL,
  fwd_from_user_id BIGINT UNSIGNED NULL,
  fwd_name VARCHAR(96) NOT NULL DEFAULT '',
  encrypted TINYINT(1) NOT NULL DEFAULT 0,
  edited_at DATETIME NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  scheduled_at DATETIME NULL,
  auto_delete_at DATETIME NULL,
  created_at DATETIME NOT NULL,
  FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE,
  INDEX idx_msg_chat (chat_id, id),
  INDEX idx_msg_sched (scheduled_at, deleted),
  INDEX idx_msg_autodel (auto_delete_at, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

"reactions" => "CREATE TABLE IF NOT EXISTS reactions (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  message_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  emoji VARCHAR(16) NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uq_reaction (message_id, user_id),
  FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

"stories" => "CREATE TABLE IF NOT EXISTS stories (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  media VARCHAR(255) NOT NULL,
  thumb VARCHAR(255) NULL,
  media_type ENUM('photo','video') NOT NULL DEFAULT 'photo',
  duration INT UNSIGNED NOT NULL DEFAULT 15,
  created_at DATETIME NOT NULL,
  expires_at DATETIME NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  INDEX idx_story_user (user_id, id),
  INDEX idx_story_exp (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

"story_views" => "CREATE TABLE IF NOT EXISTS story_views (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  story_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  viewed_at DATETIME NOT NULL,
  UNIQUE KEY uq_sview (story_id, user_id),
  FOREIGN KEY (story_id) REFERENCES stories(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

"events" => "CREATE TABLE IF NOT EXISTS events (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  type VARCHAR(24) NOT NULL,
  chat_id BIGINT UNSIGNED NULL,
  from_user BIGINT UNSIGNED NULL,
  payload TEXT NULL,
  created_at DATETIME NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  INDEX idx_events_user (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

"secret_keys" => "CREATE TABLE IF NOT EXISTS secret_keys (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL UNIQUE,
  pubkey TEXT NOT NULL,
  updated_at DATETIME NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

"backups" => "CREATE TABLE IF NOT EXISTS backups (
  user_id BIGINT UNSIGNED PRIMARY KEY,
  data MEDIUMTEXT NOT NULL,
  size INT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

"message_views" => "CREATE TABLE IF NOT EXISTS message_views (
  message_id INT NOT NULL,
  user_id INT NOT NULL,
  viewed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (message_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

"sticker_packs" => "CREATE TABLE IF NOT EXISTS sticker_packs (
  id INT AUTO_INCREMENT PRIMARY KEY,
  user_id INT NOT NULL,
  title VARCHAR(40) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

"stickers" => "CREATE TABLE IF NOT EXISTS stickers (
  id INT AUTO_INCREMENT PRIMARY KEY,
  pack_id INT NOT NULL,
  file VARCHAR(255) NOT NULL,
  added_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_pack (pack_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

"chat_folders" => "CREATE TABLE IF NOT EXISTS chat_folders (
  id INT AUTO_INCREMENT PRIMARY KEY,
  user_id INT NOT NULL,
  name VARCHAR(32) NOT NULL,
  emoji VARCHAR(8) NOT NULL DEFAULT '',
  flags INT NOT NULL DEFAULT 0,
  sort INT NOT NULL DEFAULT 0,
  UNIQUE KEY uq_folder (user_id, name),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",

"folder_chats" => "CREATE TABLE IF NOT EXISTS folder_chats (
  folder_id INT NOT NULL,
  chat_id INT NOT NULL,
  PRIMARY KEY (folder_id, chat_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
];

if ($fresh) {
    $dropOrder = ['reactions','story_views','stories','messages','chat_members','chats','blocks','contacts',
                  'verification_codes','sessions','secret_keys','events','users'];
    foreach ($dropOrder as $t) db()->exec("DROP TABLE IF EXISTS `$t`");
}

$done = [];
foreach ($tables as $name => $sql) { db()->exec($sql); $done[] = $name; }

foreach (['uploads','uploads/photo','uploads/file','uploads/voice','uploads/video','uploads/avatar','uploads/group','uploads/story','uploads/gif','uploads/links','uploads/enc','uploads/stickers','uploads/stickers/builtin'] as $d) {
    if (!is_dir(__DIR__ . '/' . $d)) @mkdir(__DIR__ . '/' . $d, 0777, true);
}

ok(['message' => 'TelX database ready (' . count($done) . ' tables)', 'tables' => $done, 'fresh' => $fresh]);
