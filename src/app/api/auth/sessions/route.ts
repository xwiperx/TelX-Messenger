import { db } from '@/lib/db'
import { getSessionUser, ok, bad } from '@/lib/auth'
import { NextRequest, NextResponse } from 'next/server'

export async function GET() {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const sessions = await db.session.findMany({
    where: { userId: me.id },
    orderBy: { lastActive: 'desc' },
  })
  return ok({
    sessions: sessions.map((s) => ({
      id: s.id,
      device: s.device,
      ip: s.ip,
      current: s.current,
      createdAt: s.createdAt,
      lastActive: s.lastActive,
    })),
  })
}

/** Revoke one session (?id=) or all other sessions (?id=all). */
export async function DELETE(req: NextRequest) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const id = req.nextUrl.searchParams.get('id')
  if (id === 'all') {
    await db.session.deleteMany({ where: { userId: me.id, current: false } })
    return ok({ message: 'همه دستگاه‌های دیگر خارج شدند' })
  }
  if (!id) return bad('شناسه سشن لازم است')
  const s = await db.session.findUnique({ where: { id } })
  if (!s || s.userId !== me.id) return bad('سشن یافت نشد', 404)
  if (s.current) return bad('نمی‌توانید سشن فعلی را حذف کنید. از خروج استفاده کنید')
  await db.session.delete({ where: { id } })
  return ok({ message: 'دستگاه خارج شد' })
}
