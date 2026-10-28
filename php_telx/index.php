<?php
/* TelX — index.php : minimal service status page (also used for browser testing ping) */
define('TELX_API', 1);
require __DIR__ . '/config.php';
ok(['service' => 'TelX', 'status' => 'online', 'time' => time(), 'api' => ['auth', 'profile', 'contacts', 'chats', 'messages', 'stories', 'events', 'upload', 'calls']]);
