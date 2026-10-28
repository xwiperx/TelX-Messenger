import { db } from '@/lib/db'
import { getSessionUser, genCode, bad, ok } from '@/lib/auth'
import { NextRequest } from 'next/server'

const g = globalThis as any
g.__telxPending2fa ??= new Map<string, { code: string; expires: number }>()

/** Enable / disable 2FA. When enabling, a code is "emailed" (simulated in sandbox). */
export async function POST(req: NextRequest) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const { enabled } = await req.json()
  if (typeof enabled !== 'boolean') return bad('وضعیت نامعتبر')

  if (!enabled) {
    await db.user.update({ where: { id: me.id }, data: { twoFactorEnabled: false, twoFactorSecret: null } })
    return ok({ twoFactorEnabled: false, message: 'ورود دو مرحله‌ای غیرفعال شد' })
  }

  const code = genCode()
  g.__telxPending2fa.set(me.id, { code, expires: Date.now() + 10 * 60_000 })
  await db.user.update({ where: { id: me.id }, data: { twoFactorEnabled: true, twoFactorSecret: code } })
  return ok({
    twoFactorEnabled: true,
    message: 'ورود دو مرحله‌ای فعال شد. کد به ایمیل شما ارسال شد',
    simulatedCode: code,
    emailHint: me.email,
  })
}
