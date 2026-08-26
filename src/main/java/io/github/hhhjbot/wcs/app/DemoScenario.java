package io.github.hhhjbot.wcs.app;

import io.github.hhhjbot.wcs.domain.LocationCode;
import io.github.hhhjbot.wcs.domain.OrderRepository;
import io.github.hhhjbot.wcs.domain.OutboundFlow;
import io.github.hhhjbot.wcs.domain.OutboundOrder;
import io.github.hhhjbot.wcs.domain.TaskList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 시연용 지시 묶음.
 *
 * <p>업무 코드가 아니다. 운영이라면 지시는 WMS가 내려주므로 이 클래스가 존재하지 않는다.
 * 기동할 때 한 묶음 넣어 두고, 화면에서 더 밀어 넣거나 처음으로 되돌리기 위한 것이다.
 *
 * <h3>한 묶음이 세 건인 이유</h3>
 * 두 건으로는 보이지 않는 것이 있다. 이 조합은 한 주기에 셋을 다 드러낸다.
 *
 * <pre>
 *   TO-00001   A-01-03-02 → CHUTE-3   16:00   먼저 등록, 컷오프 늦음
 *   TO-00002   A-01-05-01 → CHUTE-3   15:00   나중 등록, 컷오프 이름
 *   TO-00003   A-02-04-02 → CHUTE-1   17:00   다른 통로
 * </pre>
 *
 * <pre>
 *   우선순위   나중에 등록된 TO-00002 가 먼저 나간다 — 컷오프가 이르므로
 *   병렬       TO-00003 은 담당 크레인이 SC-A02 라 같은 주기에 나간다
 *   직렬화     둘이 인덕션(정원 1)에서 한 줄로 선다
 * </pre>
 *
 * <h3>여러 번 넣을 수 있다</h3>
 * 묶음마다 번호가 이어지고 컷오프가 한 시간씩 밀린다. 뒤에 온 물량이 나중 차량에
 * 실린다는 뜻이라, 눌러서 쌓을수록 대기열이 길어지고 인덕션이 병목인 것이 눈에 보인다.
 *
 * <p>번호를 이어 붙이지 않으면 두 번째 묶음에서 지시번호가 겹쳐 거절당한다.
 * 그 거절 자체는 {@link OrderRepository}가 지키는 규칙이라 맞는 동작이고,
 * 시연 편의를 위해 여기서 겹치지 않는 번호를 만든다.
 */
@Component
public class DemoScenario {

    private static final Logger log = LoggerFactory.getLogger(DemoScenario.class);

    /** 한 묶음의 지시 수. */
    private static final int BATCH_SIZE = 3;

    private final OutboundFlow flow;
    private final TaskList tasks;
    private final OrderRepository orders;

    /** 몇 번째 묶음인지. 번호와 컷오프를 밀어 주는 데 쓴다. */
    private final AtomicInteger batch = new AtomicInteger();

    public DemoScenario(OutboundFlow flow, TaskList tasks, OrderRepository orders) {
        this.flow = Objects.requireNonNull(flow, "출고 흐름은 필수입니다");
        this.tasks = Objects.requireNonNull(tasks, "작업 목록은 필수입니다");
        this.orders = Objects.requireNonNull(orders, "지시 보관소는 필수입니다");
    }

    /**
     * 시연 지시 한 묶음을 접수한다. 하달은 폴러가 가져간다.
     *
     * @return 접수된 지시번호
     */
    public List<String> load() {
        int n = batch.getAndIncrement();
        var accepted = new ArrayList<String>();

        for (OutboundOrder order : batchOf(n)) {
            flow.accept(order);
            accepted.add(order.orderNo());
        }
        log.info("시연 지시 {}건 접수 — {}", accepted.size(), accepted);
        return List.copyOf(accepted);
    }

    /**
     * 처음 상태로 되돌린다.
     *
     * <p>슈트에 화물이 쌓이면 정원(20)에 걸려 더 이상 배출되지 않는다. 실제로는
     * 사람이 치우거나 차량에 실어 비워지는데, <b>그 사건을 알려주는 입력이 아직 없다.</b>
     * 그것을 만들기 전까지 막힌 상태를 푸는 방법이 이것이다.
     *
     * <p>시험 중에도 쓸모가 있다. 여러 묶음을 넣어 보다가 상태가 엉키면 여기서 되돌린다.
     */
    public void reset() {
        tasks.clear();
        orders.clear();
        batch.set(0);
        load();
        log.info("초기 상태로 되돌림");
    }

    // ------------------------------------------------------------------

    /**
     * {@code n} 번째 묶음.
     *
     * <p>번호는 이어 붙이고 컷오프는 묶음마다 한 시간씩 민다.
     * 랙 위치도 조금씩 옮겨 같은 자리를 반복해 집지 않게 한다.
     */
    private static List<OutboundOrder> batchOf(int n) {
        LocalDateTime base = LocalDateTime.of(LocalDate.now(), LocalTime.of(15, 0))
                .plusHours(n);
        int first = n * BATCH_SIZE + 1;
        int level = n % 4 + 1;          // 랙 단. 1~4

        return List.of(
                // 먼저 등록되지만 컷오프가 늦다
                order(first, "A-01-03-%02d".formatted(level), "CHUTE-3", base.plusHours(1)),

                // 나중에 등록되지만 컷오프가 이르다 — 이쪽이 먼저 나간다
                order(first + 1, "A-01-05-%02d".formatted(level), "CHUTE-3", base),

                // 다른 통로라 담당 크레인이 다르다 — 함께 나간다
                order(first + 2, "A-02-04-%02d".formatted(level), "CHUTE-1", base.plusHours(2)));
    }

    private static OutboundOrder order(int seq, String source, String chute, LocalDateTime cutoff) {
        return new OutboundOrder(
                "TO-%05d".formatted(seq),
                "CS-%04d".formatted(9000 + seq),
                LocationCode.of(source),
                LocationCode.of(chute),
                cutoff);
    }
}
