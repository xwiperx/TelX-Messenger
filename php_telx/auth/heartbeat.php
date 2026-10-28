<?php
/** TelX — auth/heartbeat.php (POST) — presence ping */
require __DIR__ . '/../config.php';
$me = require_auth();
q_exec("UPDATE tx_users SET is_online = 1, last_seen = NOW() WHERE id = ?", [$me['id']]);
jok();
