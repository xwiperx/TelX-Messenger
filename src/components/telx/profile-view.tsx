'use client'

import { useState, useRef, useEffect } from 'react'
import { api, POST, fullName } from './helpers'
import type { TUser } from './helpers'
import Avatar from './avatar'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Camera, Check, X, Loader2, Bookmark } from 'lucide-react'

export default function ProfileView({ me, onMeChanged, onOpenSaved }: { me: TUser & { email?: string; twoFactorEnabled?: boolean }; onMeChanged: () => void; onOpenSaved: () => void }) {
  const [firstName, setFirstName] = useState(me.firstName)
  const [lastName, setLastName] = useState(me.lastName || '')
  const [username, setUsername] = useState(me.username)
  const [bio, setBio] = useState(me.bio || '')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState('')
  const [err, setErr] = useState('')
  const [uState, setUState] = useState<'idle' | 'checking' | 'ok' | 'bad'>('idle')
  const avatarRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const t = setTimeout(async () => {
      if (username === me.username) { setUState('ok'); return }
      if (!username) { setUState('idle'); return }
      if (!/^[a-zA-Z][a-zA-Z0-9_]{3,24}$/.test(username)) { setUState('bad'); return }
      setUState('checking')
      const r = await api<{ available: boolean }>(`/api/auth/check-username?q=${encodeURIComponent(username)}`)
      if (r.ok) setUState(r.available ? 'ok' : 'bad')
    }, username === me.username ? 0 : 400)
    return () => clearTimeout(t)
  }, [username, me.username])

  async function save() {
    setErr(''); setMsg(''); setBusy(true)
    const r = await api<{ me: TUser }>('/api/profile', POST({ action: 'profile', firstName, lastName, username, bio }))
    setBusy(false)
    if (!r.ok) return setErr(r.error || 'خطا')
    setMsg('پروفایل ذخیره شد')
    onMeChanged()
  }

  async function uploadAvatar(f: File) {
    setBusy(true)
    const fd = new FormData()
    fd.append('file', f)
    const up = await api<{ url: string }>('/api/upload', { method: 'POST', body: fd })
    if (up.ok) {
      const r = await api<{ me: TUser }>('/api/profile', POST({ action: 'profile', avatarUrl: up.url }))
      if (r.ok) onMeChanged()
    }
    setBusy(false)
  }

  const dirty = firstName !== me.firstName || lastName !== (me.lastName || '') || username !== me.username || bio !== (me.bio || '')

  return (
    <div className="h-full flex flex-col bg-[var(--tg-panel)] text-[var(--tg-panel-text)]">
      <div className="px-4 pt-4 pb-3 shrink-0" style={{ background: 'var(--tg-blue)' }}>
        <h1 className="text-white text-xl font-bold">پروفایل من</h1>
      </div>

      <div className="flex-1 overflow-y-auto tg-scroll pb-28">
        <div className="flex flex-col items-center pt-6 pb-4">
          <div className="relative">
            <Avatar user={me} size={112} />
            <button
              onClick={() => avatarRef.current?.click()}
              className="absolute bottom-0 left-0 w-9 h-9 rounded-full text-white flex items-center justify-center border-2"
              style={{ background: 'var(--tg-blue)', borderColor: 'var(--tg-panel)' }}
              aria-label="تغییر عکس پروفایل"
            >
              {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <Camera className="w-4.5 h-4.5" />}
            </button>
          </div>
          <input ref={avatarRef} type="file" accept="image/*" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) uploadAvatar(f); e.target.value = '' }} />
          <p className="text-muted-foreground text-xs mt-2">{(me as any).email}</p>
        </div>

        <div className="px-5 space-y-4">
          <div className="grid grid-cols-2 gap-2.5">
            <div>
              <label className="text-xs text-muted-foreground">نام</label>
              <Input value={firstName} onChange={(e) => setFirstName(e.target.value)} className="h-11 rounded-xl mt-1" />
            </div>
            <div>
              <label className="text-xs text-muted-foreground">نام خانوادگی</label>
              <Input value={lastName} onChange={(e) => setLastName(e.target.value)} className="h-11 rounded-xl mt-1" />
            </div>
          </div>

          <div>
            <label className="text-xs text-muted-foreground">نام کاربری</label>
            <div className="relative">
              <Input dir="ltr" value={username} onChange={(e) => setUsername(e.target.value)} className="h-11 rounded-xl mt-1 pl-10" />
              <span className="absolute left-3 top-1/2 -translate-y-1/2 mt-0.5">
                {uState === 'checking' && <Loader2 className="w-4 h-4 text-muted-foreground animate-spin" />}
                {uState === 'ok' && <Check className="w-4 h-4 text-green-500" strokeWidth={3} />}
                {uState === 'bad' && <X className="w-4 h-4 text-red-500" strokeWidth={3} />}
              </span>
            </div>
          </div>

          <div>
            <label className="text-xs text-muted-foreground">بیوگرافی</label>
            <Input value={bio} onChange={(e) => setBio(e.target.value)} placeholder="درباره خودتان…" className="h-11 rounded-xl mt-1" maxLength={200} />
          </div>

          {err && <p className="text-xs text-red-500 text-center">{err}</p>}
          {msg && <p className="text-xs text-green-600 text-center">{msg}</p>}

          <Button onClick={save} disabled={busy || !dirty || uState === 'bad'} className="w-full h-11 rounded-xl text-white font-semibold" style={{ background: 'var(--tg-blue)', opacity: dirty && uState !== 'bad' ? 1 : 0.5 }}>
            {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : 'ذخیره تغییرات'}
          </Button>

          <button onClick={onOpenSaved} className="w-full flex items-center gap-3 px-4 py-3.5 rounded-2xl border hover:bg-[var(--tg-hover)]" style={{ borderColor: 'var(--tg-divider)' }}>
            <span className="w-10 h-10 rounded-full flex items-center justify-center" style={{ background: 'linear-gradient(135deg,#3390EC,#6ec531)' }}>
              <Bookmark className="w-5 h-5 text-white fill-white" />
            </span>
            <span className="text-[15px] font-medium flex-1 text-right">پیام‌های ذخیره شده</span>
          </button>
        </div>
      </div>
    </div>
  )
}
