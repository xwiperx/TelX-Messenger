<?php
/** TelX — auth/logout.php (POST) — deletes current token session */
require __DIR__ . '/../config.php';
$u = require_auth();
$token = $_GET['token'] ?? '';
$h = array_change_key_case(function_exists('getallheaders') ? (getallheaders() ?: []) : [], CASE_LOWER);
if (isset($h['authorization']) && preg_match('/bearer\s+(\S+)/i', $h['authorization'], $m)) $token = $m[1];
if (isset($h['x-auth-token'])) $token = $h['x-auth-token'];
q_exec("DELETE FROM tx_sessions WHERE token = ?", [$token]);
$remaining = one("SELECT COUNT(*) c FROM tx_sessions WHERE user_id = ?", [$u['id']]);
if ((int)($remaining['c'] ?? 0) === 0) q_exec("UPDATE tx_users SET is_online = 0, last_seen = NOW() WHERE id = ?", [$u['id']]);
jok(['message' => 'خارج شدید']);
