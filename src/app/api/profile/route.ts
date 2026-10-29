import { db } from '@/lib/db'
import { getSessionUser, bad, ok, meUser } from '@/lib/auth'
import { NextRequest } from 'next/server'

/** Update profile / settings. Actions: profile | wallpaper | privacy | darkmode */
export async function POST(req: NextRequest) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const body = await req.json()
  const action = body.action

  if (action === 'profile') {
    const data: any = {}
    if (typeof body.firstName === 'string' && body.firstName.trim()) data.firstName = body.firstName.trim()
    if (typeof body.lastName === 'string') data.lastName = body.lastName.trim()
    if (typeof body.bio === 'string') data.bio = body.bio.slice(0, 200)
    if (typeof body.avatarUrl === 'string') data.avatarUrl = body.avatarUrl
    if (typeof body.username === 'string' && body.username.trim().toLowerCase() !== me.username) {
      const username = body.username.trim().toLowerCase()
      if (!/^[a-zA-Z][a-zA-Z0-9_]{3,24}$/.test(username))
        return bad('نام کاربری باید ۴ تا ۲۵ کاراکتر انگلیسی باشد و با حرف شروع شود')
      const exists = await db.user.findUnique({ where: { username } })
      if (exists) return bad('این نام کاربری قبلاً گرفته شده است')
      data.username = username
    }
    const user = await db.user.update({ where: { id: me.id }, data })
    return ok({ me: meUser(user), message: 'پروفایل به‌روزرسانی شد' })
  }

  if (action === 'wallpaper') {
    const user = await db.user.update({ where: { id: me.id }, data: { wallpaper: String(body.wallpaper || 'pattern1') } })
    return ok({ me: meUser(user) })
  }

  if (action === 'privacy') {
    const user = await db.user.update({ where: { id: me.id }, data: { privacyLock: !!body.privacyLock } })
    return ok({ me: meUser(user), message: body.privacyLock ? 'قفل پیوی فعال شد' : 'قفل پیوی غیرفعال شد' })
  }

  if (action === 'darkmode') {
    const user = await db.user.update({ where: { id: me.id }, data: { darkMode: !!body.darkMode } })
    return ok({ me: meUser(user) })
  }

  return bad('عملیات نامعتبر')
}
