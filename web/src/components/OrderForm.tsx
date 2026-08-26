import { useState } from 'react'

import { api, ApiError } from '../api'
import type { OrderRequest } from '../types'

/** 오늘 16:00 을 datetime-local 이 받는 형식으로. 서버는 2026-08-28T16:00 형태를 기대한다. */
function defaultCutoff(): string {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}T16:00`
}

/** 지시번호를 겹치지 않게 만든다. 서버가 같은 번호를 두 번 받지 않는다. */
function nextOrderNo(): string {
  return `TO-${String(Date.now() % 100000).padStart(5, '0')}`
}

export function OrderForm({ onAccepted }: { onAccepted: () => void }) {
  const [form, setForm] = useState<OrderRequest>({
    orderNo: nextOrderNo(),
    loadId: 'CS-9001',
    source: 'A-01-03-02',
    chute: 'CHUTE-3',
    cutoff: defaultCutoff(),
  })
  const [error, setError] = useState<string | null>(null)
  const [sending, setSending] = useState(false)

  const set = (key: keyof OrderRequest) => (event: React.ChangeEvent<HTMLInputElement>) =>
    setForm((prev) => ({ ...prev, [key]: event.target.value }))

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setSending(true)
    setError(null)
    try {
      await api.accept(form)
      // 다음 지시를 바로 넣을 수 있게 번호만 새로 뽑고 나머지는 남겨 둔다.
      setForm((prev) => ({ ...prev, orderNo: nextOrderNo() }))
      onAccepted()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '서버에 연결하지 못했습니다')
    } finally {
      setSending(false)
    }
  }

  return (
    <form className="panel" onSubmit={submit}>
      <h3>출고 지시</h3>

      <label>
        지시번호
        <input value={form.orderNo} onChange={set('orderNo')} required />
      </label>
      <label>
        화물번호
        <input value={form.loadId} onChange={set('loadId')} required />
      </label>
      <label>
        출발 랙
        <input value={form.source} onChange={set('source')} placeholder="A-01-03-02" required />
      </label>
      <label>
        목적 슈트
        <input value={form.chute} onChange={set('chute')} placeholder="CHUTE-3" required />
      </label>
      <label>
        컷오프
        <input type="datetime-local" value={form.cutoff} onChange={set('cutoff')} required />
      </label>

      <button type="submit" disabled={sending}>
        {sending ? '접수 중…' : '지시 접수'}
      </button>

      {error && <p className="error">{error}</p>}

      <p className="hint block">
        같은 통로(A-01)로 두 건을 연달아 넣으면 크레인 정원이 1이라 두 번째가 대기합니다.
        A-02로 넣으면 크레인이 달라 함께 나갑니다.
      </p>
    </form>
  )
}
