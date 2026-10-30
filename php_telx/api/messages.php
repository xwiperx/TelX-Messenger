<?php
/* TelX API — messages.php : list/send/edit/delete/forward/pin/read/typing/react/search
   v6: media albums, silent/anon sends, text effects, multi-pin, channel comments,
   jump-to-date, slow mode, granular permissions, audio media tab. */
define('TELX_API', 1);
require __DIR__ . '/../config.php';

/** msg_public + v6 fields (group/silent/anon/pinned/effect/comment_to). */
function msg_v6(array $m, int $viewer, array $commentCounts = []): array {
    $a = msg_public($m, $viewer);
    $a['group_id'] = ($m['group_id'] ?? null) !== null ? (int)$m['group_id'] : null;
    $a['silent'] = (int)($m['silent'] ?? 0) === 1;
    $a['anon'] = (int)($m['anon'] ?? 0) === 1;
    $a['pinned'] = (int)($m['pinned'] ?? 0) === 1;
    $a['effect'] = !empty($m['effect']) ? (string)$m['effect'] : null;
    $a['comment_to'] = ($m['comment_to'] ?? null) !== null ? (int)$m['comment_to'] : null;
    if ($a['comment_to'] === null)
        $a['comment_count'] = $commentCounts[(int)$m['id']] ?? 0;
    // v7: interactive buttons / inline rich media / ephemeral / recurring
    if (!empty($m['buttons'])) $a['buttons'] = json_decode($m['buttons'], true);
    if (!empty($m['inline_json'])) $a['inline'] = json_decode($m['inline_json'], true);
    $a['ephemeral_to'] = ($m['ephemeral_to'] ?? null) !== null ? (int)$m['ephemeral_to'] : null;
    $a['repeat_every'] = (int)($m['repeat_every'] ?? 0);
    // v7: mighty polls — attach live poll data
    if (($m['type'] ?? '') === 'poll') {
        $st = db()->prepare('SELECT id FROM polls WHERE message_id = ?');
        $st->execute([(int)$m['id']]);
        $pid = $st->fetch();
        if ($pid) $a['poll'] = poll_public((int)$pid['id'], $viewer);
        else $a['poll'] = null;
    }
    return $a;
}

/** v7: serialize a poll + results for a viewer. */
function poll_public(int $pollId, int $viewer): array {
    $st = db()->prepare('SELECT * FROM polls WHERE id = ?');
    $st->execute([$pollId]);
    $p = $st->fetch();
    if (!$p) return ['id' => 0, 'question' => '', 'options' => []];
    $options = json_decode($p['options_json'], true) ?: [];
    $counts = [];
    $cst = db()->prepare('SELECT option_idx, COUNT(*) c FROM poll_votes WHERE poll_id = ? GROUP BY option_idx');
    $cst->execute([$pollId]);
    foreach ($cst->fetchAll() as $r) $counts[(int)$r['option_idx']] = (int)$r['c'];
    $mine = [];
    $mst = db()->prepare('SELECT option_idx FROM poll_votes WHERE poll_id = ? AND user_id = ?');
    $mst->execute([$pollId, $viewer]);
    foreach ($mst->fetchAll() as $r) $mine[] = (int)$r['option_idx'];
    $correct = json_decode((string)($p['correct_json'] ?? ''), true) ?: [];
    $total = array_sum($counts);
    $outOpts = [];
    foreach ($options as $i => $label) {
        $o = ['text' => (string)$label, 'votes' => $counts[$i] ?? 0,
              'mine' => in_array($i, $mine, true)];
        // quiz mode: reveal the right answer(s) only after voting or closing
        if ((int)$p['quiz'] === 1 && ($mine || (int)$p['closed'] === 1))
            $o['correct'] = in_array($i, $correct, true);
        $outOpts[] = $o;
    }
    $sugg = [];
    $sst = db()->prepare('SELECT s.option_text, COUNT(*) c FROM poll_suggestions s WHERE s.poll_id = ? GROUP BY s.option_text ORDER BY c DESC LIMIT 8');
    $sst->execute([$pollId]);
    foreach ($sst->fetchAll() as $r) $sugg[] = ['text' => (string)$r['option_text'], 'count' => (int)$r['c']];
    return [
        'id' => (int)$p['id'],
        'question' => (string)$p['question'],
        'options' => $outOpts,
        'multiple' => (int)$p['multiple'] === 1,
        'quiz' => (int)$p['quiz'] === 1,
        'closed' => (int)$p['closed'] === 1,
        'total_votes' => $total,
        'voted' => $mine !== [],
        'suggestions' => $sugg,
        'media' => $p['media'] ? absolute_url($p['media']) : null,
        'thumb' => $p['thumb'] ? absolute_url($p['thumb']) : null,
    ];
}
/** fetch comment counts for a set of channel post ids (one grouped query). */
function comment_counts(array $ids): array {
    if (!$ids) return [];
    $in = implode(',', array_fill(0, count($ids), '?'));
    $st = db()->prepare("SELECT comment_to, COUNT(*) c FROM messages WHERE comment_to IN ($in) AND deleted = 0 GROUP BY comment_to");
    $st->execute($ids);
    $out = [];
    foreach ($st->fetchAll() as $r) $out[(int)$r['comment_to']] = (int)$r['c'];
    return $out;
}
/** member permission check against chat.perms JSON (admins always pass). */
function member_can(array $membership, ?array $chat, string $right): bool {
    if (!$chat) return true;
    if ((string)$membership['role'] === 'admin') return true;
    if ($chat['type'] === 'private' || $chat['type'] === 'saved') return true;
    if (empty($chat['perms'])) return true;
    $p = json_decode($chat['perms'], true);
    if (!is_array($p)) return true;
    if ((int)($p[$right] ?? 1) === 1) return true;
    return false;
}

$action = p('action') ?? '';

switch ($action) {

case 'list': {
    $me = me();
    $cid = pint('chat_id');
    require_member($cid);
    flush_due();
    $beforeId = pint('before_id');
    $limit = max(1, min(60, pint('limit', 40)));
    // main list hides comment-thread replies (they live in their own threads)
    // v7: ephemeral messages are visible only to their target user
    $sql = 'SELECT * FROM messages WHERE chat_id = ? AND deleted = 0 AND comment_to IS NULL AND scheduled_at IS NULL AND (ephemeral_to IS NULL OR ephemeral_to = ' . (int)$me['uid'] . ')';
    $args = [$cid];
    if ($beforeId > 0) { $sql .= ' AND id < ?'; $args[] = $beforeId; }
    $sql .= ' ORDER BY id DESC LIMIT ' . $limit;
    $st = db()->prepare($sql); $st->execute($args);
    $rows = $st->fetchAll();
    $isChannel = chat_of($cid)['type'] ?? '';
    $cc = [];
    if ($isChannel === 'channel') {
        $postIds = array_map(fn($m) => (int)$m['id'], array_filter($rows, fn($m) => $m['comment_to'] === null));
        $cc = comment_counts($postIds);
    }
    $msgs = array_reverse(array_map(fn($m) => msg_v6($m, (int)$me['uid'], $cc), $rows));
    // mark delivered up to newest
    $newest = count($msgs) ? end($msgs)['id'] : 0;
    $mine = my_membership($cid, (int)$me['uid']);
    if ($newest > (int)$mine['last_delivered_message_id']) {
        db()->prepare('UPDATE chat_members SET last_delivered_message_id = ? WHERE chat_id = ? AND user_id = ?')
            ->execute([$newest, $cid, $me['uid']]);
        $senders = [];
        foreach ($msgs as $msg2)
            if ($msg2['sender_id'] && (int)$msg2['sender_id'] !== (int)$me['uid'])
                $senders[(int)$msg2['sender_id']] = true;
        if ($senders) {
            $otherIds = chat_member_ids($cid, (int)$me['uid']);
            $recipients = array_values(array_unique(array_merge($otherIds, array_map(fn($x) => (int)$x, array_keys($senders)))));
            emit($recipients, 'message_delivered', ['chat_id' => $cid, 'last_delivered_id' => $newest], $cid);
        }
    }
    ok(['messages' => $msgs, 'chat_id' => $cid]);
}

case 'send': {
    $me = me();
    $cid = pint('chat_id');
    $membership = require_member($cid);
    $chat = chat_of($cid);
    if (!$chat) fail('not_found', 'Chat not found');
    // channels are broadcast-only: admins post, subscribers read
    if ($chat['type'] === 'channel' && (string)$membership['role'] !== 'admin')
        fail('forbidden', 'Only channel admins can post here');
    $type = p('type') ?? 'text';
    if (!in_array($type, ['text','photo','file','voice','video','sticker','gif','video_note','location','contact','poll','todo'], true)) fail('invalid', 'Bad message type');
    // banned members can never post
    if ((string)$membership['role'] === 'banned') fail('banned', 'You are banned in this chat');
    // granular member permissions (admins unaffected)
    if (in_array($type, ['photo','file','voice','video','gif','video_note','sticker','location','contact'], true)
        && !member_can($membership, $chat, 'send_media'))
        fail('forbidden', 'Media sending is disabled for members here');
    if ($type === 'text' && preg_match('#https?://#i', (string)($text = (string)(p('text') ?? '')))
        && !member_can($membership, $chat, 'send_links'))
        fail('forbidden', 'Link sending is disabled for members here');
    // slow mode: non-admin members must wait between messages
    $slow = (int)($chat['slow_mode'] ?? 0);
    if ($slow > 0 && (string)$membership['role'] !== 'admin') {
        $st = db()->prepare('SELECT created_at FROM messages WHERE chat_id = ? AND sender_id = ? AND comment_to IS NULL AND scheduled_at IS NULL ORDER BY id DESC LIMIT 1');
        $st->execute([$cid, $me['uid']]);
        $last = $st->fetch();
        if ($last) {
            $waited = time() - (int)ts($last['created_at']);
            if ($waited < $slow) fail('slow_mode', 'Slow mode: wait ' . ($slow - $waited) . 's before sending again');
        }
    }
    // anonymous (channel-style) posting is admin-only
    $anon = pint('anon') === 1 && (string)$membership['role'] === 'admin' ? 1 : 0;
    $effect = null;
    $e = p('effect');
    if ($e !== null && in_array($e, ['confetti','fireworks','hearts'], true)) $effect = $e;
    $silent = pint('silent') === 1 ? 1 : 0;
    $groupId = pint('group_id');
    if ($groupId > 0) $groupId = $groupId > PHP_INT_MAX >> 1 ? 0 : $groupId;   // albums share one group id
    // channel comments: reply inside a post's thread
    $commentTo = pint('comment_to');
    if ($commentTo > 0) {
        $post = message_by_id($commentTo);
        if (!$post || (int)$post['chat_id'] !== $cid || $post['comment_to'] !== null)
            fail('invalid', 'Bad comment target');
    } else $commentTo = 0;
    $text = (string)(p('text') ?? '');
    $media = p('media_rel');
    $thumb = p('thumb_rel');
    $enc = pint('encrypted') === 1 && (int)$chat['secret'] === 1;
    if ($type === 'text' && $text === '' && !$enc) fail('invalid', 'Empty message');
    if (in_array($type, ['photo','file','voice','video','gif','video_note'], true) && !$media) fail('invalid', 'media required');
    if ($type === 'sticker' && !(p('sticker'))) fail('invalid', 'sticker id required');
    if ($type === 'location' && !(p('media_json'))) fail('invalid', 'location json required');
    if ($type === 'contact' && !(p('media_json'))) fail('invalid', 'contact json required');
    // v7: mighty polls — question + 2..10 options (+ optional media + quiz answers)
    $pollPayload = null;
    if ($type === 'poll') {
        $pollPayload = json_decode((string)(p('poll_json') ?? ''), true);
        if (!is_array($pollPayload)) fail('invalid', 'poll json required');
        $question = trim((string)($pollPayload['question'] ?? ''));
        $options = array_values(array_filter(array_map(fn($o) => trim((string)$o),
            is_array($pollPayload['options'] ?? null) ? $pollPayload['options'] : []), fn($o) => $o !== ''));
        if (mb_strlen($question) < 2) fail('invalid', 'Poll question is too short');
        if (count($options) < 2) fail('invalid', 'Poll needs at least 2 options');
        if (count($options) > 10) $options = array_slice($options, 0, 10);
        $pollPayload['question'] = $question;
        $pollPayload['options'] = $options;
        $text = $question; // chat-list preview
    }
    // v7: shared to-do checklist (items live in media_json)
    if ($type === 'todo') {
        $items = json_decode((string)(p('media_json') ?? '[]'), true);
        if (!is_array($items) || $items === []) fail('invalid', 'todo items required');
        $clean = [];
        foreach (array_slice($items, 0, 30) as $it)
            $clean[] = ['t' => mb_substr(trim((string)($it['t'] ?? '')), 0, 120), 'd' => !empty($it['d'])];
        $clean = array_values(array_filter($clean, fn($x) => $x['t'] !== ''));
        if (count($clean) === 0) fail('invalid', 'todo items cannot be empty');
        $mediaJson = json_encode(['items' => $clean], JSON_UNESCAPED_UNICODE);
        $text = 'Checklist (' . count($clean) . ' items)';
    }
    // v7: interactive buttons (max 8, label ≤ 64 chars)
    $buttonsJson = null;
    $btnsRaw = p('buttons');
    if ($btnsRaw !== null && $btnsRaw !== '') {
        $btns = json_decode($btnsRaw, true);
        if (!is_array($btns)) fail('invalid', 'bad buttons json');
        $cleanBtns = [];
        foreach (array_slice($btns, 0, 8) as $b) {
            $label = mb_substr(trim((string)($b['label'] ?? '')), 0, 64);
            if ($label === '') continue;
            $row = ['label' => $label];
            if (!empty($b['id'])) $row['id'] = (string)$b['id'];
            if (!empty($b['url'])) $row['url'] = (string)$b['url'];
            $cleanBtns[] = $row;
        }
        if ($cleanBtns) $buttonsJson = json_encode($cleanBtns, JSON_UNESCAPED_UNICODE);
    }
    // v7: inline rich media segments (text+media interleaved, max 12 segments)
    $inlineJson = null;
    $inlineRaw = p('inline_json');
    if ($inlineRaw !== null && $inlineRaw !== '') {
        $segs = json_decode($inlineRaw, true);
        if (is_array($segs) && $segs !== []) {
            $cleanSegs = [];
            foreach (array_slice($segs, 0, 12) as $s) {
                $k = (string)($s['type'] ?? 'text');
                if ($k === 'text') $cleanSegs[] = ['type' => 'text', 'text' => mb_substr((string)($s['text'] ?? ''), 0, 8000)];
                elseif (in_array($k, ['photo','video','file','music'], true) && !empty($s['media']))
                    $cleanSegs[] = ['type' => $k, 'media' => (string)$s['media'],
                        'name' => (string)($s['name'] ?? ''), 'size' => (int)($s['size'] ?? 0)];
            }
            if ($cleanSegs) $inlineJson = json_encode($cleanSegs, JSON_UNESCAPED_UNICODE);
        }
    }
    // v7: ephemeral (visible to one target user only) + recurring schedules
    $ephemeralTo = null;
    $eph = pint('ephemeral_to');
    if ($eph > 0 && in_array($chat['type'], ['group', 'channel', 'community'], true)
        && in_array((string)$membership['role'], ['admin'], true)) {
        // only admins can address private-to-one messages inside groups
        if (my_membership($cid, $eph)) $ephemeralTo = $eph;
    }
    $repeatEvery = 0;
    {
        $rep = pint('repeat_every');
        if (in_array($rep, [0, 3600, 21600, 86400, 604800], true)) $repeatEvery = $rep;
    }
    // v7: rich text limit — long posts up to 32k chars
    if (mb_strlen($text) > 32768) $text = mb_substr($text, 0, 32768);
    if ($chat['type'] === 'private') {
        $peer = peer_of_private($cid, (int)$me['uid']);
        if ($peer && is_blocked((int)$peer['uid'], (int)$me['uid'])) fail('blocked', 'You cannot message this user');
    }
    $replyTo = pint('reply_to');
    if ($replyTo) {
        $rm = message_by_id($replyTo);
        if (!$rm || (int)$rm['chat_id'] !== $cid) fail('invalid', 'Bad reply target');
    }
    $scheduledTs = pint('scheduled_ts');
    $isScheduled = $scheduledTs > time() + 30;
    $linkJson = null;
    if ($type === 'text' && !$enc) {
        $prev = fetch_link_preview($text);
        if ($prev) $linkJson = json_encode($prev, JSON_UNESCAPED_UNICODE);
    }
    $autoDeleteAt = null;
    if (!$isScheduled && (int)$chat['auto_delete_secs'] > 0)
        $autoDeleteAt = sqlts(time() + (int)$chat['auto_delete_secs']);
    $senderName = trim($me['first_name'] . ' ' . $me['last_name']);
    $schedSql = $isScheduled ? sqlts($scheduledTs) : null;
    if ($type !== 'todo') $mediaJson = p('media_json');
    db()->prepare("INSERT INTO messages (chat_id, sender_id, sender_name, type, text, media, thumb,
                    media_name, media_size, media_duration, sticker, link_json, media_json, reply_to, group_id,
                    silent, anon, effect, comment_to, encrypted,
                    buttons, inline_json, ephemeral_to, repeat_every,
                    scheduled_at, auto_delete_at, created_at)
                    VALUES (:chat_id, :sender_id, :sender_name, :type, :text, :media, :thumb,
                            :media_name, :media_size, :media_duration, :sticker, :link_json, :media_json, :reply_to, :group_id,
                            :silent, :anon, :effect, :comment_to, :encrypted,
                            :buttons, :inline_json, :ephemeral_to, :repeat_every,
                            :scheduled_at, :auto_delete_at, UTC_TIMESTAMP())")
        ->execute(['chat_id' => $cid, 'sender_id' => $me['uid'], 'sender_name' => $senderName, 'type' => $type,
                   'text' => $enc ? $text : ($text !== '' ? $text : null),
                   'media' => $media, 'thumb' => $thumb,
                   'media_name' => (string)(p('media_name') ?? ''), 'media_size' => pint('media_size'),
                   'media_duration' => pint('media_duration'), 'sticker' => p('sticker'), 'link_json' => $linkJson,
                   'media_json' => $mediaJson,
                   'reply_to' => $replyTo ?: null,
                   'group_id' => $groupId > 0 ? $groupId : null,
                   'silent' => $silent, 'anon' => $anon, 'effect' => $effect,
                   'comment_to' => $commentTo > 0 ? $commentTo : null,
                   'encrypted' => $enc ? 1 : 0,
                   'buttons' => $buttonsJson, 'inline_json' => $inlineJson,
                   'ephemeral_to' => $ephemeralTo, 'repeat_every' => $repeatEvery,
                   'scheduled_at' => $schedSql, 'auto_delete_at' => $autoDeleteAt]);
    $mid = (int)db()->lastInsertId();
    // v7: create the poll row after the message exists
    if ($type === 'poll' && $pollPayload) {
        $multiple = !empty($pollPayload['multiple']) ? 1 : 0;
        $quiz = !empty($pollPayload['quiz']) ? 1 : 0;
        $correct = is_array($pollPayload['correct'] ?? null) ? $pollPayload['correct'] : [];
        if (!$quiz) $correct = [];
        db()->prepare('INSERT INTO polls (message_id, question, options_json, multiple, quiz, correct_json, media, thumb)
                       VALUES (?,?,?,?,?,?,?,?)')
            ->execute([$mid, $pollPayload['question'], json_encode($pollPayload['options'], JSON_UNESCAPED_UNICODE),
                       $multiple, $quiz, $correct ? json_encode($correct) : null,
                       $pollPayload['media_rel'] ?? null, $pollPayload['thumb_rel'] ?? null]);
    }
    $m = message_by_id($mid);
    if (!$isScheduled) {
        // comments only notify the other participants of the thread / chat,
        // and do NOT bump the main chat list entry
        $isComment = $commentTo > 0;
        if (!$isComment && !$ephemeralTo) {
            db()->prepare('UPDATE chats SET last_message_id = ?, last_message_at = UTC_TIMESTAMP() WHERE id = ? AND (last_message_id IS NULL OR last_message_id < ?)')
                ->execute([$mid, $cid, $mid]);
            if ($ephemeralTo === null) {
                $up = db()->prepare('UPDATE chat_members SET unread_count = unread_count + 1 WHERE chat_id = ? AND user_id = ?');
                foreach ($others = chat_member_ids($cid, (int)$me['uid']) as $uid2) $up->execute([$cid, $uid2]);
            }
        }
        if ($ephemeralTo !== null) {
            // private-to-one message: only the target gets the event + unread bump
            db()->prepare('UPDATE chat_members SET unread_count = unread_count + 1 WHERE chat_id = ? AND user_id = ?')
                ->execute([$cid, $ephemeralTo]);
            emit($ephemeralTo, 'message_new', ['chat_id' => $cid, 'message' => msg_v6($m, $ephemeralTo)], $cid, (int)$me['uid']);
        } else {
            foreach (chat_member_ids($cid, (int)$me['uid']) as $uid2)
                emit($uid2, 'message_new', ['chat_id' => $cid, 'message' => msg_v6($m, $uid2)], $cid, (int)$me['uid']);
        }
        db()->prepare('UPDATE chat_members SET unread_count = 0, last_read_message_id = ?, last_delivered_message_id = ? WHERE chat_id = ? AND user_id = ?')
            ->execute([$mid, $mid, $cid, $me['uid']]);
    }
    ok(['message' => msg_v6($m, (int)$me['uid']), 'scheduled' => $isScheduled]);
}

case 'edit': {
    $me = me();
    $mid = pint('message_id');
    $m = message_by_id($mid);
    if (!$m || (int)$m['deleted'] === 1) fail('not_found', 'Message not found');
    if ((int)$m['sender_id'] !== (int)$me['uid']) fail('forbidden', 'You can edit only your own messages');
    if (time() - (int)ts($m['created_at']) > 48 * 3600) fail('too_late', 'Editing allowed within 48 hours');
    $text = (string)(p('text') ?? '');
    if ($text === '') fail('invalid', 'Empty text');
    db()->prepare('UPDATE messages SET text = ?, edited_at = UTC_TIMESTAMP() WHERE id = ?')->execute([$text, $mid]);
    $m = message_by_id($mid);
    emit(chat_member_ids((int)$m['chat_id']), 'message_edited',
        ['chat_id' => (int)$m['chat_id'], 'message' => msg_v6($m, 0)], (int)$m['chat_id'], (int)$me['uid']);
    ok(['message' => msg_v6($m, (int)$me['uid'])]);
}

case 'delete': {
    $me = me();
    $mid = pint('message_id');
    $m = message_by_id($mid);
    if (!$m || (int)$m['deleted'] === 1) fail('not_found', 'Message not found');
    $chat = chat_of((int)$m['chat_id']);
    $membership = my_membership((int)$m['chat_id'], (int)$me['uid']);
    if (!$membership) fail('forbidden', 'Not your chat');
    $isOwn = (int)($m['sender_id'] ?? 0) === (int)$me['uid'];
    $isAdmin = $chat && ($chat['type'] === 'group' || $chat['type'] === 'channel') && $membership['role'] === 'admin';
    if (!$isOwn && !$isAdmin) fail('forbidden', 'Not your message');
    db()->prepare('UPDATE messages SET deleted = 1 WHERE id = ?')->execute([$mid]);
    db()->prepare('DELETE FROM reactions WHERE message_id = ?')->execute([$mid]);
    db()->prepare('UPDATE messages SET pinned = 0 WHERE id = ?')->execute([$mid]);
    if ($chat && (int)$chat['pinned_message_id'] === $mid)
        db()->prepare('UPDATE chats SET pinned_message_id = NULL WHERE id = ?')->execute([$chat['id']]);
    emit(chat_member_ids((int)$m['chat_id']), 'message_deleted',
        ['chat_id' => (int)$m['chat_id'], 'message_id' => $mid], (int)$m['chat_id'], (int)$me['uid']);
    ok(['deleted' => $mid]);
}

case 'forward': {
    $me = me();
    $ids = array_map('intval', parr('message_ids'));
    $toChat = pint('to_chat_id');
    $membership = require_member($toChat);
    $target = chat_of($toChat);
    if (!$target) fail('not_found', 'Target chat not found');
    $copied = [];
    foreach ($ids as $mid) {
        $m = message_by_id($mid);
        if (!$m || (int)$m['deleted'] === 1) continue;
        $srcChat = chat_of((int)$m['chat_id']);
        if ($srcChat && (int)$srcChat['secret'] === 1) fail('forbidden', 'Cannot forward from secret chat');
        if ((int)$m['encrypted'] === 1) fail('forbidden', 'Cannot forward encrypted message');
        $srcSender = (int)($m['sender_id'] ?? 0);
        if ($srcSender && $srcSender !== (int)$me['uid']) {
            $srcUser = user_by_id($srcSender);
            if ($srcUser && (int)$srcUser['privacy_lock'] === 1)
                fail('privacy_locked', 'This user locked their messages');
        }
        if ($target['type'] === 'private') {
            $peer = peer_of_private($toChat, (int)$me['uid']);
            if ($peer && is_blocked((int)$peer['uid'], (int)$me['uid'])) fail('blocked', 'Cannot forward there');
        }
        $senderName = trim($me['first_name'] . ' ' . $me['last_name']);
        db()->prepare("INSERT INTO messages (chat_id, sender_id, sender_name, type, text, media, thumb,
                        media_name, media_size, media_duration, sticker, link_json, fwd_from_user_id, fwd_name, created_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP())")
            ->execute([$toChat, $me['uid'], $senderName, $m['type'], $m['text'], $m['media'], $m['thumb'],
                       $m['media_name'], (int)$m['media_size'], (int)$m['media_duration'], $m['sticker'],
                       $m['link_json'], $srcSender ?: null, (string)$m['sender_name']]);
        $newId = (int)db()->lastInsertId();
        $nm = message_by_id($newId);
        db()->prepare('UPDATE chats SET last_message_id = ?, last_message_at = UTC_TIMESTAMP() WHERE id = ? AND (last_message_id IS NULL OR last_message_id < ?)')
            ->execute([$newId, $toChat, $newId]);
        $others = chat_member_ids($toChat, (int)$me['uid']);
        $up = db()->prepare('UPDATE chat_members SET unread_count = unread_count + 1 WHERE chat_id = ? AND user_id = ?');
        foreach ($others as $uid2) $up->execute([$toChat, $uid2]);
        foreach ($others as $uid2)
            emit($uid2, 'message_new', ['chat_id' => $toChat, 'message' => msg_public($nm, $uid2)], $toChat, (int)$me['uid']);
        $copied[] = $newId;
    }
    ok(['forwarded' => $copied]);
}

case 'pin': {
    $me = me();
    $cid = pint('chat_id');
    require_member($cid);
    $mid = pint('message_id');
    if ($mid > 0) {
        $m = message_by_id($mid);
        if (!$m || (int)$m['chat_id'] !== $cid || (int)$m['deleted'] === 1) fail('not_found', 'Message not found');
        db()->prepare('UPDATE chats SET pinned_message_id = ? WHERE id = ?')->execute([$mid, $cid]);
        emit(chat_member_ids($cid), 'message_pinned', ['chat_id' => $cid, 'message_id' => $mid, 'pinned' => true], $cid, (int)$me['uid']);
        ok(['pinned' => $mid]);
    } else {
        db()->prepare('UPDATE chats SET pinned_message_id = NULL WHERE id = ?')->execute([$cid]);
        emit(chat_member_ids($cid), 'message_pinned', ['chat_id' => $cid, 'message_id' => null, 'pinned' => false], $cid, (int)$me['uid']);
        ok(['pinned' => null]);
    }
}

case 'read': {
    $me = me();
    $cid = pint('chat_id');
    require_member($cid);
    $lastId = pint('last_read_id');
    db()->prepare('UPDATE chat_members SET last_read_message_id = ?, unread_count = 0 WHERE chat_id = ? AND user_id = ?')
        ->execute([$lastId, $cid, $me['uid']]);
    $members = chat_member_ids($cid, 0);
    emit($members, 'message_read', ['chat_id' => $cid, 'by_user' => (int)$me['uid'], 'last_read_id' => $lastId], $cid, (int)$me['uid']);
    ok(['read' => $lastId]);
}

case 'delivered': {
    $me = me();
    $cid = pint('chat_id');
    require_member($cid);
    $lastId = pint('last_delivered_id');
    db()->prepare('UPDATE chat_members SET last_delivered_message_id = ? WHERE chat_id = ? AND user_id = ?')
        ->execute([$lastId, $cid, $me['uid']]);
    $senders = chat_member_ids($cid, (int)$me['uid']);
    emit($senders, 'message_delivered', ['chat_id' => $cid, 'last_delivered_id' => $lastId], $cid, (int)$me['uid']);
    ok();
}

case 'typing': {
    $me = me();
    $cid = pint('chat_id');
    require_member($cid);
    db()->prepare('UPDATE chat_members SET typing_at = UTC_TIMESTAMP() WHERE chat_id = ? AND user_id = ?')
        ->execute([$cid, $me['uid']]);
    ok();
}

case 'react': {
    $me = me();
    $mid = pint('message_id');
    $emoji = p('emoji');
    $m = message_by_id($mid);
    if (!$m || (int)$m['deleted'] === 1) fail('not_found', 'Message not found');
    require_member((int)$m['chat_id']);
    $srcChat = chat_of((int)$m['chat_id']);
    if ($srcChat && (int)$srcChat['secret'] === 1) fail('forbidden', 'No reactions in secret chats');
    if ($emoji === null || $emoji === '') {
        db()->prepare('DELETE FROM reactions WHERE message_id = ? AND user_id = ?')->execute([$mid, $me['uid']]);
    } else {
        if (mb_strlen($emoji) > 8) fail('invalid', 'Bad emoji');
        db()->prepare('INSERT INTO reactions (message_id, user_id, emoji, created_at) VALUES (?,?,?,UTC_TIMESTAMP())
                       ON DUPLICATE KEY UPDATE emoji = VALUES(emoji)')
            ->execute([$mid, $me['uid'], $emoji]);
    }
    $st = db()->prepare('SELECT emoji, COUNT(*) c, SUM(user_id = ?) mine FROM reactions WHERE message_id = ? GROUP BY emoji');
    $st->execute([(int)$me['uid'], $mid]);
    $reactions = array_map(fn($r) => ['emoji' => $r['emoji'], 'count' => (int)$r['c'], 'mine' => (int)$r['mine'] > 0], $st->fetchAll());
    emit(chat_member_ids((int)$m['chat_id']), 'message_reacted',
        ['chat_id' => (int)$m['chat_id'], 'message_id' => $mid, 'reactions' => $reactions], (int)$m['chat_id'], (int)$me['uid']);
    ok(['reactions' => $reactions]);
}

case 'search': {
    $me = me();
    $cid = pint('chat_id');
    require_member($cid);
    $q = trim(p('q') ?? '');
    if ($q === '') ok(['messages' => []]);
    $st = db()->prepare("SELECT * FROM messages WHERE chat_id = ? AND deleted = 0 AND text LIKE ? ORDER BY id DESC LIMIT 50");
    $st->execute([$cid, "%$q%"]);
    ok(['messages' => array_map(fn($m) => msg_public($m, (int)$me['uid']), $st->fetchAll())]);
}

case 'search_global': {
    $me = me();
    $q = trim(p('q') ?? '');
    if ($q === '') ok(['users' => [], 'messages' => []]);
    $like = "%$q%";
    // users
    $st = db()->prepare("SELECT *, id AS uid FROM users WHERE LOWER(username) LIKE LOWER(?) OR CONCAT(first_name,' ',last_name) LIKE ? LIMIT 20");
    $st->execute([$like, $like]);
    $users = array_map(fn($u) => user_public($u), $st->fetchAll());
    // my chats messages
    $st = db()->prepare("SELECT m.* FROM messages m
                          JOIN chat_members cm ON cm.chat_id = m.chat_id AND cm.user_id = ?
                          WHERE m.deleted = 0 AND m.text LIKE ? ORDER BY m.id DESC LIMIT 50");
    $st->execute([$me['uid'], $like]);
    $msgs = [];
    foreach ($st->fetchAll() as $m) {
        $mp = msg_public($m, (int)$me['uid']);
        unset($mp['reactions']);
        $msgs[] = $mp;
    }
    ok(['users' => $users, 'messages' => $msgs]);
}

case 'media_list': {
    $me = me();
    $cid = pint('chat_id');
    require_member($cid);
    $kind = p('kind') ?: 'all';           // all | photo | video | file | voice
    $sql = 'SELECT * FROM messages WHERE chat_id = ? AND deleted = 0';
    $args = [$cid];
    if ($kind === 'photo')  $sql .= " AND type IN ('photo','gif')";
    if ($kind === 'video')  $sql .= " AND type IN ('video','video_note')";
    if ($kind === 'file')   $sql .= " AND type = 'file'";
    if ($kind === 'voice')  $sql .= " AND type = 'voice'";
    if ($kind === 'audio')  $sql .= " AND (type = 'voice' OR (type = 'file' AND LOWER(media_name) REGEXP '\\\\.(mp3|m4a|aac|flac|wav|ogg)$'))";
    if ($kind === 'media')  $sql .= " AND type IN ('photo','gif','video','video_note','file')";
    $sql .= ' ORDER BY id DESC LIMIT 400';
    $st = db()->prepare($sql); $st->execute($args);
    $out = array_map(fn($m) => msg_public($m, (int)$me['uid']), $st->fetchAll());
    ok(['messages' => $out]);
}

case 'view_ping': {
    // channel post view counter (unique per user)
    $me = me();
    $ids = array_map('intval', array_slice(parr('message_ids'), 0, 200));
    if (!$ids) ok();
    $in = implode(',', array_fill(0, count($ids), '?'));
    $st = db()->prepare("SELECT m.id, c.type FROM messages m JOIN chats c ON c.id = m.chat_id
                          WHERE m.id IN ($in)");
    $st->execute($ids);
    $ins = db()->prepare('INSERT IGNORE INTO message_views (message_id, user_id) VALUES (?,?)');
    foreach ($st->fetchAll() as $row) {
        if ($row['type'] === 'channel') $ins->execute([(int)$row['id'], $me['uid']]);
    }
    ok(['counted' => count($ids)]);
}

case 'export': {
    $me = me();
    $cid = pint('chat_id');
    require_member($cid);
    $chat = chat_of($cid);
    $title = $chat ? ($chat['title'] ?: 'Chat') : 'Chat';
    $st = db()->prepare('SELECT * FROM messages WHERE chat_id = ? AND deleted = 0 AND scheduled_at IS NULL ORDER BY id LIMIT 5000');
    $st->execute([$cid]);
    $out = array_map(fn($m) => msg_public($m, (int)$me['uid']), $st->fetchAll());
    ok(['title' => $title, 'messages' => $out]);
}

case 'pin_list': {
    // Telegram-style multi-pin: all pinned messages of a chat, newest first
    $me = me();
    $cid = pint('chat_id');
    require_member($cid);
    $st = db()->prepare('SELECT * FROM messages WHERE chat_id = ? AND pinned = 1 AND deleted = 0 ORDER BY id DESC LIMIT 50');
    $st->execute([$cid]);
    ok(['messages' => array_map(fn($m) => msg_v6($m, (int)$me['uid']), $st->fetchAll())]);
}

case 'pin_set': {
    // pin/unpin one message (multi-pin). Groups/channels: admin only. notify=1 → silent=false ping
    $me = me();
    $cid = pint('chat_id');
    $membership = require_member($cid);
    $chat = chat_of($cid);
    $mid = pint('message_id');
    $pin = pint('pinned') === 1;
    if (!$chat) fail('not_found', 'Chat not found');
    if (($chat['type'] === 'group' || $chat['type'] === 'channel') && (string)$membership['role'] !== 'admin')
        fail('forbidden', 'Only admins can pin here');
    if ($mid > 0) {
        $m = message_by_id($mid);
        if (!$m || (int)$m['chat_id'] !== $cid || (int)$m['deleted'] === 1) fail('not_found', 'Message not found');
        db()->prepare('UPDATE messages SET pinned = ? WHERE id = ?')->execute([$pin ? 1 : 0, $mid]);
        if ($pin) db()->prepare('UPDATE chats SET pinned_message_id = ? WHERE id = ?')->execute([$mid, $cid]);
        else {
            $st = db()->prepare('SELECT id FROM messages WHERE chat_id = ? AND pinned = 1 AND deleted = 0 ORDER BY id DESC LIMIT 1');
            $st->execute([$cid]);
            $top = $st->fetch();
            db()->prepare('UPDATE chats SET pinned_message_id = ? WHERE id = ?')
                ->execute([$top ? (int)$top['id'] : null, $cid]);
        }
    } else {
        db()->prepare('UPDATE messages SET pinned = 0 WHERE chat_id = ?')->execute([$cid]);
        db()->prepare('UPDATE chats SET pinned_message_id = NULL WHERE id = ?')->execute([$cid]);
    }
    emit(chat_member_ids($cid), 'message_pinned',
        ['chat_id' => $cid, 'message_id' => $pin ? $mid : null, 'pinned' => $pin], $cid, (int)$me['uid']);
    ok(['pinned' => $pin ? $mid : null]);
}

case 'comments': {
    // list a channel post's comment thread
    $me = me();
    $mid = pint('post_id');
    $m = message_by_id($mid);
    if (!$m || (int)$m['deleted'] === 1) fail('not_found', 'Post not found');
    require_member((int)$m['chat_id']);
    flush_due();
    $st = db()->prepare('SELECT * FROM messages WHERE comment_to = ? AND deleted = 0 ORDER BY id ASC LIMIT 300');
    $st->execute([$mid]);
    ok(['post' => msg_v6($m, (int)$me['uid']),
        'messages' => array_map(fn($c) => msg_v6($c, (int)$me['uid']), $st->fetchAll())]);
}

case 'by_date': {
    // jump-to-date: 40 messages starting at (or just after) YYYY-MM-DD 00:00
    $me = me();
    $cid = pint('chat_id');
    require_member($cid);
    flush_due();
    $date = p('date') ?? '';
    if (!preg_match('/^\d{4}-\d{2}-\d{2}$/', $date)) fail('invalid', 'Bad date (YYYY-MM-DD)');
    $dayEnd = sqlts(strtotime($date . ' UTC') + 86400 - 1);
    $st = db()->prepare('SELECT id FROM messages WHERE chat_id = ? AND deleted = 0 AND comment_to IS NULL AND scheduled_at IS NULL AND created_at >= ? ORDER BY id ASC LIMIT 1');
    $st->execute([$cid, sqlts(strtotime($date . ' UTC'))]);
    $first = $st->fetch();
    if ($first) {
        $st = db()->prepare('SELECT * FROM messages WHERE chat_id = ? AND deleted = 0 AND comment_to IS NULL AND scheduled_at IS NULL AND id >= ? ORDER BY id ASC LIMIT 40');
        $st->execute([$cid, (int)$first['id']]);
    } else {
        // date is in the future / no newer messages → newest page
        $st = db()->prepare('SELECT * FROM messages WHERE chat_id = ? AND deleted = 0 AND comment_to IS NULL AND scheduled_at IS NULL ORDER BY id DESC LIMIT 40');
        $st->execute([$cid]);
    }
    $rows = $st->fetchAll();
    if (!$first) $rows = array_reverse($rows);
    $isChannel = chat_of($cid)['type'] ?? '';
    $cc = [];
    if ($isChannel === 'channel') $cc = comment_counts(array_map(fn($m) => (int)$m['id'], $rows));
    ok(['messages' => array_map(fn($m) => msg_v6($m, (int)$me['uid'], $cc), $rows)]);
}

case 'scheduled': {
    $me = me();
    $st = db()->prepare("SELECT * FROM messages m JOIN chat_members cm ON cm.chat_id = m.chat_id AND cm.user_id = ?
                          WHERE m.sender_id = ? AND m.scheduled_at IS NOT NULL AND m.deleted = 0
                          ORDER BY m.scheduled_at LIMIT 100");
    $st->execute([$me['uid'], $me['uid']]);
    $out = [];
    foreach ($st->fetchAll() as $row) {
        $mp = msg_public($row, (int)$me['uid']);
        $out[] = $mp;
    }
    ok(['messages' => $out]);
}

/* ================= v7: polls / to-dos / buttons ================= */

case 'poll_get': {
    $me = me();
    $mid = pint('message_id');
    $m = message_by_id($mid);
    if (!$m) fail('not_found', 'Poll not found');
    require_member((int)$m['chat_id']);
    $st = db()->prepare('SELECT id FROM polls WHERE message_id = ?');
    $st->execute([$mid]);
    $p = $st->fetch();
    if (!$p) fail('not_found', 'Poll not found');
    ok(['poll' => poll_public((int)$p['id'], (int)$me['uid']), 'message_id' => $mid]);
}

case 'poll_vote': {
    $me = me();
    $mid = pint('message_id');
    $m = message_by_id($mid);
    if (!$m || $m['type'] !== 'poll') fail('not_found', 'Poll not found');
    $cid = (int)$m['chat_id'];
    $membership = require_member($cid);
    if ((string)$membership['role'] === 'banned') fail('banned', 'You are banned in this chat');
    $st = db()->prepare('SELECT * FROM polls WHERE message_id = ?');
    $st->execute([$mid]);
    $p = $st->fetch();
    if (!$p) fail('not_found', 'Poll not found');
    if ((int)$p['closed'] === 1) fail('closed', 'This poll is closed');

    // optional: suggest a brand-new option (open polls only)
    $suggestion = trim((string)(p('add_option') ?? ''));
    if ($suggestion !== '' && (int)$p['quiz'] !== 1) {
        $suggestion = mb_substr($suggestion, 0, 100);
        db()->prepare('INSERT IGNORE INTO poll_suggestions (poll_id, user_id, option_text, created_at)
                       VALUES (?,?,?,UTC_TIMESTAMP())')->execute([(int)$p['id'], $me['uid'], $suggestion]);
        emit(chat_member_ids($cid), 'poll_updated', ['chat_id' => $cid, 'message_id' => $mid], $cid, (int)$me['uid']);
        ok(['poll' => poll_public((int)$p['id'], (int)$me['uid']), 'suggested' => true]);
    }

    $options = json_decode((string)$p['options_json'], true) ?: [];
    $idxs = array_map('intval', parr('option_idxs'));
    $idxs = array_values(array_filter($idxs, fn($i) => $i >= 0 && $i < count($options)));
    if (!$idxs) fail('invalid', 'Pick at least one option');
    if ((int)$p['multiple'] !== 1) $idxs = [$idxs[0]];   // single-answer polls keep one vote
    if ((int)$p['quiz'] === 1) {
        // quiz: one shot only — vote once, no changing
        $had = db()->prepare('SELECT id FROM poll_votes WHERE poll_id = ? AND user_id = ? LIMIT 1');
        $had->execute([(int)$p['id'], $me['uid']]);
        if ($had->fetch()) fail('voted', 'You already answered this quiz');
    }
    db()->prepare('DELETE FROM poll_votes WHERE poll_id = ? AND user_id = ?')->execute([(int)$p['id'], $me['uid']]);
    foreach (array_unique($idxs) as $i)
        db()->prepare('INSERT INTO poll_votes (poll_id, user_id, option_idx, created_at) VALUES (?,?,?,UTC_TIMESTAMP())')
            ->execute([(int)$p['id'], $me['uid'], $i]);
    emit(chat_member_ids($cid, (int)$me['uid']), 'poll_updated', ['chat_id' => $cid, 'message_id' => $mid], $cid, (int)$me['uid']);
    ok(['poll' => poll_public((int)$p['id'], (int)$me['uid'])]);
}

case 'poll_close': {
    $me = me();
    $mid = pint('message_id');
    $m = message_by_id($mid);
    if (!$m || $m['type'] !== 'poll') fail('not_found', 'Poll not found');
    $cid = (int)$m['chat_id'];
    if ((int)$m['sender_id'] !== (int)$me['uid'] && (string)(my_membership($cid, (int)$me['uid'])['role'] ?? '') !== 'admin')
        fail('forbidden', 'Only the poll author or admins can stop it');
    db()->prepare('UPDATE polls SET closed = 1 WHERE message_id = ?')->execute([$mid]);
    emit(chat_member_ids($cid), 'poll_updated', ['chat_id' => $cid, 'message_id' => $mid], $cid, (int)$me['uid']);
    ok(['closed' => $mid]);
}

case 'todo_toggle': {
    $me = me();
    $mid = pint('message_id');
    $idx = pint('idx');
    $done = pint('done') === 1;
    $m = message_by_id($mid);
    if (!$m || $m['type'] !== 'todo') fail('not_found', 'Checklist not found');
    $cid = (int)$m['chat_id'];
    require_member($cid);
    $j = json_decode((string)($m['media_json'] ?? ''), true);
    $items = $j['items'] ?? [];
    if ($idx < 0 || $idx >= count($items)) fail('invalid', 'Bad item index');
    $items[$idx]['d'] = $done;
    db()->prepare('UPDATE messages SET media_json = ? WHERE id = ?')
        ->execute([json_encode(['items' => array_values($items)], JSON_UNESCAPED_UNICODE), $mid]);
    $m = message_by_id($mid);
    emit(chat_member_ids($cid), 'message_edited',
        ['chat_id' => $cid, 'message' => msg_v6($m, 0)], $cid, (int)$me['uid']);
    ok(['message' => msg_v6($m, (int)$me['uid'])]);
}

case 'button_click': {
    $me = me();
    $mid = pint('message_id');
    $btnId = (string)(p('button_id') ?? '');
    $m = message_by_id($mid);
    if (!$m) fail('not_found', 'Message not found');
    $cid = (int)$m['chat_id'];
    require_member($cid);
    if (empty($m['buttons'])) fail('invalid', 'No buttons on this message');
    $btns = json_decode((string)$m['buttons'], true) ?: [];
    $btn = null;
    foreach ($btns as $b) if ((string)($b['id'] ?? $b['label'] ?? '') === $btnId) { $btn = $b; break; }
    if (!$btn) fail('invalid', 'Button not found');
    $chat = chat_of($cid);
    emit(chat_member_ids($cid, (int)$me['uid']), 'button_clicked',
        ['chat_id' => $cid, 'message_id' => $mid, 'button' => $btn,
         'clicker' => user_public(user_by_id((int)$me['uid'])),
         'title' => (string)($chat['title'] ?? '')], $cid, (int)$me['uid']);
    ok(['button' => $btn]);
}

default: fail('unknown_action', 'Unknown messages action');
}
