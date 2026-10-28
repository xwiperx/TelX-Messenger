import { db } from '@/lib/db'
import { getSessionUser, bad, ok } from '@/lib/auth'
import { serializeMessage, chatReadState, extractUrl } from '@/lib/message'
import { emitToUsers } from '@/lib/events'
import { NextRequest } from 'next/server'

async function getMembership(chatId: string, userId: string) {
  return db.chatMember.findUnique({
    where: { chatId_userId: { chatId, userId } },
    include: { chat: true },
  })
}

/** GET message history (?before= for pagination) */
export async function GET(req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const { id: chatId } = await params
  const member = await getMembership(chatId, me.id)
  if (!member) return bad('چت یافت نشد', 404)

  const before = req.nextUrl.searchParams.get('before')
  const limit = 50
  const messages = await db.message.findMany({
    where: { chatId, isDeleted: false, ...(before ? { createdAt: { lt: new Date(before) } } : {}) },
    orderBy: { createdAt: 'desc' },
    take: limit,
    include: {
      sender: true,
      replyTo: { include: { sender: true } },
      reactions: { include: { user: true } },
    },
  })

  const ids = messages.map((m) => m.id)
  const readSet = await chatReadState(chatId, me.id, ids)

  return ok({
    messages: messages.reverse().map((m) => serializeMessage(m, me.id, readSet.has(m.id))),
    hasMore: messages.length === limit,
  })
}

/** POST send a message */
export async function POST(req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const { id: chatId } = await params
  const member = await getMembership(chatId, me.id)
  if (!member) return bad('چت یافت نشد', 404)

  const body = await req.json()
  const text = (body.text || '').trim()
  const type = body.type || 'text'
  if (!text && !body.mediaUrl) return bad('پیام خالی است')
  if (type === 'text' && text.length > 4000) return bad('متن پیام خیلی طولانی است')

  // link preview for text messages containing URLs
  let preview: { title?: string; image?: string; url?: string; description?: string } | null = null
  const url = type === 'text' ? extractUrl(text) : null
  if (url) {
    try {
      const ctrl = new AbortController()
      const t = setTimeout(() => ctrl.abort(), 4000)
      const r = await fetch(url, { signal: ctrl.signal, headers: { 'user-agent': 'Mozilla/5.0 TelXBot' } })
      clearTimeout(t)
      if (r.ok) {
        const html = (await r.text()).slice(0, 200_000)
        const pick = (re: RegExp) => html.match(re)?.[1]?.trim()
        preview = {
          title: pick(/<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']/i)
            || pick(/<title[^>]*>([^<]+)<\/title>/i),
          image: pick(/<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']/i),
          description: pick(/<meta[^>]+property=["']og:description["'][^>]+content=["']([^"']+)["']/i),
          url,
        }
      }
    } catch { /* no preview */ }
  }

  const message = await db.message.create({
    data: {
      chatId,
      senderId: me.id,
      text,
      type,
      mediaUrl: body.mediaUrl || null,
      fileName: body.fileName || null,
      fileSize: body.fileSize || null,
      duration: body.duration || null,
      replyToId: body.replyToId || null,
      forwardedFrom: body.forwardedFrom || null,
      forwardedByName: body.forwardedByName || null,
      previewTitle: preview?.title || null,
      previewImage: preview?.image || null,
      previewUrl: preview?.url || null,
      previewDesc: preview?.description || null,
    },
    include: { sender: true, replyTo: { include: { sender: true } }, reactions: { include: { user: true } } },
  })

  // unread counts: everyone except sender
  const members = await db.chatMember.findMany({ where: { chatId } })
  for (const m of members) {
    if (m.userId === me.id) {
      await db.chatMember.update({ where: { id: m.id }, data: { unreadCount: 0, lastReadMessageId: message.id } })
    } else {
      await db.chatMember.update({ where: { id: m.id }, data: { unreadCount: { increment: 1 } } })
      // notification for muted/unmuted handling on client
      if (!m.muted) {
        await db.notification.create({
          data: {
            userId: m.userId,
            title: `${me.firstName} ${me.lastName}`,
            body: type === 'text' ? text : type === 'image' ? '🖼 عکس' : type === 'voice' ? '🎤 پیام صوتی' : type === 'file' ? `📎 ${body.fileName || 'فایل'}` : 'پیام جدید',
            chatId,
          },
        })
        emitToUsers([m.userId], { type: 'message', chatId, payload: { notify: true, from: me.firstName } })
      }
    }
  }

  // mark my own message as read by me
  await db.messageRead.create({ data: { messageId: message.id, userId: me.id } }).catch(() => {})

  const others = members.filter((m) => m.userId !== me.id).map((m) => m.userId)
  emitToUsers(others, { type: 'message', chatId })
  emitToUsers([me.id], { type: 'chat', chatId })

  // In Saved Messages (no other member) show ✓✓ immediately; otherwise start with single ✓
  const isSaved = member.chat.type === 'saved'
  return ok({ message: serializeMessage(message, me.id, isSaved) })
}
