package io.github.hhhjbot.wcs.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 지시를 메모리에 담는 보관소.
 *
 * <p>테스트와 콘솔 데모가 쓴다. 인터록 규칙을 검증하는 데 데이터베이스를 띄울
 * 이유가 없고, 띄우면 테스트가 느려지는 만큼 덜 돌리게 된다.
 *
 * <p>운영에서는 {@code JpaOrderRepository}가 같은 자리를 채운다.
 * 프로세스가 내려가면 여기 담긴 것은 사라진다.
 */
public final class InMemoryOrderRepository implements OrderRepository {

    private final List<OutboundOrder> orders = new ArrayList<>();

    @Override
    public void add(OutboundOrder order) {
        Objects.requireNonNull(order, "출고 지시는 필수입니다");

        if (find(order.orderNo()).isPresent()) {
            throw new IllegalArgumentException("이미 등록된 지시번호입니다: " + order.orderNo());
        }
        orders.add(order);
    }

    @Override
    public Optional<OutboundOrder> find(String orderNo) {
        Objects.requireNonNull(orderNo, "지시번호는 필수입니다");
        return orders.stream()
                .filter(order -> order.orderNo().equals(orderNo))
                .findFirst();
    }

    @Override
    public List<OutboundOrder> all() {
        return List.copyOf(orders);
    }

    @Override
    public List<OutboundOrder> byCutoff() {
        return orders.stream()
                .sorted(Comparator.comparing(OutboundOrder::cutoff)
                        .thenComparing(OutboundOrder::orderNo))
                .toList();
    }

    @Override
    public int size() {
        return orders.size();
    }

    @Override
    public void clear() {
        orders.clear();
    }

    @Override
    public String toString() {
        return "지시 %d건 (메모리)".formatted(orders.size());
    }
}
