'use client'

import { useState, useEffect, useRef, useCallback } from 'react'
import { api, POST, fullName, timeStr, avatarColor } from './helpers'
import type { TStoryGroup, TUser } from './helpers'
import Avatar from './avatar'
import { X, Eye, Trash2, Pause, Play, Loader2 } from 'lucide-react'
import { Dialog, DialogContent } from '@/components/ui/dialog'

type Props = {
  groups: TStoryGroup[]
  startIndex: number
  me: TUser
  onClose: () => void
  onChanged: () => void // refresh groups + chat list
}

export default function StoryViewer({ groups, startIndex, me, onClose, onChanged }: Props) {
  const [gi, setGi] = useState(startIndex)
  const [ii, setIi] = useState(0)
  const [progress, setProgress] = useState(0)
  const [paused, setPaused] = useState(false)
  const [viewers, setViewers] = useState<TUser[] | null>(null)
  const [viewerCount, setViewerCount] = useState(0)
  const [loadingViewers, setLoadingViewers] = useState(false)

  const group = groups[gi]
  const item = group?.items[ii]

  const nextItem = useCallback(() => {
    if (!group) return
    if (ii < group.items.length - 1) { setIi(ii + 1); setProgress(0) }
    else if (gi < groups.length - 1) { setGi(gi + 1); setIi(0); setProgress(0) }
    else { onClose(); onChanged() }
  }, [group, ii, gi, groups.length, onClose, onChanged])

  const prevItem = useCallback(() => {
    if (ii > 0) { setIi(ii - 1); setProgress(0) }
    else if (gi > 0) { setGi(gi - 1); setIi(0); setProgress(0) }
  }, [ii, gi])

  // progress ticker
  useEffect(() => {
    if (!item || paused || viewers) return
    const durationMs = (item.duration || 15) * 1000
    const step = 100 / (durationMs / 100)
    const t = setInterval(() => {
      setProgress((p) => {
        if (p + step >= 100) { clearInterval(t); nextItem(); return 100 }
        return p + step
      })
    }, 100)
    return () => clearInterval(t)
  }, [item, paused, viewers, nextItem])

  // mark viewed
  useEffect(() => {
    if (!item || group?.isMine) return
    api(`/api/stories/${item.id}`, POST({})).catch(() => {})
  }, [item?.id, group?.isMine])

  // hold to pause
  const holdRef = useRef(false)
  function onPointerDown(zone: 'next' | 'prev' | 'center') {
    if (zone === 'center') { holdRef.current = true; setTimeout(() => { if (holdRef.current) setPaused(true) }, 150) }
  }
  function onPointerUp(zone: 'next' | 'prev' | 'center') {
    if (zone === 'center') { holdRef.current = false; setPaused(false); return }
    if (paused) { setPaused(false); return }
    if (zone === 'next') nextItem()
    else prevItem()
  }

  async function showViewers() {
    if (!item) return
    setLoadingViewers(true)
    const r = await api<{ viewers: TUser[] }>(`/api/stories/${item.id}`)
    setLoadingViewers(false)
    if (r.ok) { setViewers(r.viewers); setViewerCount(r.viewers.length) }
  }

  async function deleteStory() {
    if (!item) return
    const r = await api<{ message: string }>(`/api/stories/${item.id}`, { method: 'DELETE' })
    if (r.ok) {
      onChanged()
      if (group && group.items.length > 1) {
        setIi(0); setProgress(0)
      } else if (gi < groups.length - 1) {
        setGi(gi + 1); setIi(0); setProgress(0)
      } else onClose()
    }
  }

  if (!group || !item) return null

  return (
    <div className="fixed inset-0 z-50 bg-black flex flex-col tg-fade-in" dir="rtl">
      {/* progress bars */}
      <div className="flex gap-1 px-3 pt-3 z-10">
        {group.items.map((s, i) => (
          <div key={s.id} className="h-[3px] flex-1 rounded-full bg-white/25 overflow-hidden">
            <div
              className="h-full bg-white rounded-full transition-none"
              style={{ width: i < ii ? '100%' : i === ii ? `${progress}%` : '0%' }}
            />
          </div>
        ))}
      </div>

      {/* header */}
      <div className="flex items-center gap-2.5 px-3 py-2.5 z-10">
        <Avatar user={group.user} size={38} />
        <div className="flex-1 min-w-0">
          <p className="text-white text-[14.5px] font-semibold truncate">{group.isMine ? 'استوری شما' : fullName(group.user)}</p>
          <p className="text-white/60 text-[11px]">{timeStr(item.createdAt)}</p>
        </div>
        {group.isMine && (
          <>
            <button onClick={deleteStory} className="p-2 rounded-full hover:bg-white/10" aria-label="حذف استوری">
              <Trash2 className="w-5 h-5 text-white/85" />
            </button>
          </>
        )}
        <button onClick={onClose} className="p-2 rounded-full hover:bg-white/10" aria-label="بستن">
          <X className="w-6 h-6 text-white" />
        </button>
      </div>

      {/* media + tap zones */}
      <div className="relative flex-1 flex items-center justify-center overflow-hidden select-none" onPointerLeave={() => { holdRef.current = false; setPaused(false) }}>
        {item.mediaType === 'video' ? (
          <video
            key={item.id}
            src={item.mediaUrl}
            autoPlay
            playsInline
            onEnded={nextItem}
            className="max-h-full max-w-full object-contain"
          />
        ) : (
          <img key={item.id} src={item.mediaUrl} alt="استوری" className="max-h-full max-w-full object-contain" />
        )}

        {item.caption && (
          <div className="absolute bottom-8 inset-x-6 text-center">
            <p className="inline-block bg-black/45 text-white text-sm px-4 py-2 rounded-2xl max-w-full break-words">{item.caption}</p>
          </div>
        )}

        <div className="absolute inset-y-0 right-0 w-[30%]" onPointerDown={() => onPointerDown('next')} onPointerUp={() => onPointerUp('next')} />
        <div className="absolute inset-y-0 left-0 w-[30%]" onPointerDown={() => onPointerDown('prev')} onPointerUp={() => onPointerUp('prev')} />
        <div className="absolute inset-y-0 right-[30%] left-[30%]" onPointerDown={() => onPointerDown('center')} onPointerUp={() => onPointerUp('center')} />

        {paused && (
          <div className="absolute top-4 left-1/2 -translate-x-1/2 bg-black/60 rounded-full p-2">
            <Pause className="w-5 h-5 text-white" />
          </div>
        )}
      </div>

      {/* footer: viewers for mine / hint */}
      <div className="px-4 py-3 flex items-center justify-between z-10">
        <div className="text-white/55 text-[11.5px] flex items-center gap-3">
          <span className="flex items-center gap-1"><Play className="w-3 h-3" /> نگه‌داشتن = توقف</span>
          <span>تپ راست/چپ = بعدی/قبلی</span>
        </div>
        {group.isMine && (
          <button onClick={showViewers} className="flex items-center gap-1.5 text-white/85 text-sm hover:text-white">
            <Eye className="w-5 h-5" />
            <span>{item.viewsCount} بازدید</span>
          </button>
        )}
      </div>

      {/* viewers dialog */}
      <Dialog open={viewers !== null} onOpenChange={(o) => { if (!o) setViewers(null) }}>
        <DialogContent className="rounded-2xl max-w-xs max-h-[60vh] overflow-y-auto tg-scroll" dir="rtl">
          <h3 className="font-bold text-right mb-1">بازدیدکنندگان ({viewerCount})</h3>
          {loadingViewers ? (
            <div className="flex justify-center py-4"><Loader2 className="w-5 h-5 animate-spin" /></div>
          ) : viewers && viewers.length === 0 ? (
            <p className="text-sm text-muted-foreground text-center py-4">هنوز کسی ندیده است</p>
          ) : (
            viewers?.map((v) => (
              <div key={v.id} className="flex items-center gap-2.5 py-2">
                <Avatar user={v} size={38} showOnline />
                <div>
                  <p className="text-sm font-medium">{fullName(v)}</p>
                  <p dir="ltr" className="text-[11px] text-muted-foreground text-left">@{v.username}</p>
                </div>
              </div>
            ))
          )}
        </DialogContent>
      </Dialog>
    </div>
  )
}
