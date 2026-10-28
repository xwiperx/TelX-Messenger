<?php
/* TelX Server — config.php (bootstrap, not an entry point) */
define('TELX', 1);

/* ---------- Settings ---------- */
const DB_HOST   = 'YOUR_DB_HOST';
const DB_NAME   = 'YOUR_DB_NAME';
const DB_USER   = 'YOUR_DB_USER';
const DB_PASS   = 'YOUR_DB_PASSWORD';

const BASE_URL  = 'https://yourdomain.example.com/telx/';
const UPLOAD_DIR = __DIR__ . '/uploads';
const MAIL_FROM  = 'no-reply@yourdomain.example.com';
const MAX_UPLOAD = 200 * 1024 * 1024;          // 200 MB
const LONGPOLL_TIMEOUT = 22;                   // seconds of server-side wait
const STORY_TTL = 86400;                        // 24h

date_default_timezone_set('UTC');
error_reporting(E_ALL & ~E_DEPRECATED & ~E_NOTICE);
ini_set('display_errors', '0');
ini_set('log_errors', '1');

header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Headers: Authorization, Content-Type');
header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
if (($_SERVER['REQUEST_METHOD'] ?? '') === 'OPTIONS') { http_response_code(204); exit; }

/* ---------- Errors as JSON ---------- */
set_exception_handler(function ($e) {
    error_log('TelX 500: ' . $e->getMessage() . ' @ ' . $e->getFile() . ':' . $e->getLine());
    http_response_code(200);
    echo json_encode(['ok' => false, 'error' => 'server', 'message' => $e->getMessage()]);
    exit;
});
set_error_handler(function ($no, $str, $file, $line) {
    if (!(error_reporting() & $no)) return true;
    error_log("TelX err: [$no] $str @ $file:$line");
    http_response_code(200);
    echo json_encode(['ok' => false, 'error' => 'server', 'message' => "$str @ $file:$line"]);
    exit;
});

/* ---------- DB ---------- */
function db(): PDO {
    static $pdo = null;
    if ($pdo === null) {
        $pdo = new PDO(
            'mysql:host=' . DB_HOST . ';dbname=' . DB_NAME . ';charset=utf8mb4',
            DB_USER, DB_PASS,
            [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
             PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
             PDO::ATTR_EMULATE_PREPARES => false]
        );
        $pdo->exec("SET time_zone = '+00:00'");
    }
    return $pdo;
}

/* ---------- Output ---------- */
function ok(array $data = []): never {
    $data['ok'] = true;
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}
function fail(string $code, string $msg = '', int $http = 200): never {
    http_response_code($http);
    echo json_encode(['ok' => false, 'error' => $code, 'message' => $msg], JSON_UNESCAPED_UNICODE);
    exit;
}

/* ---------- Input ---------- */
function body(): array {
    static $b = null;
    if ($b === null) {
        $raw = file_get_contents('php://input');
        $j = $raw !== '' && $raw !== false ? json_decode($raw, true) : null;
        $b = is_array($j) ? $j : $_POST;
    }
    return $b;
}
function p(string $k, ?string $default = null): ?string {
    $b = body();
    return isset($b[$k]) && is_scalar($b[$k]) ? (string)$b[$k] : $default;
}
function pint(string $k, int $default = 0): int {
    $b = body();
    return isset($b[$k]) && is_numeric($b[$k]) ? (int)$b[$k] : $default;
}
function parr(string $k): array {
    $b = body();
    return isset($b[$k]) && is_array($b[$k]) ? array_values($b[$k]) : [];
}

/* ---------- Auth ---------- */
function bearer(): string {
    $h = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
    if (preg_match('/Bearer\s+(\S+)/i', $h, $m)) return $m[1];
    return '';
}
function touch_presence(int $uid): void {
    db()->prepare('UPDATE users SET last_seen = UTC_TIMESTAMP() WHERE id = ?')->execute([$uid]);
}
/** @return array{user:array,session:array} */
function require_auth(): array {
    $token = bearer();
    if (strlen($token) < 32) fail('auth', 'Not authorized', 401);
    $st = db()->prepare(
        'SELECT s.*, u.*, s.id AS sid, u.id AS uid FROM sessions s
         JOIN users u ON u.id = s.user_id
         WHERE s.token_hash = ? LIMIT 1');
    $st->execute([hash('sha256', $token)]);
    $row = $st->fetch();
    if (!$row) fail('auth', 'Invalid session', 401);
    touch_presence((int)$row['uid']);
    db()->prepare('UPDATE sessions SET last_active = UTC_TIMESTAMP() WHERE id = ?')->execute([$row['sid']]);
    return ['user' => $row, 'session' => ['id' => (int)$row['sid'], 'token' => $token]];
}
function me(): array { static $c = null; if ($c === null) $c = require_auth(); return $c['user']; }
function me_id(): int { $u = me(); return (int)$u['uid']; }

/* ---------- Time ---------- */
function ts(?string $dt): ?int { return $dt ? strtotime($dt . ' UTC') : null; }
function sqlts(int $unix): string { return gmdate('Y-m-d H:i:s', $unix); }

/* ---------- Users ---------- */
function user_public(array $u, bool $full = false): array {
    $last = ts($u['last_seen']);
    $online = $last !== null && (time() - $last) < 65;
    $uid = (int)($u['uid'] ?? ($u['id'] ?? 0));
    $a = [
        'id' => $uid,
        'first' => $u['first_name'], 'last' => $u['last_name'],
        'name' => trim($u['first_name'] . ' ' . $u['last_name']),
        'username' => $u['username'], 'bio' => (string)($u['bio'] ?? ''),
        'avatar' => $u['avatar'] ? absolute_url($u['avatar']) : null,
        'online' => $online, 'last_ts' => $last,
        'privacy_lock' => (int)($u['privacy_lock'] ?? 0) === 1,
    ];
    if ($full) { $a['email'] = $u['email']; $a['twofa'] = (int)$u['twofa_enabled'] === 1; }
    return $a;
}
function user_by_id(int $id): ?array {
    $st = db()->prepare('SELECT *, id AS uid FROM users WHERE id = ?');
    $st->execute([$id]);
    return $st->fetch() ?: null;
}
function absolute_url(?string $rel): ?string {
    if (!$rel) return null;
    if (str_starts_with($rel, 'http')) return $rel;
    return BASE_URL . $rel;
}

/* ---------- Events (long-polling backbone) ---------- */
function emit(array|int $user_ids, string $type, array $payload = [], ?int $chat_id = null, ?int $from = null): void {
    if (!is_array($user_ids)) $user_ids = [$user_ids];
    $ids = array_values(array_unique(array_map('intval', $user_ids)));
    if (!$ids) return;
    $sql = 'INSERT INTO events (user_id, type, chat_id, from_user, payload, created_at) VALUES ';
    $ph = [];
    $vals = [];
    foreach ($ids as $uid) {
        $ph[] = '(?,?,?,?,?,UTC_TIMESTAMP())';
        $vals[] = $uid; $vals[] = $type; $vals[] = $chat_id; $vals[] = $from;
        $vals[] = json_encode($payload, JSON_UNESCAPED_UNICODE);
    }
    db()->prepare($sql . implode(',', $ph))->execute($vals);
}
function chat_member_ids(int $chat_id, int $exclude = 0): array {
    $st = db()->prepare('SELECT user_id FROM chat_members WHERE chat_id = ?');
    $st->execute([$chat_id]);
    $r = [];
    foreach ($st->fetchAll() as $row) { $i = (int)$row['user_id']; if ($i !== $exclude) $r[] = $i; }
    return $r;
}

/* ---------- Mail ---------- */
function send_mail(string $to, string $subject, string $html): bool {
    $from = MAIL_FROM;
    $hdr  = "From: TelX <$from>\r\n";
    $hdr .= "MIME-Version: 1.0\r\nContent-Type: text/html; charset=UTF-8\r\n";
    $subj = '=?UTF-8?B?' . base64_encode($subject) . '?=';
    $okk = @mail($to, $subj, $html, $hdr);
    if (!$okk) error_log("TelX mail failed to=$to subj=$subject");
    return $okk;
}

/* ---------- Uploads ---------- */
function mime_of(string $path): string {
    $fi = new finfo(FILEINFO_MIME_TYPE);
    return (string)$fi->file($path);
}
function image_size(string $path): array {
    $i = @getimagesize($path);
    return $i ? [(int)$i[0], (int)$i[1]] : [0, 0];
}
function make_thumb(string $src, int $max): ?string {
    $size = @getimagesize($src);
    if (!$size) return null;
    [$w, $h] = [$size[0], $size[1]];
    if (!$w || !$h) return null;
    if ($w <= $max && $h <= $max) return null;
    $ratio = min($max / $w, $max / $h);
    $nw = max(1, (int)round($w * $ratio)); $nh = max(1, (int)round($h * $ratio));
    $img = match ($size[2]) {
        IMAGETYPE_JPEG => @imagecreatefromjpeg($src),
        IMAGETYPE_PNG  => @imagecreatefrompng($src),
        IMAGETYPE_GIF  => @imagecreatefromgif($src),
        IMAGETYPE_WEBP => function_exists('imagecreatefromwebp') ? @imagecreatefromwebp($src) : null,
        default => null,
    };
    if (!$img) return null;
    $out = imagecreatetruecolor($nw, $nh);
    if (in_array($size[2], [IMAGETYPE_PNG, IMAGETYPE_GIF, IMAGETYPE_WEBP], true)) {
        imagealphablending($out, false); imagesavealpha($out, true);
        $transparent = imagecolorallocatealpha($out, 255, 255, 255, 127);
        imagefilledrectangle($out, 0, 0, $nw, $nh, $transparent);
    }
    imagecopyresampled($out, $img, 0, 0, 0, 0, $nw, $nh, $w, $h);
    $thumb = preg_replace('/\.(\w+)$/', '_t.jpg', $src);
    imagejpeg($out, $thumb, 80);
    imagedestroy($img); imagedestroy($out);
    return str_replace(UPLOAD_DIR . '/', 'uploads/', $thumb);
}
function save_upload(string $kind, string $field = 'file'): array {
    if (empty($_FILES[$field]) || !is_uploaded_file($_FILES[$field]['tmp_name'] ?? ''))
        fail('upload', 'No file received');
    $f = $_FILES[$field];
    if ((int)$f['error'] !== UPLOAD_ERR_OK) fail('upload', 'Upload error code ' . $f['error']);
    if ($f['size'] <= 0 || $f['size'] > MAX_UPLOAD) fail('upload', 'File too large (max 200MB)');
    $mime = mime_of($f['tmp_name']);
    $ext  = strtolower(pathinfo($f['name'] ?? '', PATHINFO_EXTENSION));
    $allowed = [
        'photo'  => ['image/jpeg' => 'jpg', 'image/png' => 'png', 'image/webp' => 'webp', 'image/gif' => 'gif'],
        'avatar' => ['image/jpeg' => 'jpg', 'image/png' => 'png', 'image/webp' => 'webp'],
        'group'  => ['image/jpeg' => 'jpg', 'image/png' => 'png', 'image/webp' => 'webp'],
        'story'  => ['image/jpeg' => 'jpg', 'image/png' => 'png', 'image/webp' => 'webp',
                     'image/gif' => 'gif', 'video/mp4' => 'mp4', 'video/quicktime' => 'mov', 'video/webm' => 'webm'],
        'voice'  => ['audio/mp4' => 'm4a', 'audio/mpeg' => 'mp3', 'audio/ogg' => 'ogg', 'audio/opus' => 'ogg',
                     'audio/webm' => 'weba', 'audio/wav' => 'wav', 'audio/x-wav' => 'wav', 'audio/aac' => 'aac',
                     'audio/amr' => 'amr', 'audio/3gpp' => '3gp', 'video/3gpp' => '3gp', 'audio/mp3' => 'mp3', 'audio/m4a' => 'm4a', 'audio/aac' => 'aac'],
        'gif'    => ['image/gif' => 'gif'],
        'video'  => ['video/mp4' => 'mp4', 'video/quicktime' => 'mov', 'video/webm' => 'webm', 'video/3gpp' => '3gp'],
        'enc'    => [],
        'file'   => [],
    ];
    if (!isset($allowed[$kind])) fail('upload', 'Unknown upload kind');
    $map = $allowed[$kind];
    if ($kind === 'file' || $kind === 'enc') {
        if (in_array($ext, ['php','phtml','phar','exe','sh','bat','com','cgi','pl','py','jsp'], true))
            fail('upload', 'This file type is not allowed');
        if ($kind === 'enc') $ext = 'bin';
    } else {
        if (!isset($map[$mime])) fail('upload', "Invalid media type for $kind: $mime");
        $ext = $map[$mime];
    }
    if (!$ext) $ext = 'bin';
    $dir = UPLOAD_DIR . '/' . $kind;
    if (!is_dir($dir)) @mkdir($dir, 0777, true);
    $name = time() . '_' . bin2hex(random_bytes(8)) . '.' . $ext;
    $dst  = "$dir/$name";
    if (!@move_uploaded_file($f['tmp_name'], $dst)) fail('upload', 'Cannot store file');
    @chmod($dst, 0644);
    $rel = "uploads/$kind/$name";
    $out = ['url' => absolute_url($rel), 'rel' => $rel, 'name' => $f['name'] ?? $name,
            'size' => (int)$f['size'], 'mime' => $mime];
    if (in_array($kind, ['photo', 'story', 'avatar', 'group'], true) && str_starts_with($mime, 'image/')) {
        $thumb = make_thumb($dst, $kind === 'avatar' ? 256 : 400);
        if ($thumb) { $out['thumb'] = absolute_url($thumb); $out['thumb_rel'] = $thumb; }
        [$w, $h] = image_size($dst);
        $out['width'] = $w; $out['height'] = $h;
    }
    return $out;
}

/* ---------- Link preview ---------- */
function rel2abs(string $u, string $base): string {
    if (str_starts_with($u, 'http')) return $u;
    $pa = parse_url($base);
    $root = $pa['scheme'] . '://' . $pa['host'];
    if (str_starts_with($u, '/')) return $root . $u;
    return rtrim($base, '/') . '/' . $u;
}
function download_link_image(string $url): ?string {
    $dir = UPLOAD_DIR . '/links';
    if (!is_dir($dir)) @mkdir($dir, 0777, true);
    $ctx = stream_context_create(['http' => ['timeout' => 4.0, 'header' => "User-Agent: TelX\r\n"]]);
    $data = @file_get_contents($url, false, $ctx, 0, 400000);
    if (!$data || strlen($data) < 100) return null;
    $tmp = tempnam(sys_get_temp_dir(), 'limg');
    file_put_contents($tmp, $data);
    $mime = mime_of($tmp);
    if (!str_starts_with($mime, 'image/')) { @unlink($tmp); return null; }
    $name = time() . '_' . bin2hex(random_bytes(6)) . '.jpg';
    $ok = make_thumb($tmp, 400);
    @unlink($tmp);
    return $ok ? absolute_url($ok) : null;
}
function fetch_link_preview(string $text): ?array {
    if (!preg_match('#(https?://[^\s<>"\']+)#i', $text, $m)) return null;
    $url = $m[1];
    try {
        $ctx = stream_context_create(['http' => ['timeout' => 4.0, 'header' => "User-Agent: TelXBot\r\n",
            'ignore_errors' => true, 'max_redirects' => 3]]);
        $html = @file_get_contents($url, false, $ctx);
        if (!$html || strlen($html) > 1500000) return ['url' => $url, 'site' => parse_url($url, PHP_URL_HOST)];
        $title = null; $desc = null;
        if (preg_match('#<meta[^>]+property=["\']og:title["\'][^>]+content=["\']([^"\']*)#i', $html, $t))
            $title = html_entity_decode($t[1], ENT_QUOTES);
        if (!$title && preg_match('#<title[^>]*>(.*?)</title>#is', $html, $t))
            $title = html_entity_decode(trim(strip_tags($t[1])), ENT_QUOTES);
        if (preg_match('#<meta[^>]+property=["\']og:description["\'][^>]+content=["\']([^"\']*)#i', $html, $t))
            $desc = html_entity_decode($t[1], ENT_QUOTES);
        $img = null;
        if (preg_match('#<meta[^>]+property=["\']og:image["\'][^>]+content=["\']([^"\']*)#i', $html, $t)) {
            $img = rel2abs(html_entity_decode($t[1], ENT_QUOTES), $url);
            $img = download_link_image($img) ?: $img;
        }
        return ['url' => $url, 'title' => mb_substr((string)$title, 0, 120),
                'desc' => mb_substr((string)$desc, 0, 200), 'image' => $img,
                'site' => parse_url($url, PHP_URL_HOST)];
    } catch (Throwable) { return ['url' => $url]; }
}

/* ---------- Chats helpers ---------- */
function my_membership(int $chat_id, int $uid): ?array {
    $st = db()->prepare('SELECT * FROM chat_members WHERE chat_id = ? AND user_id = ?');
    $st->execute([$chat_id, $uid]);
    return $st->fetch() ?: null;
}
function chat_of(int $chat_id): ?array {
    $st = db()->prepare('SELECT * FROM chats WHERE id = ?');
    $st->execute([$chat_id]);
    return $st->fetch() ?: null;
}
function require_member(int $chat_id): array {
    $m = my_membership($chat_id, me_id());
    if (!$m) fail('forbidden', 'You are not a member of this chat');
    return $m;
}
function is_blocked(int $owner, int $other): bool {
    $st = db()->prepare('SELECT id FROM blocks WHERE user_id = ? AND blocked_id = ?');
    $st->execute([$owner, $other]);
    return (bool)$st->fetch();
}
function peer_of_private(int $chat_id, int $me): ?array {
    $st = db()->prepare('SELECT user_id FROM chat_members WHERE chat_id = ? AND user_id <> ?');
    $st->execute([$chat_id, $me]);
    $r = $st->fetch();
    return $r ? user_by_id((int)$r['user_id']) : null;
}
function ensure_saved_chat(int $uid): int {
    $st = db()->prepare("SELECT c.id FROM chats c JOIN chat_members m ON m.chat_id = c.id
                         WHERE c.type = 'saved' AND m.user_id = ? LIMIT 1");
    $st->execute([$uid]);
    $r = $st->fetch();
    if ($r) return (int)$r['id'];
    db()->prepare("INSERT INTO chats (type, created_at, last_message_at) VALUES ('saved', UTC_TIMESTAMP(), UTC_TIMESTAMP())")->execute();
    $id = (int)db()->lastInsertId();
    db()->prepare("INSERT INTO chat_members (chat_id, user_id, role, joined_at) VALUES (?,?,?,UTC_TIMESTAMP())")->execute([$id, $uid, 'admin']);
    return $id;
}

/* ---------- Message assembler ---------- */
function msg_public(array $m, int $viewer): array {
    $enc = (int)$m['encrypted'] === 1;
    $a = [
        'id' => (int)$m['id'], 'chat_id' => (int)$m['chat_id'],
        'sender_id' => $m['sender_id'] !== null ? (int)$m['sender_id'] : 0,
        'sender_name' => (string)$m['sender_name'],
        'type' => $m['type'],
        'text' => (string)($m['text'] ?? ''),
        'media' => $m['media'] ? absolute_url($m['media']) : null,
        'thumb' => $m['thumb'] ? absolute_url($m['thumb']) : null,
        'media_name' => (string)$m['media_name'], 'media_size' => (int)$m['media_size'],
        'media_duration' => (int)$m['media_duration'],
        'sticker' => $m['sticker'] !== null ? (string)$m['sticker'] : null,
        'fwd_from' => $m['fwd_from_user_id'] !== null ? (int)$m['fwd_from_user_id'] : null,
        'fwd_name' => (string)($m['fwd_name'] ?? ''),
        'reply_to' => $m['reply_to'] !== null ? (int)$m['reply_to'] : null,
        'edited' => $m['edited_at'] !== null,
        'scheduled' => $m['scheduled_at'] !== null,
        'scheduled_ts' => ts($m['scheduled_at']),
        'enc' => $enc,
        'ts' => ts($m['created_at']),
    ];
    if (!empty($m['link_json'])) $a['link'] = json_decode($m['link_json'], true);
    if ($m['reply_to'] !== null) {
        $st = db()->prepare('SELECT * FROM messages WHERE id = ?');
        $st->execute([(int)$m['reply_to']]);
        $r = $st->fetch();
        $a['reply'] = $r ? [
            'id' => (int)$r['id'], 'name' => (string)$r['sender_name'], 'type' => $r['type'],
            'text' => mb_substr((string)($r['text'] ?? ''), 0, 120),
            'has_media' => $r['media'] !== null,
        ] : ['id' => (int)$m['reply_to'], 'deleted' => true];
    }
    if ($a['fwd_from'] && !$a['fwd_name']) {
        $u = user_by_id($a['fwd_from']);
        $a['fwd_name'] = $u ? trim($u['first_name'] . ' ' . $u['last_name']) : 'Deleted Account';
    }
    $st = db()->prepare('SELECT emoji, COUNT(*) c, SUM(user_id = ?) mine FROM reactions WHERE message_id = ? GROUP BY emoji');
    $st->execute([$viewer, (int)$m['id']]);
    $a['reactions'] = array_map(fn($r) => ['emoji' => $r['emoji'], 'count' => (int)$r['c'], 'mine' => (int)$r['mine'] > 0], $st->fetchAll());
    return $a;
}
function message_by_id(int $id): ?array {
    $st = db()->prepare('SELECT * FROM messages WHERE id = ?');
    $st->execute([$id]);
    return $st->fetch() ?: null;
}

/* ---------- Scheduled & auto-delete flush ---------- */
function flush_due(): void {
    $pdo = db();
    $st = $pdo->query("SELECT * FROM messages WHERE scheduled_at IS NOT NULL AND scheduled_at <= UTC_TIMESTAMP() AND deleted = 0 LIMIT 20");
    foreach ($st->fetchAll() as $m) {
        $pdo->prepare("UPDATE messages SET scheduled_at = NULL WHERE id = ?")->execute([$m['id']]);
        $pdo->prepare('UPDATE chats SET last_message_id = ?, last_message_at = ? WHERE id = ? AND (last_message_id IS NULL OR last_message_id < ?)')
            ->execute([$m['id'], $m['created_at'], $m['chat_id'], $m['id']]);
        $others = chat_member_ids((int)$m['chat_id']);
        foreach ($others as $uid) {
            $pdo->prepare('UPDATE chat_members SET unread_count = unread_count + 1 WHERE chat_id = ? AND user_id = ?')
                ->execute([$m['chat_id'], $uid]);
            emit($uid, 'message_new', ['chat_id' => (int)$m['chat_id'], 'message' => msg_public($m, $uid)], (int)$m['chat_id'], (int)($m['sender_id'] ?? 0));
        }
    }
    $st = $pdo->query("SELECT id, chat_id FROM messages WHERE auto_delete_at IS NOT NULL AND auto_delete_at <= UTC_TIMESTAMP() AND deleted = 0 LIMIT 50");
    $del = $st->fetchAll();
    if ($del) {
        $ids = array_map(fn($d) => (int)$d['id'], $del);
        $in = implode(',', array_fill(0, count($ids), '?'));
        $pdo->prepare("UPDATE messages SET deleted = 1 WHERE id IN ($in)")->execute($ids);
        foreach ($del as $d) {
            $chat = chat_of((int)$d['chat_id']);
            if ($chat && (int)$chat['pinned_message_id'] === (int)$d['id'])
                $pdo->prepare('UPDATE chats SET pinned_message_id = NULL WHERE id = ?')->execute([$chat['id']]);
            emit(chat_member_ids((int)$d['chat_id']), 'message_deleted', ['chat_id' => (int)$d['chat_id'], 'message_id' => (int)$d['id']], (int)$d['chat_id']);
        }
    }
    $pdo->prepare("DELETE FROM stories WHERE expires_at < UTC_TIMESTAMP()")->execute();
}

/* ---------- Registration helpers ---------- */
function random_token(int $len = 64): string { return bin2hex(random_bytes(intdiv($len, 2))); }
function valid_username(string $u): bool { return (bool)preg_match('/^[a-zA-Z0-9_]{5,32}$/', $u); }
function username_taken(string $u): bool {
    $st = db()->prepare('SELECT id FROM users WHERE LOWER(username) = LOWER(?)');
    $st->execute([$u]);
    return (bool)$st->fetch();
}
function create_session(int $uid, string $device = '', string $ip = ''): array {
    $token = random_token();
    db()->prepare('INSERT INTO sessions (user_id, token_hash, device, ip, created_at, last_active)
                   VALUES (?,?,?,?,UTC_TIMESTAMP(),UTC_TIMESTAMP())')
        ->execute([$uid, hash('sha256', $token), $device ?: 'Unknown device', $ip ?: ($_SERVER['REMOTE_ADDR'] ?? '')]);
    return ['token' => $token, 'id' => (int)db()->lastInsertId()];
}
function store_code(int $uid, string $purpose, int $ttl = 900): array {
    $code = sprintf('%06d', random_int(0, 999999));
    db()->prepare('DELETE FROM verification_codes WHERE user_id = ? AND purpose = ?')->execute([$uid, $purpose]);
    db()->prepare('INSERT INTO verification_codes (user_id, purpose, code_hash, expires_at, created_at)
                   VALUES (?,?,?, UTC_TIMESTAMP() + INTERVAL ? SECOND, UTC_TIMESTAMP())')
        ->execute([$uid, $purpose, hash('sha256', $code), $ttl]);
    return ['code' => $code];
}
function check_code(int $uid, string $purpose, string $code): bool {
    $st = db()->prepare("SELECT * FROM verification_codes WHERE user_id = ? AND purpose = ? AND code_hash = ? AND used = 0 AND expires_at > UTC_TIMESTAMP()");
    $st->execute([$uid, $purpose, hash('sha256', $code)]);
    $r = $st->fetch();
    if (!$r) return false;
    db()->prepare('UPDATE verification_codes SET used = 1 WHERE id = ?')->execute([$r['id']]);
    return true;
}
function contact_ids_of(int $uid): array {
    $st = db()->prepare('SELECT contact_id FROM contacts WHERE user_id = ?');
    $st->execute([$uid]);
    return array_map(fn($r) => (int)$r['contact_id'], $st->fetchAll());
}

/* entry guard */
if (!defined('TELX_API')) { http_response_code(403); exit('forbidden'); }
