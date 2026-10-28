<?php
/** TelX — auth/me.php (GET) — current user + token refresh */
require __DIR__ . '/../config.php';
$u = auth_user();
if (!$u) jok(['me' => null]);
jok(['me' => me_user($u)]);
