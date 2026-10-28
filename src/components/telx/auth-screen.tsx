'use client'

import { useState, useEffect } from 'react'
import { api, POST } from './helpers'
import type { TUser } from './helpers'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Check, X, Send, Loader2, KeyRound, MailQuestion, ShieldCheck } from 'lucide-react'

type Mode = 'login' | 'register' | 'forgot' | 'reset' | 'twofa'

export default function AuthScreen({ onLoggedIn }: { onLoggedIn: (me: TUser) => void }) {
  const [mode, setMode] = useState<Mode>('login')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [info, setInfo] = useState('')

  // login
  const [login, setLogin] = useState('')
  const [password, setPassword] = useState('')
  // register
  const [firstName, setFirstName] = useState('')
  const [lastName, setLastName] = useState('')
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [regPass, setRegPass] = useState('')
  // username availability
  const [uState, setUState] = useState<'idle' | 'checking' | 'ok' | 'bad'>('idle')
  const [uMsg, setUMsg] = useState('')
  // 2fa
  const [twoFaUserId, setTwoFaUserId] = useState('')
  const [code, setCode] = useState('')
  // forgot
  const [forgotEmail, setForgotEmail] = useState('')
  const [resetCode, setResetCode] = useState('')
  const [newPass, setNewPass] = useState('')

  useEffect(() => {
    const t = setTimeout(async () => {
      if (!username) { setUState('idle'); setUMsg(''); return }
      if (!/^[a-zA-Z][a-zA-Z0-9_]{3,24}$/.test(username)) {
        setUState('bad')
        setUMsg('۴ تا ۲۵ کاراکتر انگلیسی، شروع با حرف')
        return
      }
      setUState('checking')
      const r = await api<{ available: boolean }>(`/api/auth/check-username?q=${encodeURIComponent(username)}`)
      if (r.ok) {
        setUState(r.available ? 'ok' : 'bad')
        setUMsg(r.available ? 'نام کاربری آزاد است' : 'این نام کاربری گرفته شده')
      } else setUState('idle')
    }, username ? 400 : 0)
    return () => clearTimeout(t)
  }, [username])

  async function doLogin(e?: React.FormEvent) {
    e?.preventDefault()
    setError(''); setInfo(''); setBusy(true)
    const r = await api<{ me: TUser; need2fa: boolean; userId: string; simulatedCode?: string }>('/api/auth/login', POST({ login, password }))
    setBusy(false)
    if (!r.ok) return setError(r.error || 'خطا')
    if (r.need2fa) {
      setTwoFaUserId(r.userId)
      setMode('twofa')
      setInfo(r.simulatedCode ? `ایمیل شبیه‌سازی‌شده — کد ورود: ${r.simulatedCode}` : 'کد به ایمیل شما ارسال شد')
      return
    }
    onLoggedIn(r.me)
  }

  async function do2fa(e?: React.FormEvent) {
    e?.preventDefault()
    setError(''); setBusy(true)
    const r = await api<{ me: TUser }>('/api/auth/verify-2fa', POST({ userId: twoFaUserId, code }))
    setBusy(false)
    if (!r.ok) return setError(r.error || 'خطا')
    onLoggedIn(r.me)
  }

  async function doRegister(e?: React.FormEvent) {
    e?.preventDefault()
    setError(''); setInfo('')
    if (uState !== 'ok') return setError('ابتدا نام کاربری معتبر انتخاب کنید')
    setBusy(true)
    const r = await api<{ me: TUser }>('/api/auth/register', POST({ firstName, lastName, username, email, password: regPass }))
    setBusy(false)
    if (!r.ok) return setError(r.error || 'خطا')
    onLoggedIn(r.me)
  }

  async function doForgot(e?: React.FormEvent) {
    e?.preventDefault()
    setError(''); setInfo(''); setBusy(true)
    const r = await api<{ message: string; simulatedCode?: string }>('/api/auth/forgot', POST({ email: forgotEmail }))
    setBusy(false)
    if (!r.ok) return setError(r.error || 'خطا')
    setMode('reset')
    setInfo(r.simulatedCode ? `ایمیل شبیه‌سازی‌شده — کد بازیابی: ${r.simulatedCode}` : r.message)
  }

  async function doReset(e?: React.FormEvent) {
    e?.preventDefault()
    setError(''); setBusy(true)
    const r = await api<{ message: string }>('/api/auth/reset', POST({ email: forgotEmail, code: resetCode, password: newPass }))
    setBusy(false)
    if (!r.ok) return setError(r.error || 'خطا')
    setMode('login')
    setInfo('رمز عبور تغییر کرد. وارد شوید')
  }

  const inputCls = 'h-11 rounded-xl bg-muted/60 border-border'

  return (
    <div className="min-h-screen flex items-center justify-center p-4" style={{ background: 'linear-gradient(160deg,#3390EC 0%,#17212B 100%)' }}>
      <div className="w-full max-w-sm tg-slide-up">
        <div className="flex flex-col items-center mb-6">
          <div className="w-24 h-24 rounded-full bg-white/10 backdrop-blur flex items-center justify-center mb-4 border border-white/20">
            <Send className="w-12 h-12 text-white -rotate-12" strokeWidth={2.2} />
          </div>
          <h1 className="text-3xl font-bold text-white tracking-wide">TelX</h1>
          <p className="text-white/75 text-sm mt-1">پیام‌رسان سریع و امن</p>
        </div>

        <div className="rounded-3xl bg-card shadow-2xl p-6 border border-border">
          {mode === 'login' && (
            <form onSubmit={doLogin} className="space-y-4">
              <h2 className="text-lg font-bold text-foreground text-center">ورود به حساب</h2>
              <div>
                <Label htmlFor="login">نام کاربری یا ایمیل</Label>
                <Input id="login" dir="ltr" className={`${inputCls} mt-1.5`} value={login} onChange={(e) => setLogin(e.target.value)} placeholder="username" required />
              </div>
              <div>
                <Label htmlFor="pass">رمز عبور</Label>
                <Input id="pass" type="password" dir="ltr" className={`${inputCls} mt-1.5`} value={password} onChange={(e) => setPassword(e.target.value)} placeholder="••••••••" required />
              </div>
              {error && <p className="text-xs text-red-500 text-center">{error}</p>}
              {info && <p className="text-xs text-green-600 text-center">{info}</p>}
              <Button type="submit" disabled={busy} className="w-full h-11 rounded-xl text-white font-semibold" style={{ background: '#3390EC' }}>
                {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : 'ورود'}
              </Button>
              <div className="flex items-center justify-between text-xs">
                <button type="button" onClick={() => { setMode('forgot'); setError(''); setInfo('') }} className="text-[#3390EC] hover:underline flex items-center gap-1">
                  <MailQuestion className="w-3.5 h-3.5" /> فراموشی رمز عبور
                </button>
                <button type="button" onClick={() => { setMode('register'); setError(''); setInfo('') }} className="text-[#3390EC] hover:underline font-semibold">
                  ساخت حساب جدید
                </button>
              </div>
            </form>
          )}

          {mode === 'register' && (
            <form onSubmit={doRegister} className="space-y-3.5">
              <h2 className="text-lg font-bold text-foreground text-center">ساخت حساب کاربری</h2>
              <div className="grid grid-cols-2 gap-2">
                <div>
                  <Label htmlFor="fn">نام</Label>
                  <Input id="fn" className={`${inputCls} mt-1.5`} value={firstName} onChange={(e) => setFirstName(e.target.value)} placeholder="علی" required />
                </div>
                <div>
                  <Label htmlFor="ln">نام خانوادگی</Label>
                  <Input id="ln" className={`${inputCls} mt-1.5`} value={lastName} onChange={(e) => setLastName(e.target.value)} placeholder="رضایی" />
                </div>
              </div>
              <div>
                <Label htmlFor="un">نام کاربری (انگلیسی)</Label>
                <div className="relative">
                  <Input id="un" dir="ltr" className={`${inputCls} mt-1.5 pl-10`} value={username} onChange={(e) => setUsername(e.target.value)} placeholder="username" required />
                  <span className="absolute left-3 top-1/2 -translate-y-1/2 mt-0.5">
                    {uState === 'checking' && <Loader2 className="w-4 h-4 text-muted-foreground animate-spin" />}
                    {uState === 'ok' && <Check className="w-4.5 h-4.5 text-green-500" strokeWidth={3} />}
                    {uState === 'bad' && <X className="w-4.5 h-4.5 text-red-500" strokeWidth={3} />}
                  </span>
                </div>
                {uMsg && <p className={`text-[11px] mt-1 ${uState === 'ok' ? 'text-green-600' : 'text-red-500'}`}>{uMsg}</p>}
              </div>
              <div>
                <Label htmlFor="em">ایمیل</Label>
                <Input id="em" type="email" dir="ltr" className={`${inputCls} mt-1.5`} value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" required />
              </div>
              <div>
                <Label htmlFor="rp">رمز عبور</Label>
                <Input id="rp" type="password" dir="ltr" className={`${inputCls} mt-1.5`} value={regPass} onChange={(e) => setRegPass(e.target.value)} placeholder="حداقل ۶ کاراکتر" required />
              </div>
              {error && <p className="text-xs text-red-500 text-center">{error}</p>}
              <Button type="submit" disabled={busy || uState !== 'ok'} className="w-full h-11 rounded-xl text-white font-semibold" style={{ background: '#3390EC' }}>
                {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : 'ثبت‌نام'}
              </Button>
              <button type="button" onClick={() => { setMode('login'); setError('') }} className="w-full text-xs text-[#3390EC] hover:underline">
                حساب دارید؟ وارد شوید
              </button>
            </form>
          )}

          {mode === 'twofa' && (
            <form onSubmit={do2fa} className="space-y-4">
              <div className="flex flex-col items-center gap-2">
                <ShieldCheck className="w-10 h-10 text-[#3390EC]" />
                <h2 className="text-lg font-bold text-foreground">ورود دو مرحله‌ای</h2>
                <p className="text-xs text-muted-foreground text-center">کد ۶ رقمی ارسال‌شده به ایمیل خود را وارد کنید</p>
              </div>
              <Input dir="ltr" inputMode="numeric" maxLength={6} className={`${inputCls} text-center text-xl tracking-[0.5em] font-bold`} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} placeholder="──────" required />
              {error && <p className="text-xs text-red-500 text-center">{error}</p>}
              {info && <p className="text-[11px] text-green-600 text-center break-all">{info}</p>}
              <Button type="submit" disabled={busy} className="w-full h-11 rounded-xl text-white font-semibold" style={{ background: '#3390EC' }}>
                {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : 'تأیید و ورود'}
              </Button>
              <button type="button" onClick={() => { setMode('login'); setError(''); setInfo('') }} className="w-full text-xs text-muted-foreground hover:underline">
                بازگشت
              </button>
            </form>
          )}

          {mode === 'forgot' && (
            <form onSubmit={doForgot} className="space-y-4">
              <div className="flex flex-col items-center gap-2">
                <KeyRound className="w-10 h-10 text-[#3390EC]" />
                <h2 className="text-lg font-bold text-foreground">بازیابی رمز عبور</h2>
                <p className="text-xs text-muted-foreground text-center">ایمیل حساب خود را وارد کنید تا کد بازیابی ارسال شود</p>
              </div>
              <Input type="email" dir="ltr" className={inputCls} value={forgotEmail} onChange={(e) => setForgotEmail(e.target.value)} placeholder="you@example.com" required />
              {error && <p className="text-xs text-red-500 text-center">{error}</p>}
              <Button type="submit" disabled={busy} className="w-full h-11 rounded-xl text-white font-semibold" style={{ background: '#3390EC' }}>
                {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : 'ارسال کد'}
              </Button>
              <button type="button" onClick={() => { setMode('login'); setError('') }} className="w-full text-xs text-muted-foreground hover:underline">
                بازگشت به ورود
              </button>
            </form>
          )}

          {mode === 'reset' && (
            <form onSubmit={doReset} className="space-y-4">
              <h2 className="text-lg font-bold text-foreground text-center">تنظیم رمز جدید</h2>
              <Input dir="ltr" inputMode="numeric" className={`${inputCls} text-center tracking-widest`} value={resetCode} onChange={(e) => setResetCode(e.target.value)} placeholder="کد بازیابی" required />
              <Input type="password" dir="ltr" className={inputCls} value={newPass} onChange={(e) => setNewPass(e.target.value)} placeholder="رمز عبور جدید" required />
              {error && <p className="text-xs text-red-500 text-center">{error}</p>}
              {info && <p className="text-[11px] text-green-600 text-center break-all">{info}</p>}
              <Button type="submit" disabled={busy} className="w-full h-11 rounded-xl text-white font-semibold" style={{ background: '#3390EC' }}>
                {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : 'تغییر رمز'}
              </Button>
              <button type="button" onClick={() => { setMode('login'); setError('') }} className="w-full text-xs text-muted-foreground hover:underline">
                بازگشت
              </button>
            </form>
          )}
        </div>
      </div>
    </div>
  )
}
