/**
 * 서버 응답 형태.
 *
 * 자바의 record 와 이름·필드를 맞춰 둔다. 서버가 바뀌면 여기가 먼저 어긋나야
 * 화면 곳곳에서 undefined 로 새는 것을 막을 수 있다.
 */

export type TaskStatus =
  | 'CREATED'
  | 'QUEUED'
  | 'SENT'
  | 'ACKED'
  | 'EXECUTING'
  | 'COMPLETED'
  | 'BLOCKED'
  | 'FAILED'

/** GET /api/tasks */
export interface Task {
  taskNo: string
  orderNo: string
  seq: number
  equipmentCode: string
  loadId: string
  from: string
  to: string
  status: TaskStatus
  reason: string | null
  inFlight: boolean
}

/** GET /api/equipments — 설비가 지금 몇 건을 붙들고 있는가 */
export interface EquipmentLoad {
  code: string
  capacity: number
  inFlight: number
  available: number
  canAccept: boolean
}

/** GET /api/stations — 자리에 화물이 몇 개 놓여 있는가 */
export interface StationLoad {
  code: string
  kind: 'RACK' | 'PND' | 'INDUCTION' | 'CHUTE'
  occupancy: number
  capacity: number
}

/** POST /api/orders */
export interface OrderRequest {
  orderNo: string
  loadId: string
  source: string
  chute: string
  cutoff: string
}

export interface AcceptResult {
  orderNo: string
  taskCount: number
  taskNos: string[]
}

/** POST /api/dispatch */
export interface DispatchResult {
  dispatched: number
  blocked: number
  failed: number
  dispatchedTasks: { taskNo: string; equipmentCode: string; from: string; to: string }[]
  blockedTasks: { taskNo: string; equipmentCode: string; reason: string; retryCount: number }[]
  failedTasks: { taskNo: string; equipmentCode: string; reason: string; retryCount: number }[]
}
