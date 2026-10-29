'use client'

import { useState, useEffect, useCallback, useRef } from 'react'
import { api } from '@/components/telx/helpers'
import type { TUser, TChat, TStoryGroup } from '@/components/telx/helpers'
import AuthScreen from '@/components/telx/auth-screen'
import ChatList from '@/components/telx/chat-list'
import ChatView from '@/components/telx/chat-view'
import StoryViewer from '@/components/telx/story-viewer'
import ContactsView from '@/components/telx/contacts-view'
import SettingsView from '@/components/telx/settings-view'
import ProfileView from '@/components/telx/profile-view'
import BottomNav, { type Screen } from '@/components/telx/bottom-nav'
import ProfileSheet from '@/components/telx/profile-sheet'

type Me = TUser & { email?: string; twoFactorEnabled?: boolean; wallpaper?: string }

export default function Home() {
  const [me, setMe] = useState<Me | null>(null)
  const [booting, setBooting] = useState(true)
  const [screen, setScreen] = useState<Screen>('chats')
  const [activeChatId, setActiveChatId] = useState<string | null>(null)
  const [chats, setChats] = useState<TChat[]>([])
  const [refreshSignal, setRefreshSignal] = useState(0)
  const [dark, setDark] = useState(false)
  const [storyIndex, setStoryIndex] = useState<number | null>(null)
  const [profileUser, setProfileUser] = useState<TUser | null>(null)
  const [browserUnread, setBrowserUnread] = useState(0)
  const notifAsked = useRef(false)

  // boot: session restore
  useEffect(() => {
    api<{ me: Me | null }>('/api/auth/me').then((r) => {
      if (r.ok && r.me) {
        setMe(r.me)
        setDark(!!r.me.darkMode)
      }
      setBooting(false)
    })
  }, [])

  // theme
  useEffect(() => {
    document.documentElement.classList.toggle('dark', dark)
  }, [dark])

  const refreshChats = useCallback(async () => {
    if (!me) return
    const r = await api<{ chats: TChat[] }>('/api/chats')
    if (r.ok) {
      setChats(r.chats)
      const total = r.chats.reduce((a, c) => a + (c.muted ? 0 : c.unreadCount), 0)
      setBrowserUnread(total)
      // PWA badge
      if (navigator.setAppBadge) {
        try { if (total > 0) { navigator.setAppBadge(total) } else { navigator.clearAppBadge() } } catch {}
      }
    }
  }, [me])

  useEffect(() => {
    if (!me) return
    refreshChats()
    const t = setInterval(refreshChats, 4000)
    const h = setInterval(() => api('/api/auth/heartbeat', { method: 'POST' }).catch(() => {}), 20000)
    return () => { clearInterval(t); clearInterval(h) }
  }, [me, refreshChats])

  // browser notification permission + in-page push when hidden
  useEffect(() => {
    if (!me || notifAsked.current) return
    notifAsked.current = true
    if ('Notification' in window && Notification.permission === 'default') {
      Notification.requestPermission().catch(() => {})
    }
  }, [me])

  const lastUnread = useRef(0)
  useEffect(() => {
    if (browserUnread > lastUnread.current && 'Notification' in window && Notification.permission === 'granted' && document.hidden) {
      try {
        new Notification('TelX', { body: `پیام جدید دارید (${browserUnread})`, icon: '/icon.svg', tag: 'telx-unread' })
      } catch {}
    }
    lastUnread.current = browserUnread
  }, [browserUnread])

  function onLoggedIn(u: Me) {
    setMe(u)
    setDark(!!u.darkMode)
    setScreen('chats')
    setActiveChatId(null)
  }

  async function toggleDark(v: boolean) {
    setDark(v)
    await api('/api/profile', { method: 'POST', body: JSON.stringify({ action: 'darkmode', darkMode: v }), headers: { 'content-type': 'application/json' } })
  }

  const meChanged = useCallback(() => {
    api<{ me: Me | null }>('/api/auth/me').then((r) => { if (r.ok && r.me) setMe(r.me) })
    setRefreshSignal((s) => s + 1)
  }, [])

  const openChat = useCallback((chatId: string) => {
    setActiveChatId(chatId)
    setRefreshSignal((s) => s + 1)
  }, [])

  if (booting) {
    return (
      <div className="min-h-screen flex items-center justify-center" style={{ background: 'linear-gradient(160deg,#3390EC 0%,#17212B 100%)' }}>
        <div className="text-white text-2xl font-bold flex items-center gap-2 tg-fade-in">
          <span>TelX</span>
          <span className="w-2.5 h-2.5 rounded-full bg-white animate-ping" />
        </div>
      </div>
    )
  }

  if (!me) return <AuthScreen onLoggedIn={onLoggedIn} />

  const activeChat = chats.find((c) => c.id === activeChatId) || null
  const totalUnread = chats.reduce((a, c) => a + (c.muted ? 0 : c.unreadCount), 0)

  return (
    <div className="h-screen w-full flex overflow-hidden bg-[var(--tg-panel)]">
      {/* Sidebar (desktop) / List (mobile) */}
      <div className={`w-full md:w-[380px] md:max-w-[42%] shrink-0 h-full ${activeChatId ? 'hidden md:block' : ''}`}>
        {screen === 'chats' && (
          <ChatList me={me} onOpenChat={openChat} onOpenStories={(i) => setStoryIndex(i)} refreshSignal={refreshSignal} />
        )}
        {screen === 'contacts' && <ContactsView onOpenChat={openChat} />}
        {screen === 'settings' && (
          <SettingsView me={me} onMeChanged={meChanged} onLoggedOut={() => { setMe(null); setActiveChatId(null) }} dark={dark} onToggleDark={toggleDark} />
        )}
        {screen === 'profile' && (
          <ProfileView me={me} onMeChanged={meChanged} onOpenSaved={() => openSaved()} />
        )}
        {screen !== 'chats' && screen !== 'contacts' && screen !== 'settings' && screen !== 'profile' && null}
      </div>

      {/* Chat pane */}
      <div className={`flex-1 h-full min-w-0 ${activeChatId ? 'block' : 'hidden md:block'}`}>
        {activeChatId && activeChat !== undefined ? (
          <ChatView
            me={me}
            chatId={activeChatId}
            chatInfo={activeChat}
            onBack={() => { setActiveChatId(null); setRefreshSignal((s) => s + 1) }}
            onChatsChanged={() => setRefreshSignal((s) => s + 1)}
            onOpenProfile={(u) => setProfileUser(u)}
          />
        ) : (
          <div className={`h-full flex flex-col items-center justify-center ${wallpaperBg(me.wallpaper)}`}>
            <div className="bg-black/25 text-white rounded-3xl px-8 py-6 text-center backdrop-blur tg-fade-in">
              <p className="text-lg font-bold mb-1">TelX Messenger</p>
              <p className="text-sm opacity-85">یک گفتگو را از لیست چت‌ها انتخاب کنید</p>
              <p className="text-xs opacity-60 mt-3">💡 در گوشی می‌توانید از منوی مرورگر «افزودن به صفحه اصلی» را بزنید</p>
            </div>
          </div>
        )}
      </div>

      {/* Bottom nav (mobile) */}
      {!activeChatId && (
        <BottomNav screen={screen} onNavigate={setScreen} unread={totalUnread} />
      )}

      {/* Story viewer */}
      {storyIndex !== null && (
        <StoryGroupsBridge me={me} startIndex={storyIndex} onClose={() => { setStoryIndex(null); setRefreshSignal((s) => s + 1) }} />
      )}

      {/* Profile sheet */}
      {profileUser && (
        <ProfileSheet user={profileUser} onClose={() => setProfileUser(null)} />
      )}
    </div>
  )

  async function openSaved() {
    const r = await api<{ chatId: string }>('/api/chats', { method: 'POST', body: JSON.stringify({}), headers: { 'content-type': 'application/json' } })
    if (r.ok) openChat(r.chatId)
  }
}

function wallpaperBg(id?: string | null) {
  const map: Record<string, string> = {
    pattern1: 'tg-wallpaper',
    w1: 'tg-wallpaper tg-wallpaper-w1',
    w2: 'tg-wallpaper tg-wallpaper-w2',
    w3: 'tg-wallpaper tg-wallpaper-w3',
    w4: 'tg-wallpaper tg-wallpaper-w4',
    w5: 'tg-wallpaper tg-wallpaper-w5',
    w6: 'tg-wallpaper tg-wallpaper-w6',
  }
  return map[id || 'pattern1'] || 'tg-wallpaper'
}

/** Loads story groups fresh and renders the viewer */
function StoryGroupsBridge({ me, startIndex, onClose }: { me: Me; startIndex: number; onClose: () => void }) {
  const [groups, setGroups] = useState<TStoryGroup[]>([])
  useEffect(() => {
    api<{ groups: TStoryGroup[] }>('/api/stories').then((r) => { if (r.ok) setGroups(r.groups) })
  }, [])
  if (groups.length === 0) return null
  return (
    <StoryViewer
      groups={groups}
      startIndex={Math.min(startIndex, groups.length - 1)}
      me={me}
      onClose={onClose}
      onChanged={() => {
        api<{ groups: TStoryGroup[] }>('/api/stories').then((r) => { if (r.ok) setGroups(r.groups) })
      }}
    />
  )
}
