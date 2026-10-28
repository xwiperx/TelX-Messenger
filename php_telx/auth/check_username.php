<?php
/** TelX — auth/check_username.php (GET ?q=) */
require __DIR__ . '/../config.php';
$q = strtolower(trim($_GET['q'] ?? ''));
if (!$q) jok(['available' => false]);
if (!preg_match('/^[a-zA-Z][a-zA-Z0-9_]{3,24}$/', $q)) jok(['available' => false, 'invalid' => true]);
$exists = one("SELECT id FROM tx_users WHERE username = ?", [$q]);
jok(['available' => !$exists]);
