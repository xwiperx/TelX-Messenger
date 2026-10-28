import { getSessionUser, meUser, ok } from '@/lib/auth'

export async function GET() {
  const user = await getSessionUser()
  if (!user) return Response.json({ ok: true, me: null })
  return ok({ me: meUser(user) })
}

export async function POST() {
  const user = await getSessionUser()
  if (!user) return Response.json({ ok: true, me: null })
  return ok({ me: meUser(user) })
}
