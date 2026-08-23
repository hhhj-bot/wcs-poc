package io.github.hhhjbot.wcs.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 위치 코드의 해석 규칙 검증.
 *
 * <p>형식이 틀린 자리가 만들어지지 않는지, 코드만으로 담당 크레인이 정해지는지를 확인한다.
 */
class LocationCodeTest {

    @Nested
    @DisplayName("랙 주소")
    class Rack {

        @Test
        @DisplayName("존·통로·열·단으로 해석한다")
        void parsesCoordinates() {
            var location = LocationCode.of("A-01-03-02");

            assertEquals(LocationKind.RACK, location.kind());
            assertTrue(location.isRack());
            assertFalse(location.isStation());
            assertEquals("A", location.rack().zone());
            assertEquals(1, location.rack().aisle());
            assertEquals(3, location.rack().bay());
            assertEquals(2, location.rack().level());
        }

        @Test
        @DisplayName("소문자와 앞뒤 공백은 정리한다")
        void normalizesInput() {
            assertEquals(LocationCode.of("A-01-03-02"), LocationCode.of("  a-01-03-02 "));
        }

        @Test
        @DisplayName("자릿수가 맞지 않으면 만들어지지 않는다")
        void rejectsLooseFormat() {
            // 사람이 쓰기 쉬운 형태지만 정렬도 비교도 되지 않는다
            assertThrows(IllegalArgumentException.class, () -> LocationCode.of("A-1-3-2"));
            assertThrows(IllegalArgumentException.class, () -> LocationCode.of("A-001-03-02"));
            assertThrows(IllegalArgumentException.class, () -> LocationCode.of("A-01-03"));
        }

        @Test
        @DisplayName("번호가 0이면 만들어지지 않는다")
        void rejectsZeroNumber() {
            assertThrows(IllegalArgumentException.class, () -> LocationCode.of("A-00-03-02"));
            assertThrows(IllegalArgumentException.class, () -> LocationCode.of("A-01-03-00"));
        }
    }

    @Nested
    @DisplayName("스테이션")
    class Station {

        @Test
        @DisplayName("종류별로 구분해 해석한다")
        void identifiesEachKind() {
            assertEquals(LocationKind.PND,       LocationCode.of("PND-A01").kind());
            assertEquals(LocationKind.INDUCTION, LocationCode.of("IND-01").kind());
            assertEquals(LocationKind.CHUTE,     LocationCode.of("CHUTE-3").kind());

            for (String code : new String[]{"PND-A01", "IND-01", "CHUTE-3"}) {
                var location = LocationCode.of(code);
                assertTrue(location.isStation(), code + "은 스테이션이어야 한다");
                assertFalse(location.isRack());
            }
        }

        @Test
        @DisplayName("P&D는 존과 통로를 갖는다")
        void pndBelongsToAisle() {
            var pnd = LocationCode.of("PND-A01");

            assertTrue(pnd.belongsToAisle());
            assertEquals("A", pnd.aisleRef().zone());
            assertEquals(1, pnd.aisleRef().aisle());
        }

        @Test
        @DisplayName("P&D에서 열·단을 꺼내려 하면 예외가 발생한다")
        void pndHasNoRackAddress() {
            var pnd = LocationCode.of("PND-A01");

            var e = assertThrows(IllegalStateException.class, pnd::rack);
            assertTrue(e.getMessage().contains("PND-A01"), "어느 자리인지 메시지에 있어야 한다");
        }

        @Test
        @DisplayName("인덕션과 슈트는 통로에 속하지 않는다")
        void inductionAndChuteHaveNoAisle() {
            for (String code : new String[]{"IND-01", "CHUTE-3"}) {
                var location = LocationCode.of(code);
                assertFalse(location.belongsToAisle(), code + "은 통로에 속하지 않는다");
                assertThrows(IllegalStateException.class, location::aisleRef);
            }
        }

        @Test
        @DisplayName("설비 코드는 자리가 아니다")
        void equipmentCodeIsNotLocation() {
            // CV-01, SC-A01, SRT-01 은 설비이지 화물이 놓이는 자리가 아니다
            assertThrows(IllegalArgumentException.class, () -> LocationCode.of("CV-01"));
            assertThrows(IllegalArgumentException.class, () -> LocationCode.of("SC-A01"));
            assertThrows(IllegalArgumentException.class, () -> LocationCode.of("SRT-01"));
        }
    }

    @Nested
    @DisplayName("형식 위반")
    class InvalidFormat {

        @Test
        @DisplayName("빈 값은 자리가 될 수 없다")
        void rejectsBlank() {
            assertThrows(NullPointerException.class, () -> LocationCode.of(null));
            assertThrows(IllegalArgumentException.class, () -> LocationCode.of("   "));
        }

        @Test
        @DisplayName("구분자가 다르면 만들어지지 않는다")
        void rejectsWrongSeparator() {
            assertThrows(IllegalArgumentException.class, () -> LocationCode.of("PND_A01"));
            assertThrows(IllegalArgumentException.class, () -> LocationCode.of("A/01/03/02"));
        }

        @Test
        @DisplayName("이름이 비슷해도 정의된 종류가 아니면 거부한다")
        void rejectsUnknownKind() {
            assertThrows(IllegalArgumentException.class, () -> LocationCode.of("PNDD-A01"));
            assertThrows(IllegalArgumentException.class, () -> LocationCode.of("PND-01"));
            assertThrows(IllegalArgumentException.class, () -> LocationCode.of("CHUTE-A"));
        }

        @Test
        @DisplayName("사유에 원래 입력값이 남는다")
        void reportsOriginalInput() {
            var e = assertThrows(IllegalArgumentException.class,
                    () -> LocationCode.of("A-1-3-2"));

            assertTrue(e.getMessage().contains("A-1-3-2"), "무엇이 잘못됐는지 알 수 있어야 한다");
        }
    }

    @Nested
    @DisplayName("담당 크레인 도출")
    class CraneDerivation {

        @Test
        @DisplayName("랙 주소에서 크레인 코드가 나온다")
        void derivesFromRackAddress() {
            assertEquals("SC-A01", LocationCode.of("A-01-03-02").craneCode());
            assertEquals("SC-B12", LocationCode.of("B-12-05-01").craneCode());
        }

        @Test
        @DisplayName("P&D도 통로에 붙어 있어 담당 크레인이 나온다")
        void derivesFromPnd() {
            assertEquals("SC-A01", LocationCode.of("PND-A01").craneCode());
            assertEquals("SC-B12", LocationCode.of("PND-B12").craneCode());
        }

        @Test
        @DisplayName("같은 통로의 자리는 같은 크레인이 담당한다")
        void sameAisleSharesCrane() {
            var high = LocationCode.of("A-01-03-02");
            var low  = LocationCode.of("A-01-08-05");
            var pnd  = LocationCode.of("PND-A01");

            assertTrue(high.sharesCraneWith(low));
            assertTrue(high.sharesCraneWith(pnd), "랙에서 꺼낸 화물은 같은 통로의 P&D로 내려간다");
        }

        @Test
        @DisplayName("통로가 다르면 다른 크레인이다")
        void differentAisleDiffersCrane() {
            var aisle1 = LocationCode.of("A-01-03-02");
            var aisle2 = LocationCode.of("A-02-03-02");

            assertFalse(aisle1.sharesCraneWith(aisle2));
            assertFalse(aisle1.sharesCraneWith(LocationCode.of("PND-A02")));
            assertEquals("SC-A02", aisle2.craneCode());
        }

        @Test
        @DisplayName("인덕션과 슈트는 어느 크레인에도 속하지 않는다")
        void downstreamHasNoCrane() {
            var induction = LocationCode.of("IND-01");
            var chute     = LocationCode.of("CHUTE-3");

            assertThrows(IllegalStateException.class, induction::craneCode);
            assertThrows(IllegalStateException.class, chute::craneCode);
            assertFalse(chute.sharesCraneWith(LocationCode.of("A-01-03-02")));
        }
    }

    @Nested
    @DisplayName("값 비교")
    class ValueEquality {

        @Test
        @DisplayName("코드가 같으면 같은 자리다")
        void equalByCode() {
            var one = LocationCode.of("A-01-03-02");
            var another = LocationCode.of("A-01-03-02");

            assertEquals(one, another);
            assertEquals(one.hashCode(), another.hashCode());
        }

        @Test
        @DisplayName("코드가 다르면 다른 자리다")
        void differsByCode() {
            assertFalse(LocationCode.of("A-01-03-02").equals(LocationCode.of("A-01-03-03")));
        }
    }

    @Test
    @DisplayName("한 화물의 이동 경로에 네 종류가 순서대로 나온다")
    void routeCoversEveryKind() {
        var rack      = LocationCode.of("A-01-03-02");
        var pnd       = LocationCode.of("PND-A01");
        var induction = LocationCode.of("IND-01");
        var chute     = LocationCode.of("CHUTE-3");

        assertEquals(LocationKind.RACK,      rack.kind());
        assertEquals(LocationKind.PND,       pnd.kind());
        assertEquals(LocationKind.INDUCTION, induction.kind());
        assertEquals(LocationKind.CHUTE,     chute.kind());

        // 크레인 구간은 같은 통로 안에서 끝나고, 그 뒤로는 통로와 무관해진다
        assertEquals(rack.craneCode(), pnd.craneCode());
        assertFalse(induction.belongsToAisle());
        assertFalse(chute.belongsToAisle());
    }
}
