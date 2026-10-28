import { db } from '@/lib/db'
import { getSessionUser, bad, ok } from '@/lib/auth'
import { NextRequest } from 'next/server'
import crypto from 'crypto'
import { mkdirSync, writeFileSync } from 'fs'
import path from 'path'

const UPLOAD_DIR = path.join(process.cwd(), 'db', 'uploads')
const MAX_SIZE = 25 * 1024 * 1024 // 25MB

export async function POST(req: NextRequest) {
  const me = await getSessionUser()
  if (!me) return bad('احراز هویت لازم است', 401)

  const form = await req.formData()
  const file = form.get('file') as File | null
  if (!file) return bad('فایلی ارسال نشد')
  if (file.size > MAX_SIZE) return bad('حجم فایل باید کمتر از ۲۵ مگابایت باشد')

  const ext = (file.name.split('.').pop() || 'bin').toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 8)
  const name = crypto.randomUUID() + (ext ? '.' + ext : '')

  mkdirSync(UPLOAD_DIR, { recursive: true })
  const buf = Buffer.from(await file.arrayBuffer())
  writeFileSync(path.join(UPLOAD_DIR, name), buf)

  await db.upload.create({ data: { fileName: name, mime: file.type || 'application/octet-stream', size: file.size } })

  return ok({
    url: '/api/files/' + name,
    name: file.name,
    size: file.size,
    mime: file.type,
  })
}
