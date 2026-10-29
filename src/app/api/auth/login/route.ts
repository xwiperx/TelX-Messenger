import { db } from '@/lib/db'
import { hashPassword, genToken, genCode, deviceName, TOKEN_COOKIE, bad, meUser } from '@/lib/auth'
import { NextRequest, NextResponse } from 'next/server'

const g = globalThis as any
g.__telxPending2fa ??= new Map<string, { userId: string; code: string; expires: number }>()

export async function POST(req: NextRequest) {
  try {
    const body = await req.json()
    const login = (body.login || '').trim().toLowerCase()
    const password = body.password || ''
    if (!login || !password) return bad('نام کاربری و رمز عبور الزامی است')

    const user = await db.user.findFirst({
      where: { OR: [{ username: login }, { email: login }] },
    })
    if (!user || user.passwordHash !== hashPassword(password))
      return bad('نام کاربری یا رمز عبور اشتباه است')

    // If 2FA enabled → require code
    if (user.twoFactorEnabled) {
      const code = genCode()
      g.__telxPending2fa.set(user.id, { userId: user.id, code, expires: Date.now() + 5 * 60_000 })
      // In production this is sent by email (SMTP). Sandbox shows it to the user.
      return Response.json({ ok: true, need2fa: true, userId: user.id, emailHint: user.email, simulatedCode: code })
    }

    const token = genToken()
    const device = deviceName(req.headers.get('user-agent') || '')
    await db.session.create({ data: { token, userId: user.id, device, current: true } })
    await db.user.update({ where: { id: user.id }, data: { isOnline: true, lastSeen: new Date() } })

    const res = NextResponse.json({ ok: true, me: meUser(user) })
    res.cookies.set(TOKEN_COOKIE, token, {
      httpOnly: true,
      sameSite: 'lax',
      maxAge: 60 * 60 * 24 * 365,
      path: '/',
    })
    return res
  } catch (e: any) {
    return bad('خطا در ورود: ' + e.message, 500)
  }
}
