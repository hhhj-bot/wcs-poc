package io.github.hhhjbot.wcs.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

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
 *
 * <h3>여러 스레드가 동시에 본다</h3>
 * 이 목록을 만지는 스레드는 하나가 아니다.
 *
 * <pre>
 *   Tomcat 요청 스레드    POST /api/orders, POST /api/dispatch
 *   스케줄러 스레드       설비 폴링 주기
 * </pre>
 *
 * <p>그래서 {@link CopyOnWriteArrayList}를 쓴다. 읽을 때 잠그지 않고 그 순간의 사본을
 * 훑으므로, 다른 스레드가 작업을 추가해도 순회 도중 {@code ConcurrentModificationException}이
 * 나지 않는다. 쓸 때마다 배열을 통째로 복사하는 대가를 치르지만, 작업은 수십 건 규모이고
 * 추가는 지시를 받을 때만 일어나는 반면 조회는 주기마다 수차례 일어나므로 이쪽이 맞다.
 *
 * <h3>여기까지만 책임진다</h3>
 * 이 클래스가 지키는 것은 <b>목록의 구조</b>뿐이다. 다음 둘은 여기서 못 지킨다.
 *
 * <pre>
 *   작업 한 건의 필드 일관성   →  EquipmentTask 가 자기 잠금으로 지킨다
 *   "세고 → 판단하고 → 쓰기"   →  OutboundFlow 가 주기 전체를 잠근다
 * </pre>
 *
 * <p>{@link #inFlightCount(String)}이 안전해도, 그 값을 받아 판단하고 쓰는 사이에
 * 다른 스레드가 끼어들면 결과는 어긋난다. 자료구조를 바꾸는 것으로는 그 문제가 풀리지 않는다.
 */
public final class TaskList {

    private final List<EquipmentTask> tasks = new CopyOnWriteArrayList<>();

    /**
     * 작업을 목록에 넣는다.
     *
     * <p>중복 검사와 추가 사이에 다른 스레드가 같은 번호를 넣지 못하도록 잠근다.
     * {@code CopyOnWriteArrayList} 는 추가 자체는 안전하게 해 주지만
     * "없는지 보고 → 넣기" 두 단계가 하나로 묶이지는 않는다.
     *
     * @throws IllegalArgumentException 같은 작업 번호가 이미 있을 때
     */
    public synchronized void add(EquipmentTask task) {
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

    /**
     * 목록을 비운다.
     *
     * <p>업무 흐름에는 이런 순간이 없다. 시연과 시험에서 처음 상태로 돌리기 위한 것이고,
     * 운영이라면 조작 권한 뒤에 두거나 아예 두지 않는다.
     */
    public synchronized void clear() {
        tasks.clear();
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
     * 하달되어 아직 끝나지 않은 작업 전체.
     *
     * <p>설비 응답을 읽어야 할 대상이다. 폴러가 매 주기 이 목록만 훑는다.
     */
    public List<EquipmentTask> inFlight() {
        return tasks.stream()
                .filter(EquipmentTask::isInFlight)
                .sorted(Comparator.comparing(EquipmentTask::getTaskNo))
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
