import { db } from '@/lib/db'
import { cookies } from 'next/headers'
import { TOKEN_COOKIE, ok } from '@/lib/auth'
import { NextResponse } from 'next/server'

export async function POST() {
  const store = await cookies()
  const token = store.get(TOKEN_COOKIE)?.value
  if (token) {
    const s = await db.session.findUnique({ where: { token } })
    if (s) {
      await db.session.delete({ where: { token } })
      const remaining = await db.session.count({ where: { userId: s.userId } })
      if (remaining === 0)
        await db.user.update({ where: { id: s.userId }, data: { isOnline: false, lastSeen: new Date() } })
    }
  }
  const res = NextResponse.json({ ok: true })
  res.cookies.set(TOKEN_COOKIE, '', { maxAge: 0, path: '/' })
  return res
}
