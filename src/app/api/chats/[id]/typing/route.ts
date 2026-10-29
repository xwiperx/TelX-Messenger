import { db } from '@/lib/db'
import { getSessionUser, bad, ok } from '@/lib/auth'
import { emitToUsers } from '@/lib/events'
import { NextRequest } from 'next/server'

/** Set typing state for the current user in this chat. */
export async function POST(req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const { id: chatId } = await params
  const member = await db.chatMember.findUnique({ where: { chatId_userId: { chatId, userId: me.id } } })
  if (!member) return bad('چت یافت نشد', 404)

  await db.typingState.upsert({
    where: { chatId_userId: { chatId, userId: me.id } },
    create: { chatId, userId: me.id },
    update: { updatedAt: new Date() },
  })

  const others = await db.chatMember.findMany({ where: { chatId, userId: { not: me.id } } })
  emitToUsers(others.map((o) => o.userId), { type: 'typing', chatId })
  return ok()
}
