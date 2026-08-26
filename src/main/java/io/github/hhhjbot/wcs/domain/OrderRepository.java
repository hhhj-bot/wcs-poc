package io.github.hhhjbot.wcs.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 출고 지시 보관소.
 *
 * <p>작업은 자기 구간만 안다. 크레인 작업의 목적지는 P&amp;D이므로,
 * 그 화물이 어느 슈트로 갈 예정인지와 언제까지 나가야 하는지는 지시가 갖고 있다.
 * {@link TaskNo#orderNo()}로 여기를 찾아온다.
 *
 * <h3>왜 인터페이스인가</h3>
 * 지시를 어디에 담아 두는지는 업무 규칙이 아니다. 메모리에 있든 데이터베이스에 있든
 * "컷오프가 이른 순으로 하달한다"는 규칙은 바뀌지 않는다.
 * 그래서 {@link OutboundFlow}는 담는 방법을 모르는 채로 담긴 것만 쓴다.
 *
 * <pre>
 *   OutboundFlow ──▶ OrderRepository          ← 규칙은 여기까지만 안다
 *                        ▲          ▲
 *          InMemoryOrderRepository   JpaOrderRepository
 *              (테스트·데모)              (운영)
 * </pre>
 *
 * <p>바꿔 끼울 수 있다는 것보다 중요한 것은, 이 선이 있어서 <b>도메인이
 * 데이터베이스를 모른 채로 테스트된다</b>는 점이다. 인터록 규칙을 검증하는 데
 * 데이터베이스를 띄울 이유가 없다.
 *
 * <h3>추상 메서드와 기본 메서드를 가르는 기준</h3>
 * 저장 방식을 알아야 답할 수 있는 것만 추상 메서드로 둔다.
 * {@link #findByTask(TaskNo)}처럼 다른 메서드에서 유도되는 것은 여기서 한 번만 쓴다.
 * 구현체마다 같은 코드를 반복할 이유가 없다.
 */
public interface OrderRepository {

    /**
     * 지시를 받는다.
     *
     * @throws IllegalArgumentException 같은 지시번호가 이미 있을 때
     */
    void add(OutboundOrder order);

    /** 지시번호로 찾는다. */
    Optional<OutboundOrder> find(String orderNo);

    /** 등록된 지시 전체. 등록 순이다. */
    List<OutboundOrder> all();

    /**
     * 컷오프가 이른 순.
     *
     * <p>정렬해 주기만 한다. 이 순서로 하달할지는 하달 정책이 정한다.
     *
     * <p>{@link #all()}을 받아 밖에서 정렬해도 결과는 같지만, 여기에 두면
     * 데이터베이스 구현이 {@code ORDER BY}로 넘길 수 있다. 건수가 늘면 차이가 난다.
     */
    List<OutboundOrder> byCutoff();

    int size();

    /**
     * 전부 지운다.
     *
     * <p>시연과 시험에서 처음 상태로 돌리기 위한 것이다. 업무 흐름에는 이런 순간이 없다.
     */
    void clear();

    /**
     * 작업 번호로 그 작업이 속한 지시를 찾는다.
     *
     * <p>저장 방식과 무관하게 지시번호를 떼어 내 찾는 것이므로 여기서 한 번만 정의한다.
     */
    default Optional<OutboundOrder> findByTask(TaskNo taskNo) {
        Objects.requireNonNull(taskNo, "작업 번호는 필수입니다");
        return find(taskNo.orderNo());
    }
}
