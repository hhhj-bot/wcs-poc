package io.github.hhhjbot.wcs.domain;

/**
 * 설비가 되돌려 주는 상태 신호.
 *
 * <p>PLC는 "끝났다"고 알려 주지 않는다. WCS가 상태 태그를 주기마다 읽어서 알아낸다.
 * 이 enum은 그 태그에서 읽어 온 값을 도메인 말로 옮긴 것이다.
 *
 * <pre>
 *   WCS                                  PLC
 *   CMD.FROM / CMD.TO / CMD.ID  기록
 *   CMD.TRIGGER = 1             ──▶
 *                               ◀──     STS.ACK   = 1          ACK
 *                               ◀──     STS.STATE = RUNNING    RUNNING
 *                               ◀──     STS.STATE = DONE       DONE
 *   CMD.TRIGGER = 0             ──▶
 * </pre>
 *
 * <h3>중간 단계를 놓칠 수 있다</h3>
 * 폴링 주기가 설비의 동작보다 길면 {@link #ACK}와 {@link #RUNNING}을 못 보고
 * 곧장 {@link #DONE}을 읽는다. 신호를 놓친 것이지 설비가 건너뛴 것이 아니므로,
 * 읽은 쪽이 중간 상태를 채워 넣어야 한다. 그 처리는 {@link OutboundFlow}가 한다.
 *
 * <p>{@code IDLE}이 없는 것은 "아무 신호도 없음"을 {@code Optional.empty()}로
 * 나타내기 때문이다. 신호가 없는 것은 값이 아니다.
 */
public enum EquipmentSignal {

    /** 명령을 받았다. 아직 움직이지는 않았다. */
    ACK,

    /** 반송 중. */
    RUNNING,

    /** 반송 완료. 화물이 목적지에 놓였다. */
    DONE,

    /** 설비 이상. 사람이 확인해야 한다. */
    FAULT;

    /** 이 신호가 뜻하는 작업 상태. */
    public TaskStatus toTaskStatus() {
        return switch (this) {
            case ACK -> TaskStatus.ACKED;
            case RUNNING -> TaskStatus.EXECUTING;
            case DONE -> TaskStatus.COMPLETED;
            case FAULT -> TaskStatus.FAILED;
        };
    }
}
