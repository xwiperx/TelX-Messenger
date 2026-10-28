<?php
/* TelX API — upload.php : photos, files, voice, video, avatars, stories, gifs */
define('TELX_API', 1);
require __DIR__ . '/../config.php';

$kind = p('kind') ?? 'file';
require_auth();
if (!in_array($kind, ['photo','file','voice','video','avatar','group','story','gif','enc'], true))
    fail('invalid', 'Unknown upload kind');

$field = 'file';
$out = save_upload($kind, $field);
// 'enc' kind stores encrypted blobs (secret chats) — serve with generic mime
if ($kind === 'enc') $out['url'] = absolute_url($out['rel']);
ok(['upload' => $out]);
