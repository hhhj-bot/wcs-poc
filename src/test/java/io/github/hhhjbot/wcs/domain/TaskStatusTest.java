package io.github.hhhjbot.wcs.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 작업 상태 전이 규칙 검증.
 */
class TaskStatusTest {

    @Nested
    @DisplayName("정상 경로")
    class HappyPath {

        @Test
        @DisplayName("CREATED에서 COMPLETED까지 한 단계씩 진행한다")
        void stepByStep() {
            assertTrue(TaskStatus.CREATED.canTransitionTo(TaskStatus.QUEUED));
            assertTrue(TaskStatus.QUEUED.canTransitionTo(TaskStatus.SENT));
            assertTrue(TaskStatus.SENT.canTransitionTo(TaskStatus.ACKED));
            assertTrue(TaskStatus.ACKED.canTransitionTo(TaskStatus.EXECUTING));
            assertTrue(TaskStatus.EXECUTING.canTransitionTo(TaskStatus.COMPLETED));
        }
    }

    @Nested
    @DisplayName("단계 건너뛰기 차단")
    class NoSkipping {

        @Test
        @DisplayName("지시를 하달하지 않은 작업은 완료할 수 없다")
        void cannotCompleteBeforeDispatch() {
            assertFalse(TaskStatus.CREATED.canTransitionTo(TaskStatus.COMPLETED));
            assertFalse(TaskStatus.QUEUED.canTransitionTo(TaskStatus.COMPLETED));
        }

        @Test
        @DisplayName("수신확인을 받지 않은 작업은 완료할 수 없다")
        void cannotCompleteBeforeAck() {
            assertFalse(TaskStatus.SENT.canTransitionTo(TaskStatus.COMPLETED));
            assertFalse(TaskStatus.SENT.canTransitionTo(TaskStatus.EXECUTING));
            assertFalse(TaskStatus.ACKED.canTransitionTo(TaskStatus.COMPLETED));
        }

        @Test
        @DisplayName("대기열을 거치지 않고 하달할 수 없다")
        void cannotDispatchWithoutQueue() {
            assertFalse(TaskStatus.CREATED.canTransitionTo(TaskStatus.SENT));
        }
    }

    @Nested
    @DisplayName("차단과 실패")
    class BlockedAndFailed {

        @Test
        @DisplayName("인터록 위반은 하달 전 두 지점에서 발생할 수 있다")
        void interlockBlocksBeforeDispatch() {
            assertTrue(TaskStatus.CREATED.canTransitionTo(TaskStatus.BLOCKED));
            assertTrue(TaskStatus.QUEUED.canTransitionTo(TaskStatus.BLOCKED));
        }

        @Test
        @DisplayName("설비 점유 구간에서는 실패로 전이할 수 있다")
        void canFailWhileInFlight() {
            assertTrue(TaskStatus.SENT.canTransitionTo(TaskStatus.FAILED));
            assertTrue(TaskStatus.ACKED.canTransitionTo(TaskStatus.FAILED));
            assertTrue(TaskStatus.EXECUTING.canTransitionTo(TaskStatus.FAILED));
        }

        @Test
        @DisplayName("재시도는 CREATED로 되돌린 뒤 다시 진행한다")
        void retryReturnsToCreated() {
            assertTrue(TaskStatus.BLOCKED.canTransitionTo(TaskStatus.CREATED));
            assertTrue(TaskStatus.FAILED.canTransitionTo(TaskStatus.CREATED));

            assertFalse(TaskStatus.BLOCKED.canTransitionTo(TaskStatus.SENT));
            assertFalse(TaskStatus.FAILED.canTransitionTo(TaskStatus.EXECUTING));
        }

        @Test
        @DisplayName("재시도 가능 상태를 구분한다")
        void retryableStates() {
            assertTrue(TaskStatus.BLOCKED.isRetryable());
            assertTrue(TaskStatus.FAILED.isRetryable());
            assertFalse(TaskStatus.EXECUTING.isRetryable());
            assertFalse(TaskStatus.COMPLETED.isRetryable());
        }
    }

    @Nested
    @DisplayName("종료 상태")
    class Terminal {

        @Test
        @DisplayName("완료된 작업은 어떤 상태로도 전이하지 않는다")
        void completedIsFinal() {
            assertTrue(TaskStatus.COMPLETED.isTerminal());
            assertTrue(TaskStatus.COMPLETED.allowedNext().isEmpty());

            for (TaskStatus next : TaskStatus.values()) {
                assertFalse(TaskStatus.COMPLETED.canTransitionTo(next),
                        "COMPLETED에서 " + next + "로 전이가 허용되었다");
            }
        }
    }

    @Nested
    @DisplayName("설비 점유 판정")
    class InFlight {

        @Test
        @DisplayName("지시가 설비에 전달된 구간만 점유로 본다")
        void onlyDispatchedStates() {
            assertTrue(TaskStatus.SENT.isInFlight());
            assertTrue(TaskStatus.ACKED.isInFlight());
            assertTrue(TaskStatus.EXECUTING.isInFlight());
        }

        @Test
        @DisplayName("대기 중이거나 종료된 작업은 설비를 점유하지 않는다")
        void notOccupyingEquipment() {
            assertFalse(TaskStatus.CREATED.isInFlight());
            assertFalse(TaskStatus.QUEUED.isInFlight());
            assertFalse(TaskStatus.BLOCKED.isInFlight());
            assertFalse(TaskStatus.COMPLETED.isInFlight());
            assertFalse(TaskStatus.FAILED.isInFlight());
        }
    }

    @Test
    @DisplayName("null 전이는 거부한다")
    void nullIsRejected() {
        assertFalse(TaskStatus.CREATED.canTransitionTo(null));
    }
}
