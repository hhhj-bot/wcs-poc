import type { Task, TaskStatus } from '../types'

/** 상태를 색 계열로 묶는다. 세부 단계보다 "지금 어느 국면인가"가 먼저 읽혀야 한다. */
const TONE: Record<TaskStatus, string> = {
  CREATED: 'wait',
  QUEUED: 'wait',
  SENT: 'run',
  ACKED: 'run',
  EXECUTING: 'run',
  COMPLETED: 'done',
  BLOCKED: 'hold',
  FAILED: 'stop',
}

const LABEL: Record<TaskStatus, string> = {
  CREATED: '대기',
  QUEUED: '하달중',
  SENT: '전송',
  ACKED: '수신확인',
  EXECUTING: '반송중',
  COMPLETED: '완료',
  BLOCKED: '차단',
  FAILED: '실패',
}

export function TaskTable({ tasks }: { tasks: Task[] }) {
  if (tasks.length === 0) {
    return <p className="empty">지시 없음</p>
  }

  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th>작업</th>
            <th>설비</th>
            <th>출발</th>
            <th>도착</th>
            <th>상태</th>
            <th>사유</th>
          </tr>
        </thead>
        <tbody>
          {tasks.map((task) => (
            <tr key={task.taskNo} className={task.inFlight ? 'live' : undefined}>
              <td className="mono">{task.taskNo}</td>
              <td className="mono">{task.equipmentCode}</td>
              <td className="mono dim">{task.from}</td>
              <td className="mono">{task.to}</td>
              <td>
                <span className={`badge ${TONE[task.status]}`}>{LABEL[task.status]}</span>
              </td>
              <td className="reason">{task.reason ?? ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
