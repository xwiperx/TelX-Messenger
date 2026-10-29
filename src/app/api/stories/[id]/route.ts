import { db } from '@/lib/db'
import { getSessionUser, publicUser, bad, ok } from '@/lib/auth'
import { NextRequest } from 'next/server'

/** POST ?action=view → mark story viewed; GET → viewers list. */
export async function GET(req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const { id } = await params
  const story = await db.story.findUnique({ where: { id }, include: { user: true } })
  if (!story) return bad('استوری یافت نشد', 404)
  if (story.userId !== me.id) return bad('فقط استوری خودتان قابل مشاهده بازدیدکنندگان است', 403)

  return ok({ viewers: story ? (await db.storyView.findMany({ where: { storyId: id }, include: { user: true }, orderBy: { viewedAt: 'desc' } })).map((v) => ({ ...publicUser(v.user), viewedAt: v.viewedAt })) : [] })
}

export async function POST(req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const { id } = await params
  const story = await db.story.findUnique({ where: { id } })
  if (!story) return bad('استوری یافت نشد', 404)

  await db.storyView.upsert({
    where: { storyId_userId: { storyId: id, userId: me.id } },
    create: { storyId: id, userId: me.id },
    update: { viewedAt: new Date() },
  })
  return ok()
}

export async function DELETE(req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const { id } = await params
  const story = await db.story.findUnique({ where: { id } })
  if (!story) return bad('استوری یافت نشد', 404)
  if (story.userId !== me.id) return bad('فقط استوری خودتان قابل حذف است', 403)
  await db.story.delete({ where: { id } })
  return ok({ message: 'استوری حذف شد' })
}
