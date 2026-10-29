'use client'

import { useState, useEffect, useCallback } from 'react'
import { api, POST, fullName, timeStr } from './helpers'
import type { TUser } from './helpers'
import { Button } from '@/components/ui/button'
import { Switch } from '@/components/ui/switch'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import {
  Moon, Sun, Lock, LogOut, Trash2, MonitorSmartphone, ShieldCheck, Loader2,
  Image as ImageIcon, AlertTriangle, Bell,
} from 'lucide-react'

type Session = { id: string; device: string; ip: string; current: boolean; createdAt: string; lastActive: string }

const WALLS = ['pattern1', 'w1', 'w2', 'w3', 'w4', 'w5', 'w6']

export default function SettingsView({
  me, onMeChanged, onLoggedOut, dark, onToggleDark,
}: {
  me: TUser & { twoFactorEnabled?: boolean }
  onMeChanged: () => void
  onLoggedOut: () => void
  dark: boolean
  onToggleDark: (v: boolean) => void
}) {
  const [sessions, setSessions] = useState<Session[]>([])
  const [busy, setBusy] = useState('')
  const [privacyOverride, setPrivacyOverride] = useState<boolean | null>(null)
  const [twoFaOverride, setTwoFaOverride] = useState<boolean | null>(null)
  const [delOpen, setDelOpen] = useState(false)
  const [delPass, setDelPass] = useState('')
  const [err, setErr] = useState('')

  const privacyLock = privacyOverride ?? !!me.privacyLock
  const twoFa = twoFaOverride ?? !!(me as any).twoFactorEnabled

  const loadSessions = useCallback(async () => {
    const r = await api<{ sessions: Session[] }>('/api/auth/sessions')
    if (r.ok) setSessions(r.sessions)
  }, [])

  useEffect(() => {
    const t = setTimeout(loadSessions, 0)
    return () => clearTimeout(t)
  }, [loadSessions])

  async function togglePrivacy(v: boolean) {
    setPrivacyOverride(v)
    const r = await api('/api/profile', POST({ action: 'privacy', privacyLock: v }))
    if (r.ok) onMeChanged()
  }

  async function toggle2fa(v: boolean) {
    setBusy('2fa')
    const r = await api<{ twoFactorEnabled: boolean; message: string; simulatedCode?: string }>('/api/auth/2fa', POST({ enabled: v }))
    setBusy('')
    if (r.ok) {
      setTwoFaOverride(r.twoFactorEnabled)
      if (r.simulatedCode) alert(`${r.message}\n(ایمیل شبیه‌سازی‌شده) کد: ${r.simulatedCode}`)
      onMeChanged()
    }
  }

  async function setWallpaper(id: string) {
    await api('/api/profile', POST({ action: 'wallpaper', wallpaper: id }))
    onMeChanged()
  }

  async function revoke(id: string | 'all') {
    setBusy(String(id))
    const r = await api<{ message: string }>(`/api/auth/sessions?id=${id}`, { method: 'DELETE' })
    setBusy('')
    if (r.ok) loadSessions()
  }

  async function logout() {
    await api('/api/auth/logout', { method: 'POST' })
    onLoggedOut()
  }

  async function deleteAccount() {
    setErr('')
    setBusy('del')
    const r = await api<{ message: string }>('/api/auth/delete-account', POST({ password: delPass }))
    setBusy('')
    if (!r.ok) return setErr(r.error || 'خطا')
    setDelOpen(false)
    onLoggedOut()
  }

  const row = 'flex items-center justify-between gap-3 px-4 py-3.5'
  const rowBtn = 'flex items-center gap-3 px-4 py-3.5 w-full text-right hover:bg-[var(--tg-hover)]'

  return (
    <div className="h-full flex flex-col bg-[var(--tg-panel)] text-[var(--tg-panel-text)]">
      <div className="px-4 pt-4 pb-3 shrink-0" style={{ background: 'var(--tg-blue)' }}>
        <h1 className="text-white text-xl font-bold">تنظیمات</h1>
      </div>

      <div className="flex-1 overflow-y-auto tg-scroll pb-28">
        {/* appearance */}
        <p className="text-xs font-bold text-muted-foreground px-4 pt-4 pb-1">ظاهر</p>
        <div className={row}>
          <span className="flex items-center gap-3 text-[15px]">
            {dark ? <Moon className="w-5 h-5 text-muted-foreground" /> : <Sun className="w-5 h-5 text-muted-foreground" />}
            حالت شب (Dark Mode)
          </span>
          <Switch checked={dark} onCheckedChange={onToggleDark} />
        </div>

        <div className="px-4 py-3">
          <p className="text-[15px] flex items-center gap-3 mb-3"><ImageIcon className="w-5 h-5 text-muted-foreground" /> پس‌زمینه چت</p>
          <div className="grid grid-cols-4 gap-2.5">
            {WALLS.map((w) => (
              <button
                key={w}
                onClick={() => setWallpaper(w)}
                className={`aspect-square rounded-xl tg-wallpaper ${w !== 'pattern1' ? `tg-wallpaper-${w}` : ''} border-2 transition-all`}
                style={{ borderColor: me.wallpaper === w ? 'var(--tg-blue)' : 'var(--tg-divider)' }}
                aria-label={w}
              />
            ))}
          </div>
        </div>

        {/* privacy & security */}
        <p className="text-xs font-bold text-muted-foreground px-4 pt-4 pb-1">حریم خصوصی و امنیت</p>
        <div className={row}>
          <span className="flex items-center gap-3 text-[15px]">
            <Lock className="w-5 h-5 text-muted-foreground" />
            <span>
              قفل پیوی
              <span className="block text-[11px] text-muted-foreground">جلوگیری از فوروارد و ذخیره محتوای شما</span>
            </span>
          </span>
          <Switch checked={privacyLock} onCheckedChange={togglePrivacy} />
        </div>
        <div className={row}>
          <span className="flex items-center gap-3 text-[15px]">
            <ShieldCheck className="w-5 h-5 text-muted-foreground" />
            <span>
              ورود دو مرحله‌ای (2FA)
              <span className="block text-[11px] text-muted-foreground">ارسال کد به ایمیل هنگام ورود</span>
            </span>
          </span>
          {busy === '2fa' ? <Loader2 className="w-5 h-5 animate-spin" /> : <Switch checked={twoFa} onCheckedChange={toggle2fa} />}
        </div>

        {/* sessions */}
        <p className="text-xs font-bold text-muted-foreground px-4 pt-4 pb-1 flex items-center gap-1.5">
          <MonitorSmartphone className="w-4 h-4" /> دستگاه‌ها و سشن‌های فعال
        </p>
        {sessions.map((s) => (
          <div key={s.id} className={row}>
            <div className="min-w-0">
              <p className="text-[14.5px] truncate">
                {s.device} {s.current && <span className="text-[11px] font-bold" style={{ color: 'var(--tg-blue)' }}>(این دستگاه)</span>}
              </p>
              <p className="text-[11px] text-muted-foreground">آخرین فعالیت: {timeStr(s.lastActive)}</p>
            </div>
            {!s.current && (
              <button onClick={() => revoke(s.id)} className="text-xs px-3 py-1.5 rounded-full bg-red-500/10 text-red-500 font-medium" disabled={busy === s.id}>
                {busy === s.id ? '…' : 'خروج'}
              </button>
            )}
          </div>
        ))}
        {sessions.length > 1 && (
          <div className="px-4 pt-1">
            <button onClick={() => revoke('all')} className="text-xs underline text-muted-foreground">
              خروج از همه دستگاه‌های دیگر
            </button>
          </div>
        )}

        {/* account */}
        <p className="text-xs font-bold text-muted-foreground px-4 pt-4 pb-1">حساب کاربری</p>
        <button onClick={logout} className={rowBtn}>
          <LogOut className="w-5 h-5 text-muted-foreground" />
          <span className="text-[15px] flex-1">خروج از حساب</span>
        </button>
        <button onClick={() => setDelOpen(true)} className={rowBtn}>
          <Trash2 className="w-5 h-5 text-red-500" />
          <span className="text-[15px] flex-1 text-red-500">حذف دائمی حساب</span>
        </button>
      </div>

      {/* delete account dialog */}
      <Dialog open={delOpen} onOpenChange={setDelOpen}>
        <DialogContent className="rounded-2xl max-w-xs" dir="rtl">
          <DialogHeader>
            <DialogTitle className="text-right flex items-center gap-2 text-red-500">
              <AlertTriangle className="w-5 h-5" /> حذف دائمی حساب
            </DialogTitle>
          </DialogHeader>
          <p className="text-[13px] text-muted-foreground">
            تمام چت‌ها، پیام‌ها، استوری‌ها و داده‌های شما برای همیشه حذف می‌شود. این عمل قابل بازگشت نیست.
          </p>
          <input
            type="password"
            value={delPass}
            onChange={(e) => setDelPass(e.target.value)}
            placeholder="رمز عبور خود را وارد کنید"
            dir="ltr"
            className="h-11 rounded-xl bg-muted px-3 outline-none text-sm w-full"
          />
          {err && <p className="text-xs text-red-500">{err}</p>}
          <div className="flex gap-2">
            <Button variant="outline" className="flex-1 rounded-xl" onClick={() => setDelOpen(false)}>انصراف</Button>
            <Button className="flex-1 rounded-xl bg-red-500 hover:bg-red-600 text-white" onClick={deleteAccount} disabled={busy === 'del'}>
              {busy === 'del' ? <Loader2 className="w-4 h-4 animate-spin" /> : 'حذف حساب'}
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  )
}
