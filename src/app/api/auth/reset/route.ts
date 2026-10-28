import { db } from '@/lib/db'
import { hashPassword, bad, ok } from '@/lib/auth'
import { NextRequest } from 'next/server'

export async function POST(req: NextRequest) {
  const { email, code, password } = await req.json()
  const em = (email || '').trim().toLowerCase()
  if (!em || !code || !password) return bad('همه فیلدها الزامی است')
  if (password.length < 6) return bad('رمز عبور باید حداقل ۶ کاراکتر باشد')

  const reset = await db.passwordReset.findFirst({
    where: { code: String(code).trim(), used: false, expiresAt: { gt: new Date() } },
    orderBy: { createdAt: 'desc' },
  })
  if (!reset) return bad('کد نامعتبر یا منقضی شده است')

  const user = await db.user.findUnique({ where: { email: em } })
  if (!user || user.id !== reset.userId) return bad('کد با این ایمیل مطابقت ندارد')

  await db.user.update({ where: { id: user.id }, data: { passwordHash: hashPassword(password) } })
  await db.passwordReset.update({ where: { id: reset.id }, data: { used: true } })
  return ok({ message: 'رمز عبور با موفقیت تغییر کرد' })
}
