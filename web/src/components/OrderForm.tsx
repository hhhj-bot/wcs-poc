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
  const [added, setAdded] = useState<string[] | null>(null)

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
      setError(e instanceof ApiError ? e.message : '서버 응답 없음')
    } finally {
      setSending(false)
    }
  }

  /**
   * 미리 짜 둔 세 건을 한 번에 넣는다.
   *
   * 한 묶음이 우선순위·병렬·병목을 동시에 드러내도록 구성돼 있다.
   * 누를 때마다 번호가 이어지므로 여러 번 눌러 대기열을 쌓아 볼 수 있다.
   */
  async function loadSample() {
    setSending(true)
    setError(null)
    try {
      const result = await api.loadSample()
      setAdded(result.orderNos)
      onAccepted()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '서버 응답 없음')
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
        {sending ? '전송 중' : '지시 접수'}
      </button>

      {error && <p className="error">{error}</p>}

      <p className="hint block">
        A-01 연속 2건 → 크레인 정원 1, 두 번째 대기 · A-02 → 담당 크레인 달라 병렬
      </p>

      <div className="reset-row">
        <button
          type="button"
          className="ghost"
          onClick={() => void loadSample()}
          disabled={sending}
        >
          시연 3건 추가
        </button>
        <p className="hint">우선순위 · 병렬 · 병목 동시 확인. 누를수록 대기열 증가</p>
      </div>

      {added && (
        <p className="hint block mono-hint">접수 {added.join(' · ')}</p>
      )}
    </form>
  )
}
