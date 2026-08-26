import { useEffect, useState } from 'react'

import { api, ApiError } from '../api'
import type { DispatchResult } from '../types'

/**
 * 운전 모드와 수동 주기.
 *
 * 자동일 때는 서버의 EquipmentPoller 가 1초마다 판단 주기를 돌린다.
 * 수동으로 바꾸면 그 폴러가 실제로 멈추고, 여기서 누를 때만 한 주기가 돈다.
 *
 * 화면에서만 껐다 켜면 거짓말이 된다 — 서버 주기가 계속 지나가므로
 * 수동으로 한 번 눌러 봐야 이미 다 흘러가 있다. 그래서 토글이 서버 상태를 바꾼다.
 */
export function CyclePanel({ onChanged }: { onChanged: () => void }) {
  const [auto, setAuto] = useState(true)
  const [result, setResult] = useState<DispatchResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // 서버가 실제로 어느 모드인지 한 번 읽어 온다. 새로고침해도 어긋나지 않게.
  useEffect(() => {
    api
      .polling()
      .then((state) => setAuto(state.enabled))
      .catch(() => undefined)
  }, [])

  function fail(e: unknown) {
    setError(e instanceof ApiError ? e.message : '서버 응답 없음')
  }

  async function toggle() {
    setError(null)
    try {
      const state = await api.setPolling(!auto)
      setAuto(state.enabled)
      setResult(null)
    } catch (e) {
      fail(e)
    }
  }

  async function runOnce() {
    setError(null)
    try {
      setResult(await api.dispatch())
      onChanged()
    } catch (e) {
      fail(e)
    }
  }

  async function reset() {
    setBusy(true)
    setError(null)
    try {
      await api.reset()
      setResult(null)
      onChanged()
    } catch (e) {
      fail(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="panel">
      <h3>운전 모드</h3>

      <div className="modes" role="group" aria-label="운전 모드">
        <button
          type="button"
          className={auto ? 'seg on' : 'seg'}
          aria-pressed={auto}
          onClick={() => (auto ? undefined : void toggle())}
        >
          자동
        </button>
        <button
          type="button"
          className={auto ? 'seg' : 'seg on'}
          aria-pressed={!auto}
          onClick={() => (auto ? void toggle() : undefined)}
        >
          수동
        </button>
      </div>

      {auto ? (
        <p className="hint block">주기 1초 · 자동 하달</p>
      ) : (
        <>
          <button type="button" onClick={() => void runOnce()}>
            한 주기 실행
          </button>
          <p className="hint block">자동 정지 · 수동 하달</p>
        </>
      )}

      {error && <p className="error">{error}</p>}

      {result && (
        <div className="cycle-result">
          <p className="tally">
            <span className="badge run">하달 {result.dispatched}</span>
            <span className="badge hold">대기 {result.blocked}</span>
            {result.failed > 0 && <span className="badge stop">실패 {result.failed}</span>}
          </p>

          <div className="why-list">
            {result.blockedTasks.map((task) => (
              <p key={task.taskNo} className="why">
                <span className="mono">{task.taskNo}</span> {task.reason}
                {task.retryCount > 0 && <span className="dim"> · 대기 {task.retryCount}주기</span>}
              </p>
            ))}
            {result.failedTasks.map((task) => (
              <p key={task.taskNo} className="why stop-text">
                <span className="mono">{task.taskNo}</span> {task.reason}
              </p>
            ))}
          </div>
        </div>
      )}

      <div className="reset-row">
        <button type="button" className="ghost" onClick={() => void reset()} disabled={busy}>
          {busy ? '처리 중' : '처음 상태로'}
        </button>
        <p className="hint">
          작업·지시 삭제 후 시연 지시 재접수. 슈트 정원 초과 시 복구 수단
        </p>
      </div>
    </div>
  )
}
