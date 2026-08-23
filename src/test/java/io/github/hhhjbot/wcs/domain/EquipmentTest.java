package io.github.hhhjbot.wcs.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 설비의 동시 처리 판정 검증.
 *
 * <p>설비마다 동시에 받을 수 있는 수가 다르다.
 * 크레인 기준으로 일반화하면 컨베이어와 소터의 처리량이 제한된다.
 */
class EquipmentTest {

    private static final Equipment CRANE = new Equipment("SC-A01", 1);
    private static final Equipment CONVEYOR = new Equipment("CV-01", 8);
    private static final Equipment SORTER = new Equipment("SRT-01", 24);

    @Nested
    @DisplayName("생성")
    class Creation {

        @Test
        @DisplayName("코드와 동시 처리 수를 갖는다")
        void hasCodeAndCapacity() {
            assertEquals("SC-A01", CRANE.code());
            assertEquals(1, CRANE.capacity());
        }

        @Test
        @DisplayName("코드가 비어 있으면 생성되지 않는다")
        void rejectsBlankCode() {
            assertThrows(IllegalArgumentException.class, () -> new Equipment(null, 1));
            assertThrows(IllegalArgumentException.class, () -> new Equipment("  ", 1));
        }

        @Test
        @DisplayName("동시 처리 수가 1 미만이면 생성되지 않는다")
        void rejectsInvalidCapacity() {
            assertThrows(IllegalArgumentException.class, () -> new Equipment("CV-01", 0));
            assertThrows(IllegalArgumentException.class, () -> new Equipment("CV-01", -1));
        }
    }

    @Nested
    @DisplayName("크레인 — 한 번에 한 건")
    class SingleLoad {

        @Test
        @DisplayName("비어 있으면 받는다")
        void acceptsWhenIdle() {
            assertTrue(CRANE.canAccept(0));
        }

        @Test
        @DisplayName("한 건이라도 진행 중이면 더 받지 않는다")
        void rejectsWhenBusy() {
            assertFalse(CRANE.canAccept(1));
        }

        @Test
        @DisplayName("단일 처리 설비로 구분된다")
        void identifiedAsSingleLoad() {
            assertTrue(CRANE.isSingleLoad());
            assertFalse(CONVEYOR.isSingleLoad());
        }
    }

    @Nested
    @DisplayName("컨베이어·소터 — 동시에 여러 건")
    class MultiLoad {

        @Test
        @DisplayName("정원에 못 미치면 계속 받는다")
        void acceptsUntilFull() {
            assertTrue(CONVEYOR.canAccept(0));
            assertTrue(CONVEYOR.canAccept(5));
            assertTrue(CONVEYOR.canAccept(7));
        }

        @Test
        @DisplayName("정원이 차면 받지 않는다")
        void rejectsWhenFull() {
            assertFalse(CONVEYOR.canAccept(8));
            assertFalse(SORTER.canAccept(24));
        }

        @Test
        @DisplayName("크레인 기준을 그대로 적용하면 안 된다")
        void doesNotFollowCraneRule() {
            // 진행 중 1건. 크레인이라면 거부하지만 컨베이어는 받아야 한다.
            assertFalse(CRANE.canAccept(1));
            assertTrue(CONVEYOR.canAccept(1), "컨베이어는 존마다 화물이 올라간다");
            assertTrue(SORTER.canAccept(1), "소터는 캐리어마다 화물이 올라간다");
        }
    }

    @Nested
    @DisplayName("여유 슬롯")
    class AvailableSlots {

        @Test
        @DisplayName("남은 수를 알려준다")
        void reportsRemaining() {
            assertEquals(8, CONVEYOR.availableSlots(0));
            assertEquals(3, CONVEYOR.availableSlots(5));
            assertEquals(0, CONVEYOR.availableSlots(8));
        }

        @Test
        @DisplayName("정원을 넘겨도 음수가 되지 않는다")
        void neverNegative() {
            assertEquals(0, CONVEYOR.availableSlots(12));
        }
    }

    @Test
    @DisplayName("진행 중 작업 수가 음수면 예외")
    void rejectsNegativeCount() {
        assertThrows(IllegalArgumentException.class, () -> CRANE.canAccept(-1));
        assertThrows(IllegalArgumentException.class, () -> CRANE.availableSlots(-1));
    }

    @Test
    @DisplayName("같은 코드와 정원이면 같은 설비로 본다")
    void valueEquality() {
        assertEquals(new Equipment("SC-A01", 1), CRANE);
    }
}
