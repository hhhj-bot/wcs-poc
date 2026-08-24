package io.github.hhhjbot.wcs.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 설비 작업의 상태 변경 규칙 검증.
 *
 * <p>{@link TaskStatus}가 규칙을 정의하고, 이 클래스가 그 규칙을 실제로 강제하는지 확인한다.
 */
class EquipmentTaskTest {

    /** 크레인이 랙에서 케이스를 꺼내 P&D로 옮기는 작업. */
    private EquipmentTask craneTask() {
        return new EquipmentTask(TaskNo.of("TO-00001", 1), "SC-A01", "CS-9001",
                LocationCode.of("A-01-03-02"), LocationCode.of("PND-A01"));
    }

    @Nested
    @DisplayName("생성")
    class Creation {

        @Test
        @DisplayName("생성 직후 상태는 CREATED다")
        void startsAsCreated() {
            assertEquals(TaskStatus.CREATED, craneTask().getStatus());
        }

        @Test
        @DisplayName("필수 항목이 비어 있으면 생성되지 않는다")
        void rejectsBlankFields() {
            assertThrows(NullPointerException.class,
                    () -> new EquipmentTask(null, "SC-A01", "CS-9001",
                            LocationCode.of("A-01-03-02"), LocationCode.of("PND-A01")));
            assertThrows(IllegalArgumentException.class,
                    () -> new EquipmentTask(TaskNo.of("TO-1", 1), "  ", "CS-9001",
                            LocationCode.of("A-01-03-02"), LocationCode.of("PND-A01")));
        }

        @Test
        @DisplayName("출발지와 목적지가 같으면 생성되지 않는다")
        void rejectsSameLocation() {
            assertThrows(IllegalArgumentException.class,
                    () -> new EquipmentTask(TaskNo.of("TO-1", 1), "SC-A01", "CS-9001",
                            LocationCode.of("PND-A01"), LocationCode.of("PND-A01")));
        }
    }

    @Nested
    @DisplayName("정상 흐름")
    class HappyPath {

        @Test
        @DisplayName("대기열을 거쳐 하달되고 완료까지 진행한다")
        void runsToCompletion() {
            var task = craneTask();

            task.transitionTo(TaskStatus.QUEUED);
            assertEquals(TaskStatus.QUEUED, task.getStatus());

            task.transitionTo(TaskStatus.SENT);
            task.transitionTo(TaskStatus.ACKED);
            task.transitionTo(TaskStatus.EXECUTING);
            task.transitionTo(TaskStatus.COMPLETED);

            assertEquals(TaskStatus.COMPLETED, task.getStatus());
        }
    }

    @Nested
    @DisplayName("규칙 위반 차단")
    class RejectsInvalidTransition {

        @Test
        @DisplayName("하달하지 않은 작업을 완료 처리하면 예외가 발생한다")
        void cannotCompleteBeforeDispatch() {
            var task = craneTask();

            var e = assertThrows(IllegalStateException.class,
                    () -> task.transitionTo(TaskStatus.COMPLETED));

            assertTrue(e.getMessage().contains("TO-00001-1"), "메시지에 작업번호가 있어야 조치가 가능하다");
            assertEquals(TaskStatus.CREATED, task.getStatus(), "실패한 전이는 상태를 바꾸지 않는다");
        }

        @Test
        @DisplayName("수신확인 전에 실행 중으로 바꿀 수 없다")
        void cannotExecuteBeforeAck() {
            var task = craneTask();
            task.transitionTo(TaskStatus.QUEUED);
            task.transitionTo(TaskStatus.SENT);

            assertThrows(IllegalStateException.class,
                    () -> task.transitionTo(TaskStatus.EXECUTING));
        }

        @Test
        @DisplayName("완료된 작업은 되돌릴 수 없다")
        void completedIsFinal() {
            var task = craneTask();
            task.transitionTo(TaskStatus.QUEUED);
            task.transitionTo(TaskStatus.SENT);
            task.transitionTo(TaskStatus.ACKED);
            task.transitionTo(TaskStatus.EXECUTING);
            task.transitionTo(TaskStatus.COMPLETED);

            assertThrows(IllegalStateException.class,
                    () -> task.transitionTo(TaskStatus.CREATED));
        }
    }

    @Nested
    @DisplayName("차단과 실패")
    class BlockedAndFailed {

        @Test
        @DisplayName("인터록에 걸리면 사유와 함께 차단된다")
        void blockKeepsReason() {
            var task = craneTask();
            task.block("PND_OCCUPIED — P&D에 이전 케이스가 남아 있음");

            assertEquals(TaskStatus.BLOCKED, task.getStatus());
            assertTrue(task.getReason().contains("PND_OCCUPIED"));
            assertTrue(task.isRetryable());
        }

        @Test
        @DisplayName("설비 이상으로 실패하면 사유가 남는다")
        void failKeepsReason() {
            var task = craneTask();
            task.transitionTo(TaskStatus.QUEUED);
            task.transitionTo(TaskStatus.SENT);
            task.fail("EQP_FAULT — SC-A01 이상");

            assertEquals(TaskStatus.FAILED, task.getStatus());
            assertTrue(task.getReason().contains("EQP_FAULT"));
        }

        @Test
        @DisplayName("재시도하면 CREATED로 돌아가고 사유가 지워진다")
        void retryClearsReason() {
            var task = craneTask();
            task.block("CHUTE_FULL");

            task.transitionTo(TaskStatus.CREATED);

            assertEquals(TaskStatus.CREATED, task.getStatus());
            assertNull(task.getReason(), "재시도 시 이전 차단 사유는 남기지 않는다");
        }

        @Test
        @DisplayName("차단된 작업을 인터록 재검사 없이 하달할 수 없다")
        void cannotDispatchDirectlyFromBlocked() {
            var task = craneTask();
            task.block("EQP_FAULT");

            assertThrows(IllegalStateException.class,
                    () -> task.transitionTo(TaskStatus.SENT));
        }
    }

    @Nested
    @DisplayName("설비 진행 중 판정")
    class InFlight {

        @Test
        @DisplayName("하달 전에는 진행 중이 아니다")
        void notInFlightBeforeDispatch() {
            var task = craneTask();
            assertFalse(task.isInFlight());

            task.transitionTo(TaskStatus.QUEUED);
            assertFalse(task.isInFlight(), "대기 중일 뿐 설비에 내려가지 않았다");
        }

        @Test
        @DisplayName("하달 후 완료 전까지 진행 중이다")
        void inFlightUntilCompleted() {
            var task = craneTask();
            task.transitionTo(TaskStatus.QUEUED);

            task.transitionTo(TaskStatus.SENT);
            assertTrue(task.isInFlight());

            task.transitionTo(TaskStatus.ACKED);
            assertTrue(task.isInFlight());

            task.transitionTo(TaskStatus.EXECUTING);
            assertTrue(task.isInFlight());

            task.transitionTo(TaskStatus.COMPLETED);
            assertFalse(task.isInFlight(), "완료되면 계수 대상에서 빠진다");
        }

        @Test
        @DisplayName("진행 중 여부만으로 설비 가용을 판단하지 않는다")
        void doesNotDecideEquipmentAvailability() {
            var task = craneTask();
            task.transitionTo(TaskStatus.QUEUED);
            task.transitionTo(TaskStatus.SENT);

            var crane = new Equipment("SC-A01", 1);
            var conveyor = new Equipment("CV-01", 8);
            int inFlight = task.isInFlight() ? 1 : 0;

            // 같은 진행 중 1건이라도 설비에 따라 판단이 다르다
            assertFalse(crane.canAccept(inFlight));
            assertTrue(conveyor.canAccept(inFlight));
        }
    }

    @Test
    @DisplayName("출고 지시 하나가 설비별 작업으로 나뉜다")
    void oneOrderSplitsIntoEquipmentTasks() {
        var crane = new EquipmentTask(TaskNo.of("TO-00001", 1), "SC-A01", "CS-9001",
                LocationCode.of("A-01-03-02"), LocationCode.of("PND-A01"));
        var conveyor = new EquipmentTask(TaskNo.of("TO-00001", 2), "CV-01", "CS-9001",
                LocationCode.of("PND-A01"), LocationCode.of("IND-01"));
        var sorter = new EquipmentTask(TaskNo.of("TO-00001", 3), "SRT-01", "CS-9001",
                LocationCode.of("IND-01"), LocationCode.of("CHUTE-3"));

        // 같은 화물이 설비를 옮겨 다닌다
        assertEquals("CS-9001", crane.getLoadId());
        assertEquals(crane.getLoadId(), conveyor.getLoadId());
        assertEquals(conveyor.getLoadId(), sorter.getLoadId());

        // 앞 작업의 목적지가 다음 작업의 출발지가 된다
        assertEquals(crane.getTo(), conveyor.getFrom());
        assertEquals(conveyor.getTo(), sorter.getFrom());
    }
}
