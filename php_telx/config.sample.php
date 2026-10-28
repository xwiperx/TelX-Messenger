<?php
/**
 * TelX — config.sample.php
 * Copy this file to config.php and fill in your credentials.
 * config.php is git-ignored on purpose.
 */

define('DB_HOST', 'mysql-YOURUSER.alwaysdata.net');
define('DB_NAME', 'YOURUSER_telx');
define('DB_USER', 'YOURUSER');
define('DB_PASS', 'YOUR_PASSWORD');

define('UPLOAD_DIR', __DIR__ . '/uploads');
define('UPLOAD_URL_BASE', 'uploads');
define('LONGPOLL_SECONDS', 25);
define('ONLINE_WINDOW', 35);
define('STORY_LIFETIME_HOURS', 24);
define('MAX_UPLOAD_BYTES', 25 * 1024 * 1024);

// true  → 2FA/reset codes are also returned in JSON (development convenience)
// false → codes are ONLY emailed (production)
define('TELX_DEBUG_CODES', false);

define('MAIL_FROM', 'no-reply@YOURUSER.alwaysdata.net');
