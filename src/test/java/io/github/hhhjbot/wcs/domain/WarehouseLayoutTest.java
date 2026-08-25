package io.github.hhhjbot.wcs.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 창고 구성이 경로를 만들어 내는지 검증.
 *
 * <p>확인하려는 것은 두 가지다. 출발 주소만으로 담당 설비가 정해지는지,
 * 그리고 구성에 없는 설비로 경로가 만들어지지 않는지.
 */
class WarehouseLayoutTest {

    private static final Equipment CRANE_A01 = new Equipment("SC-A01", 1);
    private static final Equipment CRANE_A02 = new Equipment("SC-A02", 1);
    private static final Equipment CONVEYOR  = new Equipment("CV-01", 8);
    private static final Equipment SORTER    = new Equipment("SRT-01", 24);

    private static final LocationCode INDUCTION = LocationCode.of("IND-01");

    private static WarehouseLayout layout() {
        return new WarehouseLayout(
                List.of(CRANE_A01, CRANE_A02, CONVEYOR, SORTER),
                INDUCTION, "CV-01", "SRT-01");
    }

    @Nested
    @DisplayName("구성")
    class Construction {

        @Test
        @DisplayName("등록한 설비를 코드로 찾는다")
        void findsEquipmentByCode() {
            assertEquals(CONVEYOR, layout().equipment("CV-01"));
            assertEquals(4, layout().equipments().size());
        }

        @Test
        @DisplayName("없는 설비를 찾으면 무엇이 등록돼 있는지 알려준다")
        void reportsRegisteredCodes() {
            var e = assertThrows(IllegalArgumentException.class,
                    () -> layout().equipment("SC-B01"));

            assertTrue(e.getMessage().contains("SC-B01"));
            assertTrue(e.getMessage().contains("SC-A01"), "등록된 목록이 보여야 조치가 된다");
        }

        @Test
        @DisplayName("설비 코드가 중복되면 구성되지 않는다")
        void rejectsDuplicateCode() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WarehouseLayout(
                            List.of(CRANE_A01, new Equipment("SC-A01", 2), CONVEYOR, SORTER),
                            INDUCTION, "CV-01", "SRT-01"));
        }

        @Test
        @DisplayName("컨베이어나 소터가 설비 목록에 없으면 구성되지 않는다")
        void rejectsUnregisteredLine() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WarehouseLayout(List.of(CRANE_A01, SORTER),
                            INDUCTION, "CV-99", "SRT-01"));
            assertThrows(IllegalArgumentException.class,
                    () -> new WarehouseLayout(List.of(CRANE_A01, CONVEYOR),
                            INDUCTION, "CV-01", "SRT-99"));
        }

        @Test
        @DisplayName("인덕션 자리가 아니면 구성되지 않는다")
        void rejectsWrongInduction() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WarehouseLayout(List.of(CRANE_A01, CONVEYOR, SORTER),
                            LocationCode.of("PND-A01"), "CV-01", "SRT-01"));
        }

        @Test
        @DisplayName("설비가 하나도 없으면 구성되지 않는다")
        void rejectsEmpty() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WarehouseLayout(List.of(), INDUCTION, "CV-01", "SRT-01"));
        }
    }

    @Nested
    @DisplayName("경로 생성")
    class RouteBuilding {

        @Test
        @DisplayName("랙에서 슈트까지 구간 셋으로 잇는다")
        void buildsThreeMoves() {
            var route = layout().routeFor(LocationCode.of("A-01-03-02"), LocationCode.of("CHUTE-3"));

            assertEquals(3, route.size());
            assertEquals(LocationCode.of("A-01-03-02"), route.origin());
            assertEquals(LocationCode.of("CHUTE-3"), route.destination());
        }

        @Test
        @DisplayName("거쳐 가는 자리는 넷이다")
        void passesFourStops() {
            var route = layout().routeFor(LocationCode.of("A-01-03-02"), LocationCode.of("CHUTE-3"));

            assertEquals(
                    List.of("A-01-03-02", "PND-A01", "IND-01", "CHUTE-3"),
                    route.stops().stream().map(LocationCode::value).toList());
        }

        @Test
        @DisplayName("출발 통로가 담당 크레인과 인계 지점을 정한다")
        void aisleDecidesCraneAndHandover() {
            var aisle2 = layout().routeFor(LocationCode.of("A-02-05-01"), LocationCode.of("CHUTE-1"));

            assertEquals("SC-A02", aisle2.at(1).equipment().code());
            assertEquals(LocationCode.of("PND-A02"), aisle2.at(1).to(), "P&D도 통로에서 나온다");
        }

        @Test
        @DisplayName("설비 정원이 구간에 실려 온다")
        void hopCarriesCapacity() {
            var route = layout().routeFor(LocationCode.of("A-01-03-02"), LocationCode.of("CHUTE-3"));

            assertEquals(1, route.at(1).equipment().capacity());
            assertEquals(8, route.at(2).equipment().capacity());
            assertEquals(24, route.at(3).equipment().capacity());
        }

        @Test
        @DisplayName("담당 크레인이 등록돼 있지 않으면 경로가 만들어지지 않는다")
        void rejectsUnknownAisle() {
            var e = assertThrows(IllegalArgumentException.class,
                    () -> layout().routeFor(LocationCode.of("B-09-01-01"), LocationCode.of("CHUTE-1")));

            assertTrue(e.getMessage().contains("SC-B09"), "없는 통로로 작업이 나가면 안 된다");
        }

        @Test
        @DisplayName("출발지가 랙이 아니면 거부한다")
        void rejectsNonRackSource() {
            assertThrows(IllegalArgumentException.class,
                    () -> layout().routeFor(LocationCode.of("PND-A01"), LocationCode.of("CHUTE-1")));
        }

        @Test
        @DisplayName("목적지가 슈트가 아니면 거부한다")
        void rejectsNonChuteTarget() {
            assertThrows(IllegalArgumentException.class,
                    () -> layout().routeFor(LocationCode.of("A-01-03-02"), LocationCode.of("IND-01")));
        }
    }

    @Test
    @DisplayName("통로가 달라도 컨베이어와 소터는 같은 설비를 쓴다")
    void sharesDownstreamEquipment() {
        var fromA01 = layout().routeFor(LocationCode.of("A-01-03-02"), LocationCode.of("CHUTE-3"));
        var fromA02 = layout().routeFor(LocationCode.of("A-02-03-02"), LocationCode.of("CHUTE-3"));

        // 앞 구간은 통로마다 다르고
        assertTrue(!fromA01.at(1).equipment().equals(fromA02.at(1).equipment()));

        // 뒤 구간에서 합류한다 — 컨베이어가 병목이 되는 지점이다
        assertEquals(fromA01.at(2).equipment(), fromA02.at(2).equipment());
        assertEquals(fromA01.at(3).equipment(), fromA02.at(3).equipment());
    }
}
