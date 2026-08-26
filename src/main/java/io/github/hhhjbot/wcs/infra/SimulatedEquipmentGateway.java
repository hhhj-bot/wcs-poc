package io.github.hhhjbot.wcs.infra;

import io.github.hhhjbot.wcs.domain.EquipmentGateway;
import io.github.hhhjbot.wcs.domain.EquipmentSignal;
import io.github.hhhjbot.wcs.domain.EquipmentTask;
import io.github.hhhjbot.wcs.domain.TaskNo;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
 *   release   →  슬롯 해제
 * </pre>
 *
 * <h3>실물과 다른 점</h3>
 * 실물 PLC는 <b>시간이 흐르면</b> 상태가 바뀌고, 읽는 행위는 아무것도 바꾸지 않는다.
 * 여기서는 읽을 때마다 진행시킨다. 시계를 따로 두지 않으려는 단순화이고,
 * 폴링 주기가 곧 설비의 진행 속도가 된다는 뜻이다.
 *
 * <p>실물 게이트웨이를 만들 때 이 방식을 따라가면 안 된다. 그쪽의 {@code read}는
 * 태그를 읽기만 해야 한다.
 *
 * <h3>여러 스레드가 부른다</h3>
 * 폴러 스레드가 읽고 요청 스레드가 보낸다. 그래서 {@link ConcurrentHashMap}을 쓴다.
 * 실물 게이트웨이도 마찬가지로 스레드 안전해야 한다 — 소켓 하나를 여럿이 쓰게 되므로
 * 그쪽은 연결 풀이나 잠금이 필요해진다.
 */
public class SimulatedEquipmentGateway implements EquipmentGateway {

    /** 명령 하나가 완료까지 가는 데 필요한 읽기 횟수. */
    private static final int DEFAULT_READS_TO_DONE = 3;

    /** 이 화물번호를 실으면 설비가 이상을 낸다. 인터록·복구를 시연할 때 쓴다. */
    private final String faultLoadId;

    private final int readsToDone;
    private final Map<TaskNo, AtomicInteger> slots = new ConcurrentHashMap<>();
    private final Map<TaskNo, Boolean> faulty = new ConcurrentHashMap<>();

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
        if (slots.putIfAbsent(task.getTaskNo(), new AtomicInteger()) != null) {
            throw new IllegalStateException(
                    "이미 명령이 걸려 있습니다: " + task.getTaskNo().value());
        }
        if (faultLoadId != null && faultLoadId.equals(task.getLoadId())) {
            faulty.put(task.getTaskNo(), Boolean.TRUE);
        }
    }

    @Override
    public Optional<EquipmentSignal> read(TaskNo taskNo) {
        Objects.requireNonNull(taskNo, "작업 번호는 필수입니다");

        AtomicInteger slot = slots.get(taskNo);
        if (slot == null) {
            return Optional.empty();   // 이 작업에 대한 명령이 걸려 있지 않다
        }
        if (faulty.containsKey(taskNo)) {
            return Optional.of(EquipmentSignal.FAULT);
        }

        int reads = slot.incrementAndGet();
        if (reads >= readsToDone) {
            return Optional.of(EquipmentSignal.DONE);
        }
        if (reads >= readsToDone - 1) {
            return Optional.of(EquipmentSignal.RUNNING);
        }
        return Optional.of(EquipmentSignal.ACK);
    }

    @Override
    public void release(TaskNo taskNo) {
        Objects.requireNonNull(taskNo, "작업 번호는 필수입니다");
        // 실물이라면 CMD.TRIGGER 를 내린다. 설비는 그걸 보고 STS.ACK 를 내린다.
        slots.remove(taskNo);
        faulty.remove(taskNo);
    }

    /** 지금 명령이 걸려 있는 작업 수. 화면과 로그에서 확인용. */
    public int busySlots() {
        return slots.size();
    }

    @Override
    public String toString() {
        return "모의 설비 (완료까지 %d주기, 진행 중 %d건)".formatted(readsToDone, slots.size());
    }
}
