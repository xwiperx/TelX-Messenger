import { db } from '@/lib/db'
import { getSessionUser, bad, ok } from '@/lib/auth'
import { serializeMessage, chatReadState } from '@/lib/message'
import { NextRequest } from 'next/server'

/** Search within a chat's history. */
export async function GET(req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const { id: chatId } = await params
  const member = await db.chatMember.findUnique({ where: { chatId_userId: { chatId, userId: me.id } } })
  if (!member) return bad('چت یافت نشد', 404)

  const q = (req.nextUrl.searchParams.get('q') || '').trim()
  if (!q) return ok({ messages: [] })

  const messages = await db.message.findMany({
    where: { chatId, isDeleted: false, text: { contains: q } },
    orderBy: { createdAt: 'desc' },
    take: 30,
    include: { sender: true, replyTo: { include: { sender: true } }, reactions: { include: { user: true } } },
  })
  const readSet = await chatReadState(chatId, me.id, messages.map((m) => m.id))
  return ok({ messages: messages.map((m) => serializeMessage(m, me.id, readSet.has(m.id))) })
}
