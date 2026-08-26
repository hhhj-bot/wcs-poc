package io.github.hhhjbot.wcs.web;

import io.github.hhhjbot.wcs.app.DemoScenario;
import io.github.hhhjbot.wcs.app.EquipmentPoller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * 운전 모드 조작.
 *
 * <pre>
 *   GET  /api/polling        지금 자동인지 수동인지
 *   POST /api/polling        자동/수동 전환
 *   POST /api/demo           시연 지시 한 묶음 추가
 *   POST /api/reset          처음 상태로 되돌림
 * </pre>
 *
 * <h3>화면 토글이 서버를 실제로 멈춘다</h3>
 * 버튼을 화면에서만 껐다 켜면 서버 폴러는 계속 돌므로, 수동으로 한 주기 눌러 봐야
 * 그 사이 자동 주기가 이미 지나가 버린다. 그래서 토글이 서버 상태를 바꾼다.
 *
 * <p>실물 관제반에도 자동운전과 수동이 있다. 조작 중이거나 이상을 살필 때 자동을 끄고
 * 한 스텝씩 넣는 것과 같은 것이다.
 *
 * <h3>초기화는 업무 기능이 아니다</h3>
 * {@code /api/reset}은 시연과 시험을 위한 것이다. 운영이라면 조작 권한 뒤에 두거나
 * 아예 두지 않는다. 슈트가 차서 막힌 상태를 푸는 방법이 아직 이것뿐이라 넣어 뒀다 —
 * 화물이 슈트를 떠나는 사건을 알려주는 입력이 생기면 그쪽이 정식 경로가 된다.
 */
@RestController
@RequestMapping("/api")
public class ControlController {

    private final EquipmentPoller poller;
    private final DemoScenario demo;

    public ControlController(EquipmentPoller poller, DemoScenario demo) {
        this.poller = Objects.requireNonNull(poller, "폴러는 필수입니다");
        this.demo = Objects.requireNonNull(demo, "시연 구성은 필수입니다");
    }

    @GetMapping("/polling")
    public PollingView polling() {
        return new PollingView(poller.isEnabled());
    }

    @PostMapping("/polling")
    public PollingView setPolling(@RequestBody PollingView request) {
        poller.setEnabled(request.enabled());
        return new PollingView(poller.isEnabled());
    }

    /**
     * 시연 지시 한 묶음을 밀어 넣는다.
     *
     * <p>누를 때마다 번호가 이어지고 컷오프가 한 시간씩 밀린다.
     * 쌓을수록 대기열이 길어져 인터록과 병목이 눈에 보인다.
     */
    @PostMapping("/demo")
    public LoadedView loadDemo() {
        List<String> orderNos = demo.load();
        return new LoadedView(orderNos.size(), orderNos);
    }

    @PostMapping("/reset")
    public PollingView reset() {
        demo.reset();
        return new PollingView(poller.isEnabled());
    }

    /** 자동 주기가 도는 중인지. */
    public record PollingView(boolean enabled) { }

    /** 방금 접수된 시연 지시. */
    public record LoadedView(int added, List<String> orderNos) { }
}
