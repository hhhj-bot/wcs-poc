package io.github.hhhjbot.wcs.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 출고 지시의 구성 규칙 검증.
 *
 * <p>내려온 지시가 실행 가능한 형태인지, 작업 번호와 담당 크레인이 지시에서 나오는지를 확인한다.
 */
class OutboundOrderTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 8, 23, 16, 0);

    private static OutboundOrder order() {
        return new OutboundOrder(
                "TO-00001",
                "CS-9001",
                LocationCode.of("A-01-03-02"),
                LocationCode.of("CHUTE-3"),
                CUTOFF);
    }

    @Nested
    @DisplayName("생성")
    class Creation {

        @Test
        @DisplayName("지시번호·화물·출발지·계획슈트·컷오프를 갖는다")
        void keepsGivenValues() {
            var order = order();

            assertEquals("TO-00001", order.orderNo());
            assertEquals("CS-9001", order.loadId());
            assertEquals("A-01-03-02", order.source().value());
            assertEquals("CHUTE-3", order.plannedChute().value());
            assertEquals(CUTOFF, order.cutoff());
        }

        @Test
        @DisplayName("앞뒤 공백은 정리한다")
        void trimsInput() {
            var order = new OutboundOrder(" TO-00001 ", " CS-9001 ",
                    LocationCode.of("A-01-03-02"), LocationCode.of("CHUTE-3"), CUTOFF);

            assertEquals("TO-00001", order.orderNo());
            assertEquals("CS-9001", order.loadId());
        }

        @Test
        @DisplayName("필수 항목이 비어 있으면 생성되지 않는다")
        void rejectsBlankFields() {
            assertThrows(NullPointerException.class,
                    () -> new OutboundOrder(null, "CS-9001",
                            LocationCode.of("A-01-03-02"), LocationCode.of("CHUTE-3"), CUTOFF));
            assertThrows(IllegalArgumentException.class,
                    () -> new OutboundOrder("TO-00001", "   ",
                            LocationCode.of("A-01-03-02"), LocationCode.of("CHUTE-3"), CUTOFF));
            assertThrows(NullPointerException.class,
                    () -> new OutboundOrder("TO-00001", "CS-9001",
                            LocationCode.of("A-01-03-02"), LocationCode.of("CHUTE-3"), null));
        }
    }

    @Nested
    @DisplayName("자리 검사")
    class LocationRules {

        @Test
        @DisplayName("출발지가 랙이 아니면 생성되지 않는다")
        void sourceMustBeRack() {
            for (String code : new String[]{"PND-A01", "IND-01", "CHUTE-3"}) {
                var e = assertThrows(IllegalArgumentException.class,
                        () -> new OutboundOrder("TO-00001", "CS-9001",
                                LocationCode.of(code), LocationCode.of("CHUTE-3"), CUTOFF));
                assertTrue(e.getMessage().contains(code), "어느 자리가 문제인지 알 수 있어야 한다");
            }
        }

        @Test
        @DisplayName("계획 슈트가 슈트가 아니면 생성되지 않는다")
        void plannedChuteMustBeChute() {
            for (String code : new String[]{"IND-01", "PND-A01", "A-01-03-02"}) {
                assertThrows(IllegalArgumentException.class,
                        () -> new OutboundOrder("TO-00001", "CS-9001",
                                LocationCode.of("A-01-03-02"), LocationCode.of(code), CUTOFF));
            }
        }
    }

    @Nested
    @DisplayName("작업 번호")
    class TaskNumbering {

        @Test
        @DisplayName("지시번호와 순번으로 조립한다")
        void buildsFromOrderNoAndSeq() {
            var order = order();

            assertEquals("TO-00001-1", order.taskNo(1));
            assertEquals("TO-00001-2", order.taskNo(2));
            assertEquals("TO-00001-3", order.taskNo(3));
        }

        @Test
        @DisplayName("순번이 1 미만이면 만들어지지 않는다")
        void rejectsNonPositiveSeq() {
            assertThrows(IllegalArgumentException.class, () -> order().taskNo(0));
            assertThrows(IllegalArgumentException.class, () -> order().taskNo(-1));
        }
    }

    @Nested
    @DisplayName("담당 크레인")
    class CraneAssignment {

        @Test
        @DisplayName("출발지 주소에서 첫 구간 담당 크레인이 정해진다")
        void derivedFromSource() {
            assertEquals("SC-A01", order().craneCode());

            var other = new OutboundOrder("TO-00002", "CS-9002",
                    LocationCode.of("B-12-05-01"), LocationCode.of("CHUTE-1"), CUTOFF);
            assertEquals("SC-B12", other.craneCode());
        }
    }

    @Nested
    @DisplayName("컷오프")
    class Cutoff {

        @Test
        @DisplayName("날짜까지 갖고 있어 자정을 넘겨도 순서가 유지된다")
        void keepsOrderAcrossMidnight() {
            var tonight = new OutboundOrder("TO-00001", "CS-9001",
                    LocationCode.of("A-01-03-02"), LocationCode.of("CHUTE-3"),
                    LocalDateTime.of(2026, 8, 23, 23, 0));
            var tomorrow = new OutboundOrder("TO-00002", "CS-9002",
                    LocationCode.of("A-01-03-03"), LocationCode.of("CHUTE-3"),
                    LocalDateTime.of(2026, 8, 24, 1, 0));

            // 시각만 갖고 있었다면 01:00이 23:00보다 앞선 것으로 비교된다
            assertTrue(tonight.cutoff().isBefore(tomorrow.cutoff()));
            assertFalse(tonight.cutoff().toLocalTime().isBefore(tomorrow.cutoff().toLocalTime()));
        }
    }

    @Test
    @DisplayName("같은 값이면 같은 지시다")
    void equalByValue() {
        assertEquals(order(), order());
        assertEquals(order().hashCode(), order().hashCode());
    }
}
