import { db } from './db'
import { cookies } from 'next/headers'
import crypto from 'crypto'

export const TOKEN_COOKIE = 'telx_token'
export const ONLINE_WINDOW_MS = 35_000

export function hashPassword(pw: string): string {
  return crypto.createHash('sha256').update('telx-salt-2024::' + pw).digest('hex')
}

export function genToken(): string {
  return crypto.randomBytes(32).toString('hex')
}

export function genCode(): string {
  return String(crypto.randomInt(100000, 999999))
}

export function deviceName(ua: string): string {
  const u = (ua || '').toLowerCase()
  if (u.includes('android')) return 'Android • ' + (ua.match(/android\s?([\d.]+)/)?.[1] || '')
  if (u.includes('iphone') || u.includes('ipad')) return 'iOS • iPhone'
  if (u.includes('mac')) return 'macOS • Desktop'
  if (u.includes('windows')) return 'Windows • Desktop'
  if (u.includes('linux')) return 'Linux • Desktop'
  return 'Web Browser'
}

/** Get the authenticated user from cookie; refresh presence. */
export async function getSessionUser(): Promise<ReturnType<typeof db.user.findUnique> & object | null> {
  const store = await cookies()
  const token = store.get(TOKEN_COOKIE)?.value
  if (!token) return null
  const session = await db.session.findUnique({ where: { token }, include: { user: true } })
  if (!session) return null
  // refresh presence (fire & forget style, but await for consistency)
  try {
    await db.session.update({ where: { id: session.id }, data: { lastActive: new Date() } })
    await db.user.update({ where: { id: session.userId }, data: { lastSeen: new Date(), isOnline: true } })
  } catch { /* ignore */ }
  return session.user as any
}

export function isOnline(lastSeen: Date, isOnline: boolean): boolean {
  if (!isOnline) return false
  return Date.now() - new Date(lastSeen).getTime() < ONLINE_WINDOW_MS
}

export function publicUser(u: any) {
  if (!u) return null
  return {
    id: u.id,
    firstName: u.firstName,
    lastName: u.lastName,
    username: u.username,
    bio: u.bio,
    avatarUrl: u.avatarUrl,
    privacyLock: u.privacyLock,
    online: isOnline(u.lastSeen, u.isOnline),
    lastSeen: u.lastSeen,
  }
}

export function meUser(u: any) {
  if (!u) return null
  return {
    id: u.id,
    firstName: u.firstName,
    lastName: u.lastName,
    username: u.username,
    email: u.email,
    bio: u.bio,
    avatarUrl: u.avatarUrl,
    privacyLock: u.privacyLock,
    twoFactorEnabled: u.twoFactorEnabled,
    wallpaper: u.wallpaper,
    darkMode: u.darkMode,
    online: true,
    createdAt: u.createdAt,
  }
}

export function bad(message: string, status = 400) {
  return Response.json({ ok: false, error: message }, { status })
}

export function ok(data: any = {}) {
  return Response.json({ ok: true, ...data })
}

/** Set a readable Last-Seen label */
export function lastSeenLabel(u: any): string {
  if (isOnline(u.lastSeen, u.isOnline)) return 'آنلاین'
  const d = new Date(u.lastSeen)
  const now = new Date()
  const diff = now.getTime() - d.getTime()
  const mins = Math.floor(diff / 60000)
  if (mins < 1) return 'آخرین بازدید لحظاتی پیش'
  if (mins < 60) return `آخرین بازدید ${mins} دقیقه پیش`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `آخرین بازدید ${hours} ساعت پیش`
  const days = Math.floor(hours / 24)
  if (days === 1) return 'آخرین بازدید دیروز'
  if (days < 7) return `آخرین بازدید ${days} روز پیش`
  return 'آخرین بازدید ' + d.toLocaleDateString('fa-IR')
}
