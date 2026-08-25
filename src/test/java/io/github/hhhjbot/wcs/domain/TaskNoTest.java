package io.github.hhhjbot.wcs.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 작업 번호의 조립과 해석 검증.
 *
 * <p>확인하려는 것은 두 가지다. 문자열을 자르지 않고 지시번호를 꺼낼 수 있는지,
 * 그리고 형식이 어긋난 번호가 만들어지지 않는지.
 */
class TaskNoTest {

    @Nested
    @DisplayName("생성")
    class Creation {

        @Test
        @DisplayName("지시번호와 순번으로 만든다")
        void buildsFromParts() {
            var taskNo = TaskNo.of("TO-00001", 2);

            assertEquals("TO-00001", taskNo.orderNo());
            assertEquals(2, taskNo.seq());
            assertEquals("TO-00001-2", taskNo.value());
        }

        @Test
        @DisplayName("소문자와 앞뒤 공백은 정리한다")
        void normalizesOrderNo() {
            assertEquals(TaskNo.of("TO-00001", 1), TaskNo.of("  to-00001 ", 1));
        }

        @Test
        @DisplayName("지시번호가 비어 있으면 만들어지지 않는다")
        void rejectsBlankOrderNo() {
            assertThrows(NullPointerException.class, () -> TaskNo.of(null, 1));
            assertThrows(IllegalArgumentException.class, () -> TaskNo.of("   ", 1));
        }

        @Test
        @DisplayName("순번이 1 미만이면 만들어지지 않는다")
        void rejectsInvalidSeq() {
            assertThrows(IllegalArgumentException.class, () -> TaskNo.of("TO-00001", 0));
            assertThrows(IllegalArgumentException.class, () -> TaskNo.of("TO-00001", -1));
        }
    }

    @Nested
    @DisplayName("해석")
    class Parsing {

        @Test
        @DisplayName("문자열을 지시번호와 순번으로 나눈다")
        void splitsIntoParts() {
            var taskNo = TaskNo.parse("TO-00001-3");

            assertEquals("TO-00001", taskNo.orderNo());
            assertEquals(3, taskNo.seq());
        }

        @Test
        @DisplayName("지시번호에 하이픈이 여러 개 있어도 순번은 맨 뒤다")
        void splitsAtLastHyphen() {
            var taskNo = TaskNo.parse("WH1-TO-00001-2");

            assertEquals("WH1-TO-00001", taskNo.orderNo(), "지시번호 형식은 상위 시스템이 정한다");
            assertEquals(2, taskNo.seq());
        }

        @Test
        @DisplayName("순번 자리가 숫자가 아니면 예외")
        void rejectsNonNumericSeq() {
            var e = assertThrows(IllegalArgumentException.class, () -> TaskNo.parse("TO-00001-A"));
            assertTrue(e.getMessage().contains("TO-00001-A"), "무엇이 잘못됐는지 알 수 있어야 한다");
        }

        @Test
        @DisplayName("구분자가 없으면 예외")
        void rejectsMissingSeparator() {
            assertThrows(NullPointerException.class, () -> TaskNo.parse(null));
            assertThrows(IllegalArgumentException.class, () -> TaskNo.parse("TO00001"));
            assertThrows(IllegalArgumentException.class, () -> TaskNo.parse("TO-00001-"));
        }

        @Test
        @DisplayName("만든 값과 해석한 값이 같다")
        void roundTrips() {
            var made = TaskNo.of("TO-00001", 2);

            assertEquals(made, TaskNo.parse(made.value()));
        }
    }

    @Nested
    @DisplayName("지시 단위로 묶기")
    class Grouping {

        @Test
        @DisplayName("같은 지시에서 나온 작업인지 판정한다")
        void detectsSameOrder() {
            var crane = TaskNo.of("TO-00001", 1);
            var conveyor = TaskNo.of("TO-00001", 2);
            var otherOrder = TaskNo.of("TO-00002", 1);

            assertTrue(crane.sameOrder(conveyor), "구간은 달라도 같은 화물이다");
            assertFalse(crane.sameOrder(otherOrder));
        }

        @Test
        @DisplayName("다음 구간의 번호를 얻는다")
        void movesToNextLeg() {
            assertEquals(TaskNo.of("TO-00001", 2), TaskNo.of("TO-00001", 1).next());
        }
    }

    @Nested
    @DisplayName("정렬")
    class Ordering {

        @Test
        @DisplayName("지시번호 순, 같으면 순번 순")
        void sortsByOrderThenSeq() {
            var sorted = List.of(
                            TaskNo.of("TO-00002", 1),
                            TaskNo.of("TO-00001", 3),
                            TaskNo.of("TO-00001", 1))
                    .stream().sorted().map(TaskNo::value).toList();

            assertEquals(List.of("TO-00001-1", "TO-00001-3", "TO-00002-1"), sorted);
        }
    }

    @Test
    @DisplayName("같은 지시번호와 순번이면 같은 작업 번호다")
    void valueEquality() {
        assertEquals(TaskNo.of("TO-00001", 1), TaskNo.of("TO-00001", 1));
        assertEquals(TaskNo.of("TO-00001", 1).hashCode(), TaskNo.of("TO-00001", 1).hashCode());
        assertFalse(TaskNo.of("TO-00001", 1).equals(TaskNo.of("TO-00001", 2)));
    }
}
