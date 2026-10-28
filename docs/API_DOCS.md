# TelX API Documentation (PHP v2 — Alwaysdata)

Base URL: `https://<USER>.alwaysdata.net/telx/v2/`

- All endpoints accept/return JSON (`Content-Type: application/json`).
- Authentication: `Authorization: Bearer <token>` header (also accepts `?token=` or `X-Auth-Token`).
- All responses have the shape `{ ok: true, ... }` or `{ ok: false, error: "..." }`.
- Tables are prefixed with `tx_` so the backend coexists with any previous installation.

---

## Auth

| Endpoint | Method | Body | Response |
|---|---|---|---|
| `auth/register.php` | POST | `{firstName, lastName, username, email, password}` | `{me, token}` |
| `auth/login.php` | POST | `{login, password}` | `{me, token}` or `{need2fa, userId, emailHint, simulatedCode?}` |
| `auth/verify_2fa.php` | POST | `{userId, code}` | `{me, token}` |
| `auth/logout.php` | POST | — | `{message}` |
| `auth/me.php` | GET | — | `{me}` |
| `auth/check_username.php?q=` | GET | — | `{available, invalid?}` |
| `auth/forgot.php` | POST | `{email}` | `{message, simulatedCode?}` |
| `auth/reset.php` | POST | `{email, code, password}` | `{message}` |
| `auth/twofactor.php` | POST | `{enabled}` | `{twoFactorEnabled, message, simulatedCode?}` |
| `auth/sessions.php` | GET | — | `{sessions[]}` |
| `auth/sessions.php?id=ID\|all` | DELETE | — | `{message}` |
| `auth/delete_account.php` | POST | `{password}` | `{message}` — permanent! |
| `auth/heartbeat.php` | POST | — | `{}` |

> `simulatedCode` is only returned when `TELX_DEBUG_CODES = true` in `config.php`.
> In production the codes are emailed via PHP `mail()`.

## Profile

| Endpoint | Method | Body |
|---|---|---|
| `profile.php` | POST | `{action:"profile", firstName?, lastName?, username?, bio?, avatarUrl?}` |
| `profile.php` | POST | `{action:"wallpaper", wallpaper:"pattern1|w1..w6"}` |
| `profile.php` | POST | `{action:"privacy", privacyLock:bool}` |
| `profile.php` | POST | `{action:"darkmode", darkMode:bool}` |

## Chats & Messages

| Endpoint | Method | Params / Body | Notes |
|---|---|---|---|
| `chats.php` | GET | — | list of chats + last message + unreadCount + online |
| `chats.php` | POST | `{userId}` (empty → Saved Messages) | creates or reuses private chat → `{chatId}` |
| `messages.php?chatId=&before=` | GET | — | history (50/page, `before` = ISO date for paging) |
| `messages.php` | POST | `{chatId, type:text|image|file|voice, text?, mediaUrl?, fileName?, fileSize?, duration?, replyToId?}` | auto link-preview for URLs |
| `longpoll.php?chatId=&changed=SIGNATURE` | GET | — | **holds up to 25s**; returns `{changed:true, signature}` when anything changes |
| `longpoll.php` | POST | `{chatId, afterId?}` | fetch new messages + `typing[]` names |
| `read.php` | POST | `{chatId, lastMessageId?}` | mark read → sender sees checkmarks |
| `typing.php` | POST | `{chatId}` | typing state (4s TTL) |
| `chat_search.php?chatId=&q=` | GET | — | search in one chat |
| `message_action.php` | POST | `{messageId, action:"edit", text}` | own messages only |
| `message_action.php` | POST | `{messageId, action:"react", emoji}` | toggle reaction |
| `message_action.php` | POST | `{messageId, action:"pin", pinned:bool}` | one pinned per chat |
| `message_action.php?messageId=` | DELETE | — | soft-delete own message |
| `forward.php` | POST | `{messageIds:[], chatId}` | respects privacy_lock of source sender and destination |

## Contacts & Search

| Endpoint | Method | Params / Body |
|---|---|---|
| `contacts.php` | GET | — |
| `contacts.php` | POST | `{userId}` or `{username}` |
| `contacts.php?contactId=` | DELETE | — |
| `search.php?q=` | GET | global: users + messages across my chats |

## Stories

| Endpoint | Method | Params / Body |
|---|---|---|
| `stories.php` | GET | feed grouped by user (`isMine`, `hasUnseen`, `viewsCount`, `seenByMe`) |
| `stories.php` | POST | `{mediaUrl, mediaType:image|video, duration:15|30|45|60, caption?}` |
| `story_action.php` | POST | `{storyId}` → mark viewed |
| `story_action.php?storyId=` | GET | viewers list (own story) |
| `story_action.php?storyId=` | DELETE | delete own story |

## Upload & Notifications

| Endpoint | Method | Notes |
|---|---|---|
| `upload.php` | POST multipart `file` | max 25 MB → `{url:"uploads/xxx.jpg", name, size, mime}` |
| `notifications.php` | GET | `{count, items:[{id,title,body,chatId}]}` (unread only) |
| `notifications.php` | POST | `{id?}` mark one/all read |

## Message JSON shape

`{ "id", "chatId", "text", "type", "mediaUrl", "fileName", "fileSize", "duration",
"replyTo" {id,text,type,senderName,mine}, "forwardedByName", "preview" {title,image,url,description},
"isEdited", "isDeleted", "pinned", "createdAt", "mine", "sender" {...public user}, "read", "reactions": [{emoji, users:[{id,name}]}] }`

## Long Polling client recipe

```
sig = "0"
loop:
  res = GET longpoll.php?chatId=C&changed=sig        # holds <=25s
  sig = res.signature
  if res.changed:
      new = POST longpoll.php {chatId:C, afterId:lastId}
      render new.messages + new.typing
```
