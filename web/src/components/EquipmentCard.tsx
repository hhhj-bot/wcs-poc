import type { EquipmentLoad, EquipmentStatus } from '../types'

const MOTION: Record<EquipmentStatus['motion'], string> = {
  IDLE: '대기',
  TRAVELING: '주행',
  HOISTING: '승강',
  FORK_OUT: '포크 출',
  FORK_IN: '포크 입',
  DONE: '완료',
  FAULT: '이상',
}

const MODE: Record<EquipmentStatus['mode'], string> = {
  STOPPED: '정지',
  AUTO: '자동',
  MANUAL: '수동',
  REMOTE: '보수',
}

function tone(status: EquipmentStatus): string {
  if (status.alarmCode !== 0 || status.motion === 'FAULT') return 'stop'
  if (status.motion === 'IDLE') return 'wait'
  return 'run'
}

/**
 * 설비 한 대.
 *
 * 왼쪽은 WCS 가 목록에서 세어 낸 값, 오른쪽은 설비가 STS 태그로 내놓은 값이다.
 * 출처가 다르므로 어긋날 수 있고, 어긋나면 그것 자체가 신호다 —
 * 진행 중이 1인데 설비가 대기면 명령이 유실된 것.
 */
export function EquipmentCard({ load }: { load: EquipmentLoad }) {
  const s = load.status
  const ratio = load.capacity > 0 ? Math.min(load.inFlight / load.capacity, 1) : 0
  const full = load.inFlight >= load.capacity

  return (
    <li className="eq">
      <div className="eq-head">
        <span className="mono code">{load.code}</span>
        <span className={`badge ${tone(s)}`}>{MOTION[s.motion]}</span>
        <span className="mono count">
          {load.inFlight}/{load.capacity}
        </span>
      </div>

      <div className="meter">
        <div
          className={`meter-bar ${full ? 'full' : load.inFlight > 0 ? 'busy' : 'idle'}`}
          style={{ width: `${ratio * 100}%` }}
        />
      </div>

      <dl className="tags">
        <div>
          <dt>모드</dt>
          <dd>{MODE[s.mode]}</dd>
        </div>
        <div>
          <dt>명령</dt>
          <dd>{s.commandId ?? '—'}</dd>
        </div>
        <div>
          <dt>주행 X</dt>
          <dd>{s.positionX.toLocaleString()}</dd>
        </div>
        <div>
          <dt>승강 Y</dt>
          <dd>{s.positionY.toLocaleString()}</dd>
        </div>
        <div>
          <dt>재하</dt>
          <dd>{s.loaded ? '有' : '無'}</dd>
        </div>
        <div>
          <dt>사이클</dt>
          <dd>{s.cycleCount}</dd>
        </div>
      </dl>

      {s.alarmCode !== 0 && <p className="alarm">ALM-{s.alarmCode}</p>}
    </li>
  )
}
