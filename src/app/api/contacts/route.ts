import { db } from '@/lib/db'
import { getSessionUser, publicUser, bad, ok } from '@/lib/auth'
import { NextRequest } from 'next/server'

export async function GET() {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const contacts = await db.contact.findMany({
    where: { userId: me.id },
    include: { contact: true },
    orderBy: { createdAt: 'desc' },
  })
  return ok({ contacts: contacts.map((c) => publicUser(c.contact)) })
}

/** Add contact by userId or username. */
export async function POST(req: NextRequest) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const { userId, username } = await req.json()
  const target = userId
    ? await db.user.findUnique({ where: { id: userId } })
    : await db.user.findUnique({ where: { username: (username || '').toLowerCase() } })
  if (!target) return bad('کاربر یافت نشد', 404)
  if (target.id === me.id) return bad('نمی‌توانید خودتان را اضافه کنید')

  const exists = await db.contact.findUnique({ where: { userId_contactId: { userId: me.id, contactId: target.id } } })
  if (exists) return ok({ contact: publicUser(target), message: 'این کاربر قبلاً در مخاطبین است' })

  await db.contact.create({ data: { userId: me.id, contactId: target.id } })
  return ok({ contact: publicUser(target), message: 'به مخاطبین اضافه شد' })
}

export async function DELETE(req: NextRequest) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)
  const contactId = req.nextUrl.searchParams.get('contactId')
  if (!contactId) return bad('شناسه مخاطب لازم است')
  await db.contact.deleteMany({ where: { userId: me.id, contactId } })
  return ok({ message: 'مخاطب حذف شد' })
}
