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

/** 설비가 내놓는 상태 블록. 실물 PLC 의 STS 태그에 해당한다 */
export interface EquipmentStatus {
  equipmentCode: string
  mode: 'STOPPED' | 'AUTO' | 'MANUAL' | 'REMOTE'
  motion: 'IDLE' | 'TRAVELING' | 'HOISTING' | 'FORK_OUT' | 'FORK_IN' | 'DONE' | 'FAULT'
  /** 설비가 지금 물고 있는 명령. 에코백 */
  commandId: string | null
  /** 주행 위치 mm */
  positionX: number
  /** 승강 위치 mm */
  positionY: number
  loaded: boolean
  alarmCode: number
  cycleCount: number
}

/**
 * GET /api/equipments
 *
 * 앞의 넷은 WCS 가 세어 낸 값이고 status 는 설비가 내놓은 값이다.
 * 출처가 다르므로 어긋날 수 있고, 어긋나면 그것이 신호다.
 */
export interface EquipmentLoad {
  code: string
  capacity: number
  inFlight: number
  available: number
  canAccept: boolean
  status: EquipmentStatus
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

/** GET·POST /api/polling — 자동 주기가 도는 중인지 */
export interface PollingState {
  enabled: boolean
}

/** POST /api/demo — 시연 지시 한 묶음 */
export interface LoadedSample {
  added: number
  orderNos: string[]
}
