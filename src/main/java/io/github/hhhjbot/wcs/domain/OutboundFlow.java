package io.github.hhhjbot.wcs.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 출고 지시를 설비 작업으로 펼치고, 조건이 갖춰진 것을 하달한다.
 *
 * <p>보관소가 "무엇이 있는가"에 답한다면 이 클래스는 "무엇을 할 것인가"를 정한다.
 * 하는 일은 둘이다.
 *
 * <pre>
 *   accept(order)   지시 → 경로 → 설비 작업 n건 생성
 *   dispatch()      대기 중인 작업 중 조건이 갖춰진 것을 하달
 * </pre>
 *
 * <h3>상태를 갖지 않는다</h3>
 * 진행 상황은 이미 {@link EquipmentTask}가 갖고 있다. 여기서 또 들고 있으면
 * 같은 사실이 두 곳에 적히고, 어긋났을 때 어느 쪽이 맞는지 판단할 근거가 없다.
 * (ADR-0009와 같은 이유)
 *
 * <h3>하달 조건</h3>
 * 조건에 걸리면 {@link TaskStatus#BLOCKED}로 두고 사유를 남긴다.
 * 실패가 아니라 대기이므로 조건이 풀리면 다시 시도한다.
 *
 * <pre>
 *   EQP_BUSY        설비 정원이 참
 *   DEST_OCCUPIED   목적지 자리가 차 있음
 * </pre>
 *
 * <p>앞 구간이 끝나지 않은 작업은 차단이 아니라 후보에서 빠진다.
 * 조건 위반이 아니라 아직 차례가 아닌 것이기 때문이다.
 *
 * <h3>주기 전체를 잠근다</h3>
 * {@link #accept(OutboundOrder)}와 {@link #dispatch()}는 여러 스레드에서 불린다.
 * 요청 스레드가 지시를 받는 사이 스케줄러 스레드가 하달 주기를 돌리는 식이다.
 *
 * <p>잠가야 하는 단위는 메서드 하나가 아니라 <b>판단 한 덩어리</b>다.
 *
 * <pre>
 *   int inFlight = tasks.inFlightCount(code);   // ① 센다
 *   if (equipment.canAccept(inFlight)) {         // ② 판단한다
 *       task.transitionTo(SENT);                 // ③ 쓴다
 *   }
 * </pre>
 *
 * <p>①②③이 각각 안전해도 그 <b>사이</b>가 안 잠기면 소용없다. 두 스레드가 ①에서
 * 나란히 0을 읽으면 둘 다 ②를 통과해 정원 1짜리 크레인에 두 건이 나간다.
 * 이를 check-then-act 이라 부른다.
 *
 * <p>실제로 잠그기 전에 3000판을 돌려보면 1721판에서 어긋났다. 다만 대부분은
 * 정원 초과가 아니라 <b>두 스레드가 같은 작업을 집는</b> 형태로 터졌다.
 * 앞선 스레드가 이미 옮겨 놓은 상태를 뒤 스레드가 또 바꾸려다
 * {@link TaskStatus#canTransitionTo(TaskStatus)}에 걸린 것이다.
 * 상태 기계가 최악을 우연히 막고 있었던 셈인데, 예외가 스케줄러 스레드로 튀면
 * 그 주기가 통째로 죽으므로 막아준다고 괜찮은 것은 아니다.
 * 검증은 {@code OutboundFlowConcurrencyTest}가 한다.
 *
 * <h3>잠금 순서</h3>
 * <pre>
 *   OutboundFlow → TaskList → EquipmentTask
 * </pre>
 * 항상 이 방향이고 거꾸로 잡는 경로가 없다. 그래서 교착이 생기지 않는다.
 *
 * <p>대신 하달 주기가 도는 동안 지시 접수가 잠깐 기다린다. 주기가 밀리초 단위이므로
 * 지금 규모에서는 문제가 되지 않는다. 설비가 수백 대로 늘어 주기가 길어지면
 * 설비별로 잠금을 쪼개야 하는데, 그때는 잠금 순서를 다시 설계해야 한다.
 */
public final class OutboundFlow {

    /** 차단·실패 후 다시 시도할 수 있는 횟수. 넘기면 사람이 봐야 한다. */
    public static final int DEFAULT_MAX_RETRY = 3;

    private final WarehouseLayout layout;
    private final OrderRepository orders;
    private final TaskList tasks;
    private final int maxRetry;

    public OutboundFlow(WarehouseLayout layout, OrderRepository orders, TaskList tasks) {
        this(layout, orders, tasks, DEFAULT_MAX_RETRY);
    }

    public OutboundFlow(WarehouseLayout layout, OrderRepository orders, TaskList tasks, int maxRetry) {
        this.layout = Objects.requireNonNull(layout, "창고 구성은 필수입니다");
        this.orders = Objects.requireNonNull(orders, "지시 목록은 필수입니다");
        this.tasks = Objects.requireNonNull(tasks, "작업 목록은 필수입니다");
        if (maxRetry < 0) {
            throw new IllegalArgumentException("재시도 한도는 0 이상이어야 합니다: " + maxRetry);
        }
        this.maxRetry = maxRetry;
    }

    // ------------------------------------------------------------------
    // 지시를 받는다
    // ------------------------------------------------------------------

    /**
     * 지시를 받아 설비 작업으로 펼친다.
     *
     * <p>작업은 한꺼번에 만들어지고 모두 {@link TaskStatus#CREATED}로 시작한다.
     * 뒤 구간은 앞 구간이 끝나야 하달되므로, 만들어 두어도 설비에 내려가지 않는다.
     * 미리 만들어 두면 지시 하나의 진행 상황이 목록에 그대로 보인다.
     *
     * @return 만들어진 작업. 구간 수만큼이다
     */
    public synchronized List<EquipmentTask> accept(OutboundOrder order) {
        Objects.requireNonNull(order, "출고 지시는 필수입니다");

        orders.add(order);
        Route route = layout.routeFor(order.source(), order.plannedChute());

        var created = new ArrayList<EquipmentTask>();
        for (int seq = 1; seq <= route.size(); seq++) {
            var task = EquipmentTask.of(order, seq, route.at(seq));
            tasks.add(task);
            created.add(task);
        }
        return List.copyOf(created);
    }

    // ------------------------------------------------------------------
    // 하달한다
    // ------------------------------------------------------------------

    /**
     * 한 번의 판단 주기.
     *
     * <p>차단됐던 작업을 다시 후보에 올리고, 컷오프가 이른 순으로 검사해
     * 조건이 갖춰진 것을 하달한다.
     *
     * <p>순서대로 평가하는 것이 중요하다. 앞의 작업이 하달되면 설비 정원이 줄어들고,
     * 그 결과가 뒤 작업의 판단에 반영되어야 한다. 매번 목록에서 다시 세므로 자동으로 반영된다.
     */
    public synchronized DispatchResult dispatch() {
        retryBlocked();

        var dispatched = new ArrayList<EquipmentTask>();
        var blocked = new ArrayList<EquipmentTask>();

        for (EquipmentTask task : candidates()) {
            Optional<String> reason = blockReason(task);
            if (reason.isPresent()) {
                task.block(reason.get());
                blocked.add(task);
            } else {
                task.transitionTo(TaskStatus.QUEUED);
                task.transitionTo(TaskStatus.SENT);   // 설비에 CMD 기록 후 트리거 상승
                dispatched.add(task);
            }
        }
        return new DispatchResult(List.copyOf(dispatched), List.copyOf(blocked));
    }

    /**
     * 차단됐던 작업을 다시 후보로 되돌린다.
     *
     * <p>한도를 넘긴 것은 되돌리지 않는다. 계속 재시도하면 목록에 남아
     * 매 주기 검사 비용만 들고, 정작 사람이 조치해야 할 건이 묻힌다.
     */
    private void retryBlocked() {
        for (EquipmentTask task : tasks.byStatus(TaskStatus.BLOCKED)) {
            if (task.getRetryCount() < maxRetry) {
                task.transitionTo(TaskStatus.CREATED);
            }
        }
        // FAILED 는 되돌리지 않는다. 설비 이상이나 시한 초과는 조건이 저절로 풀리지 않으므로
        // 사람이 확인하고 다시 올려야 한다.
    }

    /**
     * 하달을 기다리는 작업. 컷오프가 이른 순, 같으면 순번 순.
     *
     * <p>앞 구간이 끝나지 않은 작업은 여기서 걸러낸다. 차단으로 기록하지 않는 이유는,
     * 그것이 조건 위반이 아니라 <b>아직 차례가 아닌 것</b>이기 때문이다.
     * 차단으로 남기면 재시도 횟수만 쌓이고 정작 사람이 봐야 할 건이 묻힌다.
     */
    private List<EquipmentTask> candidates() {
        return tasks.byStatus(TaskStatus.CREATED).stream()
                .filter(this::previousDone)
                .sorted(Comparator
                        .comparing((EquipmentTask task) -> cutoffOf(task))
                        .thenComparing(EquipmentTask::getTaskNo))
                .toList();
    }

    /** 그 작업이 속한 지시의 컷오프. 지시를 못 찾으면 가장 나중으로 민다. */
    private java.time.LocalDateTime cutoffOf(EquipmentTask task) {
        return orders.findByTask(task.getTaskNo())
                .map(OutboundOrder::cutoff)
                .orElse(java.time.LocalDateTime.MAX);
    }

    // ------------------------------------------------------------------
    // 인터록
    // ------------------------------------------------------------------

    /**
     * 지금 하달하면 안 되는 이유. 없으면 비어 있다.
     *
     * <p>설비가 받을 수 있는지와 목적지에 놓을 자리가 있는지를 본다.
     * 둘은 다르다. 크레인 작업이 끝나도 화물은 P&amp;D에 남아 있으므로,
     * 설비는 비었는데 자리는 차 있는 상태가 있다.
     */
    private Optional<String> blockReason(EquipmentTask task) {
        Equipment equipment = layout.equipment(task.getEquipmentCode());
        int inFlight = tasks.inFlightCount(equipment.code());
        if (!equipment.canAccept(inFlight)) {
            return Optional.of("EQP_BUSY — %s 정원 %d 중 %d 진행 중"
                    .formatted(equipment.code(), equipment.capacity(), inFlight));
        }

        LocationCode destination = task.getTo();
        int occupancy = tasks.occupancyOf(destination);
        int capacity = layout.stationCapacity(destination);
        if (occupancy >= capacity) {
            return Optional.of("DEST_OCCUPIED — %s 정원 %d 중 %d 점유"
                    .formatted(destination.value(), capacity, occupancy));
        }

        return Optional.empty();
    }

    /**
     * 앞 구간이 끝났는지.
     *
     * <p>첫 구간은 앞이 없으므로 항상 통과한다. 크레인이 P&amp;D에 내려놓기 전에는
     * 컨베이어가 가져갈 물건이 없다.
     */
    private boolean previousDone(EquipmentTask task) {
        int seq = task.getTaskNo().seq();
        if (seq == 1) {
            return true;
        }
        TaskNo previous = TaskNo.of(task.getTaskNo().orderNo(), seq - 1);
        return tasks.find(previous)
                .map(prev -> prev.getStatus() == TaskStatus.COMPLETED)
                .orElse(false);
    }

    // ------------------------------------------------------------------

    /**
     * 한 주기의 결과.
     *
     * @param dispatched 하달된 작업
     * @param blocked    조건이 갖춰지지 않아 대기시킨 작업
     */
    public record DispatchResult(List<EquipmentTask> dispatched, List<EquipmentTask> blocked) {

        public int dispatchedCount() { return dispatched.size(); }

        public int blockedCount() { return blocked.size(); }

        @Override
        public String toString() {
            return "하달 %d건 · 대기 %d건".formatted(dispatched.size(), blocked.size());
        }
    }
}
