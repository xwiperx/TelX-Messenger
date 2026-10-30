<?php
/* TelX API — gif.php : advanced GIF search proxy.
   Uses a server-side GIF provider so the app never needs its own key.
   Configure GIF_KEY / GIF_PROVIDER (giphy|tenor) in config.php to override;
   defaults to the well-known public GIPHY demo key. Results are proxied
   uniformly so the app downloads + re-uploads the chosen GIF to TelX itself. */
define('TELX_API', 1);
require __DIR__ . '/../config.php';

$action = p('action') ?? '';

function gif_provider(): array {
    // GIF_KEY can be set in config.php; fall back to the public demo key.
    $key = defined('GIF_KEY') && GIF_KEY ? GIF_KEY : 'dc6zaTOxFJmzC';
    $provider = defined('GIF_PROVIDER') ? GIF_PROVIDER : 'giphy';
    return [$provider, $key];
}

function gif_search_giphy(string $q, int $limit, string $key): array {
    $url = 'https://api.giphy.com/v1/gifs/search?api_key=' . rawurlencode($key)
         . '&q=' . rawurlencode($q) . '&limit=' . $limit . '&rating=pg-13';
    $ctx = stream_context_create(['http' => ['timeout' => 8.0, 'header' => "User-Agent: TelX\r\n"]]);
    $raw = @file_get_contents($url, false, $ctx, 0, 900000);
    if (!$raw) return [];
    $j = json_decode($raw, true);
    $out = [];
    foreach (($j['data'] ?? []) as $g) {
        $img = $g['images'] ?? [];
        $fixed = $img['fixed_height'] ?? null;
        $preview = $img['fixed_height_small']['url'] ?? ($img['preview_gif']['url'] ?? null);
        if (!$fixed) continue;
        $out[] = [
            'id' => (string)($g['id'] ?? ''),
            'url' => (string)$fixed['url'],
            'preview' => (string)($preview ?? $fixed['url']),
            'width' => (int)($fixed['width'] ?? 0),
            'height' => (int)($fixed['height'] ?? 0),
            'size' => (int)($img['original']['size'] ?? ($fixed['size'] ?? 0)),
            'title' => (string)($g['title'] ?? ''),
        ];
    }
    return $out;
}

function gif_search_tenor(string $q, int $limit, string $key): array {
    $url = 'https://tenor.googleapis.com/v2/search?key=' . rawurlencode($key)
         . '&q=' . rawurlencode($q) . '&limit=' . $limit . '&contentfilter=medium';
    $ctx = stream_context_create(['http' => ['timeout' => 8.0, 'header' => "User-Agent: TelX\r\n"]]);
    $raw = @file_get_contents($url, false, $ctx, 0, 900000);
    if (!$raw) return [];
    $j = json_decode($raw, true);
    $out = [];
    foreach (($j['results'] ?? []) as $g) {
        $med = $g['media_formats']['gif'] ?? null;
        $tiny = $g['media_formats']['tinygif']['url'] ?? null;
        if (!$med) continue;
        $out[] = [
            'id' => (string)($g['id'] ?? ''),
            'url' => (string)$med['url'],
            'preview' => (string)($tiny ?? $med['url']),
            'width' => (int)($med['dims'][0] ?? 0),
            'height' => (int)($med['dims'][1] ?? 0),
            'size' => (int)($med['size'] ?? 0),
            'title' => (string)($g['content_description'] ?? ''),
        ];
    }
    return $out;
}

switch ($action) {

case 'search': {
    me();
    $q = trim((string)(p('q') ?? ''));
    $limit = max(1, min(50, pint('limit', 24)));
    if ($q === '') fail('invalid', 'Enter something to search');
    [$provider, $key] = gif_provider();
    $gifs = $provider === 'tenor'
        ? gif_search_tenor($q, $limit, $key)
        : gif_search_giphy($q, $limit, $key);
    if (!$gifs) {
        // provider failed → try the other one before giving up
        $gifs = $provider === 'tenor'
            ? gif_search_giphy($q, $limit, 'dc6zaTOxFJmzC')
            : gif_search_tenor($q, $limit, $key);
    }
    ok(['gifs' => $gifs, 'provider' => $provider]);
}

default: fail('unknown_action', 'Unknown gif action');
}
