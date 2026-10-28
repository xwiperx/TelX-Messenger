# TelX Messenger 🚀

A full-featured, Telegram-style messenger — **Kotlin Android app + PHP/MySQL backend on Alwaysdata + Long Polling (no Firebase)**.

> Build: GitHub Actions auto-compiles a fresh **debug APK on every push to `main`** and publishes it to the [Releases → `latest`](../../releases/tag/latest) page.

---

## 📱 Android app (`android_telx/`)

- **Language:** Kotlin · **Package:** `com.telx.app` · **minSdk 23 / targetSdk 34**
- **UI:** Telegram-style (blue `#3390EC`), floating bottom navigation, light/dark themes, chat wallpapers (patterns + gallery image/GIF)

### Features
| Area | What's inside |
|------|---------------|
| 👤 Account | Register (live username check ✓/✗), login, persistent session, forgot-password via email code, **2FA (email codes)**, active-session manager, delete account, bio, avatar |
| 🔒 Privacy | Privacy Lock (blocks forwarding of your messages server-side + screenshot protection via `FLAG_SECURE` on the peer's device), block/unblock users |
| 💬 Chat | Instant messaging via **Long Polling**, photos/files, voice messages (hold-to-record), Saved Messages, link previews, ✓/✓✓ read ticks, typing indicator, online dot + last seen, unread badges, drafts, mute/pin/archive chats, clear history |
| ✏️ Messages | Reply, forward (privacy-lock aware), edit + `edited` tag, delete, multi-select delete/forward, **reactions** (8 emojis), pin to top |
| 📸 Stories | Upload photo/video, 15/30/45/60s duration, unseen ring in chat list, progress bars, tap next/prev, hold-to-pause, viewers list, delete |
| 🔎 Search | Global search (users + messages), search inside a chat |
| 🔔 Push | Foreground service long-polling → system notifications with **quick reply from notification**, boot receiver, per-chat mute |
| 👥 Groups | Create groups, system messages, member list, leave |

### Build locally
```bash
cd android_telx
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```
Or just push to `main` — CI builds `TelX-debug.apk` and attaches it to the `latest` release.

---

## 🖥 PHP backend (`php_telx/`)

Deployed at `https://<host>/telx/` on Alwaysdata (MySQL).

```
php_telx/
├── api/            # entry points (auth, chats, messages, stories, contacts, profile, upload, events, calls)
├── config.sample.php   # bootstrap: DB, mail, uploads, link preview, helpers (rename to config.php + fill creds)
├── init_db.php     # one-shot schema installer
└── .htaccess
```

- **Protocol:** JSON `POST api/<file>.php` with `{"action": "...", ...}` + `Authorization: Bearer <token>`
- **Realtime:** `api/events.php` long-polls up to 22 s and streams typed events (`message_new`, `message_read`, `message_reacted`, `story_new`, `call_invite`, …)
- **Uploads:** `api/upload.php` multipart (`kind=photo|file|voice|video|avatar|story|gif`) with auto-thumbnails
- **Link previews:** server-side OpenGraph fetch + image caching
- **Tables:** users, sessions, verification_codes, chats, chat_members, messages, reactions, events, contacts, blocks, stories, story_views, secret_keys (+ legacy `tx_*`)

### Deploy to Alwaysdata
```bash
ssh <user>@ssh-<host>.alwaysdata.net
rsync -av php_telx/ ~/www/telx/ --exclude uploads
# ensure config.php exists with real credentials, then visit init_db.php once
```

---

## 🗺 Roadmap ideas
- Secret (E2E) chats — server side (`open_secret`, `secret_keys`) is ready
- WebRTC voice calls — signaling relay (`api/calls.php`) is ready
- Web/Desktop client (the Next.js app in this repo), channels, bots, scheduled messages UI

## 🔐 Security notes
- Never commit `config.php` with real credentials — use `config.sample.php`
- Rotate any tokens/passwords that ever appear in chats or history
