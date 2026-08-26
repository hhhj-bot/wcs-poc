package io.github.hhhjbot.wcs.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 반송 작업의 상태.
 *
 * <p>WCS와 PLC는 지시 → 수신확인 → 실행 → 완료의 왕복으로 통신한다.
 * 이 enum은 그 순서를 타입으로 강제해, 단계를 건너뛴 상태 변경을 거부한다.
 *
 * <p>차단하려는 상황은 하나다. 설비로부터 완료 신호를 수신하지 않았는데
 * 작업이 완료로 기록되는 것. 이 경우 재고는 이동한 것으로 남지만
 * 물품은 랙에 그대로 있어, 시스템과 현물이 어긋난 채 발견되지 않는다.
 *
 * <pre>
 *   CREATED ──▶ QUEUED ──▶ SENT ──▶ ACKED ──▶ EXECUTING ──▶ COMPLETED
 *      │           │         │         │           │
 *      ▼           └─────────┴─────────┴───────────┴──▶ FAILED
 *   BLOCKED ◀──────┘                                       │
 *      │                                                   │
 *      └──────────────── 재시도 ─────────────────────────────┘
 *                          ▼
 *                       CREATED
 * </pre>
 */
public enum TaskStatus {

    /** 상위 시스템으로부터 지시를 수신한 상태. 아직 설비에 전달하지 않았다. */
    CREATED,

    /**
     * 설비별 대기열에 등록된 상태. 설비가 가용해지면 하달한다.
     *
     * <p>여기서 {@link #FAILED}로 갈 수 있다. 명령을 내보내다 통신이 끊기면
     * 아직 설비에 도달하지 않았지만 하달은 실패한 것이기 때문이다.
     */
    QUEUED,

    /** PLC 명령 태그에 값을 기록하고 트리거를 올린 상태. 수신확인 대기. */
    SENT,

    /** 설비가 수신확인(ACK)을 반환한 상태. 명령이 전달되었음이 확인되었다. */
    ACKED,

    /** 설비가 반송을 수행 중인 상태. */
    EXECUTING,

    /** 반송 완료. 재고와 로케이션 점유 상태가 갱신되었다. */
    COMPLETED,

    /** 인터록 위반으로 하달이 차단된 상태. 조건 해소 후 재시도할 수 있다. */
    BLOCKED,

    /** 설비 이상 또는 수신확인 시한 초과로 중단된 상태. 재시도할 수 있다. */
    FAILED;

    /**
     * 이 상태에서 전이할 수 있는 상태 집합.
     *
     * <p>설비 점유는 여기에 없다. 점유는 거절 사유가 아니라 대기 사유이므로
     * {@link #QUEUED}에 머무는 것으로 처리한다.
     */
    public Set<TaskStatus> allowedNext() {
        return switch (this) {
            case CREATED   -> EnumSet.of(QUEUED, BLOCKED);
            case QUEUED    -> EnumSet.of(SENT, BLOCKED, FAILED);   // 전송 자체가 실패할 수 있다
            case SENT      -> EnumSet.of(ACKED, FAILED);
            case ACKED     -> EnumSet.of(EXECUTING, FAILED);
            case EXECUTING -> EnumSet.of(COMPLETED, FAILED);
            case BLOCKED   -> EnumSet.of(CREATED);
            case FAILED    -> EnumSet.of(CREATED);
            case COMPLETED -> EnumSet.noneOf(TaskStatus.class);
        };
    }

    /** 정의된 전이인지 판정한다. */
    public boolean canTransitionTo(TaskStatus next) {
        return next != null && allowedNext().contains(next);
    }

    /** 더 이상 전이하지 않는 종료 상태인지 판정한다. */
    public boolean isTerminal() {
        return this == COMPLETED;
    }

    /**
     * 설비에 지시가 전달되어 설비가 점유된 구간인지 판정한다.
     * 이 구간의 작업이 있는 설비에는 다음 작업을 하달하지 않는다.
     */
    public boolean isInFlight() {
        return this == SENT || this == ACKED || this == EXECUTING;
    }

    /**
     * 조건 해소 후 다시 시도할 수 있는 상태인지 판정한다.
     */
    public boolean isRetryable() {
        return this == BLOCKED || this == FAILED;
    }
}
