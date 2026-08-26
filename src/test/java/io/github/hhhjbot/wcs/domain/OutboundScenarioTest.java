package io.github.hhhjbot.wcs.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 출고 지시가 랙에서 슈트까지 흘러가는 전체 흐름을 검증한다.
 *
 * <p>단위 테스트가 규칙 하나를 검증한다면 이 클래스는 규칙들이 이어졌을 때
 * 업무가 흐르는지를 검증한다. 조율은 {@link OutboundFlow}가 하므로
 * 여기서는 설비 응답만 흉내 내고 나머지는 본 코드를 그대로 거친다.
 *
 * <pre>
 *   flow.accept(order)    상위 시스템이 지시를 내렸다
 *   flow.dispatch()       WCS 판단 주기 한 번
 *   complete(task)        설비가 완료 신호를 올렸다
 * </pre>
 *
 * <h3>시나리오 목록</h3>
 * <pre>
 *   1. 지시 1건이 설비 셋을 거쳐 완료된다             구현됨
 *   2. 두 통로에서 나온 화물이 컨베이어에서 합류한다    구현됨
 *   3. 화물이 자리를 하나씩 옮겨 간다                 구현됨
 *   4. 리딩 실패는 리젝트 슈트로 배출                 설비 게이트웨이 이후
 *   5. 슈트 만재 시 재순환 후 재시도                  설비 게이트웨이 이후
 * </pre>
 */
class OutboundScenarioTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 8, 28, 16, 0);

    private WarehouseLayout layout;
    private TaskList tasks;
    private OutboundFlow flow;

    @BeforeEach
    void setUp() {
        layout = new WarehouseLayout(
                List.of(new Equipment("SC-A01", 1),    // 포크 1개
                        new Equipment("SC-A02", 1),
                        new Equipment("CV-01", 8),     // 존 8개
                        new Equipment("SRT-01", 24)),  // 캐리어 24개
                LocationCode.of("IND-01"), "CV-01", "SRT-01");
        tasks = new TaskList();
        flow = new OutboundFlow(layout, new InMemoryOrderRepository(), tasks, EquipmentGateway.NOOP);
    }

    private static OutboundOrder order(String orderNo, String loadId,
                                       String source, String chute, LocalDateTime cutoff) {
        return new OutboundOrder(orderNo, loadId,
                LocationCode.of(source), LocationCode.of(chute), cutoff);
    }

    /**
     * 설비가 명령을 받아 완료 신호를 올리기까지.
     *
     * <p>실제로는 게이트웨이가 태그를 읽어 이 전이를 일으킨다.
     * 그 계층이 아직 없으므로 테스트가 대신한다.
     */
    private static void complete(EquipmentTask task) {
        task.transitionTo(TaskStatus.ACKED);       // STS.ACK
        task.transitionTo(TaskStatus.EXECUTING);   // STS.STATE = RUNNING
        task.transitionTo(TaskStatus.COMPLETED);   // STS.STATE = DONE
    }

    /** 모든 작업이 끝났는지. */
    private boolean allDone() {
        return tasks.all().stream().allMatch(task -> task.getStatus() == TaskStatus.COMPLETED);
    }

    /** 한 주기 돌리고, 하달된 것을 모두 완료시킨다. */
    private List<EquipmentTask> cycle() {
        var dispatched = flow.dispatch().dispatched();
        dispatched.forEach(OutboundScenarioTest::complete);
        return dispatched;
    }

    @Nested
    @DisplayName("지시 한 건이 랙에서 슈트까지")
    class SingleOrder {

        @Test
        @DisplayName("세 주기에 걸쳐 설비 셋을 거친다")
        void passesThreeEquipments() {
            flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", CUTOFF));

            assertEquals("SC-A01", cycle().get(0).getEquipmentCode(), "1주기 · 크레인");
            assertEquals("CV-01", cycle().get(0).getEquipmentCode(), "2주기 · 컨베이어");
            assertEquals("SRT-01", cycle().get(0).getEquipmentCode(), "3주기 · 소터");

            assertEquals(3, tasks.byStatus(TaskStatus.COMPLETED).size());
        }

        @Test
        @DisplayName("화물이 자리를 하나씩 옮겨 간다")
        void movesFromStationToStation() {
            flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", CUTOFF));

            var pnd = LocationCode.of("PND-A01");
            var induction = LocationCode.of("IND-01");
            var chute = LocationCode.of("CHUTE-3");

            cycle();   // 크레인이 P&D에 내려놓았다
            assertEquals(1, tasks.occupancyOf(pnd));
            assertEquals(0, tasks.occupancyOf(induction));

            cycle();   // 컨베이어가 가져가 인덕션에 올렸다
            assertEquals(0, tasks.occupancyOf(pnd), "가져갔으므로 P&D가 비었다");
            assertEquals(1, tasks.occupancyOf(induction));

            cycle();   // 소터가 슈트로 배출했다
            assertEquals(0, tasks.occupancyOf(induction));
            assertEquals(1, tasks.occupancyOf(chute), "슈트는 치우기 전까지 쌓인다");
        }

        @Test
        @DisplayName("앞 구간의 목적지가 다음 구간의 출발지가 된다")
        void handoverPointsAreChained() {
            var created = flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", CUTOFF));

            assertEquals(created.get(0).getTo(), created.get(1).getFrom(), "P&D에서 인수인계");
            assertEquals(created.get(1).getTo(), created.get(2).getFrom(), "인덕션에서 소터로");
            assertEquals(LocationCode.of("CHUTE-3"), created.get(2).getTo());
        }

        @Test
        @DisplayName("화물 번호는 설비를 옮겨 다녀도 바뀌지 않는다")
        void loadIdSurvivesEveryMove() {
            var created = flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", CUTOFF));

            assertTrue(created.stream().allMatch(task -> task.getLoadId().equals("CS-9001")));
        }
    }

    @Nested
    @DisplayName("두 통로에서 나와 합류")
    class TwoAisles {

        @Test
        @DisplayName("통로가 다르면 크레인 두 대가 동시에 움직인다")
        void cranesRunInParallel() {
            flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", CUTOFF));
            flow.accept(order("TO-00002", "CS-9002", "A-02-05-01", "CHUTE-1", CUTOFF));

            var first = cycle();

            assertEquals(2, first.size());
            assertEquals(List.of("SC-A01", "SC-A02"),
                    first.stream().map(EquipmentTask::getEquipmentCode).sorted().toList());
        }

        @Test
        @DisplayName("컨베이어는 정원이 8이지만 인덕션이 1이라 하나씩 태운다")
        void inductionIsTheBottleneck() {
            flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", CUTOFF));
            flow.accept(order("TO-00002", "CS-9002", "A-02-05-01", "CHUTE-1", CUTOFF));

            cycle();   // 크레인 두 대가 각자 P&D에 내려놓았다

            var second = flow.dispatch();

            assertEquals(1, second.dispatchedCount(), "컨베이어는 여유가 있지만");
            assertEquals(1, second.blockedCount());
            assertTrue(second.blocked().get(0).getReason().contains("IND-01"),
                    "막는 것은 컨베이어가 아니라 인덕션이다: " + second.blocked().get(0).getReason());
        }

        @Test
        @DisplayName("차례를 기다렸다가 각자의 슈트로 배출된다")
        void dischargesToOwnChutes() {
            flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", CUTOFF));
            flow.accept(order("TO-00002", "CS-9002", "A-02-05-01", "CHUTE-1", CUTOFF));

            for (int cycle = 1; cycle <= 8 && !allDone(); cycle++) {
                cycle();
            }

            assertTrue(allDone(), "여덟 주기 안에 끝난다");
            assertEquals(1, tasks.occupancyOf(LocationCode.of("CHUTE-3")));
            assertEquals(1, tasks.occupancyOf(LocationCode.of("CHUTE-1")));
            assertEquals(6, tasks.byStatus(TaskStatus.COMPLETED).size());
        }
    }

    @Nested
    @DisplayName("설비마다 동시 처리 수가 다르다")
    class EquipmentCapacity {

        @Test
        @DisplayName("크레인은 한 건, 컨베이어는 여러 건")
        void craneHoldsOneConveyorHoldsMany() {
            var crane = layout.equipment("SC-A01");
            var conveyor = layout.equipment("CV-01");

            assertFalse(crane.canAccept(1), "포크가 하나다");
            assertTrue(conveyor.canAccept(1), "존마다 화물이 올라간다");
            assertTrue(conveyor.canAccept(7));
            assertFalse(conveyor.canAccept(8));
        }

        @Test
        @DisplayName("같은 통로의 두 번째 지시는 크레인이 빌 때까지 기다린다")
        void secondOrderWaitsForCrane() {
            flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", CUTOFF));
            flow.accept(order("TO-00002", "CS-9002", "A-01-05-01", "CHUTE-3", CUTOFF.plusHours(1)));

            var result = flow.dispatch();

            assertEquals(1, result.dispatchedCount());
            assertEquals(1, result.blockedCount());
            assertTrue(result.blocked().get(0).getReason().contains("EQP_BUSY"));
        }
    }
}
