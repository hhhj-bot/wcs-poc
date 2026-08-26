import { useState } from 'react'

import { api, ApiError } from '../api'
import type { DispatchResult } from '../types'

/**
 * 주기를 손으로 한 번 돌린다.
 *
 * 평소에는 서버의 EquipmentPoller 가 1초마다 같은 것을 부르므로 이 버튼이 없어도 흘러간다.
 * 한 칸씩 보고 싶을 때 wcs.polling.enabled=false 로 두고 이 버튼을 쓴다.
 */
export function CyclePanel({ onDispatched }: { onDispatched: () => void }) {
  const [result, setResult] = useState<DispatchResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function run() {
    setError(null)
    try {
      setResult(await api.dispatch())
      onDispatched()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '서버에 연결하지 못했습니다')
    }
  }

  return (
    <div className="panel">
      <h3>판단 주기</h3>
      <button type="button" onClick={run}>
        한 주기 실행
      </button>

      {error && <p className="error">{error}</p>}

      {result && (
        <div className="cycle-result">
          <p className="tally">
            <span className="badge run">하달 {result.dispatched}</span>
            <span className="badge hold">대기 {result.blocked}</span>
            {result.failed > 0 && <span className="badge stop">실패 {result.failed}</span>}
          </p>

          {result.blockedTasks.map((task) => (
            <p key={task.taskNo} className="why">
              <span className="mono">{task.taskNo}</span> {task.reason}
              {task.retryCount > 0 && <span className="dim"> · 재시도 {task.retryCount}</span>}
            </p>
          ))}
          {result.failedTasks.map((task) => (
            <p key={task.taskNo} className="why stop-text">
              <span className="mono">{task.taskNo}</span> {task.reason}
            </p>
          ))}
        </div>
      )}

      <p className="hint block">
        서버 폴러가 켜져 있으면 누르지 않아도 1초마다 같은 일이 일어납니다.
        여기서 누르는 것은 대기 사유를 그 자리에서 보기 위한 것입니다.
      </p>
    </div>
  )
}
