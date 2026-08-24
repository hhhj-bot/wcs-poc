package io.github.hhhjbot.wcs.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 출고 지시 하나가 랙에서 슈트까지 흘러가는 전체 흐름을 검증한다.
 *
 * <p>단위 테스트가 규칙 하나를 검증한다면 이 클래스는 규칙들이 이어졌을 때
 * 업무가 흐르는지를 검증한다.
 *
 * <p>조율 계층은 아직 도메인 계층에 없으므로 흐름 구성은 아래 helper가 담당한다.
 * 해당 책임을 갖는 객체가 도입되면 helper 호출을 그 객체로 대체한다.
 *
 * <pre>
 *   routeOf         설비 경로 구성          → Route / Hop
 *   taskOf          홉별 설비 작업 생성       → OutboundFlow
 *   inFlightCount   설비별 진행 중 작업 계수   → TaskList   (ADR-0009)
 *   완료 후 다음 작업 생성 순서               → OutboundFlow
 * </pre>
 *
 * <h3>시나리오 목록</h3>
 * <pre>
 *   1. 지시 1건이 설비 셋을 거쳐 완료된다            구현됨
 *   2. 설비마다 동시에 받을 수 있는 수가 다르다       구현됨
 *   3. 컷오프가 임박한 지시가 먼저 하달된다           4단계
 *   4. P&amp;D 점유 시 BLOCKED, 해소 후 재시도        5단계
 *   5. 리딩 실패는 리젝트 슈트로 배출                7단계
 *   6. 슈트 만재 시 재순환 후 재시도                 7단계
 * </pre>
 */
class OutboundScenarioTest {

    // ------------------------------------------------------------------
    // 설비 구성 — 3단계에서 application.yml 로 분리한다
    // ------------------------------------------------------------------

    private static final Equipment CRANE    = new Equipment("SC-A01", 1);   // 포크 1개
    private static final Equipment CONVEYOR = new Equipment("CV-01", 8);    // 존 8개
    private static final Equipment SORTER   = new Equipment("SRT-01", 24);  // 캐리어 24개

    /** 크레인과 컨베이어가 화물을 주고받는 자리. */
    private static final String PND       = "PND-A01";
    /** 컨베이어에서 소터로 화물을 태우는 자리. */
    private static final String INDUCTION = "IND-01";

    // ------------------------------------------------------------------
    // helper
    // ------------------------------------------------------------------

    /** 홉 하나. 어느 설비가 어디서 어디로 옮기는가. → Route / Hop */
    private record Hop(String equipmentCode, String from, String to) { }

    /**
     * 출발 로케이션과 목적 슈트로 설비 경로를 구성한다.
     *
     * <p>담당 크레인은 {@link LocationCode#craneCode()} 로 출발 주소에서 도출한다. (ADR-0010)
     * 경로는 조건문이 아니라 목록이므로 설비가 늘면 항목이 하나 는다. (ADR-0008)
     */
    private static List<Hop> routeOf(LocationCode source, LocationCode chute) {
        return List.of(
                new Hop(source.craneCode(), source.value(), PND),
                new Hop(CONVEYOR.code(),    PND,            INDUCTION),
                new Hop(SORTER.code(),      INDUCTION,      chute.value())
        );
    }

    /** 경로의 {@code seq} 번째 홉으로 설비 작업을 만든다. → OutboundFlow */
    private static EquipmentTask taskOf(String orderNo, String loadId, List<Hop> route, int seq) {
        Hop hop = route.get(seq - 1);
        return new EquipmentTask(TaskNo.of(orderNo, seq), hop.equipmentCode(), loadId,
                LocationCode.of(hop.from()), LocationCode.of(hop.to()));
    }

    /** 하달 → 수신확인 → 실행 → 완료. 설비 한 대와의 핸드셰이크 한 번에 해당한다. */
    private static void runToCompletion(EquipmentTask task) {
        task.transitionTo(TaskStatus.QUEUED);
        task.transitionTo(TaskStatus.SENT);       // CMD 기록 후 트리거 상승
        task.transitionTo(TaskStatus.ACKED);      // 설비 수신확인
        task.transitionTo(TaskStatus.EXECUTING);  // 설비 동작 중
        task.transitionTo(TaskStatus.COMPLETED);  // 완료 신호 수신
    }

    /** 해당 설비에 진행 중인 작업 수. → TaskList (ADR-0009) */
    private static int inFlightCount(List<EquipmentTask> all, String equipmentCode) {
        return (int) all.stream()
                .filter(t -> t.getEquipmentCode().equals(equipmentCode))
                .filter(EquipmentTask::isInFlight)
                .count();
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("시나리오 1 — 지시 하나가 랙에서 슈트까지 간다")
    class OneOrderFromRackToChute {

        @Test
        @DisplayName("설비 셋을 순서대로 거쳐 완료된다")
        void flowsThroughThreeEquipments() {
            // 상위 시스템이 내려준 지시 한 건. 네 값은 OutboundOrder 도입 시 한 객체가 된다.
            String orderNo = "TO-00001";
            String loadId  = "CS-9001";
            var source = LocationCode.of("A-01-03-02");
            var chute  = LocationCode.of("CHUTE-3");

            var route = routeOf(source, chute);
            var done  = new ArrayList<EquipmentTask>();

            // 크레인 — 랙에서 꺼내 P&D에 내려놓는다
            var crane = taskOf(orderNo, loadId, route, 1);
            assertEquals("SC-A01", crane.getEquipmentCode(), "출발 로케이션이 담당 크레인을 결정한다");
            assertEquals(source.craneCode(), crane.getEquipmentCode());
            runToCompletion(crane);
            done.add(crane);

            // 컨베이어 — 크레인이 P&D에 내려놓은 뒤에야 가져갈 화물이 생긴다.
            // 이 선후 관계는 OutboundFlow 도입 시 코드로 강제한다.
            assertEquals(TaskStatus.COMPLETED, crane.getStatus());
            var conveyor = taskOf(orderNo, loadId, route, 2);
            runToCompletion(conveyor);
            done.add(conveyor);

            // 소터 — 인덕션에서 태워 슈트로 배출한다
            assertEquals(TaskStatus.COMPLETED, conveyor.getStatus());
            var sorter = taskOf(orderNo, loadId, route, 3);
            runToCompletion(sorter);
            done.add(sorter);

            // 지시 하나가 설비 작업 셋으로 나뉜다
            assertEquals(3, done.size());
            assertEquals(List.of("TO-00001-1", "TO-00001-2", "TO-00001-3"),
                    done.stream().map(t -> t.getTaskNo().value()).toList());
        }

        @Test
        @DisplayName("앞 작업의 목적지가 다음 작업의 출발지가 된다")
        void handoverPointsAreChained() {
            var route = routeOf(LocationCode.of("A-01-03-02"), LocationCode.of("CHUTE-3"));

            var crane    = taskOf("TO-00001", "CS-9001", route, 1);
            var conveyor = taskOf("TO-00001", "CS-9001", route, 2);
            var sorter   = taskOf("TO-00001", "CS-9001", route, 3);

            assertEquals(crane.getTo(),    conveyor.getFrom(), "P&D에서 인수인계된다");
            assertEquals(conveyor.getTo(), sorter.getFrom(),   "인덕션에서 소터로 태운다");
            assertEquals(LocationCode.of("CHUTE-3"), sorter.getTo());
        }

        @Test
        @DisplayName("화물 번호는 설비를 옮겨 다녀도 바뀌지 않는다")
        void loadIdSurvivesEveryHop() {
            var route = routeOf(LocationCode.of("A-01-03-02"), LocationCode.of("CHUTE-3"));

            for (int seq = 1; seq <= 3; seq++) {
                assertEquals("CS-9001", taskOf("TO-00001", "CS-9001", route, seq).getLoadId());
            }
        }
    }

    @Nested
    @DisplayName("시나리오 2 — 설비마다 받을 수 있는 수가 다르다")
    class EquipmentCapacityDiffers {

        @Test
        @DisplayName("크레인이 한 건을 물고 있으면 다음 지시는 기다린다")
        void craneTakesOneAtATime() {
            var route = routeOf(LocationCode.of("A-01-03-02"), LocationCode.of("CHUTE-3"));
            var all   = new ArrayList<EquipmentTask>();

            var first = taskOf("TO-00001", "CS-9001", route, 1);
            first.transitionTo(TaskStatus.QUEUED);
            first.transitionTo(TaskStatus.SENT);
            all.add(first);

            assertFalse(CRANE.canAccept(inFlightCount(all, "SC-A01")),
                    "포크가 하나뿐이라 두 번째 작업을 받을 수 없다");

            first.transitionTo(TaskStatus.ACKED);
            first.transitionTo(TaskStatus.EXECUTING);
            first.transitionTo(TaskStatus.COMPLETED);
            assertTrue(CRANE.canAccept(inFlightCount(all, "SC-A01")));
        }

        @Test
        @DisplayName("컨베이어는 여러 화물을 동시에 싣는다")
        void conveyorCarriesManyAtOnce() {
            var route = routeOf(LocationCode.of("A-01-03-02"), LocationCode.of("CHUTE-3"));
            var all   = new ArrayList<EquipmentTask>();

            // 서로 다른 지시 다섯 건이 컨베이어에 올라가 있는 상태
            for (int i = 1; i <= 5; i++) {
                var task = taskOf("TO-0000" + i, "CS-900" + i, route, 2);
                task.transitionTo(TaskStatus.QUEUED);
                task.transitionTo(TaskStatus.SENT);
                all.add(task);
            }

            assertEquals(5, inFlightCount(all, "CV-01"));
            assertTrue(CONVEYOR.canAccept(inFlightCount(all, "CV-01")), "존이 8개라 세 건 더 받는다");
            assertEquals(3, CONVEYOR.availableSlots(inFlightCount(all, "CV-01")));

            // 동일 상황을 크레인 기준으로 판정하면 처리량이 수십 분의 일이 된다
            assertFalse(CRANE.canAccept(inFlightCount(all, "CV-01")));
        }

        @Test
        @DisplayName("소터는 캐리어 수만큼 받는다")
        void sorterCarriesOnePerCarrier() {
            assertTrue(SORTER.canAccept(23));
            assertFalse(SORTER.canAccept(24), "캐리어가 24개뿐이다");
        }
    }
}
