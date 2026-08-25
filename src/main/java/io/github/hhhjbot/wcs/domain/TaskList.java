package io.github.hhhjbot.wcs.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 지금까지 만들어진 설비 작업 전체.
 *
 * <p>설비가 작업을 더 받을 수 있는지 판단하려면 그 설비에 몇 건이 올라가 있는지를 알아야 한다.
 * 그 수를 설비에 저장하지 않고 이 목록에서 센다. (ADR-0009)
 *
 * <pre>
 *                SC-A01      CV-01       SRT-01
 *              ┌─────────┬───────────┬──────────┐
 *   TO-00001   │ 완료    │ 진행 중   │ 대기     │
 *   TO-00002   │ 진행 중 │ 대기      │ 대기     │
 *              └─────────┴───────────┴──────────┘
 *                    ↑          ↑
 *                 이 열을 세는 것이 이 클래스의 일이다
 * </pre>
 *
 * <p>작업 상태가 바뀌면 계수 결과도 함께 바뀐다. 증감 연산이 없으므로
 * 상태와 계수가 어긋날 수 없다.
 *
 * <p>3단계에서 저장소로 바뀔 자리다. 그때 이름이 {@code TaskRepository}가 될 수 있으나,
 * 지금은 메모리 위의 목록이라 그대로 부른다.
 */
public final class TaskList {

    private final List<EquipmentTask> tasks = new ArrayList<>();

    /**
     * 작업을 목록에 넣는다.
     *
     * @throws IllegalArgumentException 같은 작업 번호가 이미 있을 때
     */
    public void add(EquipmentTask task) {
        Objects.requireNonNull(task, "작업은 필수입니다");

        if (find(task.getTaskNo()).isPresent()) {
            throw new IllegalArgumentException("이미 등록된 작업 번호입니다: " + task.getTaskNo().value());
        }
        tasks.add(task);
    }

    /** 작업 번호로 찾는다. */
    public Optional<EquipmentTask> find(TaskNo taskNo) {
        Objects.requireNonNull(taskNo, "작업 번호는 필수입니다");
        return tasks.stream()
                .filter(task -> task.getTaskNo().equals(taskNo))
                .findFirst();
    }

    /**
     * 한 출고 지시에서 나온 작업 전체. 순번 순으로 돌려준다.
     *
     * <p>지시의 진행 상황을 보려면 세 건을 함께 봐야 한다.
     */
    public List<EquipmentTask> byOrder(String orderNo) {
        Objects.requireNonNull(orderNo, "지시번호는 필수입니다");
        return tasks.stream()
                .filter(task -> task.getOrderNo().equals(orderNo))
                .sorted(Comparator.comparing(EquipmentTask::getTaskNo))
                .toList();
    }

    /** 등록된 작업 전체. 순서는 등록 순이다. */
    public List<EquipmentTask> all() {
        return List.copyOf(tasks);
    }

    /** 특정 설비의 작업 전체. 완료된 것도 포함한다. */
    public List<EquipmentTask> byEquipment(String equipmentCode) {
        Objects.requireNonNull(equipmentCode, "설비 코드는 필수입니다");
        return tasks.stream()
                .filter(task -> task.getEquipmentCode().equals(equipmentCode))
                .toList();
    }

    /** 등록된 작업 수. */
    public int size() {
        return tasks.size();
    }

    // ------------------------------------------------------------------
    // 설비 가용 판정
    // ------------------------------------------------------------------

    /**
     * 이 설비에 지금 올라가 있는 작업 수.
     *
     * <p>하달되어 아직 끝나지 않은 것만 센다. 대기 중이거나 완료된 작업은 설비 위에 없다.
     */
    public int inFlightCount(String equipmentCode) {
        Objects.requireNonNull(equipmentCode, "설비 코드는 필수입니다");
        return (int) tasks.stream()
                .filter(task -> task.getEquipmentCode().equals(equipmentCode))
                .filter(EquipmentTask::isInFlight)
                .count();
    }

    /**
     * 상태로 거른다.
     */
    public List<EquipmentTask> byStatus(TaskStatus status) {
        Objects.requireNonNull(status, "상태는 필수입니다");
        return tasks.stream()
                .filter(task -> task.getStatus() == status)
                .toList();
    }

    /**
     * 이 자리에 지금 화물이 있거나 곧 도착하는 건수.
     *
     * <p>설비 정원이 "설비가 붙들고 있는 수"라면, 이것은 "자리에 놓여 있는 수"다.
     * 둘은 다르다. 크레인이 P&amp;D에 내려놓고 작업이 끝나도 화물은 그 자리에 남아 있고,
     * 컨베이어가 가져가야 비워진다.
     *
     * <p>세는 대상은 둘이다.
     * <pre>
     *   진행 중이며 목적지가 이 자리   →  곧 도착한다
     *   완료됐고 목적지가 이 자리인데
     *   이 자리에서 출발하는 다음 구간이 아직 완료되지 않음  →  아직 놓여 있다
     * </pre>
     *
     * <p>슈트처럼 다음 구간이 없는 자리는 완료된 것이 계속 쌓인 것으로 센다.
     * 사람이 치우거나 차량에 실어야 줄어들기 때문이다.
     */
    public int occupancyOf(LocationCode station) {
        Objects.requireNonNull(station, "자리는 필수입니다");
        return (int) tasks.stream()
                .filter(task -> task.getTo().equals(station))
                .filter(task -> task.isInFlight() || stillThere(task))
                .count();
    }

    /** 도착은 끝났는데 아직 떠나지 않았는지. */
    private boolean stillThere(EquipmentTask arrival) {
        if (arrival.getStatus() != TaskStatus.COMPLETED) {
            return false;
        }
        return tasks.stream()
                .filter(task -> task.getTaskNo().sameOrder(arrival.getTaskNo()))
                .filter(task -> task.getFrom().equals(arrival.getTo()))
                .noneMatch(task -> task.getStatus() == TaskStatus.COMPLETED);
    }

    /** 이 설비가 작업을 더 받을 수 있는지. */
    public boolean canAccept(Equipment equipment) {
        Objects.requireNonNull(equipment, "설비는 필수입니다");
        return equipment.canAccept(inFlightCount(equipment.code()));
    }

    /** 이 설비가 지금 추가로 받을 수 있는 작업 수. */
    public int availableSlots(Equipment equipment) {
        Objects.requireNonNull(equipment, "설비는 필수입니다");
        return equipment.availableSlots(inFlightCount(equipment.code()));
    }

    @Override
    public String toString() {
        return "작업 %d건".formatted(tasks.size());
    }
}
