import { db } from '@/lib/db'
import { getSessionUser, bad, ok } from '@/lib/auth'
import { serializeMessage, chatReadState } from '@/lib/message'
import { emitToUsers } from '@/lib/events'
import { NextRequest } from 'next/server'

/**
 * Message actions:
 *  POST { action: 'edit', text }      → edit own message
 *  POST { action: 'react', emoji }    → toggle reaction
 *  POST { action: 'pin', pinned }     → pin/unpin in chat
 *  DELETE                             → delete own message (soft)
 */
export async function POST(req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const { id } = await params
  const message = await db.message.findUnique({ where: { id } })
  if (!message || message.isDeleted) return bad('پیام یافت نشد', 404)
  const member = await db.chatMember.findUnique({ where: { chatId_userId: { chatId: message.chatId, userId: me.id } } })
  if (!member) return bad('دسترسی ندارید', 403)

  const body = await req.json()
  const action = body.action

  if (action === 'edit') {
    if (message.senderId !== me.id) return bad('فقط پیام خودتان قابل ویرایش است')
    const text = (body.text || '').trim()
    if (!text) return bad('متن خالی است')
    const updated = await db.message.update({
      where: { id },
      data: { text, isEdited: true, editedAt: new Date() },
      include: { sender: true, replyTo: { include: { sender: true } }, reactions: { include: { user: true } } },
    })
    const members = await db.chatMember.findMany({ where: { chatId: message.chatId } })
    emitToUsers(members.map((m) => m.userId), { type: 'edit', chatId: message.chatId })
    const readSet = await chatReadState(message.chatId, me.id, [id])
    return ok({ message: serializeMessage(updated, me.id, readSet.has(id)) })
  }

  if (action === 'react') {
    const emoji = String(body.emoji || '').slice(0, 8)
    if (!emoji) return bad('ایموجی نامعتبر')
    const existing = await db.reaction.findUnique({
      where: { messageId_userId_emoji: { messageId: id, userId: me.id, emoji } },
    })
    if (existing) {
      await db.reaction.delete({ where: { id: existing.id } })
    } else {
      await db.reaction.create({ data: { messageId: id, userId: me.id, emoji } })
    }
    const updated = await db.message.findUnique({
      where: { id },
      include: { sender: true, replyTo: { include: { sender: true } }, reactions: { include: { user: true } } },
    })
    const members = await db.chatMember.findMany({ where: { chatId: message.chatId } })
    emitToUsers(members.map((m) => m.userId), { type: 'reaction', chatId: message.chatId })
    const readSet = await chatReadState(message.chatId, me.id, [id])
    return ok({ message: serializeMessage(updated, me.id, readSet.has(id)) })
  }

  if (action === 'pin') {
    const pinned = !!body.pinned
    await db.message.updateMany({ where: { chatId: message.chatId, pinned: true }, data: { pinned: false } })
    if (pinned) await db.message.update({ where: { id }, data: { pinned: true } })
    const members = await db.chatMember.findMany({ where: { chatId: message.chatId } })
    emitToUsers(members.map((m) => m.userId), { type: 'pin', chatId: message.chatId })
    return ok({ pinned })
  }

  return bad('عملیات نامعتبر')
}

export async function DELETE(req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const { id } = await params
  const message = await db.message.findUnique({ where: { id } })
  if (!message) return bad('پیام یافت نشد', 404)
  if (message.senderId !== me.id) return bad('فقط پیام خودتان قابل حذف است')

  await db.message.update({ where: { id }, data: { isDeleted: true, pinned: false } })
  const members = await db.chatMember.findMany({ where: { chatId: message.chatId } })
  emitToUsers(members.map((m) => m.userId), { type: 'delete', chatId: message.chatId })
  return ok()
}
