import { db } from '@/lib/db'
import { getSessionUser, publicUser, ok, bad } from '@/lib/auth'
import { NextRequest } from 'next/server'

export async function GET() {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)

  const memberships = await db.chatMember.findMany({
    where: { userId: me.id },
    include: { chat: true },
  })

  const chats = []
  for (const m of memberships) {
    const otherMembers = await db.chatMember.findMany({
      where: { chatId: m.chatId, userId: { not: me.id } },
      include: { user: true },
    })
    const other = m.chat.type === 'saved' ? null : otherMembers[0]?.user || null
    const lastMessage = await db.message.findFirst({
      where: { chatId: m.chatId, isDeleted: false },
      orderBy: { createdAt: 'desc' },
      include: { sender: true },
    })
    chats.push({
      id: m.chatId,
      type: m.chat.type,
      unreadCount: m.unreadCount,
      muted: m.muted,
      updatedAt: lastMessage?.createdAt || m.chat.createdAt,
      other: publicUser(other),
      lastMessage: lastMessage
        ? {
            id: lastMessage.id,
            text: lastMessage.text,
            type: lastMessage.type,
            isDeleted: lastMessage.isDeleted,
            createdAt: lastMessage.createdAt,
            mine: lastMessage.senderId === me.id,
            senderName: lastMessage.senderId === me.id ? 'شما' : lastMessage.sender.firstName,
          }
        : null,
    })
  }

  chats.sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
  return ok({ chats })
}

/** Open (or reuse) a private chat with a user, or the Saved Messages chat. */
export async function POST(req: NextRequest) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const { userId } = await req.json()
  const targetId = userId || me.id

  const target = await db.user.findUnique({ where: { id: targetId } })
  if (!target) return bad('کاربر یافت نشد', 404)

  const type = targetId === me.id ? 'saved' : 'private'

  // find existing private chat between the two
  const myChats = await db.chatMember.findMany({ where: { userId: me.id }, select: { chatId: true } })
  const theirChats = await db.chatMember.findMany({ where: { userId: targetId }, select: { chatId: true } })
  const shared = myChats.filter((x) => theirChats.some((y) => y.chatId === x.chatId))

  for (const s of shared) {
    const chat = await db.chat.findUnique({ where: { id: s.chatId } })
    if (chat && chat.type === type) return ok({ chatId: chat.id })
  }

  const chat = await db.chat.create({ data: { type } })
  await db.chatMember.create({ data: { chatId: chat.id, userId: me.id } })
  if (targetId !== me.id)
    await db.chatMember.create({ data: { chatId: chat.id, userId: targetId } })
  return ok({ chatId: chat.id })
}
