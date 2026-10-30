<?php
/* TelX API — ai.php : on-device-style privacy-first AI helpers.
   - complete  : writing assistant (improve / formal / friendly / shorten /
                 expand / generate) for the message composer
   - summarize : extractive summarization of long channel posts or chats
   Optional: set AI_URL + AI_KEY in config.php to proxy to an OpenAI-compatible
   endpoint; when absent everything runs locally on this server (privacy mode). */
define('TELX_API', 1);
require __DIR__ . '/../config.php';

$action = p('action') ?? '';

/** try a remote OpenAI-compatible endpoint; returns null when unavailable. */
function ai_remote(string $system, string $user): ?string {
    if (!defined('AI_URL') || !defined('AI_KEY') || !AI_KEY) return null;
    try {
        $payload = json_encode([
            'model' => defined('AI_MODEL') ? AI_MODEL : 'gpt-4o-mini',
            'messages' => [
                ['role' => 'system', 'content' => $system],
                ['role' => 'user', 'content' => $user],
            ],
            'max_tokens' => 700,
        ], JSON_UNESCAPED_UNICODE);
        $ctx = stream_context_create(['http' => [
            'method' => 'POST',
            'timeout' => 12.0,
            'header' => "Content-Type: application/json\r\nAuthorization: Bearer " . AI_KEY . "\r\n",
            'ignore_errors' => true,
        ]]);
        $raw = @file_get_contents(AI_URL, false, $ctx, 0, 400000);
        if (!$raw) return null;
        $j = json_decode($raw, true);
        return is_array($j) && isset($j['choices'][0]['message']['content'])
            ? trim((string)$j['choices'][0]['message']['content']) : null;
    } catch (Throwable) { return null; }
}

/** sentence-split helper (works for most latin scripts; falls back to line-based). */
function ai_sentences(string $text): array {
    $text = preg_replace('/\s+/u', ' ', trim($text));
    $parts = preg_split('/(?<=[.!?؟।])\s+/u', $text);
    return array_values(array_filter(array_map('trim', $parts ?: []), fn($s) => $s !== ''));
}

/** word-frequency scoring of sentences (classic extractive summarizer). */
function ai_summarize_text(string $text, int $maxSentences = 5): string {
    $sentences = ai_sentences($text);
    if (count($sentences) <= $maxSentences) return trim($text);
    $freq = [];
    $words = preg_split('/[^\p{L}\p{N}]+/u', mb_strtolower($text)) ?: [];
    foreach ($words as $w) if (mb_strlen($w) > 3) $freq[$w] = ($freq[$w] ?? 0) + 1;
    $scored = [];
    foreach ($sentences as $i => $s) {
        $sw = preg_split('/[^\p{L}\p{N}]+/u', mb_strtolower($s)) ?: [];
        $score = 0.0;
        foreach (array_unique($sw) as $w) $score += (float)($freq[$w] ?? 0);
        // slight positional bonus: early sentences usually carry the gist
        $score *= 1.0 + max(0, 0.25 - $i * 0.02);
        $scored[] = ['i' => $i, 's' => $score];
    }
    usort($scored, fn($a, $b) => $b['s'] <=> $a['s']);
    $pick = array_slice($scored, 0, $maxSentences);
    usort($pick, fn($a, $b) => $a['i'] <=> $b['i']);
    return implode(' ', array_map(fn($x) => $sentences[$x['i']], $pick));
}

switch ($action) {

case 'complete': {
    me();
    $text = trim((string)(p('text') ?? ''));
    $mode = (string)(p('mode') ?? 'improve');
    $hint = trim((string)(p('hint') ?? ''));
    if ($text === '' && $mode !== 'generate') fail('invalid', 'Write some text first');
    if (mb_strlen($text) > 12000) fail('invalid', 'Text too long');

    $sys = 'You are a concise writing assistant inside a messenger. Rewrite the user text as requested. Output ONLY the final text, no explanations.';
    $modeTexts = [
        'improve'  => "Improve the text: fix grammar, clarity and flow. Keep the language, keep the meaning.",
        'formal'   => "Rewrite in a formal, polite tone.",
        'friendly' => "Rewrite in a warm, friendly, casual tone.",
        'shorten'  => "Shorten the text to its essentials (about half the length).",
        'expand'   => "Expand the text with helpful supporting detail (about double the length).",
        'generate' => "Write a message. Intent/hint: " . ($hint !== '' ? $hint : 'a friendly check-in message') . ".",
    ];
    $prompt = ($modeTexts[$mode] ?? $modeTexts['improve']) . "\n\nText:\n" . $text;

    $remote = ai_remote($sys, $prompt);
    if ($remote !== null) ok(['result' => $remote, 'engine' => 'remote']);

    // ---- local, privacy-mode transformations ----
    $result = $text;
    switch ($mode) {
        case 'shorten':
            $result = ai_summarize_text($text, max(1, (int)ceil(count(ai_sentences($text)) / 2)));
            break;
        case 'expand':
            $fillers = ["To give you a bit more detail:", "Just to add some context —", "Also worth mentioning:"];
            $sents = ai_sentences($text);
            $out = [];
            foreach ($sents as $k => $s) {
                $out[] = $s;
                if ($k === 0 && count($sents) > 1) $out[] = $fillers[$k % count($fillers)];
            }
            $result = trim(implode(' ', $out)) . " Let me know if you have any questions!";
            break;
        case 'formal':
            $result = str_ireplace(
                ['hi ', 'hey ', 'thanks', 'thx', 'gonna', 'wanna', "can't", "don't", 'u ', ' ur ', 'asap', '!'],
                ['Hello, ', 'Dear ', 'thank you', 'thank you', 'going to', 'would like to', 'cannot', 'do not', 'you ', ' your ', 'as soon as possible', '.'],
                $text);
            $result = trim(ucfirst($result));
            if (!str_ends_with($result, '.') && !str_ends_with($result, '!') && !str_ends_with($result, '?'))
                $result .= '.';
            $result = "Dear recipient,\n\n" . $result . "\n\nKind regards";
            break;
        case 'friendly':
            $result = str_ireplace(['Dear ', 'Regards', 'Sincerely', ' I am ', ' I would like to '],
                ['Hey ', 'Cheers', 'Cheers', " I'm ", " I'd love to "], $text);
            $result = "Hey! 👋\n\n" . trim($result) . "\n\nTake care! 🌟";
            break;
        case 'generate':
            $result = $hint !== ''
                ? "Hi there! 👋\n\n" . ucfirst($hint) . "\n\nLet me know if there's anything I can help with. 🙌"
                : "Hi! Just checking in — how have you been lately? 😊";
            break;
        case 'improve':
        default:
            // light cleanup: collapse whitespace, capitalize sentence starts
            $result = trim(preg_replace('/[ \t]+/u', ' ', $text) ?? $text);
            $sents = ai_sentences($result);
            $result = implode(' ', array_map(
                fn($s) => mb_strtoupper(mb_substr($s, 0, 1)) . mb_substr($s, 1), $sents));
            break;
    }
    ok(['result' => $result, 'engine' => 'local']);
}

case 'summarize': {
    $me = me();
    $text = trim((string)(p('text') ?? ''));
    $mid = pint('message_id');
    $chatId = pint('chat_id');
    $count = max(3, min(12, pint('count', 6)));
    if ($text === '' && $mid <= 0 && $chatId <= 0) fail('invalid', 'Nothing to summarize');

    if ($text === '') {
        if ($mid > 0) {
            $m = message_by_id($mid);
            if (!$m) fail('not_found', 'Message not found');
            require_member((int)$m['chat_id']);
            $text = (string)$m['text'];
        } else {
            require_member($chatId);
            $st = db()->prepare('SELECT text FROM messages WHERE chat_id = ? AND deleted = 0 AND comment_to IS NULL AND scheduled_at IS NULL ORDER BY id DESC LIMIT 40');
            $st->execute([$chatId]);
            $lines = [];
            foreach (array_reverse($st->fetchAll()) as $r)
                if (!empty($r['text'])) $lines[] = (string)$r['text'];
            $text = implode("\n", $lines);
        }
    }
    if (mb_strlen($text) < 120) { ok(['result' => trim($text), 'engine' => 'local', 'short' => true]); }
    else {
        $remote = ai_remote('Summarize the following chat or post in a few clear sentences.',
            "Summarize this:\n\n" . $text);
        if ($remote !== null) ok(['result' => $remote, 'engine' => 'remote']);
        ok(['result' => ai_summarize_text($text, $count), 'engine' => 'local']);
    }
}

default: fail('unknown_action', 'Unknown ai action');
}
