import { db } from '@/lib/db'
import { genCode, bad, ok } from '@/lib/auth'
import { NextRequest } from 'next/server'

/** Request a password reset code (simulated email in sandbox). */
export async function POST(req: NextRequest) {
  const { email } = await req.json()
  const em = (email || '').trim().toLowerCase()
  if (!em) return bad('ایمیل الزامی است')
  const user = await db.user.findUnique({ where: { email: em } })
  if (!user) return bad('کاربری با این ایمیل یافت نشد')

  const code = genCode()
  await db.passwordReset.create({
    data: { userId: user.id, code, expiresAt: new Date(Date.now() + 5 * 60_000) },
  })
  // In production this is emailed. Sandbox returns it for display.
  return ok({ message: 'کد بازیابی به ایمیل شما ارسال شد', simulatedCode: code })
}
