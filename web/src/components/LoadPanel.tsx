import type { EquipmentLoad, StationLoad } from '../types'

/**
 * 설비 정원과 자리 정원을 나란히 보여준다.
 *
 * 둘을 갈라 놓은 것이 이 화면의 요점이다. 크레인 작업이 끝나도 화물은 P&D 에 남아 있으므로,
 * 설비는 비었는데 자리가 차서 하달이 막히는 상태가 있다. 표를 나란히 두면 그 순간이 눈에 띈다.
 */

function Meter({ used, capacity }: { used: number; capacity: number }) {
  // 슈트 정원은 20 이고 크레인은 1 이라, 비율만으로는 둘 다 "거의 참"으로 보인다.
  // 그래서 막대와 숫자를 함께 둔다.
  const ratio = capacity > 0 ? Math.min(used / capacity, 1) : 0
  const tone = used >= capacity ? 'full' : used > 0 ? 'busy' : 'idle'

  return (
    <div className="meter">
      <div className={`meter-bar ${tone}`} style={{ width: `${ratio * 100}%` }} />
    </div>
  )
}

export function LoadPanel({
  equipments,
  stations,
}: {
  equipments: EquipmentLoad[]
  stations: StationLoad[]
}) {
  return (
    <div className="loads">
      <section>
        <h3>
          설비 <span className="hint">진행 중 / 정원</span>
        </h3>
        <ul>
          {equipments.map((eq) => (
            <li key={eq.code}>
              <span className="mono code">{eq.code}</span>
              <Meter used={eq.inFlight} capacity={eq.capacity} />
              <span className="mono count">
                {eq.inFlight}/{eq.capacity}
              </span>
            </li>
          ))}
        </ul>
      </section>

      <section>
        <h3>
          자리 <span className="hint">점유 / 정원</span>
        </h3>
        {stations.length === 0 ? (
          <p className="empty small">—</p>
        ) : (
          <ul>
            {stations.map((st) => (
              <li key={st.code}>
                <span className="mono code">{st.code}</span>
                <Meter used={st.occupancy} capacity={st.capacity} />
                <span className="mono count">
                  {st.occupancy}/{st.capacity === 2147483647 ? '∞' : st.capacity}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
