import { db } from '@/lib/db'
import { getSessionUser, ok } from '@/lib/auth'

/** Presence heartbeat: marks the user online. */
export async function POST() {
  const me = await getSessionUser()
  if (!me) return ok()
  await db.user.update({ where: { id: me.id }, data: { isOnline: true, lastSeen: new Date() } })
  return ok()
}
