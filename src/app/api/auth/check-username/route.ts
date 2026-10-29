import { db } from '@/lib/db'
import { bad, ok } from '@/lib/auth'
import { NextRequest } from 'next/server'

export async function GET(req: NextRequest) {
  const q = (req.nextUrl.searchParams.get('q') || '').trim().toLowerCase()
  if (!q) return ok({ available: false })
  if (!/^[a-zA-Z][a-zA-Z0-9_]{3,24}$/.test(q))
    return ok({ available: false, invalid: true })
  const exists = await db.user.findUnique({ where: { username: q } })
  return ok({ available: !exists })
}
