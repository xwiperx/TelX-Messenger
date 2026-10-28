import { db } from '@/lib/db'
import { getSessionUser, bad, ok } from '@/lib/auth'
import { emitToUsers } from '@/lib/events'
import { NextRequest } from 'next/server'

/** Forward one or more messages to a chat. Respects privacy lock. */
export async function POST(req: NextRequest) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const { messageIds, chatId } = await req.json()
  if (!Array.isArray(messageIds) || messageIds.length === 0 || !chatId)
    return bad('پیام‌ها و چت مقصد الزامی است')

  const member = await db.chatMember.findUnique({ where: { chatId_userId: { chatId, userId: me.id } } })
  if (!member) return bad('چت مقصد یافت نشد', 404)

  const targetMembers = await db.chatMember.findMany({ where: { chatId } })
  const targetOtherIds = targetMembers.filter((m) => m.userId !== me.id).map((m) => m.userId)
  const targetOthers = await db.user.findMany({ where: { id: { in: targetOtherIds } } })
  const locked = targetOthers.find((u) => u.privacyLock)
  if (locked) return bad(`قفل پیوی ${locked.firstName} فعال است؛ فوروارد مجاز نیست`)

  const created = []
  for (const mid of messageIds) {
    const src = await db.message.findUnique({ where: { id: mid } })
    if (!src || src.isDeleted) continue

    const srcSender = await db.user.findUnique({ where: { id: src.senderId } })
    if (srcSender?.privacyLock && src.chatId !== chatId)
      return bad(`قفل پیوی ${srcSender.firstName} فعال است؛ فوروارد این پیام مجاز نیست`)

    const msg = await db.message.create({
      data: {
        chatId,
        senderId: me.id,
        text: src.text,
        type: src.type,
        mediaUrl: src.mediaUrl,
        fileName: src.fileName,
        fileSize: src.fileSize,
        duration: src.duration,
        forwardedFrom: src.chatId,
        forwardedByName: srcSender ? `${srcSender.firstName} ${srcSender.lastName}`.trim() : 'ناشناس',
        previewTitle: src.previewTitle,
        previewImage: src.previewImage,
        previewUrl: src.previewUrl,
        previewDesc: src.previewDesc,
      },
      include: { sender: true, replyTo: { include: { sender: true } }, reactions: { include: { user: true } } },
    })
    created.push(msg)
  }

  for (const m of targetMembers) {
    if (m.userId === me.id)
      await db.chatMember.update({ where: { id: m.id }, data: { unreadCount: 0, lastReadMessageId: created.at(-1)?.id } })
    else await db.chatMember.update({ where: { id: m.id }, data: { unreadCount: { increment: created.length } } })
  }

  emitToUsers(targetOtherIds, { type: 'message', chatId })
  emitToUsers([me.id], { type: 'chat', chatId })
  return ok({ count: created.length })
}
