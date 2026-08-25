import io.github.hhhjbot.wcs.domain.Equipment;
import io.github.hhhjbot.wcs.domain.EquipmentTask;
import io.github.hhhjbot.wcs.domain.LocationCode;
import io.github.hhhjbot.wcs.domain.OutboundOrder;
import io.github.hhhjbot.wcs.domain.TaskStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 출고 지시 하나가 랙에서 슈트까지 흘러가는 과정을 콘솔에 출력한다.
 *
 * <p>검증은 {@code OutboundScenarioTest}가 담당하고, 이 파일은 흐름을 눈으로 보기 위한 용도다.
 * 빌드 도구 없이 표준 JDK만으로 실행된다.
 *
 * <pre>
 *   javac -encoding UTF-8 -d out src\main\java\io\github\hhhjbot\wcs\domain\*.java
 *   javac -encoding UTF-8 -cp out -d out tools\ScenarioRun.java
 *   java -Dfile.encoding=UTF-8 -cp out ScenarioRun
 * </pre>
 */
public class ScenarioRun {

    // 설비 구성 — 3단계에서 application.yml 로 분리한다
    private static final Equipment CRANE    = new Equipment("SC-A01", 1);
    private static final Equipment CONVEYOR = new Equipment("CV-01", 8);
    private static final Equipment SORTER   = new Equipment("SRT-01", 24);

    private static final String PND       = "PND-A01";
    private static final String INDUCTION = "IND-01";

    /** 구간 하나. 어느 설비가 어디서 어디로 옮기는가. */
    private record Move(Equipment equipment, String from, String to) { }

    /** 지금까지 만들어진 모든 설비 작업. 진행 중 계수의 원천이다. */
    private static final List<EquipmentTask> ALL_TASKS = new ArrayList<>();

    public static void main(String[] args) {
        var order = new OutboundOrder(
                "TO-00001",
                "CS-9001",
                LocationCode.of("A-01-03-02"),
                LocationCode.of("CHUTE-3"),
                LocalDateTime.of(2026, 8, 23, 16, 0));

        title("WCS 출고 흐름");
        printOrder(order);

        var route = routeOf(order);
        printRoute(route);

        var done = run(order, route);
        printResult(order, done);

        title("설비 점유 — 같은 설비에 여러 건이 올라간 경우");
        printOccupancy(order, route);
    }

    // ------------------------------------------------------------------
    // 흐름
    // ------------------------------------------------------------------

    /**
     * 설비 경로. 담당 크레인은 출발 주소에서 도출하고, 순서는 조건문이 아니라 목록으로 둔다.
     * (ADR-0008 · ADR-0010)
     */
    private static List<Move> routeOf(OutboundOrder order) {
        return List.of(
                new Move(CRANE,    order.source().value(), PND),
                new Move(CONVEYOR, PND,                    INDUCTION),
                new Move(SORTER,   INDUCTION,              order.plannedChute().value())
        );
    }

    /** 경로를 순서대로 실행한다. 앞 작업이 완료된 뒤에 다음 작업을 만든다. */
    private static List<EquipmentTask> run(OutboundOrder order, List<Move> route) {
        title("실행");
        var done = new ArrayList<EquipmentTask>();

        for (int seq = 1; seq <= route.size(); seq++) {
            Move move = route.get(seq - 1);

            System.out.printf("  %s   %s%n", order.taskNo(seq), move.equipment().code());

            var task = new EquipmentTask(
                    order.taskNo(seq), move.equipment().code(), order.loadId(),
                    LocationCode.of(move.from()), LocationCode.of(move.to()));
            ALL_TASKS.add(task);

            step(task, TaskStatus.QUEUED,    "대기열 등록");
            step(task, TaskStatus.SENT,      "CMD 기록 후 트리거 상승");
            step(task, TaskStatus.ACKED,     "설비 수신확인");
            step(task, TaskStatus.EXECUTING, "설비 동작 중");
            step(task, TaskStatus.COMPLETED, "완료 신호 수신");

            done.add(task);
            System.out.println();
        }
        return done;
    }

    /** 상태를 한 단계 옮기고, 그 시점의 설비 점유 상황을 함께 보여준다. */
    private static void step(EquipmentTask task, TaskStatus next, String note) {
        TaskStatus before = task.getStatus();
        task.transitionTo(next);

        Equipment equipment = equipmentOf(task.getEquipmentCode());
        int inFlight = inFlightCount(task.getEquipmentCode());

        System.out.printf("     %-10s → %-10s  %-24s  점유 %d/%d%n",
                before, next, note, inFlight, equipment.capacity());
    }

    // ------------------------------------------------------------------
    // 출력
    // ------------------------------------------------------------------

    private static void printOrder(OutboundOrder order) {
        var rack = order.source().rack();

        System.out.println("  지시번호     " + order.orderNo());
        System.out.println("  화물         " + order.loadId());
        System.out.printf("  출발         %s   (존 %s · 통로 %d · 열 %d · 단 %d)%n",
                order.source().value(), rack.zone(), rack.aisle(), rack.bay(), rack.level());
        System.out.println("  계획 슈트    " + order.plannedChute().value());
        System.out.println("  컷오프       " + order.cutoff());
        System.out.println("  담당 크레인  " + order.craneCode() + "   ← 출발 주소에서 도출");
        System.out.println();
    }

    private static void printRoute(List<Move> route) {
        title("설비 경로");
        for (int seq = 1; seq <= route.size(); seq++) {
            Move move = route.get(seq - 1);
            System.out.printf("  %d   %-8s %-12s → %-10s  동시 처리 %d%n",
                    seq, move.equipment().code(), move.from(), move.to(), move.equipment().capacity());
        }
        System.out.println();
    }

    private static void printResult(OutboundOrder order, List<EquipmentTask> done) {
        title("결과");

        EquipmentTask last = done.get(done.size() - 1);
        var actualChute = last.getTo();

        System.out.println("  설비 작업    " + done.size() + "건 완료");
        System.out.println("  계획 슈트    " + order.plannedChute().value());
        System.out.println("  실제 슈트    " + actualChute.value()
                + (actualChute.equals(order.plannedChute()) ? "   (계획대로)" : "   (우회)"));
        System.out.println("  화물 번호    " + order.loadId() + "   설비를 옮겨 다녀도 바뀌지 않는다");
        System.out.println();
    }

    /** 크레인 1건 · 컨베이어 8건이라는 차이가 흐름에 어떻게 나타나는지 보여준다. */
    private static void printOccupancy(OutboundOrder first, List<Move> route) {
        // 다른 지시 다섯 건이 컨베이어 구간에 올라가 있는 상태를 만든다
        for (int i = 2; i <= 6; i++) {
            var other = new OutboundOrder("TO-0000" + i, "CS-900" + i,
                    LocationCode.of("A-01-03-0" + (i % 9 + 1)),
                    first.plannedChute(), first.cutoff());

            var task = new EquipmentTask(other.taskNo(2), CONVEYOR.code(), other.loadId(),
                    LocationCode.of(PND), LocationCode.of(INDUCTION));
            task.transitionTo(TaskStatus.QUEUED);
            task.transitionTo(TaskStatus.SENT);
            ALL_TASKS.add(task);
        }

        for (Equipment equipment : List.of(CRANE, CONVEYOR, SORTER)) {
            int inFlight = inFlightCount(equipment.code());
            System.out.printf("  %-8s  진행 중 %2d / 정원 %2d   %s%n",
                    equipment.code(), inFlight, equipment.capacity(),
                    equipment.canAccept(inFlight)
                            ? "여유 " + equipment.availableSlots(inFlight) + "건"
                            : "가득 참 — 대기열에서 기다린다");
        }

        System.out.println();
        System.out.println("  같은 진행 중 " + inFlightCount(CONVEYOR.code())
                + "건이라도 크레인 기준으로 판정했다면 이미 가득 찬 것으로 처리된다.");
        System.out.println();
    }

    // ------------------------------------------------------------------

    /** 해당 설비에 진행 중인 작업 수. 저장하지 않고 작업 목록에서 센다. (ADR-0009) */
    private static int inFlightCount(String equipmentCode) {
        return (int) ALL_TASKS.stream()
                .filter(t -> t.getEquipmentCode().equals(equipmentCode))
                .filter(EquipmentTask::isInFlight)
                .count();
    }

    private static Equipment equipmentOf(String code) {
        for (Equipment equipment : List.of(CRANE, CONVEYOR, SORTER)) {
            if (equipment.code().equals(code)) {
                return equipment;
            }
        }
        throw new IllegalArgumentException("등록되지 않은 설비입니다: " + code);
    }

    private static void title(String text) {
        System.out.println();
        System.out.println("── " + text + " " + "─".repeat(Math.max(0, 56 - text.length() * 2)));
        System.out.println();
    }
}
