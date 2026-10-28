'use client'

import { api, fullName, lastSeenFa, avatarColor } from './helpers'
import type { TUser } from './helpers'
import Avatar from './avatar'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { MessageCircle, Lock, AtSign, Info } from 'lucide-react'

export default function ProfileSheet({
  user, onClose, onStartChat, editable, children,
}: {
  user: TUser
  onClose: () => void
  onStartChat?: () => void
  editable?: boolean
  children?: React.ReactNode
}) {
  async function copyUsername() {
    navigator.clipboard?.writeText('@' + user.username).catch(() => {})
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose() }}>
      <DialogContent className="rounded-3xl max-w-sm p-0 overflow-hidden" dir="rtl">
        <div className="h-28" style={{ background: `linear-gradient(135deg, ${avatarColor(user.id)}, var(--tg-blue))` }} />
        <div className="px-5 pb-5 -mt-12">
          <div className="inline-block rounded-full ring-4 ring-card">
            <Avatar user={user} size={88} />
          </div>
          <h2 className="text-xl font-bold mt-2">{fullName(user)}</h2>
          <p className="text-xs text-muted-foreground mt-0.5">{lastSeenFa(user)}</p>

          <div className="mt-4 space-y-2.5">
            <button onClick={copyUsername} className="flex items-center gap-3 w-full text-right hover:bg-muted rounded-xl px-2 py-2">
              <AtSign className="w-5 h-5 text-muted-foreground" />
              <div>
                <p dir="ltr" className="text-sm text-left">@{user.username}</p>
                <p className="text-[10.5px] text-muted-foreground">نام کاربری — برای کپی کلیک کنید</p>
              </div>
            </button>
            <div className="flex items-center gap-3 px-2 py-2">
              <Info className="w-5 h-5 text-muted-foreground" />
              <div className="min-w-0">
                <p className="text-sm break-words">{user.bio || 'بیوگرافی ندارد'}</p>
                <p className="text-[10.5px] text-muted-foreground">بیوگرافی</p>
              </div>
            </div>
            {user.privacyLock && (
              <div className="flex items-center gap-3 px-2 py-2 text-amber-600">
                <Lock className="w-5 h-5" />
                <div>
                  <p className="text-sm">قفل پیوی فعال است</p>
                  <p className="text-[10.5px] opacity-80">فوروارد و ذخیره محتوای این کاربر مجاز نیست</p>
                </div>
              </div>
            )}
          </div>

          {children}

          {onStartChat && (
            <Button onClick={onStartChat} className="w-full h-11 rounded-xl text-white mt-4 font-semibold" style={{ background: 'var(--tg-blue)' }}>
              <MessageCircle className="w-4 h-4 ml-1" /> ارسال پیام
            </Button>
          )}
        </div>
      </DialogContent>
    </Dialog>
  )
}
