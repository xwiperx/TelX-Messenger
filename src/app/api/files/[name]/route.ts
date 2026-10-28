import { NextRequest } from 'next/server'
import { readFileSync, existsSync } from 'fs'
import path from 'path'

const UPLOAD_DIR = path.join(process.cwd(), 'db', 'uploads')

const MIME: Record<string, string> = {
  jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', gif: 'image/gif',
  webp: 'image/webp', svg: 'image/svg+xml', mp4: 'video/mp4', webm: 'video/webm',
  mp3: 'audio/mpeg', ogg: 'audio/ogg', wav: 'audio/wav', weba: 'audio/webm',
  pdf: 'application/pdf', txt: 'text/plain', zip: 'application/zip',
  doc: 'application/msword', docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
}

export async function GET(req: NextRequest, { params }: { params: Promise<{ name: string }> }) {
  const { name } = await params
  const safe = path.basename(name).replace(/[^a-zA-Z0-9._-]/g, '')
  const filePath = path.join(UPLOAD_DIR, safe)
  if (!existsSync(filePath)) return new Response('Not found', { status: 404 })

  const ext = safe.split('.').pop()?.toLowerCase() || ''
  const mime = MIME[ext] || 'application/octet-stream'
  const data = readFileSync(filePath)
  return new Response(new Uint8Array(data), {
    headers: {
      'content-type': mime,
      'cache-control': 'public, max-age=31536000, immutable',
    },
  })
}
