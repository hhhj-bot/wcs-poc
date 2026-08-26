import { useCallback, useEffect, useState } from 'react'

import { api } from './api'
import { CyclePanel } from './components/CyclePanel'
import { LoadPanel } from './components/LoadPanel'
import { OrderForm } from './components/OrderForm'
import { TaskTable } from './components/TaskTable'
import type { EquipmentLoad, StationLoad, Task } from './types'

/** 서버 폴러와 같은 주기로 화면도 다시 읽는다. */
const REFRESH_MS = 1000

export default function App() {
  const [tasks, setTasks] = useState<Task[]>([])
  const [equipments, setEquipments] = useState<EquipmentLoad[]>([])
  const [stations, setStations] = useState<StationLoad[]>([])
  const [offline, setOffline] = useState(false)

  const refresh = useCallback(async () => {
    try {
      const [t, e, s] = await Promise.all([api.tasks(), api.equipments(), api.stations()])
      setTasks(t)
      setEquipments(e)
      setStations(s)
      setOffline(false)
    } catch {
      // 서버가 아직 안 떴거나 재시작 중일 수 있다. 화면을 비우지 않고 표시만 바꾼다.
      setOffline(true)
    }
  }, [])

  useEffect(() => {
    void refresh()
    const timer = setInterval(() => void refresh(), REFRESH_MS)
    return () => clearInterval(timer)
  }, [refresh])

  const running = tasks.filter((task) => task.inFlight).length
  const done = tasks.filter((task) => task.status === 'COMPLETED').length
  const held = tasks.filter((task) => task.status === 'BLOCKED' || task.status === 'FAILED').length

  return (
    <div className="app">
      <header>
        <div>
          <p className="eyebrow">wcs-poc</p>
          <h1>출고 관제</h1>
        </div>
        <div className="tally">
          <span className="badge run">진행 {running}</span>
          <span className="badge done">완료 {done}</span>
          {held > 0 && <span className="badge hold">대기·실패 {held}</span>}
          {offline && <span className="badge stop">서버 응답 없음</span>}
        </div>
      </header>

      <div className="layout">
        <aside>
          <OrderForm onAccepted={refresh} />
          <CyclePanel onChanged={refresh} />
        </aside>

        <main>
          <LoadPanel equipments={equipments} stations={stations} />
          <div className="panel">
            <h3>
              작업 <span className="hint">1초 갱신</span>
            </h3>
            <TaskTable tasks={tasks} />
          </div>
        </main>
      </div>
    </div>
  )
}
