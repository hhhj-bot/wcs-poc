package io.github.hhhjbot.wcs.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 작업 목록의 계수 규칙 검증.
 *
 * <p>설비에 올라가 있는 작업 수를 저장하지 않고 세는 방식이 상태 변화를 그대로 따라가는지 확인한다.
 */
class TaskListTest {

    private static final Equipment CRANE    = new Equipment("SC-A01", 1);
    private static final Equipment CONVEYOR = new Equipment("CV-01", 8);

    private TaskList tasks;

    @BeforeEach
    void setUp() {
        tasks = new TaskList();
    }

    /** 지정한 설비의 작업을 만들어 목록에 넣는다. */
    private EquipmentTask register(String taskNo, Equipment equipment, String from, String to) {
        var task = new EquipmentTask(TaskNo.parse(taskNo), equipment.code(), "CS-9001",
                LocationCode.of(from), LocationCode.of(to));
        tasks.add(task);
        return task;
    }

    /** 하달까지 진행한다. 이 시점부터 설비 위에 올라간 것으로 센다. */
    private static void dispatch(EquipmentTask task) {
        task.transitionTo(TaskStatus.QUEUED);
        task.transitionTo(TaskStatus.SENT);
    }

    @Nested
    @DisplayName("등록")
    class Registration {

        @Test
        @DisplayName("등록한 작업을 번호로 찾는다")
        void findsByTaskNo() {
            register("TO-00001-1", CRANE, "A-01-03-02", "PND-A01");

            assertTrue(tasks.find(TaskNo.parse("TO-00001-1")).isPresent());
            assertTrue(tasks.find(TaskNo.parse("TO-00099-1")).isEmpty());
            assertEquals(1, tasks.size());
        }

        @Test
        @DisplayName("같은 작업 번호는 두 번 등록되지 않는다")
        void rejectsDuplicate() {
            register("TO-00001-1", CRANE, "A-01-03-02", "PND-A01");

            var e = assertThrows(IllegalArgumentException.class,
                    () -> register("TO-00001-1", CRANE, "A-01-03-02", "PND-A01"));
            assertTrue(e.getMessage().contains("TO-00001-1"));
        }

        @Test
        @DisplayName("설비별로 작업을 모아 본다")
        void groupsByEquipment() {
            register("TO-00001-1", CRANE, "A-01-03-02", "PND-A01");
            register("TO-00001-2", CONVEYOR, "PND-A01", "IND-01");
            register("TO-00002-2", CONVEYOR, "PND-A01", "IND-01");

            assertEquals(1, tasks.byEquipment("SC-A01").size());
            assertEquals(2, tasks.byEquipment("CV-01").size());
            assertEquals(0, tasks.byEquipment("SRT-01").size());
        }
    }

    @Nested
    @DisplayName("진행 중 계수")
    class InFlightCounting {

        @Test
        @DisplayName("하달 전에는 세지 않는다")
        void ignoresQueued() {
            var task = register("TO-00001-1", CRANE, "A-01-03-02", "PND-A01");
            assertEquals(0, tasks.inFlightCount("SC-A01"));

            task.transitionTo(TaskStatus.QUEUED);
            assertEquals(0, tasks.inFlightCount("SC-A01"), "대기 중일 뿐 설비에 내려가지 않았다");
        }

        @Test
        @DisplayName("하달하면 세고, 완료하면 빠진다")
        void followsStatus() {
            var task = register("TO-00001-1", CRANE, "A-01-03-02", "PND-A01");

            dispatch(task);
            assertEquals(1, tasks.inFlightCount("SC-A01"));

            task.transitionTo(TaskStatus.ACKED);
            task.transitionTo(TaskStatus.EXECUTING);
            assertEquals(1, tasks.inFlightCount("SC-A01"));

            task.transitionTo(TaskStatus.COMPLETED);
            assertEquals(0, tasks.inFlightCount("SC-A01"), "완료된 작업은 설비 위에 없다");
        }

        @Test
        @DisplayName("실패한 작업도 계수에서 빠진다")
        void excludesFailed() {
            var task = register("TO-00001-1", CRANE, "A-01-03-02", "PND-A01");
            dispatch(task);

            task.fail("EQP_FAULT — SC-A01 이상");

            assertEquals(0, tasks.inFlightCount("SC-A01"));
        }

        @Test
        @DisplayName("설비마다 따로 센다")
        void countsPerEquipment() {
            var crane = register("TO-00001-1", CRANE, "A-01-03-02", "PND-A01");
            var first = register("TO-00001-2", CONVEYOR, "PND-A01", "IND-01");
            var second = register("TO-00002-2", CONVEYOR, "PND-A01", "IND-01");

            dispatch(crane);
            dispatch(first);
            dispatch(second);

            assertEquals(1, tasks.inFlightCount("SC-A01"));
            assertEquals(2, tasks.inFlightCount("CV-01"));
        }
    }

    @Nested
    @DisplayName("설비 가용 판정")
    class Availability {

        @Test
        @DisplayName("크레인은 한 건이 올라가면 더 받지 않는다")
        void craneFillsAtOne() {
            var task = register("TO-00001-1", CRANE, "A-01-03-02", "PND-A01");
            assertTrue(tasks.canAccept(CRANE));

            dispatch(task);
            assertFalse(tasks.canAccept(CRANE));
            assertEquals(0, tasks.availableSlots(CRANE));
        }

        @Test
        @DisplayName("컨베이어는 존 수만큼 받는다")
        void conveyorFillsAtZoneCount() {
            for (int i = 1; i <= 8; i++) {
                var task = register("TO-0000" + i + "-2", CONVEYOR, "PND-A01", "IND-01");
                assertTrue(tasks.canAccept(CONVEYOR), i + "번째 화물까지는 받을 수 있어야 한다");
                dispatch(task);
            }

            assertEquals(8, tasks.inFlightCount("CV-01"));
            assertFalse(tasks.canAccept(CONVEYOR), "존이 8개뿐이다");
        }

        @Test
        @DisplayName("한 건이 끝나면 자리가 다시 생긴다")
        void slotFreesOnCompletion() {
            var task = register("TO-00001-1", CRANE, "A-01-03-02", "PND-A01");
            dispatch(task);
            assertFalse(tasks.canAccept(CRANE));

            task.transitionTo(TaskStatus.ACKED);
            task.transitionTo(TaskStatus.EXECUTING);
            task.transitionTo(TaskStatus.COMPLETED);

            assertTrue(tasks.canAccept(CRANE));
            assertEquals(1, tasks.availableSlots(CRANE));
        }

        @Test
        @DisplayName("같은 진행 중 수라도 설비에 따라 판단이 다르다")
        void sameCountDiffersByEquipment() {
            var conveyor = register("TO-00001-2", CONVEYOR, "PND-A01", "IND-01");
            var crane = register("TO-00001-1", CRANE, "A-01-03-02", "PND-A01");
            dispatch(conveyor);
            dispatch(crane);

            assertEquals(1, tasks.inFlightCount("CV-01"));
            assertEquals(1, tasks.inFlightCount("SC-A01"));

            assertTrue(tasks.canAccept(CONVEYOR));
            assertFalse(tasks.canAccept(CRANE));
        }
    }
}
