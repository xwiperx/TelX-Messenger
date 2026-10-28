<?php
/** TelX — lib_messages.php — shared message serialization helpers */

function serialize_message(array $m, string $meId): array {
    // reply-to
    $reply = null;
    if (!empty($m['reply_to_id'])) {
        $r = one("SELECT m.id, m.text, m.type, m.sender_id, u.first_name FROM tx_messages m JOIN tx_users u ON u.id = m.sender_id WHERE m.id = ?",
            [$m['reply_to_id']]);
        if ($r) {
            $reply = [
                'id' => $r['id'], 'text' => $r['text'], 'type' => $r['type'],
                'senderName' => $r['first_name'], 'mine' => $r['sender_id'] === $meId,
            ];
        }
    }

    // reactions grouped by emoji
    $reactions = [];
    foreach (all("SELECT r.emoji, r.user_id, u.first_name FROM tx_reactions r JOIN tx_users u ON u.id = r.user_id WHERE r.message_id = ?", [$m['id']]) as $r) {
        if (!isset($reactions[$r['emoji']])) $reactions[$r['emoji']] = ['emoji' => $r['emoji'], 'users' => []];
        $reactions[$r['emoji']]['users'][] = ['id' => $r['user_id'], 'name' => $r['first_name']];
    }

    // read-by-others (for ✓✓)
    $read = true;
    if ($m['sender_id'] === $meId) {
        $row = one("SELECT COUNT(*) c FROM tx_message_reads WHERE message_id = ? AND user_id != ?", [$m['id'], $meId]);
        $read = (int)($row['c'] ?? 0) > 0;
    }

    return [
        'id'              => $m['id'],
        'chatId'          => $m['chat_id'],
        'text'            => (string)$m['text'],
        'type'            => $m['type'],
        'mediaUrl'        => $m['media_url'],
        'fileName'        => $m['file_name'],
        'fileSize'        => $m['file_size'] !== null ? (int)$m['file_size'] : null,
        'duration'        => $m['duration'] !== null ? (int)$m['duration'] : null,
        'replyTo'         => $reply,
        'forwardedByName' => $m['forwarded_by_name'],
        'preview'         => $m['preview_url'] && !(int)$m['is_deleted'] ? [
            'title' => $m['preview_title'], 'image' => $m['preview_image'],
            'url' => $m['preview_url'], 'description' => $m['preview_desc'],
        ] : null,
        'isEdited'        => (bool)$m['is_edited'],
        'isDeleted'       => (bool)$m['is_deleted'],
        'pinned'          => (bool)$m['pinned'],
        'createdAt'       => $m['created_at'],
        'mine'            => $m['sender_id'] === $meId,
        'sender'          => public_user(one("SELECT * FROM tx_users WHERE id = ?", [$m['sender_id']]), $meId),
        'read'            => $read,
        'reactions'       => array_values($reactions),
    ];
}

function is_member(string $chatId, string $userId): ?array {
    return one("SELECT * FROM tx_chat_members WHERE chat_id = ? AND user_id = ?", [$chatId, $userId]);
}

function chat_member_ids(string $chatId): array {
    return array_map(fn($r) => $r['user_id'], all("SELECT user_id FROM tx_chat_members WHERE chat_id = ?", [$chatId]));
}

function extract_url(string $text): ?string {
    if (preg_match('/https?:\/\/[^\s<>"\']+/i', $text, $m)) return $m[0];
    return null;
}

/** Fetch basic OpenGraph metadata (best-effort, 4s timeout). */
function fetch_preview(string $url): ?array {
    $ctx = stream_context_create(['http' => [
        'timeout' => 4, 'ignore_errors' => true,
        'header' => "User-Agent: Mozilla/5.0 TelXBot\r\n",
    ]]);
    $html = @file_get_contents($url, false, $ctx);
    if (!$html) return null;
    $html = mb_substr($html, 0, 200000);
    $pick = function (string $re) use ($html) {
        return preg_match($re, $html, $m) ? trim($m[1]) : null;
    };
    return [
        'title' => $pick('/<meta[^>]+property=["\']og:title["\'][^>]+content=["\']([^"\']+)["\']/i')
            ?? $pick('/<title[^>]*>([^<]+)<\/title>/i'),
        'image' => $pick('/<meta[^>]+property=["\']og:image["\'][^>]+content=["\']([^"\']+)["\']/i'),
        'description' => $pick('/<meta[^>]+property=["\']og:description["\'][^>]+content=["\']([^"\']+)["\']/i'),
        'url' => $url,
    ];
}
