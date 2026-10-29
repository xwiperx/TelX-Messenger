import { EventEmitter } from 'events'

const g = globalThis as any

export const emitter: EventEmitter =
  g.__telxEmitter ?? (g.__telxEmitter = new EventEmitter())

emitter.setMaxListeners(0)

export type TelxEvent = {
  type: 'message' | 'typing' | 'read' | 'edit' | 'delete' | 'reaction' | 'pin' | 'chat' | 'story'
  chatId?: string
  payload?: any
}

/** Emit an event to a specific set of users. */
export function emitToUsers(userIds: string[], ev: TelxEvent) {
  for (const id of userIds) {
    emitter.emit('u:' + id, ev)
  }
}

/** Wait up to timeoutMs for any event for this user. Returns null on timeout. */
export function waitEvent(userId: string, timeoutMs: number): Promise<TelxEvent | null> {
  return new Promise((resolve) => {
    const key = 'u:' + userId
    let done = false
    const cleanup = () => {
      if (done) return
      done = true
      clearTimeout(timer)
      emitter.removeListener(key, listener)
    }
    const timer = setTimeout(() => { cleanup(); resolve(null) }, timeoutMs)
    const listener = (ev: TelxEvent) => { cleanup(); resolve(ev) }
    emitter.once(key, listener)
  })
}
