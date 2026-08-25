package io.github.hhhjbot.wcs.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 설비 경로의 구성 규칙 검증.
 *
 * <p>구간이 실제로 이어지는지, 순번으로 구간을 꺼낼 수 있는지를 확인한다.
 */
class RouteTest {

    private static final Equipment CRANE    = new Equipment("SC-A01", 1);
    private static final Equipment CONVEYOR = new Equipment("CV-01", 8);
    private static final Equipment SORTER   = new Equipment("SRT-01", 24);

    private static final LocationCode RACK      = LocationCode.of("A-01-03-02");
    private static final LocationCode PND       = LocationCode.of("PND-A01");
    private static final LocationCode INDUCTION = LocationCode.of("IND-01");
    private static final LocationCode CHUTE     = LocationCode.of("CHUTE-3");

    /** 랙에서 슈트까지의 표준 경로. */
    private static Route outboundRoute() {
        return Route.of(List.of(
                new Route.Move(CRANE,    RACK,      PND),
                new Route.Move(CONVEYOR, PND,       INDUCTION),
                new Route.Move(SORTER,   INDUCTION, CHUTE)));
    }

    @Nested
    @DisplayName("구성")
    class Composition {

        @Test
        @DisplayName("구간 순서대로 설비가 선다")
        void keepsEquipmentOrder() {
            var route = outboundRoute();

            assertEquals(3, route.size());
            assertEquals("SC-A01", route.at(1).equipment().code());
            assertEquals("CV-01",  route.at(2).equipment().code());
            assertEquals("SRT-01", route.at(3).equipment().code());
        }

        @Test
        @DisplayName("출발지와 도착지를 알려준다")
        void reportsOriginAndDestination() {
            var route = outboundRoute();

            assertEquals(RACK, route.origin());
            assertEquals(CHUTE, route.destination());
        }

        @Test
        @DisplayName("구간이 셋이면 지나는 자리는 넷이다")
        void stopsAreOneMoreThanMoves() {
            var route = outboundRoute();

            assertEquals(List.of(RACK, PND, INDUCTION, CHUTE), route.stops());
        }

        @Test
        @DisplayName("설비를 추가하면 항목이 하나 는다")
        void extendsByOneEntry() {
            // 디팔레타이저가 랙과 P&D 사이에 들어온 경우 (ADR-0008 확장 예시)
            var depalletizer = new Equipment("DEP-01", 1);
            var buffer = LocationCode.of("PND-A02");

            var extended = Route.of(List.of(
                    new Route.Move(CRANE,        RACK,      buffer),
                    new Route.Move(depalletizer, buffer,    PND),
                    new Route.Move(CONVEYOR,     PND,       INDUCTION),
                    new Route.Move(SORTER,       INDUCTION, CHUTE)));

            assertEquals(4, extended.size());
            assertEquals(RACK, extended.origin());
            assertEquals(CHUTE, extended.destination());
        }
    }

    @Nested
    @DisplayName("연결 검사")
    class Continuity {

        @Test
        @DisplayName("앞 구간의 목적지와 다음 구간의 출발지가 다르면 만들어지지 않는다")
        void rejectsBrokenChain() {
            var e = assertThrows(IllegalArgumentException.class,
                    () -> Route.of(List.of(
                            new Route.Move(CRANE,    RACK,      PND),
                            new Route.Move(CONVEYOR, INDUCTION, CHUTE))));

            assertTrue(e.getMessage().contains("CV-01"), "어느 설비에서 끊겼는지 알 수 있어야 한다");
        }

        @Test
        @DisplayName("한 구간 안에서 출발지와 목적지가 같을 수 없다")
        void rejectsSelfMove() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Route.Move(CONVEYOR, PND, PND));
        }

        @Test
        @DisplayName("구간이 없으면 경로가 아니다")
        void rejectsEmpty() {
            assertThrows(IllegalArgumentException.class, () -> Route.of(List.of()));
        }
    }

    @Nested
    @DisplayName("순번")
    class Sequence {

        @Test
        @DisplayName("작업 순번과 같은 1부터 센다")
        void isOneBased() {
            var route = outboundRoute();

            assertEquals(RACK, route.at(1).from());
            assertEquals(CHUTE, route.at(3).to());
        }

        @Test
        @DisplayName("범위를 벗어난 순번은 거부한다")
        void rejectsOutOfRange() {
            var route = outboundRoute();

            assertThrows(IllegalArgumentException.class, () -> route.at(0));
            assertThrows(IllegalArgumentException.class, () -> route.at(4));
        }
    }

    @Test
    @DisplayName("경로가 자리 순서로 표시된다")
    void printsAsStops() {
        assertEquals("A-01-03-02 → PND-A01 → IND-01 → CHUTE-3", outboundRoute().toString());
    }
}
