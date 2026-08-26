package io.github.hhhjbot.wcs.app;

import io.github.hhhjbot.wcs.domain.EquipmentTask;
import io.github.hhhjbot.wcs.domain.OutboundFlow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 판단 주기를 돌리는 것.
 *
 * <p>WCS가 스스로 움직이게 하는 유일한 부품이다. 이것이 멈추면 사람이
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
 * <h3>자동과 수동</h3>
 * 실물 관제반에도 자동운전과 수동이 있다. 조작 중이거나 이상을 살필 때는 자동을 끄고
 * 한 스텝씩 넣는다. 여기서도 같다 — {@link #setEnabled(boolean)}로 주기를 멈추면
 * 사람이 {@code POST /api/dispatch}로 한 주기씩 돌린다.
 *
 * <p>이 값을 {@code volatile}로 두는 것은, 바꾸는 쪽이 요청 스레드이고
 * 읽는 쪽이 스케줄러 스레드라 서로 다른 스레드이기 때문이다.
 * 잠그지 않으면 바꾼 값이 상대 스레드에 안 보일 수 있다.
 * 값 하나를 읽고 쓰는 것뿐이라 {@code synchronized}까지는 필요 없다.
 *
 * <h3>얇게 둔다</h3>
 * 이 클래스는 <b>언제</b> 판단할지만 정하고 <b>무엇을</b> 할지는 정하지 않는다.
 * 하달 규칙은 {@link OutboundFlow}가, 상태 전이 규칙은
 * {@code TaskStatus}가 갖는다. 심장이지 뇌가 아니다.
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
public class EquipmentPoller {

    private static final Logger log = LoggerFactory.getLogger(EquipmentPoller.class);

    private final OutboundFlow flow;

    /** 요청 스레드가 바꾸고 스케줄러 스레드가 읽는다. */
    private volatile boolean enabled;

    public EquipmentPoller(OutboundFlow flow,
                           @Value("${wcs.polling.enabled:true}") boolean enabled) {
        this.flow = Objects.requireNonNull(flow, "출고 흐름은 필수입니다");
        this.enabled = enabled;
    }

    /**
     * 한 주기.
     *
     * <p>{@code fixedDelay}는 <b>끝난 뒤부터</b> 세고 {@code fixedRate}는 시작 시각 기준으로 센다.
     * 주기가 밀리면 {@code fixedRate}는 밀린 만큼을 몰아서 실행하려 든다.
     * 설비를 상대로 그러면 명령이 몰려 나가므로 {@code fixedDelay}를 쓴다.
     *
     * <p>수동으로 돌린 경우에도 스케줄은 계속 뛰지만 여기서 곧장 돌아간다.
     * 스케줄 자체를 멈췄다 되살리는 것보다 이 편이 단순하다.
     */
    @Scheduled(fixedDelayString = "${wcs.polling.interval-ms:1000}")
    public void tick() {
        if (!enabled) {
            return;   // 수동 모드. 사람이 POST /api/dispatch 로 돌린다
        }
        runOnce();
    }

    /** 주기 한 번. 수동 하달도 결국 같은 것을 부른다. */
    private void runOnce() {
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

    /** 자동 주기가 도는 중인지. */
    public boolean isEnabled() {
        return enabled;
    }

    /** 자동 주기를 켜거나 끈다. 끄면 사람이 한 주기씩 돌린다. */
    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            log.info("자동 주기 {}", enabled ? "켬" : "끔 — 수동 모드");
        }
        this.enabled = enabled;
    }
}
