import { db } from '@/lib/db'
import { getSessionUser, bad, ok } from '@/lib/auth'
import { emitToUsers } from '@/lib/events'
import { NextRequest } from 'next/server'

/** Mark all messages in chat as read (up to latest). */
export async function POST(req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const { id: chatId } = await params
  const member = await db.chatMember.findUnique({ where: { chatId_userId: { chatId, userId: me.id } } })
  if (!member) return bad('چت یافت نشد', 404)

  const { lastMessageId } = await req.json().catch(() => ({ lastMessageId: null }))
  const last = lastMessageId
    ? await db.message.findUnique({ where: { id: lastMessageId } })
    : await db.message.findFirst({ where: { chatId, isDeleted: false }, orderBy: { createdAt: 'desc' } })
  if (!last) return ok()

  const unreadMessages = await db.message.findMany({
    where: { chatId, senderId: { not: me.id }, isDeleted: false, createdAt: { lte: last.createdAt } },
    select: { id: true, senderId: true },
  })

  let marked = 0
  const senderIds = new Set<string>()
  for (const m of unreadMessages) {
    const exists = await db.messageRead.findUnique({ where: { messageId_userId: { messageId: m.id, userId: me.id } } })
    if (!exists) {
      await db.messageRead.create({ data: { messageId: m.id, userId: me.id } })
      marked++
      senderIds.add(m.senderId)
    }
  }

  await db.chatMember.update({
    where: { id: member.id },
    data: { unreadCount: 0, lastReadMessageId: last.id },
  })

  if (marked > 0) emitToUsers([...senderIds], { type: 'read', chatId })
  return ok({ marked })
}
