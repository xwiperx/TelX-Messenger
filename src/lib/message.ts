import { db } from '@/lib/db'
import { publicUser } from '@/lib/auth'

/** Serialize a Prisma message (with included relations) for the client. */
export function serializeMessage(m: any, meId: string, readByOthers: boolean) {
  const reactions: Record<string, { emoji: string; users: { id: string; name: string }[] }> = {}
  for (const r of m.reactions || []) {
    if (!reactions[r.emoji]) reactions[r.emoji] = { emoji: r.emoji, users: [] }
    reactions[r.emoji].users.push({ id: r.userId, name: r.user?.firstName || '' })
  }
  return {
    id: m.id,
    chatId: m.chatId,
    text: m.text,
    type: m.type,
    mediaUrl: m.mediaUrl,
    fileName: m.fileName,
    fileSize: m.fileSize,
    duration: m.duration,
    replyTo: m.replyTo
      ? {
          id: m.replyTo.id,
          text: m.replyTo.text,
          type: m.replyTo.type,
          senderName: m.replyTo.sender?.firstName || '',
          mine: m.replyTo.senderId === meId,
        }
      : null,
    forwardedByName: m.forwardedByName,
    preview:
      m.previewUrl && !m.isDeleted
        ? { title: m.previewTitle, image: m.previewImage, url: m.previewUrl, description: m.previewDesc }
        : null,
    isEdited: m.isEdited,
    isDeleted: m.isDeleted,
    pinned: m.pinned,
    createdAt: m.createdAt,
    mine: m.senderId === meId,
    sender: publicUser(m.sender),
    read: m.mine ? readByOthers : true,
    reactions: Object.values(reactions),
  }
}

/** Which other members have read messages of this chat (for ✓✓). */
export async function chatReadState(chatId: string, meId: string, messageIds: string[]) {
  if (messageIds.length === 0) return new Set<string>()
  const reads = await db.messageRead.findMany({
    where: { messageId: { in: messageIds }, userId: { not: meId } },
  })
  return new Set(reads.map((r) => r.messageId))
}

export function extractUrl(text: string): string | null {
  const match = text.match(/https?:\/\/[^\s<>"']+/i)
  return match ? match[0] : null
}
