import { db } from '@/lib/db'
import { hashPassword, genToken, deviceName, TOKEN_COOKIE, bad, meUser } from '@/lib/auth'
import { NextRequest, NextResponse } from 'next/server'

export async function POST(req: NextRequest) {
  try {
    const body = await req.json()
    const firstName = (body.firstName || '').trim()
    const lastName = (body.lastName || '').trim()
    const username = (body.username || '').trim().toLowerCase()
    const email = (body.email || '').trim().toLowerCase()
    const password = body.password || ''

    if (!firstName || !username || !email || !password)
      return bad('همه فیلدها الزامی است')
    if (!/^[a-zA-Z][a-zA-Z0-9_]{3,24}$/.test(username))
      return bad('نام کاربری باید ۴ تا ۲۵ کاراکتر انگلیسی باشد و با حرف شروع شود')
    if (!/^\S+@\S+\.\S+$/.test(email)) return bad('ایمیل معتبر نیست')
    if (password.length < 6) return bad('رمز عبور باید حداقل ۶ کاراکتر باشد')

    const existsU = await db.user.findUnique({ where: { username } })
    if (existsU) return bad('این نام کاربری قبلاً گرفته شده است')
    const existsE = await db.user.findUnique({ where: { email } })
    if (existsE) return bad('این ایمیل قبلاً ثبت شده است')

    const user = await db.user.create({
      data: { firstName, lastName, username, email, passwordHash: hashPassword(password) },
    })

    // create Saved Messages chat
    const saved = await db.chat.create({ data: { type: 'saved' } })
    await db.chatMember.create({ data: { chatId: saved.id, userId: user.id } })

    const token = genToken()
    const device = deviceName(req.headers.get('user-agent') || '')
    await db.session.create({
      data: { token, userId: user.id, device, current: true },
    })

    const res = NextResponse.json({ ok: true, me: meUser(user) })
    res.cookies.set(TOKEN_COOKIE, token, {
      httpOnly: true,
      sameSite: 'lax',
      maxAge: 60 * 60 * 24 * 365,
      path: '/',
    })
    return res
  } catch (e: any) {
    return bad('خطا در ثبت‌نام: ' + e.message, 500)
  }
}
