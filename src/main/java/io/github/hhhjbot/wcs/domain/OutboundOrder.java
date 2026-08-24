package io.github.hhhjbot.wcs.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 출고 지시 한 건. 상위 시스템(WMS)이 내려준다.
 *
 * <p>화물 하나에 지시 하나가 대응한다. WMS가 주문을 케이스 단위로 쪼개 내려주기 때문이다.
 * 이 지시 하나가 설비를 거치면서 {@link EquipmentTask} 여러 건으로 나뉜다.
 *
 * <pre>
 *   TO-00001   CS-9001   A-01-03-02 → CHUTE-3   컷오프 16:00
 *      ├─ TO-00001-1   SC-A01    A-01-03-02 → PND-A01
 *      ├─ TO-00001-2   CV-01     PND-A01    → IND-01
 *      └─ TO-00001-3   SRT-01    IND-01     → CHUTE-3
 * </pre>
 *
 * <h3>계획 슈트</h3>
 * {@code plannedChute}는 WMS가 배차 계획에 따라 정한 슈트다.
 * 만재나 장애로 다른 슈트를 쓰게 되면 그 결과는 이 지시가 아니라 작업 쪽에 남는다.
 * 지시는 내려온 그대로 바뀌지 않는다. (ADR-0003)
 *
 * <h3>컷오프</h3>
 * 슈트에 붙는 차량의 출발 시각이다. 대기열에서 어느 지시를 먼저 하달할지 정하는
 * 기준이 된다. (ADR-0005)
 *
 * <p>날짜까지 갖는 것은 야간 운영에서 시각만으로는 순서가 뒤집히기 때문이다.
 * 23:00 출발 차량과 다음 날 01:00 출발 차량을 시각만으로 비교하면 뒤가 앞이 된다.
 */
public record OutboundOrder(
        String orderNo,
        String loadId,
        LocationCode source,
        LocationCode plannedChute,
        LocalDateTime cutoff) {

    public OutboundOrder {
        orderNo = require(orderNo, "지시번호");
        loadId  = require(loadId, "화물번호");
        Objects.requireNonNull(source, "출발지는 필수입니다");
        Objects.requireNonNull(plannedChute, "계획 슈트는 필수입니다");
        Objects.requireNonNull(cutoff, "컷오프는 필수입니다");

        if (!source.isRack()) {
            throw new IllegalArgumentException(
                    "출고는 랙에서 시작합니다: " + source.value());
        }
        if (plannedChute.kind() != LocationKind.CHUTE) {
            throw new IllegalArgumentException(
                    "계획 슈트가 슈트가 아닙니다: " + plannedChute.value());
        }
    }

    /**
     * 이 지시의 {@code seq} 번째 설비 작업 번호. {@code TO-00001} + {@code 1} → {@code TO-00001-1}.
     *
     * <p>조립 규칙은 {@link TaskNo}가 갖는다. 지시는 자기 번호만 넘긴다.
     *
     * @throws IllegalArgumentException 순번이 1 미만일 때
     */
    public TaskNo taskNo(int seq) {
        return TaskNo.of(orderNo, seq);
    }

    /** 첫 구간을 담당할 크레인. 출발지 주소에서 정해진다. (ADR-0010) */
    public String craneCode() {
        return source.craneCode();
    }

    @Override
    public String toString() {
        return "%s [%s] %s → %s (컷오프 %s)"
                .formatted(orderNo, loadId, source, plannedChute, cutoff);
    }

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field + "은(는) 필수입니다");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + "은(는) 비어 있을 수 없습니다");
        }
        return trimmed;
    }
}
