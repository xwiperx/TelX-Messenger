import { db } from '@/lib/db'
import { getSessionUser, bad, ok } from '@/lib/auth'
import { serializeMessage, chatReadState } from '@/lib/message'
import { waitEvent, emitToUsers } from '@/lib/events'
import { NextRequest } from 'next/server'

/**
 * Long Polling endpoint: holds the request up to ~25s and returns as soon as
 * there are new messages / read receipts / typing changes in this chat.
 */
export async function GET(req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const { id: chatId } = await params
  const member = await db.chatMember.findUnique({ where: { chatId_userId: { chatId, userId: me.id } } })
  if (!member) return bad('چت یافت نشد', 404)

  const start = Date.now()

  while (Date.now() - start < 24_000) {
    const ev = await waitEvent(me.id, 2500)
    if (ev && (ev.type === 'message' || ev.type === 'read' || ev.type === 'typing' || ev.type === 'edit' || ev.type === 'delete' || ev.type === 'reaction' || ev.type === 'pin')) {
      if (!ev.chatId || ev.chatId === chatId) {
        return ok({ changed: ev.type })
      }
    }
  }
  return ok({ timeout: true })
}

/** Re-export full new messages for the client to fetch (helper route used by client after changed=true) */
export async function POST(req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const { id: chatId } = await params
  const member = await db.chatMember.findUnique({ where: { chatId_userId: { chatId, userId: me.id } } })
  if (!member) return bad('چت یافت نشد', 404)

  const { afterId } = await req.json()
  const after = afterId ? await db.message.findUnique({ where: { id: afterId }, select: { createdAt: true } }) : null

  const messages = await db.message.findMany({
    where: { chatId, isDeleted: false, ...(after ? { createdAt: { gt: after.createdAt } } : {}) },
    orderBy: { createdAt: 'asc' },
    take: 100,
    include: { sender: true, replyTo: { include: { sender: true } }, reactions: { include: { user: true } } },
  })
  const readSet = await chatReadState(chatId, me.id, messages.map((m) => m.id))

  // typing indicator (updated within last 4s, not me)
  const typing = await db.typingState.findMany({
    where: { chatId, userId: { not: me.id }, updatedAt: { gt: new Date(Date.now() - 4000) } },
    include: { user: true },
  })

  return ok({
    messages: messages.map((m) => serializeMessage(m, me.id, readSet.has(m.id))),
    typing: typing.map((t) => t.user.firstName),
  })
}
