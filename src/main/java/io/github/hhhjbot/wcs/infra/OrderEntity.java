package io.github.hhhjbot.wcs.infra;

import io.github.hhhjbot.wcs.domain.LocationCode;
import io.github.hhhjbot.wcs.domain.OutboundOrder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 출고 지시를 테이블 한 줄로 옮긴 것.
 *
 * <h3>왜 {@link OutboundOrder}에 직접 애너테이션을 붙이지 않는가</h3>
 * 붙일 수가 없다. {@code OutboundOrder}는 {@code record}라서
 * 필드가 {@code final}이고 인자 없는 생성자가 없는데, Hibernate는 둘 다 요구한다.
 *
 * <p>억지로 맞추려면 record를 일반 클래스로 되돌리고, {@code final}을 떼고,
 * 빈 생성자를 열고, 검증을 하는 compact 생성자를 포기해야 한다.
 * 즉 <b>저장 기술의 사정 때문에 업무 규칙이 무너진다.</b>
 * 그래서 저장용 클래스를 따로 두고 경계에서 변환한다.
 *
 * <pre>
 *   OutboundOrder          OrderEntity
 *   ─────────────          ───────────
 *   record, 불변           class, 가변
 *   생성자에서 검증        검증 없음
 *   LocationCode           String
 *   업무 규칙이 안다        데이터베이스가 안다
 * </pre>
 *
 * <p>변환 비용이 드는 대신 얻는 것은, 도메인이 Hibernate를 모른 채로 남는다는 점이다.
 * 나중에 저장 기술을 바꿔도 바뀌는 것은 이 패키지뿐이다.
 *
 * <h3>{@link LocationCode}를 문자열로 푸는 이유</h3>
 * 주소는 값 객체지만 테이블에는 글자로 들어간다. 읽을 때 {@code LocationCode.of()}로
 * 다시 조립하면서 형식 검증이 한 번 더 걸리므로, 손으로 고친 잘못된 값이 있으면 여기서 걸린다.
 */
@Entity
@Table(name = "outbound_order")
public class OrderEntity {

    @Id
    @Column(name = "order_no", length = 32)
    private String orderNo;

    @Column(name = "load_id", nullable = false, length = 32)
    private String loadId;

    @Column(name = "source_code", nullable = false, length = 32)
    private String sourceCode;

    @Column(name = "chute_code", nullable = false, length = 32)
    private String chuteCode;

    /**
     * 컷오프. 컬럼명은 {@code cutoff_at}이지만 필드명은 {@code cutoff}다.
     *
     * <p>조회 메서드 이름을 지을 때 기준이 되는 것은 컬럼이 아니라 <b>필드</b>다.
     * {@code findAllByOrderByCutoffAsc...}가 되는 이유다.
     */
    @Column(name = "cutoff_at", nullable = false)
    private LocalDateTime cutoff;

    /**
     * Hibernate 전용.
     *
     * <p>Hibernate는 조회할 때 인자 없는 생성자로 빈 객체를 만든 뒤 필드를 하나씩 채워 넣는다.
     * 그래서 이 생성자가 없으면 기동 시점에 오류가 난다.
     * {@code protected}로 둔 것은 업무 코드가 실수로 빈 엔티티를 만들지 못하게 하기 위한 것이다.
     */
    protected OrderEntity() {
    }

    private OrderEntity(String orderNo, String loadId,
                        String sourceCode, String chuteCode, LocalDateTime cutoff) {
        this.orderNo = orderNo;
        this.loadId = loadId;
        this.sourceCode = sourceCode;
        this.chuteCode = chuteCode;
        this.cutoff = cutoff;
    }

    /** 도메인 → 테이블. */
    public static OrderEntity from(OutboundOrder order) {
        Objects.requireNonNull(order, "출고 지시는 필수입니다");
        return new OrderEntity(
                order.orderNo(),
                order.loadId(),
                order.source().value(),
                order.plannedChute().value(),
                order.cutoff());
    }

    /** 테이블 → 도메인. 여기서 {@code OutboundOrder}의 검증이 다시 걸린다. */
    public OutboundOrder toDomain() {
        return new OutboundOrder(
                orderNo,
                loadId,
                LocationCode.of(sourceCode),
                LocationCode.of(chuteCode),
                cutoff);
    }

    public String getOrderNo() {
        return orderNo;
    }

    @Override
    public String toString() {
        return "%s [%s] %s → %s".formatted(orderNo, loadId, sourceCode, chuteCode);
    }
}
