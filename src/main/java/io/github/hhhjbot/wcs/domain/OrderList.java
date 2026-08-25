package io.github.hhhjbot.wcs.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 상위 시스템에서 받은 출고 지시 전체.
 *
 * <p>작업은 자기 구간만 안다. 크레인 작업의 목적지는 P&amp;D이므로,
 * 그 화물이 어느 슈트로 갈 예정인지와 언제까지 나가야 하는지는 지시가 갖고 있다.
 * {@link TaskNo#orderNo()}로 여기를 찾아온다.
 *
 * <p>{@link TaskList}와 같은 성격의 보관소다. 담고, 찾아주고, 정렬해 줄 뿐
 * 무엇을 먼저 처리할지는 정하지 않는다.
 */
public final class OrderList {

    private final List<OutboundOrder> orders = new ArrayList<>();

    /**
     * 지시를 받는다.
     *
     * @throws IllegalArgumentException 같은 지시번호가 이미 있을 때
     */
    public void add(OutboundOrder order) {
        Objects.requireNonNull(order, "출고 지시는 필수입니다");

        if (find(order.orderNo()).isPresent()) {
            throw new IllegalArgumentException("이미 등록된 지시번호입니다: " + order.orderNo());
        }
        orders.add(order);
    }

    public Optional<OutboundOrder> find(String orderNo) {
        Objects.requireNonNull(orderNo, "지시번호는 필수입니다");
        return orders.stream()
                .filter(order -> order.orderNo().equals(orderNo))
                .findFirst();
    }

    /** 작업 번호로 그 작업이 속한 지시를 찾는다. */
    public Optional<OutboundOrder> findByTask(TaskNo taskNo) {
        Objects.requireNonNull(taskNo, "작업 번호는 필수입니다");
        return find(taskNo.orderNo());
    }

    /** 등록된 지시 전체. 등록 순이다. */
    public List<OutboundOrder> all() {
        return List.copyOf(orders);
    }

    /**
     * 컷오프가 이른 순.
     *
     * <p>정렬해 주기만 한다. 이 순서로 하달할지는 하달 정책이 정한다.
     */
    public List<OutboundOrder> byCutoff() {
        return orders.stream()
                .sorted(Comparator.comparing(OutboundOrder::cutoff)
                        .thenComparing(OutboundOrder::orderNo))
                .toList();
    }

    public int size() {
        return orders.size();
    }

    @Override
    public String toString() {
        return "지시 %d건".formatted(orders.size());
    }
}
