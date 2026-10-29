// TelX client helpers: API wrapper, types, formatting

export type TUser = {
  id: string
  firstName: string
  lastName: string
  username: string
  bio?: string
  avatarUrl?: string | null
  privacyLock: boolean
  online: boolean
  lastSeen: string
}

export type TMessage = {
  id: string
  chatId: string
  text: string
  type: 'text' | 'image' | 'file' | 'voice'
  mediaUrl?: string | null
  fileName?: string | null
  fileSize?: number | null
  duration?: number | null
  replyTo?: { id: string; text: string; type: string; senderName: string; mine: boolean } | null
  forwardedByName?: string | null
  preview?: { title?: string; image?: string; url?: string; description?: string } | null
  isEdited: boolean
  isDeleted: boolean
  pinned: boolean
  createdAt: string
  mine: boolean
  sender: TUser
  read: boolean
  reactions: { emoji: string; users: { id: string; name: string }[] }[]
}

export type TChat = {
  id: string
  type: 'private' | 'saved'
  unreadCount: number
  muted: boolean
  updatedAt: string
  other: TUser | null
  lastMessage: {
    id: string
    text: string
    type: string
    createdAt: string
    mine: boolean
    senderName: string
  } | null
}

export type TStoryItem = {
  id: string
  mediaUrl: string
  mediaType: 'image' | 'video'
  duration: number
  caption: string
  createdAt: string
  seenByMe: boolean
  viewsCount: number
}

export type TStoryGroup = {
  user: TUser
  isMine: boolean
  items: TStoryItem[]
  hasUnseen: boolean
}

export async function api<T = any>(path: string, opts: RequestInit = {}): Promise<T & { ok: boolean; error?: string }> {
  try {
    const res = await fetch(path, {
      ...opts,
      headers: {
        ...(opts.body && !(opts.body instanceof FormData) ? { 'content-type': 'application/json' } : {}),
        ...(opts.headers || {}),
      },
      cache: 'no-store',
    })
    const data = await res.json().catch(() => ({ ok: false, error: 'پاسخ نامعتبر از سرور' }))
    return data
  } catch {
    return { ok: false, error: 'خطا در اتصال به سرور' } as any
  }
}

export const POST = (body: any) => ({ method: 'POST', body: JSON.stringify(body) })

export function fullName(u: TUser | null | undefined): string {
  if (!u) return 'پیام‌های ذخیره شده'
  return (u.firstName + ' ' + (u.lastName || '')).trim()
}

export function initials(u: TUser | null | undefined): string {
  if (!u) return '🔖'
  return (u.firstName?.[0] || '') + (u.lastName?.[0] || u.firstName?.[1] || '')
}

const AVATAR_COLORS = ['#e17076', '#7bc862', '#65aadd', '#a695e7', '#ee7aae', '#faa774', '#6ec9cb', '#f2a33c']

export function avatarColor(id: string): string {
  let h = 0
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) >>> 0
  return AVATAR_COLORS[h % AVATAR_COLORS.length]
}

export function timeStr(d: string | Date): string {
  return new Date(d).toLocaleTimeString('fa-IR', { hour: '2-digit', minute: '2-digit' })
}

export function chatTimeStr(d: string | Date): string {
  const date = new Date(d)
  const now = new Date()
  const sameDay = date.toDateString() === now.toDateString()
  if (sameDay) return timeStr(d)
  const yesterday = new Date(now.getTime() - 86400000)
  if (date.toDateString() === yesterday.toDateString()) return 'دیروز'
  return date.toLocaleDateString('fa-IR', { month: 'numeric', day: 'numeric' })
}

export function dayDividerStr(d: string | Date): string {
  const date = new Date(d)
  const now = new Date()
  if (date.toDateString() === now.toDateString()) return 'امروز'
  const yesterday = new Date(now.getTime() - 86400000)
  if (date.toDateString() === yesterday.toDateString()) return 'دیروز'
  return date.toLocaleDateString('fa-IR', { weekday: 'long', month: 'long', day: 'numeric' })
}

export function lastSeenFa(u: TUser): string {
  if (u.online) return 'آنلاین'
  const d = new Date(u.lastSeen)
  const diff = Date.now() - d.getTime()
  const mins = Math.floor(diff / 60000)
  if (mins < 1) return 'لحظاتی پیش آنلاین بود'
  if (mins < 60) return `${mins} دقیقه پیش آنلاین بود`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours} ساعت پیش آنلاین بود`
  return 'آخرین بازدید ' + d.toLocaleDateString('fa-IR')
}

export function sizeStr(bytes?: number | null): string {
  if (!bytes) return ''
  if (bytes < 1024) return bytes + ' بایت'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' کیلوبایت'
  return (bytes / 1024 / 1024).toFixed(1) + ' مگابایت'
}

export function msgPreview(m: TChat['lastMessage']): string {
  if (!m) return 'پیامی نیست'
  const prefix = m.mine ? 'شما: ' : m.senderName && m.senderName !== 'شما' ? m.senderName + ': ' : ''
  const body =
    m.type === 'image' ? '🖼 عکس'
    : m.type === 'voice' ? '🎤 پیام صوتی'
    : m.type === 'file' ? '📎 فایل'
    : m.text || 'پیام'
  return prefix + body
}

export function waveHeights(seed: string, n = 24): number[] {
  let h = 0
  for (let i = 0; i < seed.length; i++) h = (h * 33 + seed.charCodeAt(i)) >>> 0
  const out: number[] = []
  for (let i = 0; i < n; i++) {
    h = (h * 1103515245 + 12345) >>> 0
    out.push(4 + (h % 14))
  }
  return out
}

export const REACTION_EMOJIS = ['👍', '❤️', '🔥', '🥰', '👏', '😁', '🤔', '🤯', '😱', '😢', '🎉', '💩']

export const WALLPAPERS = [
  { id: 'pattern1', label: 'پیش‌فرض' },
  { id: 'w1', label: 'حباب آبی' },
  { id: 'w2', label: 'راه‌راه' },
  { id: 'w3', label: 'سبز' },
  { id: 'w4', label: 'گرادیان' },
  { id: 'w5', label: 'شب' },
  { id: 'w6', label: 'ستاره' },
]

export function wallpaperClass(id?: string | null): string {
  switch (id) {
    case 'w1': return 'tg-wallpaper tg-wallpaper-w1'
    case 'w2': return 'tg-wallpaper tg-wallpaper-w2'
    case 'w3': return 'tg-wallpaper tg-wallpaper-w3'
    case 'w4': return 'tg-wallpaper tg-wallpaper-w4'
    case 'w5': return 'tg-wallpaper tg-wallpaper-w5'
    case 'w6': return 'tg-wallpaper tg-wallpaper-w6'
    default: return 'tg-wallpaper'
  }
}
