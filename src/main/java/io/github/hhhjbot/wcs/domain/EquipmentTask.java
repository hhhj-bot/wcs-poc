package io.github.hhhjbot.wcs.domain;

import java.util.Objects;

/**
 * 설비 작업 한 건.
 *
 * <p>출고 지시 하나가 여러 설비를 거치므로, 설비마다 이 작업이 하나씩 생긴다.
 * 예를 들어 케이스를 랙에서 슈트까지 보내는 지시 하나는 세 건으로 나뉜다.
 *
 * <pre>
 *   TO-00001-1   SC-A01    A-01-03-02 → PND-A01     크레인
 *   TO-00001-2   CV-01     PND-A01    → IND-01      컨베이어
 *   TO-00001-3   SRT-01    IND-01     → CHUTE-3     소터
 * </pre>
 *
 * <p>지시 하나에 대한 작업 세 건은 한꺼번에 만들어지고, 조건이 갖춰진 것부터 하달된다.
 * 아직 하달되지 않은 작업은 {@link TaskStatus#QUEUED} 상태로 목록에 남는다.
 *
 * <h3>자기 구간만 안다</h3>
 * 크레인 작업의 목적지는 P&amp;D다. 그 화물이 결국 어느 슈트로 가는지는 이 작업이 모른다.
 * 알아야 하면 {@link TaskNo#orderNo()}로 지시를 찾아간다.
 * 최종 목적지를 여기에 복사해 두면 지시의 슈트가 바뀔 때 두 값이 어긋난다.
 *
 * <h3>상태 변경</h3>
 * 상태는 {@link #transitionTo(TaskStatus)}로만 바꿀 수 있고, 그 안에서
 * {@link TaskStatus#canTransitionTo(TaskStatus)}로 규칙을 검사한다.
 * setter를 두지 않는 것은 단계를 건너뛴 변경을 막기 위한 것이다.
 *
 * <h3>운반 대상의 이름</h3>
 * 운반 대상을 {@code loadId}로 부른다. 케이스·파렛트·토트 중 무엇이 오더라도
 * 이름을 바꾸지 않기 위한 것이다. (ADR-0008)
 */
public class EquipmentTask {

    private final TaskNo taskNo;
    private final String equipmentCode;
    private final String loadId;
    private final LocationCode from;
    private final LocationCode to;

    private TaskStatus status;
    private String reason;
    private int retryCount;

    public EquipmentTask(TaskNo taskNo,
                         String equipmentCode,
                         String loadId,
                         LocationCode from,
                         LocationCode to) {
        this.taskNo = Objects.requireNonNull(taskNo, "작업 번호는 필수입니다");
        this.equipmentCode = require(equipmentCode, "설비 코드");
        this.loadId = require(loadId, "화물 번호");
        this.from = Objects.requireNonNull(from, "출발지는 필수입니다");
        this.to = Objects.requireNonNull(to, "목적지는 필수입니다");

        if (this.from.equals(this.to)) {
            throw new IllegalArgumentException(
                    "출발지와 목적지가 같습니다: " + this.from.value());
        }
        this.status = TaskStatus.CREATED;
    }

    /**
     * 지시의 {@code seq}번째 구간을 작업으로 만든다.
     *
     * <p>경로의 구간은 화물을 모르는 계획이고, 지시는 경로를 모른다.
     * 둘을 합쳐 실행 단위를 만드는 자리가 여기다.
     */
    public static EquipmentTask of(OutboundOrder order, int seq, Route.Move move) {
        Objects.requireNonNull(order, "출고 지시는 필수입니다");
        Objects.requireNonNull(move, "구간은 필수입니다");

        return new EquipmentTask(
                order.taskNo(seq),
                move.equipment().code(),
                order.loadId(),
                move.from(),
                move.to());
    }

    // ------------------------------------------------------------------
    // 상태 변경
    // ------------------------------------------------------------------

    /**
     * 다음 상태로 옮긴다. 정의되지 않은 전이는 거부한다.
     *
     * @throws IllegalStateException 허용되지 않은 전이일 때
     */
    public void transitionTo(TaskStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "허용되지 않은 상태 전이입니다: %s → %s (작업 %s)"
                            .formatted(status, next, taskNo));
        }
        // 차단·실패에서 돌아오는 것만 재시도로 센다. 몇 번째 시도인지는 사실이고,
        // 몇 번까지 허용할지는 정책이므로 한도는 여기서 갖지 않는다.
        if (next == TaskStatus.CREATED && status.isRetryable()) {
            this.retryCount++;
        }

        this.status = next;

        // 정상 흐름으로 되돌아오면 이전 사유는 의미가 없다.
        if (next == TaskStatus.CREATED) {
            this.reason = null;
        }
    }

    /** 인터록 위반으로 하달을 차단한다. 사유를 남겨 조치할 수 있게 한다. */
    public void block(String reason) {
        transitionTo(TaskStatus.BLOCKED);
        this.reason = require(reason, "사유");
    }

    /** 설비 이상 또는 시한 초과로 중단한다. */
    public void fail(String reason) {
        transitionTo(TaskStatus.FAILED);
        this.reason = require(reason, "사유");
    }

    // ------------------------------------------------------------------
    // 조회
    // ------------------------------------------------------------------

    /**
     * 이 작업이 설비에서 진행 중인지.
     *
     * <p>설비가 더 받을 수 있는지는 이 값으로 판단하지 않는다. 설비마다 동시 처리 수가
     * 다르므로, 진행 중인 작업들을 세어 {@link Equipment#canAccept(int)}에 넘겨야 한다.
     * 이 메서드는 그 계수의 대상인지만 알려준다.
     */
    public boolean isInFlight() {
        return status.isInFlight();
    }

    /** 조건 해소 후 다시 시도할 수 있는 상태인지. */
    public boolean isRetryable() {
        return status.isRetryable();
    }

    /** 같은 출고 지시에서 나온 작업인지. */
    public boolean sameOrderAs(EquipmentTask other) {
        Objects.requireNonNull(other, "비교할 작업은 필수입니다");
        return taskNo.sameOrder(other.taskNo);
    }

    /** 차단·실패 후 다시 시도한 횟수. 한도 판단은 하달 정책이 한다. */
    public int getRetryCount() { return retryCount; }

    public TaskNo getTaskNo() { return taskNo; }
    public String getOrderNo() { return taskNo.orderNo(); }
    public String getEquipmentCode() { return equipmentCode; }
    public String getLoadId() { return loadId; }
    public LocationCode getFrom() { return from; }
    public LocationCode getTo() { return to; }
    public TaskStatus getStatus() { return status; }
    public String getReason() { return reason; }

    @Override
    public String toString() {
        return "%s [%s] %s: %s → %s (%s)"
                .formatted(taskNo, equipmentCode, loadId, from, to, status);
    }

    // ------------------------------------------------------------------

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field + "은(는) 필수입니다");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + "은(는) 비어 있을 수 없습니다");
        }
        return trimmed;
    }
}
