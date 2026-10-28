<?php
/** TelX — auth/delete_account.php (POST {password}) — permanent deletion */
require __DIR__ . '/../config.php';
$me = require_auth();
$b = body();
if ($me['password_hash'] !== hash_password($b['password'] ?? '')) jerr('رمز عبور اشتباه است');
q_exec("DELETE FROM tx_users WHERE id = ?", [$me['id']]);
jok(['message' => 'حساب شما برای همیشه حذف شد']);
