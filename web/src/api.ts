import type {
  AcceptResult,
  DispatchResult,
  EquipmentLoad,
  LoadedSample,
  OrderRequest,
  PollingState,
  StationLoad,
  Task,
} from './types'

/**
 * 서버 호출.
 *
 * 개발 중에는 이 화면이 5173, 서버가 8080 이라 그대로 부르면 CORS 에 막힌다.
 * vite.config.ts 가 /api 를 8080 으로 넘겨주므로 여기서는 상대 경로만 쓴다.
 * 빌드 결과는 서버의 정적 자원으로 들어가 같은 출처가 되므로 그때도 그대로 동작한다.
 */
const BASE = '/api'

/** 서버가 내려주는 오류 형태: { "error": "..." } */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE}${path}`, init)

  if (!response.ok) {
    let message = `요청이 실패했습니다 (${response.status})`
    try {
      const body = (await response.json()) as { error?: string }
      if (body.error) message = body.error
    } catch {
      // 본문이 JSON 이 아닐 수 있다. 그때는 기본 문구를 쓴다.
    }
    throw new ApiError(response.status, message)
  }
  return (await response.json()) as T
}

export const api = {
  tasks: () => request<Task[]>('/tasks'),

  equipments: () => request<EquipmentLoad[]>('/equipments'),

  stations: () => request<StationLoad[]>('/stations'),

  accept: (order: OrderRequest) =>
    request<AcceptResult>('/orders', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(order),
    }),

  dispatch: () => request<DispatchResult>('/dispatch', { method: 'POST' }),

  polling: () => request<PollingState>('/polling'),

  /** 서버 폴러를 실제로 켜고 끈다. 화면에서만 끄면 자동 주기가 계속 지나가 버린다. */
  setPolling: (enabled: boolean) =>
    request<PollingState>('/polling', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled }),
    }),

  /** 시연 지시 한 묶음. 누를 때마다 번호가 이어진다. */
  loadSample: () => request<LoadedSample>('/demo', { method: 'POST' }),

  reset: () => request<PollingState>('/reset', { method: 'POST' }),
}
