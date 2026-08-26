package io.github.hhhjbot.wcs.app;

import io.github.hhhjbot.wcs.domain.EquipmentTask;
import io.github.hhhjbot.wcs.domain.OutboundFlow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 판단 주기를 돌리는 것.
 *
 * <p>WCS가 스스로 움직이게 하는 유일한 부품이다. 이것이 없으면 사람이
 * {@code POST /api/dispatch}를 눌러야 한 칸씩 나아간다.
 *
 * <pre>
 *   tick()  매 주기
 *     ├─ flow.dispatch()   나가는 길 — 조건이 갖춰진 작업을 설비에 내린다
 *     └─ flow.collect()    돌아오는 길 — 설비 상태를 읽어 작업을 옮긴다
 * </pre>
 *
 * <h3>왜 주기인가</h3>
 * PLC는 "끝났다"고 알려 주지 않는다. 상태 태그를 계속 읽어서 알아내야 하고,
 * 그 읽기는 HTTP 요청이 없어도 돌아야 한다. 설비는 사람이 버튼을 누르든 말든 움직이기 때문이다.
 * 그래서 요청에 얹지 못하고 독립된 스레드가 필요하다.
 *
 * <h3>얇게 둔다</h3>
 * 이 클래스는 <b>언제</b> 판단할지만 정하고 <b>무엇을</b> 할지는 정하지 않는다.
 * 하달 규칙은 {@link OutboundFlow}가, 상태 전이 규칙은
 * {@code TaskStatus}가 갖는다. 심장이지 뇌가 아니다.
 *
 * <p>판단을 여기 두면 규칙을 검증하는 데 스케줄러가 필요해진다. 지금은
 * {@code OutboundFlow}만 있으면 되므로 테스트가 스프링 없이 돈다.
 *
 * <h3>예외를 삼킨다</h3>
 * 한 주기에서 예외가 나가면 스프링 스케줄러는 <b>그 작업을 더 이상 돌리지 않는다.</b>
 * 설비 하나가 이상하다고 관제가 통째로 멈추면 안 되므로, 여기서 잡아 기록하고 다음 주기로 넘어간다.
 *
 * <h3>이 클래스가 잠금을 필요하게 만든다</h3>
 * 여기까지는 요청 스레드 하나뿐이었다. 이 폴러가 붙는 순간 스케줄러 스레드가
 * 같은 {@code TaskList}를 만지기 시작한다. {@code OutboundFlow}의
 * {@code synchronized}가 실제로 일하게 되는 지점이 여기다.
 */
@Component
@ConditionalOnProperty(name = "wcs.polling.enabled", havingValue = "true", matchIfMissing = true)
public class EquipmentPoller {

    private static final Logger log = LoggerFactory.getLogger(EquipmentPoller.class);

    private final OutboundFlow flow;

    public EquipmentPoller(OutboundFlow flow) {
        this.flow = Objects.requireNonNull(flow, "출고 흐름은 필수입니다");
    }

    /**
     * 한 주기.
     *
     * <p>{@code fixedDelay}는 <b>끝난 뒤부터</b> 세고 {@code fixedRate}는 시작 시각 기준으로 센다.
     * 주기가 밀리면 {@code fixedRate}는 밀린 만큼을 몰아서 실행하려 든다.
     * 설비를 상대로 그러면 명령이 몰려 나가므로 {@code fixedDelay}를 쓴다.
     */
    @Scheduled(fixedDelayString = "${wcs.polling.interval-ms:1000}")
    public void tick() {
        try {
            OutboundFlow.DispatchResult result = flow.dispatch();
            List<EquipmentTask> changed = flow.collect();

            if (result.dispatchedCount() > 0 || !changed.isEmpty()) {
                log.info("주기 — {} · 응답 {}건", result, changed.size());
            }
            for (EquipmentTask task : result.blocked()) {
                log.debug("대기 {} {}", task.getTaskNo(), task.getReason());
            }
            for (EquipmentTask task : result.failed()) {
                log.warn("하달 실패 {} {}", task.getTaskNo(), task.getReason());
            }
        } catch (RuntimeException e) {
            // 삼키지 않으면 스케줄러가 이 작업을 영영 멈춘다.
            log.error("판단 주기 실패. 다음 주기에 다시 시도합니다", e);
        }
    }
}
