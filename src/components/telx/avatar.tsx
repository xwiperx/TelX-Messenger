'use client'

import { initials, avatarColor, fullName } from './helpers'
import type { TUser } from './helpers'

type Props = {
  user: TUser | null
  size?: number
  showOnline?: boolean
  className?: string
}

/** Telegram-style circular avatar with optional online dot. */
export default function Avatar({ user, size = 48, showOnline = false, className = '' }: Props) {
  const hasImg = !!user?.avatarUrl
  return (
    <div className={`relative shrink-0 ${className}`} style={{ width: size, height: size }}>
      {hasImg ? (
         
        <img
          src={user!.avatarUrl!}
          alt={fullName(user)}
          className="w-full h-full rounded-full object-cover"
          style={{ width: size, height: size }}
        />
      ) : (
        <div
          className="w-full h-full rounded-full flex items-center justify-center text-white font-medium select-none"
          style={{
            width: size,
            height: size,
            background: user ? avatarColor(user.id) : 'linear-gradient(135deg,#3390EC,#6ec531)',
            fontSize: size * 0.4,
          }}
        >
          {user ? initials(user) : '🔖'}
        </div>
      )}
      {showOnline && user?.online && <span className="tg-online-dot" />}
    </div>
  )
}
