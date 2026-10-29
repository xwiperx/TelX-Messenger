import { db } from '@/lib/db'
import { genToken, deviceName, TOKEN_COOKIE, bad, meUser } from '@/lib/auth'
import { NextRequest, NextResponse } from 'next/server'

const g = globalThis as any

export async function POST(req: NextRequest) {
  try {
    const { userId, code } = await req.json()
    if (!userId || !code) return bad('کد الزامی است')

    const pending = g.__telxPending2fa?.get(userId) as { code: string; expires: number } | undefined
    if (!pending || pending.expires < Date.now())
      return bad('کد منقضی شده است. دوباره وارد شوید')
    if (pending.code !== String(code).trim())
      return bad('کد وارد شده اشتباه است')

    g.__telxPending2fa.delete(userId)
    const user = await db.user.findUnique({ where: { id: userId } })
    if (!user) return bad('کاربر یافت نشد', 404)

    const token = genToken()
    const device = deviceName(req.headers.get('user-agent') || '')
    await db.session.create({ data: { token, userId: user.id, device, current: true } })
    await db.user.update({ where: { id: user.id }, data: { isOnline: true, lastSeen: new Date() } })

    const res = NextResponse.json({ ok: true, me: meUser(user) })
    res.cookies.set(TOKEN_COOKIE, token, {
      httpOnly: true, sameSite: 'lax', maxAge: 60 * 60 * 24 * 365, path: '/',
    })
    return res
  } catch (e: any) {
    return bad('خطا: ' + e.message, 500)
  }
}
