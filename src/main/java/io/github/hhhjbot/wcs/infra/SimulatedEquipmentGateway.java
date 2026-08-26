package io.github.hhhjbot.wcs.infra;

import io.github.hhhjbot.wcs.domain.EquipmentGateway;
import io.github.hhhjbot.wcs.domain.EquipmentSignal;
import io.github.hhhjbot.wcs.domain.EquipmentStatus;
import io.github.hhhjbot.wcs.domain.EquipmentTask;
import io.github.hhhjbot.wcs.domain.LocationCode;
import io.github.hhhjbot.wcs.domain.TaskNo;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 설비가 없는 자리에서 설비 노릇을 하는 게이트웨이.
 *
 * <p>명령을 받으면 슬롯을 하나 잡고, 읽을 때마다 한 단계씩 나아간다.
 *
 * <pre>
 *   send      →  슬롯 생성
 *   read 1회  →  ACK
 *   read 2회  →  RUNNING
 *   read 3회  →  DONE
 *   release   →  슬롯 해제 · 사이클 카운트 증가
 * </pre>
 *
 * <h3>상태 블록도 같이 만든다</h3>
 * {@link #readStatus(String)}는 실물 PLC의 STS 태그 블록을 흉내 낸다.
 * 위치는 명령의 출발·목적 주소에서 계산하고 진행률만큼 보간한다.
 *
 * <pre>
 *   X (주행)   열(bay) × 1200mm      통로를 따라 이동한 거리
 *   Y (승강)   단(level) × 1500mm    캐리지 높이
 * </pre>
 *
 * <p>랙이 아닌 자리(P&amp;D·인덕션·슈트)는 통로 끝이라 0으로 둔다.
 * 실물이라면 이 값이 엔코더에서 올라온다.
 *
 * <h3>실물과 다른 점</h3>
 * 실물 PLC는 <b>시간이 흐르면</b> 상태가 바뀌고, 읽는 행위는 아무것도 바꾸지 않는다.
 * 여기서는 {@link #read(TaskNo)}가 진행을 한 칸 민다. 시계를 따로 두지 않으려는 단순화이고,
 * 폴링 주기가 곧 설비의 진행 속도가 된다는 뜻이다.
 * {@link #readStatus(String)}는 진행을 밀지 않는다 — 화면이 여러 번 읽어도 흐름이 빨라지면 안 되므로.
 *
 * <p>실물 게이트웨이를 만들 때 이 방식을 따라가면 안 된다. 그쪽의 읽기는 태그를 읽기만 해야 한다.
 *
 * <h3>여러 스레드가 부른다</h3>
 * 폴러 스레드가 읽고 요청 스레드가 보낸다. 그래서 {@link ConcurrentHashMap}을 쓴다.
 * 실물 게이트웨이도 마찬가지로 스레드 안전해야 한다 — 소켓 하나를 여럿이 쓰게 되므로
 * 그쪽은 연결 풀이나 잠금이 필요해진다.
 */
public class SimulatedEquipmentGateway implements EquipmentGateway {

    /** 명령 하나가 완료까지 가는 데 필요한 읽기 횟수. */
    private static final int DEFAULT_READS_TO_DONE = 3;

    /** 열 하나의 간격. 실물 엔코더 값 자리. */
    private static final int BAY_PITCH_MM = 1200;

    /** 단 하나의 높이. */
    private static final int LEVEL_PITCH_MM = 1500;

    /** 설비 이상 시 올라오는 알람 코드. 실물이면 제조사 코드표를 따른다. */
    private static final int ALARM_FAULT = 8100;

    /** 이 화물번호를 실으면 설비가 이상을 낸다. 인터록·복구를 시연할 때 쓴다. */
    private final String faultLoadId;

    private final int readsToDone;

    /** 지금 명령이 걸려 있는 작업. 설비 코드로도 찾을 수 있어야 해서 값에 담아 둔다. */
    private final Map<TaskNo, Slot> slots = new ConcurrentHashMap<>();

    /** 설비별 누적 사이클. 기동 이후 완료한 명령 수. */
    private final Map<String, AtomicLong> cycles = new ConcurrentHashMap<>();

    public SimulatedEquipmentGateway() {
        this(DEFAULT_READS_TO_DONE, null);
    }

    /**
     * @param readsToDone 완료까지 필요한 읽기 횟수. 1이면 한 주기에 끝난다
     * @param faultLoadId 이 화물번호는 이상으로 처리한다. {@code null}이면 전부 정상
     */
    public SimulatedEquipmentGateway(int readsToDone, String faultLoadId) {
        if (readsToDone < 1) {
            throw new IllegalArgumentException("읽기 횟수는 1 이상이어야 합니다: " + readsToDone);
        }
        this.readsToDone = readsToDone;
        this.faultLoadId = faultLoadId;
    }

    // ------------------------------------------------------------------

    @Override
    public void send(EquipmentTask task) {
        Objects.requireNonNull(task, "작업은 필수입니다");

        // 실물이라면 여기서 CMD.FROM / CMD.TO / CMD.ID 를 쓰고 CMD.TRIGGER 를 올린다.
        boolean faulty = faultLoadId != null && faultLoadId.equals(task.getLoadId());
        Slot slot = new Slot(task.getEquipmentCode(), task.getFrom(), task.getTo(), faulty);

        if (slots.putIfAbsent(task.getTaskNo(), slot) != null) {
            throw new IllegalStateException(
                    "이미 명령이 걸려 있습니다: " + task.getTaskNo().value());
        }
    }

    @Override
    public Optional<EquipmentSignal> read(TaskNo taskNo) {
        Objects.requireNonNull(taskNo, "작업 번호는 필수입니다");

        Slot slot = slots.get(taskNo);
        if (slot == null) {
            return Optional.empty();   // 이 작업에 대한 명령이 걸려 있지 않다
        }
        if (slot.faulty) {
            return Optional.of(EquipmentSignal.FAULT);
        }

        int reads = slot.reads.incrementAndGet();
        if (reads >= readsToDone) {
            return Optional.of(EquipmentSignal.DONE);
        }
        if (reads >= readsToDone - 1) {
            return Optional.of(EquipmentSignal.RUNNING);
        }
        return Optional.of(EquipmentSignal.ACK);
    }

    /**
     * 상태 블록을 만들어 돌려준다.
     *
     * <p>진행을 밀지 않는다. 화면이 이 값을 자주 읽어도 설비가 빨라지면 안 된다.
     */
    @Override
    public EquipmentStatus readStatus(String equipmentCode) {
        Objects.requireNonNull(equipmentCode, "설비 코드는 필수입니다");

        long cycleCount = cycles.computeIfAbsent(equipmentCode, k -> new AtomicLong()).get();

        return slots.entrySet().stream()
                .filter(e -> e.getValue().equipmentCode.equals(equipmentCode))
                .findFirst()
                .map(e -> statusOf(equipmentCode, e.getKey(), e.getValue(), cycleCount))
                .orElseGet(() -> new EquipmentStatus(
                        equipmentCode,
                        EquipmentStatus.RunMode.AUTO,
                        EquipmentStatus.Motion.IDLE,
                        null, 0, 0, false, 0, cycleCount));
    }

    @Override
    public void release(TaskNo taskNo) {
        Objects.requireNonNull(taskNo, "작업 번호는 필수입니다");

        // 실물이라면 CMD.TRIGGER 를 내린다. 설비는 그걸 보고 STS.ACK 를 내린다.
        Slot removed = slots.remove(taskNo);
        if (removed != null && !removed.faulty) {
            cycles.computeIfAbsent(removed.equipmentCode, k -> new AtomicLong()).incrementAndGet();
        }
    }

    /** 지금 명령이 걸려 있는 작업 수. 화면과 로그에서 확인용. */
    public int busySlots() {
        return slots.size();
    }

    @Override
    public String toString() {
        return "모의 설비 (완료까지 %d주기, 진행 중 %d건)".formatted(readsToDone, slots.size());
    }

    // ------------------------------------------------------------------

    private EquipmentStatus statusOf(String equipmentCode, TaskNo taskNo,
                                     Slot slot, long cycleCount) {
        if (slot.faulty) {
            return new EquipmentStatus(equipmentCode,
                    EquipmentStatus.RunMode.AUTO, EquipmentStatus.Motion.FAULT,
                    taskNo.value(), coordinate(slot.from, true), coordinate(slot.from, false),
                    true, ALARM_FAULT, cycleCount);
        }

        int reads = slot.reads.get();
        double progress = Math.min((double) reads / readsToDone, 1.0);

        int fromX = coordinate(slot.from, true);
        int fromY = coordinate(slot.from, false);
        int toX = coordinate(slot.to, true);
        int toY = coordinate(slot.to, false);

        return new EquipmentStatus(
                equipmentCode,
                EquipmentStatus.RunMode.AUTO,
                motionOf(reads),
                taskNo.value(),                                  // 에코백
                interpolate(fromX, toX, progress),
                interpolate(fromY, toY, progress),
                reads > 0,                                       // 명령을 받은 뒤부터 재하
                0,
                cycleCount);
    }

    /**
     * 읽은 횟수를 동작 상태로 옮긴다.
     *
     * <p>실물은 축마다 별개의 상태를 내지만, 여기서는 진행률을 구간으로 잘라 흉내 낸다.
     */
    private EquipmentStatus.Motion motionOf(int reads) {
        if (reads <= 0) {
            return EquipmentStatus.Motion.FORK_OUT;   // 명령을 받고 아직 안 읽힌 시점
        }
        if (reads >= readsToDone) {
            return EquipmentStatus.Motion.DONE;
        }
        return reads == 1 ? EquipmentStatus.Motion.TRAVELING : EquipmentStatus.Motion.HOISTING;
    }

    /**
     * 자리 주소에서 좌표를 뽑는다.
     *
     * <p>랙이면 열·단으로 계산하고, 그 밖의 자리는 통로 끝이라 0이다.
     * 실물이라면 엔코더에서 올라오는 값이다.
     */
    private static int coordinate(LocationCode location, boolean travel) {
        if (!location.isRack()) {
            return 0;
        }
        LocationCode.RackAddress rack = location.rack();
        return travel ? rack.bay() * BAY_PITCH_MM : rack.level() * LEVEL_PITCH_MM;
    }

    private static int interpolate(int from, int to, double progress) {
        return (int) Math.round(from + (to - from) * progress);
    }

    /** 설비 하나에 걸린 명령 하나. */
    private static final class Slot {
        private final String equipmentCode;
        private final LocationCode from;
        private final LocationCode to;
        private final boolean faulty;

        /** 몇 번 읽혔는지. 폴러와 화면이 각각 다른 스레드에서 본다. */
        private final AtomicInteger reads = new AtomicInteger();

        private Slot(String equipmentCode, LocationCode from, LocationCode to, boolean faulty) {
            this.equipmentCode = equipmentCode;
            this.from = from;
            this.to = to;
            this.faulty = faulty;
        }
    }
}
