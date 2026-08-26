package io.github.hhhjbot.wcs.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 지시를 작업으로 펼치고 조건이 갖춰진 것만 하달하는지 검증.
 *
 * <p>여기서 확인하려는 것은 셋이다. 지시 하나가 구간 수만큼 나뉘는지,
 * 앞 구간이 끝나야 다음이 나가는지, 그리고 설비와 자리가 차 있으면 대기하는지.
 */
class OutboundFlowTest {

    private static final LocalDateTime EARLY = LocalDateTime.of(2026, 8, 28, 15, 0);
    private static final LocalDateTime LATE = LocalDateTime.of(2026, 8, 28, 18, 0);

    private WarehouseLayout layout;
    private OrderRepository orders;
    private TaskList tasks;
    private OutboundFlow flow;

    @BeforeEach
    void setUp() {
        layout = new WarehouseLayout(
                List.of(new Equipment("SC-A01", 1),
                        new Equipment("SC-A02", 1),
                        new Equipment("CV-01", 8),
                        new Equipment("SRT-01", 24)),
                LocationCode.of("IND-01"), "CV-01", "SRT-01");
        orders = new InMemoryOrderRepository();
        tasks = new TaskList();
        flow = new OutboundFlow(layout, orders, tasks, EquipmentGateway.NOOP);
    }

    private OutboundOrder order(String orderNo, String loadId, String source,
                                String chute, LocalDateTime cutoff) {
        return new OutboundOrder(orderNo, loadId,
                LocationCode.of(source), LocationCode.of(chute), cutoff);
    }

    /** 하달된 작업을 완료까지 진행시킨다. 설비 한 대와의 핸드셰이크 한 번에 해당한다. */
    private static void complete(EquipmentTask task) {
        task.transitionTo(TaskStatus.ACKED);
        task.transitionTo(TaskStatus.EXECUTING);
        task.transitionTo(TaskStatus.COMPLETED);
    }

    @Nested
    @DisplayName("지시 접수")
    class Accepting {

        @Test
        @DisplayName("지시 하나가 구간 수만큼의 작업으로 나뉜다")
        void splitsIntoTasks() {
            var created = flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", EARLY));

            assertEquals(3, created.size());
            assertEquals(3, tasks.size());
            assertEquals(1, orders.size());
        }

        @Test
        @DisplayName("만들어진 작업은 모두 대기 상태로 시작한다")
        void allStartAsCreated() {
            flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", EARLY));

            assertEquals(3, tasks.byStatus(TaskStatus.CREATED).size());
        }

        @Test
        @DisplayName("앞 작업의 목적지가 다음 작업의 출발지가 된다")
        void chainsHandoverPoints() {
            var created = flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", EARLY));

            assertEquals(created.get(0).getTo(), created.get(1).getFrom());
            assertEquals(created.get(1).getTo(), created.get(2).getFrom());
            assertEquals(LocationCode.of("CHUTE-3"), created.get(2).getTo());
        }

        @Test
        @DisplayName("같은 지시번호는 두 번 받지 않는다")
        void rejectsDuplicateOrder() {
            flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", EARLY));

            assertThrows(IllegalArgumentException.class,
                    () -> flow.accept(order("TO-00001", "CS-9002", "A-02-01-01", "CHUTE-1", EARLY)));
        }
    }

    @Nested
    @DisplayName("순차 하달")
    class Sequencing {

        @Test
        @DisplayName("첫 주기에는 크레인 구간만 나간다")
        void dispatchesOnlyFirstLeg() {
            flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", EARLY));

            var result = flow.dispatch();

            assertEquals(1, result.dispatchedCount());
            assertEquals("SC-A01", result.dispatched().get(0).getEquipmentCode());
            assertEquals(0, result.blockedCount(), "차례가 아닌 것은 차단이 아니다");
        }

        @Test
        @DisplayName("앞 구간이 끝나야 다음 구간이 나간다")
        void waitsForPreviousLeg() {
            flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", EARLY));

            var crane = flow.dispatch().dispatched().get(0);
            assertEquals(0, flow.dispatch().dispatchedCount(), "크레인이 아직 진행 중이다");

            complete(crane);
            var second = flow.dispatch();

            assertEquals(1, second.dispatchedCount());
            assertEquals("CV-01", second.dispatched().get(0).getEquipmentCode());
        }

        @Test
        @DisplayName("세 구간을 모두 거쳐 완료된다")
        void runsThroughAllMoves() {
            flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", EARLY));

            for (int cycle = 1; cycle <= 3; cycle++) {
                var dispatched = flow.dispatch().dispatched();
                assertEquals(1, dispatched.size(), cycle + "번째 주기");
                complete(dispatched.get(0));
            }

            assertEquals(3, tasks.byStatus(TaskStatus.COMPLETED).size());
        }
    }

    @Nested
    @DisplayName("인터록")
    class Interlock {

        @Test
        @DisplayName("크레인이 진행 중이면 같은 통로의 다음 지시는 대기한다")
        void blocksWhenCraneBusy() {
            flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", EARLY));
            flow.accept(order("TO-00002", "CS-9002", "A-01-05-01", "CHUTE-3", LATE));

            var result = flow.dispatch();

            assertEquals(1, result.dispatchedCount(), "크레인 정원은 1이다");
            assertEquals(1, result.blockedCount());
            assertTrue(result.blocked().get(0).getReason().contains("EQP_BUSY"));
        }

        @Test
        @DisplayName("통로가 다르면 크레인이 달라 함께 나간다")
        void separateAislesRunInParallel() {
            flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", EARLY));
            flow.accept(order("TO-00002", "CS-9002", "A-02-05-01", "CHUTE-3", LATE));

            assertEquals(2, flow.dispatch().dispatchedCount());
        }

        @Test
        @DisplayName("P&D에 화물이 남아 있으면 크레인이 하달되지 않는다")
        void blocksWhenHandoverPointOccupied() {
            flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", EARLY));
            flow.accept(order("TO-00002", "CS-9002", "A-01-05-01", "CHUTE-3", LATE));

            // 첫 화물이 P&D에 도착했다. 크레인은 비었지만 자리는 차 있다
            complete(flow.dispatch().dispatched().get(0));

            var result = flow.dispatch();
            var craneTask = result.blocked().stream()
                    .filter(task -> task.getEquipmentCode().startsWith("SC-"))
                    .findFirst()
                    .orElseThrow();

            assertTrue(craneTask.getReason().contains("DEST_OCCUPIED"),
                    "설비는 비었는데 자리가 찼다: " + craneTask.getReason());
        }

        @Test
        @DisplayName("컨베이어가 가져가면 P&D가 비고 다음 화물이 들어간다")
        void handoverPointClearsAfterPickup() {
            flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", EARLY));
            flow.accept(order("TO-00002", "CS-9002", "A-01-05-01", "CHUTE-3", LATE));

            complete(flow.dispatch().dispatched().get(0));   // 첫 화물 P&D 도착
            flow.dispatch();                                  // 컨베이어 하달 · 크레인 차단

            var conveyor = tasks.byEquipment("CV-01").stream()
                    .filter(EquipmentTask::isInFlight)
                    .findFirst()
                    .orElseThrow();
            complete(conveyor);                               // P&D 비움

            var result = flow.dispatch();

            assertTrue(result.dispatched().stream()
                            .anyMatch(task -> task.getEquipmentCode().equals("SC-A01")),
                    "자리가 비었으니 두 번째 화물이 나간다");
        }
    }

    @Nested
    @DisplayName("하달 순서")
    class Ordering {

        @Test
        @DisplayName("컷오프가 이른 지시가 먼저 나간다")
        void earlierCutoffGoesFirst() {
            // 늦은 컷오프를 먼저 등록해도 순서가 뒤집힌다
            flow.accept(order("TO-00002", "CS-9002", "A-01-05-01", "CHUTE-3", LATE));
            flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", EARLY));

            var result = flow.dispatch();

            assertEquals("TO-00001", result.dispatched().get(0).getOrderNo());
            assertEquals("TO-00002", result.blocked().get(0).getOrderNo());
        }
    }

    @Nested
    @DisplayName("재시도 한도")
    class RetryLimit {

        @Test
        @DisplayName("차단된 작업은 다음 주기에 다시 시도한다")
        void retriesBlockedTask() {
            flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", EARLY));
            flow.accept(order("TO-00002", "CS-9002", "A-01-05-01", "CHUTE-3", LATE));

            flow.dispatch();
            var blockedTask = tasks.byStatus(TaskStatus.BLOCKED).get(0);
            assertEquals(0, blockedTask.getRetryCount());

            flow.dispatch();
            assertEquals(1, blockedTask.getRetryCount(), "되돌릴 때마다 센다");
        }

        @Test
        @DisplayName("한도를 넘기면 더 이상 되돌리지 않는다")
        void stopsAfterLimit() {
            var limited = new OutboundFlow(layout, orders, tasks, EquipmentGateway.NOOP, 2);
            limited.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", EARLY));
            limited.accept(order("TO-00002", "CS-9002", "A-01-05-01", "CHUTE-3", LATE));

            for (int cycle = 1; cycle <= 5; cycle++) {
                limited.dispatch();
            }

            var stuck = tasks.byStatus(TaskStatus.BLOCKED).get(0);
            assertEquals(2, stuck.getRetryCount(), "한도에서 멈춘다");
            assertTrue(stuck.getReason().contains("EQP_BUSY"), "마지막 사유가 남는다");
        }

        @Test
        @DisplayName("설비 이상은 자동으로 되돌리지 않는다")
        void doesNotRetryFailure() {
            flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", EARLY));
            var crane = flow.dispatch().dispatched().get(0);

            crane.fail("EQP_FAULT — SC-A01 이상");
            flow.dispatch();

            assertEquals(TaskStatus.FAILED, crane.getStatus(), "사람이 확인해야 한다");
            assertEquals(0, crane.getRetryCount());
        }
    }

    @Test
    @DisplayName("하달되지 않은 작업은 설비를 점유하지 않는다")
    void queuedTasksDoNotOccupyEquipment() {
        flow.accept(order("TO-00001", "CS-9001", "A-01-03-02", "CHUTE-3", EARLY));

        assertEquals(0, tasks.inFlightCount("SC-A01"), "만들기만 해서는 설비에 내려가지 않는다");

        flow.dispatch();

        assertEquals(1, tasks.inFlightCount("SC-A01"));
        assertFalse(layout.equipment("SC-A01").canAccept(tasks.inFlightCount("SC-A01")));
    }
}
