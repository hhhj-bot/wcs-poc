package io.github.hhhjbot.wcs.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 창고에 무엇이 어떻게 놓여 있는지.
 *
 * <p>출고 지시는 출발 랙과 목적 슈트만 갖는다. 그 사이를 어떻게 가는지는
 * 지시가 아니라 <b>창고를 어떻게 지었느냐</b>가 정한다. 그 지식을 담는 곳이다.
 *
 * <pre>
 *   A-01-03-02 ──SC-A01──▶ PND-A01 ──CV-01──▶ IND-01 ──SRT-01──▶ CHUTE-3
 * </pre>
 *
 * <h3>지시를 받지 않는 이유</h3>
 * {@link #routeFor(LocationCode, LocationCode)}는 출발지와 목적지만 받는다.
 * 지금 경로를 가르는 요인이 그 둘뿐이기 때문이다.
 * 지시를 통째로 받으면 이 클래스가 화물번호와 컷오프까지 알게 되는데,
 * 쓰지 않는 정보를 아는 것도 의존이다.
 * 대형품 전용 라인처럼 <b>화물 특성에 따라 경로가 갈리는</b> 구성이 생기면 그때 바꾼다.
 *
 * <h3>설비를 생성자로 받는 이유</h3>
 * 상수로 박으면 정원을 바꿔가며 시험할 수 없다. 정원 1짜리 컨베이어로
 * 인터록을 검증하려면 밖에서 넣을 수 있어야 한다.
 * 3단계에서 이 인자들이 {@code application.yml} 로 옮겨간다.
 *
 * <h3>지금의 한계</h3>
 * 컨베이어와 소터가 각각 한 대뿐이라 코드를 직접 갖는다.
 * 여러 대가 되면 "이 P&amp;D를 어느 컨베이어가 받나"가 조회가 되고,
 * 그때 이 클래스 안쪽만 바뀐다. 부르는 쪽은 그대로다.
 */
public final class WarehouseLayout {

    private final Map<String, Equipment> equipments;
    private final LocationCode induction;
    private final String conveyorCode;
    private final String sorterCode;

    /**
     * @param equipments  창고에 있는 설비 전체. 크레인은 담당 통로 규칙으로 찾으므로
     *                    코드가 {@code SC-A01} 형태여야 한다
     * @param induction   소터 인덕션 자리
     * @param conveyorCode 반출 컨베이어. 지금은 한 대뿐이다
     * @param sorterCode   소터. 지금은 한 대뿐이다
     */
    public WarehouseLayout(List<Equipment> equipments,
                           LocationCode induction,
                           String conveyorCode,
                           String sorterCode) {
        Objects.requireNonNull(equipments, "설비 목록은 필수입니다");
        if (equipments.isEmpty()) {
            throw new IllegalArgumentException("설비가 하나도 없습니다");
        }

        var byCode = new LinkedHashMap<String, Equipment>();
        for (Equipment equipment : equipments) {
            Objects.requireNonNull(equipment, "설비는 필수입니다");
            if (byCode.put(equipment.code(), equipment) != null) {
                throw new IllegalArgumentException("설비 코드가 중복됩니다: " + equipment.code());
            }
        }
        this.equipments = Map.copyOf(byCode);

        this.induction = Objects.requireNonNull(induction, "인덕션 자리는 필수입니다");
        if (induction.kind() != LocationKind.INDUCTION) {
            throw new IllegalArgumentException("인덕션 자리가 아닙니다: " + induction.value());
        }

        this.conveyorCode = requireRegistered(conveyorCode, "반출 컨베이어");
        this.sorterCode = requireRegistered(sorterCode, "소터");
    }

    // ------------------------------------------------------------------

    /**
     * 랙에서 슈트까지의 설비 경로.
     *
     * <p>담당 크레인은 출발 주소에서, 인계 지점은 그 통로에서 도출한다. (ADR-0010)
     * 설비가 늘면 홉 목록에 항목이 하나 는다. (ADR-0008)
     *
     * @throws IllegalArgumentException 출발지가 랙이 아니거나 목적지가 슈트가 아닐 때,
     *                                  또는 담당 크레인이 등록되어 있지 않을 때
     */
    public Route routeFor(LocationCode source, LocationCode chute) {
        Objects.requireNonNull(source, "출발지는 필수입니다");
        Objects.requireNonNull(chute, "목적 슈트는 필수입니다");

        if (!source.isRack()) {
            throw new IllegalArgumentException("출고는 랙에서 시작합니다: " + source.value());
        }
        if (chute.kind() != LocationKind.CHUTE) {
            throw new IllegalArgumentException("목적지가 슈트가 아닙니다: " + chute.value());
        }

        LocationCode pnd = source.pnd();

        return Route.of(List.of(
                new Route.Hop(equipment(source.craneCode()), source, pnd),
                new Route.Hop(equipment(conveyorCode), pnd, induction),
                new Route.Hop(equipment(sorterCode), induction, chute)));
    }

    /**
     * 코드로 설비를 찾는다.
     *
     * @throws IllegalArgumentException 등록되지 않은 코드일 때. 없는 통로로 작업이
     *                                  나가는 것보다 여기서 끊는 편이 낫다
     */
    public Equipment equipment(String code) {
        Objects.requireNonNull(code, "설비 코드는 필수입니다");
        Equipment found = equipments.get(code);
        if (found == null) {
            throw new IllegalArgumentException(
                    "등록되지 않은 설비입니다: %s (등록된 설비 %s)".formatted(code, equipments.keySet()));
        }
        return found;
    }

    /** 등록된 설비 전체. */
    public List<Equipment> equipments() {
        return List.copyOf(equipments.values());
    }

    @Override
    public String toString() {
        return "설비 %d대 · 인덕션 %s".formatted(equipments.size(), induction);
    }

    // ------------------------------------------------------------------

    private String requireRegistered(String code, String field) {
        Objects.requireNonNull(code, field + " 코드는 필수입니다");
        if (!equipments.containsKey(code)) {
            throw new IllegalArgumentException(
                    "%s가 설비 목록에 없습니다: %s".formatted(field, code));
        }
        return code;
    }
}
