import { db } from '@/lib/db'
import { getSessionUser, hashPassword, TOKEN_COOKIE, bad, ok } from '@/lib/auth'
import { NextRequest, NextResponse } from 'next/server'

/** Permanently delete the account (requires password). */
export async function POST(req: NextRequest) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const { password } = await req.json()
  if (me.passwordHash !== hashPassword(password || ''))
    return bad('رمز عبور اشتباه است')

  // Cascade deletes handle chats/messages where possible; clean up memberships & private chats of the user
  await db.user.delete({ where: { id: me.id } })
  const res = NextResponse.json({ ok: true, message: 'حساب شما برای همیشه حذف شد' })
  res.cookies.set(TOKEN_COOKIE, '', { maxAge: 0, path: '/' })
  return res
}
