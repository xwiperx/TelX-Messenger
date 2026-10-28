import { db } from '@/lib/db'
import { getSessionUser, bad, ok } from '@/lib/auth'
import { NextRequest } from 'next/server'

/** GET unread notifications; POST mark all read. */
export async function GET() {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const items = await db.notification.findMany({
    where: { userId: me.id, read: false },
    orderBy: { createdAt: 'desc' },
    take: 30,
  })
  return ok({
    count: items.length,
    items: items.map((n) => ({ id: n.id, title: n.title, body: n.body, chatId: n.chatId, createdAt: n.createdAt })),
  })
}

export async function POST(req: NextRequest) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const { id } = await req.json().catch(() => ({ id: null }))
  if (id) await db.notification.updateMany({ where: { id, userId: me.id }, data: { read: true } })
  else await db.notification.updateMany({ where: { userId: me.id }, data: { read: true } })
  return ok()
}
