package io.github.hhhjbot.wcs.domain;

import java.util.Objects;

/**
 * 설비가 내놓는 상태 블록.
 *
 * <p>{@link EquipmentSignal}이 "명령 하나가 어디까지 갔나"를 한 글자로 줄인 것이라면,
 * 이쪽은 <b>설비 자체가 지금 어떤 상태인가</b>다. 실물 PLC의 STS 태그 블록에 해당한다.
 *
 * <pre>
 *   STS 블록  (PLC → WCS)
 *     D2000   응답 명령 ID    ← 에코백
 *     D2002   운전 모드       0=정지 1=자동 2=수동 3=원격
 *     D2004   동작 상태       0=대기 1=주행 2=승강 3=포크출 4=포크입 5=완료 9=이상
 *     D2010   현재 X (주행)   mm
 *     D2012   현재 Y (승강)   mm
 *     D2020   적재 유무       재하 검출 센서
 *     D2030   알람 코드
 *     D2040   사이클 카운트
 * </pre>
 *
 * <h3>에코백이 왜 필요한가</h3>
 * {@code commandId}는 설비가 <b>지금 물고 있는 명령</b>을 되돌려 준 것이다.
 * 이것이 없으면 방금 읽은 완료 신호가 어느 명령에 대한 것인지 알 수 없다.
 * 명령을 내리자마자 읽으면 아직 이전 명령의 완료가 남아 있을 수 있는데,
 * 그것을 새 명령이 끝난 것으로 오해하면 화물이 랙에 있는 채로 이동 완료가 기록된다.
 *
 * <p>{@link EquipmentGateway#read(TaskNo)}가 작업 번호를 받는 것이 이 짝맞춤이다.
 * 실물에서는 우리가 보낸 명령 ID와 여기 담겨 온 ID가 같은지 확인해야 한다.
 *
 * <h3>제어에 쓰는 값과 보기만 하는 값</h3>
 * 하달 판단에 실제로 쓰이는 것은 {@link #motion()}과 {@link #alarmCode()}뿐이다.
 * 위치·적재·사이클 카운트는 화면과 이력용이다. 둘을 한 덩어리로 받는 것은
 * PLC가 태그 블록을 통째로 내주기 때문이고, 나누는 것은 읽은 뒤의 일이다.
 *
 * @param equipmentCode 설비 코드
 * @param mode          운전 모드
 * @param motion        지금 무엇을 하고 있는지
 * @param commandId     지금 물고 있는 명령. 없으면 {@code null}
 * @param positionX     주행 위치 mm. 통로를 따라 움직인 거리
 * @param positionY     승강 위치 mm. 랙의 단 높이
 * @param loaded        재하 검출. 포크에 화물이 실려 있는지
 * @param alarmCode     0이면 정상
 * @param cycleCount    기동 이후 완료한 명령 수
 */
public record EquipmentStatus(
        String equipmentCode,
        RunMode mode,
        Motion motion,
        String commandId,
        int positionX,
        int positionY,
        boolean loaded,
        int alarmCode,
        long cycleCount) {

    /** 운전 모드. 관제반의 셀렉터 스위치에 해당한다. */
    public enum RunMode {
        /** 정지. 명령을 받지 않는다. */
        STOPPED,
        /** 자동. 상위에서 내린 명령을 수행한다. */
        AUTO,
        /** 수동. 현장 조작반에서 사람이 움직인다. */
        MANUAL,
        /** 원격 보수. 정비 중이라 상위 명령을 막는다. */
        REMOTE
    }

    /** 동작 상태. 스태커 크레인 기준의 세분이다. */
    public enum Motion {
        /** 대기. 명령이 없다. */
        IDLE,
        /** 주행. 통로를 따라 이동 중. */
        TRAVELING,
        /** 승강. 캐리지가 단을 오르내리는 중. */
        HOISTING,
        /** 포크 출. 랙 쪽으로 포크를 밀어 넣는 중. */
        FORK_OUT,
        /** 포크 입. 화물을 얹고 포크를 거두는 중. */
        FORK_IN,
        /** 완료. 명령을 끝내고 응답을 올린 상태. */
        DONE,
        /** 이상. 사람이 확인해야 한다. */
        FAULT
    }

    public EquipmentStatus {
        Objects.requireNonNull(equipmentCode, "설비 코드는 필수입니다");
        Objects.requireNonNull(mode, "운전 모드는 필수입니다");
        Objects.requireNonNull(motion, "동작 상태는 필수입니다");
        if (alarmCode < 0) {
            throw new IllegalArgumentException("알람 코드는 0 이상이어야 합니다: " + alarmCode);
        }
    }

    /** 명령이 없는 정상 대기 상태. */
    public static EquipmentStatus idle(String equipmentCode) {
        return new EquipmentStatus(equipmentCode, RunMode.AUTO, Motion.IDLE,
                null, 0, 0, false, 0, 0);
    }

    /** 지금 명령을 물고 있는지. */
    public boolean hasCommand() {
        return commandId != null;
    }

    /** 이상인지. 알람 코드와 동작 상태 둘 중 하나만 걸려도 이상으로 본다. */
    public boolean isFaulted() {
        return alarmCode != 0 || motion == Motion.FAULT;
    }

    /** 상위 명령을 받을 수 있는 모드인지. 수동이나 보수 중이면 받지 않는다. */
    public boolean acceptsCommand() {
        return mode == RunMode.AUTO && !isFaulted();
    }

    @Override
    public String toString() {
        return "%s %s/%s%s%s".formatted(
                equipmentCode, mode, motion,
                commandId == null ? "" : " " + commandId,
                alarmCode == 0 ? "" : " ALM-" + alarmCode);
    }
}
