'use client'

import { MessageCircle, Users, Settings, User } from 'lucide-react'

export type Screen = 'chats' | 'contacts' | 'settings' | 'profile'

const ITEMS: { id: Screen; label: string; Icon: any }[] = [
  { id: 'chats', label: 'چت‌ها', Icon: MessageCircle },
  { id: 'contacts', label: 'مخاطبین', Icon: Users },
  { id: 'settings', label: 'تنظیمات', Icon: Settings },
  { id: 'profile', label: 'پروفایل', Icon: User },
]

export default function BottomNav({ screen, onNavigate, unread }: { screen: Screen; onNavigate: (s: Screen) => void; unread: number }) {
  return (
    <nav className="tg-bottom-nav fixed bottom-4 left-1/2 -translate-x-1/2 z-30 flex items-center gap-1 px-2 py-1.5 rounded-full" aria-label="نویگیشن اصلی">
      {ITEMS.map(({ id, label, Icon }) => {
        const active = screen === id
        return (
          <button
            key={id}
            onClick={() => onNavigate(id)}
            className={`relative flex items-center gap-1.5 px-4 py-2 rounded-full text-[13px] font-medium transition-all ${active ? 'text-white' : 'text-muted-foreground hover:text-foreground'}`}
            style={active ? { background: 'var(--tg-blue)' } : {}}
            aria-label={label}
            aria-current={active ? 'page' : undefined}
          >
            <Icon className="w-4.5 h-4.5" />
            <span className="hidden xs:inline">{label}</span>
            {id === 'chats' && unread > 0 && !active && (
              <span className="absolute -top-1 -right-1 min-w-[18px] h-[18px] px-1 rounded-full text-[10px] font-bold text-white flex items-center justify-center" style={{ background: 'var(--tg-red)' }}>
                {unread > 99 ? '99+' : unread}
              </span>
            )}
          </button>
        )
      })}
    </nav>
  )
}
