import { db } from '@/lib/db'
import { getSessionUser, publicUser, bad, ok } from '@/lib/auth'
import { NextRequest } from 'next/server'

/** GET stories feed: my stories + contacts' stories grouped by user. */
export async function GET() {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)

  const contacts = await db.contact.findMany({ where: { userId: me.id }, select: { contactId: true } })
  const userIds = [me.id, ...contacts.map((c) => c.contactId)]

  const stories = await db.story.findMany({
    where: { userId: { in: userIds }, expiresAt: { gt: new Date() } },
    orderBy: { createdAt: 'asc' },
    include: { user: true, views: { include: { user: true } } },
  })

  const groups: any[] = []
  const byUser = new Map<string, any>()
  for (const s of stories) {
    if (!byUser.has(s.userId)) {
      const g = {
        user: publicUser(s.user),
        isMine: s.userId === me.id,
        items: [],
        hasUnseen: false,
      }
      byUser.set(s.userId, g)
      groups.push(g)
    }
    const g = byUser.get(s.userId)
    const seenByMe = s.views.some((v) => v.userId === me.id)
    g.items.push({
      id: s.id,
      mediaUrl: s.mediaUrl,
      mediaType: s.mediaType,
      duration: s.duration,
      caption: s.caption,
      createdAt: s.createdAt,
      seenByMe,
      viewsCount: s.views.length,
    })
    if (!seenByMe) g.hasUnseen = true
  }

  // my groups first, then contacts by newest
  groups.sort((a, b) => (a.isMine === b.isMine ? 0 : a.isMine ? -1 : 1))
  return ok({ groups })
}

/** POST upload a new story. */
export async function POST(req: NextRequest) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const { mediaUrl, mediaType, duration, caption } = await req.json()
  if (!mediaUrl) return bad('رسانه استوری الزامی است')

  const dur = [15, 30, 45, 60].includes(Number(duration)) ? Number(duration) : 15
  const story = await db.story.create({
    data: {
      userId: me.id,
      mediaUrl,
      mediaType: mediaType === 'video' ? 'video' : 'image',
      duration: dur,
      caption: (caption || '').slice(0, 200),
      expiresAt: new Date(Date.now() + 24 * 60 * 60_000),
    },
  })
  return ok({ story: { id: story.id }, message: 'استوری منتشر شد' })
}
