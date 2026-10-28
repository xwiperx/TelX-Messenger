import { db } from '@/lib/db'
import { getSessionUser, publicUser, bad, ok } from '@/lib/auth'
import { NextRequest } from 'next/server'

/** Global search: users + messages across all my chats. */
export async function GET(req: NextRequest) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const q = (req.nextUrl.searchParams.get('q') || '').trim()
  if (!q) return ok({ users: [], messages: [] })

  // 1) users by name/username
  const users = await db.user.findMany({
    where: {
      id: { not: me.id },
      OR: [
        { username: { contains: q.toLowerCase() } },
        { firstName: { contains: q } },
        { lastName: { contains: q } },
      ],
    },
    take: 10,
  })

  // 2) messages in my chats
  const memberships = await db.chatMember.findMany({ where: { userId: me.id }, select: { chatId: true } })
  const chatIds = memberships.map((m) => m.chatId)
  const messages = await db.message.findMany({
    where: { chatId: { in: chatIds }, isDeleted: false, text: { contains: q } },
    orderBy: { createdAt: 'desc' },
    take: 20,
    include: { sender: true, chat: { include: { members: { include: { user: true } } } } },
  })

  return ok({
    users: users.map(publicUser),
    messages: messages.map((m) => {
      const others = m.chat.members.filter((x) => x.userId !== me.id).map((x) => x.user)
      return {
        id: m.id,
        chatId: m.chatId,
        chatName: m.chat.type === 'saved' ? 'پیام‌های ذخیره شده' : others[0] ? `${others[0].firstName} ${others[0].lastName}`.trim() : 'چت',
        text: m.text,
        createdAt: m.createdAt,
        mine: m.senderId === me.id,
      }
    }),
  })
}
