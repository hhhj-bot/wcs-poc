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
 * <p>앞 작업이 완료되어야 다음 작업이 생성된다. 크레인이 P&amp;D에 내려놓기 전에는
 * 컨베이어가 가져갈 물건이 없기 때문이다.
 *
 * <h3>상태 변경</h3>
 * 상태는 {@link #transitionTo(TaskStatus)}로만 바꿀 수 있고, 그 안에서
 * {@link TaskStatus#canTransitionTo(TaskStatus)}로 규칙을 검사한다.
 * setter를 열어두면 단계를 건너뛴 변경이 가능해지고,
 * 그것은 곧 완료 신호를 받지 않은 작업이 완료로 기록되는 상황을 뜻한다.
 *
 * <h3>이름에 대하여</h3>
 * 운반 대상을 {@code loadId}로 부른다. 케이스·파렛트·토트 중 무엇이 오더라도
 * 이름을 바꾸지 않기 위한 것이다. (ADR-0008)
 */
public class EquipmentTask {

    private final String taskNo;
    private final String equipmentCode;
    private final String loadId;
    private final String fromCode;
    private final String toCode;

    private TaskStatus status;
    private String reason;

    public EquipmentTask(String taskNo,
                         String equipmentCode,
                         String loadId,
                         String fromCode,
                         String toCode) {
        this.taskNo = require(taskNo, "taskNo");
        this.equipmentCode = require(equipmentCode, "equipmentCode");
        this.loadId = require(loadId, "loadId");
        this.fromCode = require(fromCode, "fromCode");
        this.toCode = require(toCode, "toCode");

        if (this.fromCode.equals(this.toCode)) {
            throw new IllegalArgumentException(
                    "출발지와 목적지가 같습니다: " + this.fromCode);
        }
        this.status = TaskStatus.CREATED;
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
        this.status = next;

        // 정상 흐름으로 되돌아오면 이전 사유는 의미가 없다.
        if (next == TaskStatus.CREATED) {
            this.reason = null;
        }
    }

    /** 인터록 위반으로 하달을 차단한다. 사유를 남겨 조치할 수 있게 한다. */
    public void block(String reason) {
        transitionTo(TaskStatus.BLOCKED);
        this.reason = require(reason, "reason");
    }

    /** 설비 이상 또는 시한 초과로 중단한다. */
    public void fail(String reason) {
        transitionTo(TaskStatus.FAILED);
        this.reason = require(reason, "reason");
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

    public String getTaskNo() { return taskNo; }
    public String getEquipmentCode() { return equipmentCode; }
    public String getLoadId() { return loadId; }
    public String getFromCode() { return fromCode; }
    public String getToCode() { return toCode; }
    public TaskStatus getStatus() { return status; }
    public String getReason() { return reason; }

    @Override
    public String toString() {
        return "%s [%s] %s: %s → %s (%s)"
                .formatted(taskNo, equipmentCode, loadId, fromCode, toCode, status);
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
