import io.github.hhhjbot.wcs.domain.Equipment;
import io.github.hhhjbot.wcs.domain.EquipmentTask;
import io.github.hhhjbot.wcs.domain.LocationCode;
import io.github.hhhjbot.wcs.domain.InMemoryOrderRepository;
import io.github.hhhjbot.wcs.domain.OutboundFlow;
import io.github.hhhjbot.wcs.domain.OutboundOrder;
import io.github.hhhjbot.wcs.domain.Route;
import io.github.hhhjbot.wcs.domain.TaskList;
import io.github.hhhjbot.wcs.domain.TaskStatus;
import io.github.hhhjbot.wcs.domain.WarehouseLayout;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 출고 지시가 랙에서 슈트까지 흘러가는 과정을 콘솔에 출력한다.
 *
 * <p>검증은 테스트가 담당하고, 이 파일은 흐름을 눈으로 보기 위한 용도다.
 * 조율은 {@link OutboundFlow}가 그대로 하므로, 화면에 보이는 판단은 본 코드의 판단이다.
 *
 * <pre>
 *   gradlew classes
 *   javac -encoding UTF-8 -d out -cp build\classes\java\main tools\ScenarioRun.java
 *   java -Dfile.encoding=UTF-8 -cp "out;build\classes\java\main" ScenarioRun
 * </pre>
 */
public class ScenarioRun {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 8, 28, 16, 0);

    private static WarehouseLayout layout;
    private static TaskList tasks;
    private static OutboundFlow flow;

    public static void main(String[] args) {
        setUp();

        title("창고 구성");
        printLayout();

        title("출고 지시 접수");
        accept("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", CUTOFF);
        accept("TO-00002", "CS-9002", "A-01-05-01", "CHUTE-3", CUTOFF.plusHours(1));
        accept("TO-00003", "CS-9003", "A-02-04-02", "CHUTE-1", CUTOFF.minusHours(1));

        title("하달 주기");
        for (int cycle = 1; cycle <= 5; cycle++) {
            if (!runCycle(cycle)) {
                break;
            }
        }

        title("최종 상태");
        printTasks();
        printOccupancy();
    }

    // ------------------------------------------------------------------
    // 설비 구성 — 3단계에서 application.yml 로 옮긴다
    // ------------------------------------------------------------------

    private static void setUp() {
        layout = new WarehouseLayout(
                List.of(new Equipment("SC-A01", 1),    // 포크 1개
                        new Equipment("SC-A02", 1),
                        new Equipment("CV-01", 8),     // 존 8개
                        new Equipment("SRT-01", 24)),  // 캐리어 24개
                LocationCode.of("IND-01"), "CV-01", "SRT-01");
        tasks = new TaskList();
        flow = new OutboundFlow(layout, new InMemoryOrderRepository(), tasks);
    }

    // ------------------------------------------------------------------
    // 한 주기
    // ------------------------------------------------------------------

    /**
     * 판단 주기 한 번. 하달된 작업은 설비가 완료했다고 가정한다.
     *
     * @return 더 돌릴 것이 남았는지
     */
    private static boolean runCycle(int cycle) {
        OutboundFlow.DispatchResult result = flow.dispatch();

        System.out.printf("%n[%d주기]  %s%n", cycle, result);

        for (EquipmentTask task : result.dispatched()) {
            System.out.printf("  하달  %-12s %-7s %-11s → %s%n",
                    task.getTaskNo(), task.getEquipmentCode(),
                    task.getFrom(), task.getTo());
        }
        for (EquipmentTask task : result.blocked()) {
            System.out.printf("  대기  %-12s %-7s %s%n",
                    task.getTaskNo(), task.getEquipmentCode(), task.getReason());
        }

        // 설비 응답. 실제로는 게이트웨이가 STS 태그를 읽어 이 전이를 일으킨다
        for (EquipmentTask task : result.dispatched()) {
            task.transitionTo(TaskStatus.ACKED);
            task.transitionTo(TaskStatus.EXECUTING);
            task.transitionTo(TaskStatus.COMPLETED);
        }

        boolean remaining = tasks.all().stream()
                .anyMatch(task -> task.getStatus() != TaskStatus.COMPLETED);
        if (!remaining) {
            System.out.println("\n  남은 작업 없음");
        }
        return remaining;
    }

    // ------------------------------------------------------------------
    // 출력
    // ------------------------------------------------------------------

    private static void accept(String orderNo, String loadId,
                               String source, String chute, LocalDateTime cutoff) {
        var order = new OutboundOrder(orderNo, loadId,
                LocationCode.of(source), LocationCode.of(chute), cutoff);
        List<EquipmentTask> created = flow.accept(order);

        System.out.printf("%n  %s  %s  %s → %s  컷오프 %s%n",
                order.orderNo(), order.loadId(),
                order.source(), order.plannedChute(), order.cutoff().toLocalTime());

        Route route = layout.routeFor(order.source(), order.plannedChute());
        for (int seq = 1; seq <= route.size(); seq++) {
            Route.Move move = route.at(seq);
            System.out.printf("    %-12s %-7s %-11s → %s%n",
                    created.get(seq - 1).getTaskNo(), move.equipment().code(),
                    move.from(), move.to());
        }
    }

    private static void printLayout() {
        for (Equipment equipment : layout.equipments()) {
            System.out.printf("  %-8s 동시 처리 %d%n", equipment.code(), equipment.capacity());
        }
    }

    private static void printTasks() {
        System.out.printf("%n  %-12s %-7s %-11s %-11s %-10s %s%n",
                "작업", "설비", "출발", "도착", "상태", "사유");
        for (EquipmentTask task : tasks.all()) {
            System.out.printf("  %-12s %-7s %-11s %-11s %-10s %s%n",
                    task.getTaskNo(), task.getEquipmentCode(),
                    task.getFrom(), task.getTo(), task.getStatus(),
                    task.getReason() == null ? "" : task.getReason());
        }
    }

    private static void printOccupancy() {
        System.out.printf("%n  자리 점유%n");
        for (String code : List.of("PND-A01", "PND-A02", "IND-01", "CHUTE-1", "CHUTE-3")) {
            LocationCode station = LocationCode.of(code);
            System.out.printf("    %-9s %d / %d%n",
                    code, tasks.occupancyOf(station), layout.stationCapacity(station));
        }
    }

    private static void title(String text) {
        System.out.printf("%n%n== %s ==%n", text);
    }
}
