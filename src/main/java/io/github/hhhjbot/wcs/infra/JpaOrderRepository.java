package io.github.hhhjbot.wcs.infra;

import io.github.hhhjbot.wcs.domain.OrderRepository;
import io.github.hhhjbot.wcs.domain.OutboundOrder;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 지시를 데이터베이스에 담는 보관소.
 *
 * <p>{@link io.github.hhhjbot.wcs.domain.InMemoryOrderRepository}와 같은 자리를 채운다.
 * {@code OutboundFlow}는 둘 중 무엇이 꽂혔는지 모르고, 알 필요도 없다.
 *
 * <pre>
 *   OutboundFlow ──▶ OrderRepository ──▶ JpaOrderRepository ──▶ OrderJpaRepository ──▶ H2
 *                    (도메인의 약속)      (변환 담당)            (Spring이 만든 것)
 * </pre>
 *
 * <h3>왜 한 겹을 더 두는가</h3>
 * {@code OrderJpaRepository}를 {@code OrderRepository}로 바로 쓰게 할 수도 있다.
 * 그러면 이 클래스가 사라지는 대신 도메인이 {@code OrderEntity}를 알게 된다.
 * 이 클래스가 하는 일이 정확히 그 지점의 번역이다.
 *
 * <pre>
 *   들어올 때   OutboundOrder → OrderEntity
 *   나갈 때     OrderEntity   → OutboundOrder
 * </pre>
 *
 * <h3>중복 검사를 여기서 하는 이유</h3>
 * {@code save()}는 같은 키가 있으면 조용히 갱신한다(upsert).
 * 그런데 업무 규칙은 "같은 지시번호는 두 번 받지 않는다"이므로, 덮어쓰는 것이 아니라
 * 거절해야 한다. 메모리 구현이 던지는 것과 같은 예외를 여기서도 던져
 * <b>어느 구현을 꽂아도 같은 규칙이 보이게</b> 맞춘다.
 */
@Repository
public class JpaOrderRepository implements OrderRepository {

    private final OrderJpaRepository jpa;

    /** 생성자 주입. Spring이 만든 구현체가 여기로 들어온다. */
    public JpaOrderRepository(OrderJpaRepository jpa) {
        this.jpa = Objects.requireNonNull(jpa, "JPA 저장소는 필수입니다");
    }

    @Override
    public void add(OutboundOrder order) {
        Objects.requireNonNull(order, "출고 지시는 필수입니다");

        if (jpa.existsById(order.orderNo())) {
            throw new IllegalArgumentException("이미 등록된 지시번호입니다: " + order.orderNo());
        }
        jpa.save(OrderEntity.from(order));
    }

    @Override
    public Optional<OutboundOrder> find(String orderNo) {
        Objects.requireNonNull(orderNo, "지시번호는 필수입니다");
        return jpa.findById(orderNo).map(OrderEntity::toDomain);
    }

    @Override
    public List<OutboundOrder> all() {
        return jpa.findAll().stream()
                .map(OrderEntity::toDomain)
                .toList();
    }

    @Override
    public List<OutboundOrder> byCutoff() {
        // 정렬을 자바가 아니라 데이터베이스가 한다
        return jpa.findAllByOrderByCutoffAscOrderNoAsc().stream()
                .map(OrderEntity::toDomain)
                .toList();
    }

    @Override
    public int size() {
        return (int) jpa.count();
    }

    @Override
    public String toString() {
        return "지시 보관소 (H2)";
    }
}
