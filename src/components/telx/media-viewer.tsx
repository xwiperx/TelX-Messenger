'use client'

import { X } from 'lucide-react'

export default function MediaViewer({ url, onClose }: { url: string; onClose: () => void }) {
  return (
    <div className="fixed inset-0 z-50 bg-black/95 flex items-center justify-center tg-fade-in" onClick={onClose} dir="rtl">
      <button className="absolute top-4 left-4 p-2 rounded-full bg-white/10 hover:bg-white/20" aria-label="بستن">
        <X className="w-6 h-6 text-white" />
      </button>
      { }
      <img src={url} alt="نمایش رسانه" className="max-h-[92vh] max-w-[94vw] object-contain rounded-lg" onClick={(e) => e.stopPropagation()} />
    </div>
  )
}
