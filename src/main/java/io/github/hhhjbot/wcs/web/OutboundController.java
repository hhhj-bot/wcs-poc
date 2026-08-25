package io.github.hhhjbot.wcs.web;

import io.github.hhhjbot.wcs.domain.EquipmentTask;
import io.github.hhhjbot.wcs.domain.LocationCode;
import io.github.hhhjbot.wcs.domain.OutboundFlow;
import io.github.hhhjbot.wcs.domain.OutboundOrder;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 지시 접수와 하달.
 *
 * <pre>
 *   POST /api/orders     지시를 받아 설비 작업으로 펼친다
 *   POST /api/dispatch   한 주기 돌려 조건이 갖춰진 작업을 하달한다
 * </pre>
 *
 * <p>하달을 버튼으로 둔 이유는 한 단계씩 확인하기 위해서다.
 * 실제 운영에서는 {@code @Scheduled}가 스캔 주기마다 이것을 부른다.
 */
@RestController
@RequestMapping("/api")
public class OutboundController {

    private final OutboundFlow flow;

    public OutboundController(OutboundFlow flow) {
        this.flow = flow;
    }

    /** 출고 지시를 받는다. 지시 하나가 홉 수만큼의 작업이 된다. */
    @PostMapping("/orders")
    public AcceptResult accept(@RequestBody OrderRequest request) {
        List<EquipmentTask> created = flow.accept(request.toOrder());
        return new AcceptResult(
                request.orderNo(),
                created.size(),
                created.stream().map(task -> task.getTaskNo().value()).toList());
    }

    /** 한 주기. 하달된 것과 대기시킨 것을 돌려준다. */
    @PostMapping("/dispatch")
    public DispatchView dispatch() {
        OutboundFlow.DispatchResult result = flow.dispatch();
        return new DispatchView(
                result.dispatchedCount(),
                result.blockedCount(),
                result.dispatched().stream().map(DispatchedView::of).toList(),
                result.blocked().stream().map(BlockedView::of).toList());
    }

    // ------------------------------------------------------------------

    /**
     * 지시 요청.
     *
     * <p>컷오프를 문자열로 받는다. 예: {@code 2026-08-28T16:00}
     */
    public record OrderRequest(String orderNo, String loadId,
                               String source, String chute, String cutoff) {

        OutboundOrder toOrder() {
            return new OutboundOrder(orderNo, loadId,
                    LocationCode.of(source),
                    LocationCode.of(chute),
                    parseCutoff(cutoff));
        }

        private static LocalDateTime parseCutoff(String text) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("컷오프는 필수입니다");
            }
            try {
                return LocalDateTime.parse(text.trim());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "컷오프 형식이 아닙니다: '%s' (예 2026-08-28T16:00)".formatted(text));
            }
        }
    }

    /** 접수 결과. */
    public record AcceptResult(String orderNo, int taskCount, List<String> taskNos) { }

    /** 한 주기의 결과. */
    public record DispatchView(int dispatched, int blocked,
                               List<DispatchedView> dispatchedTasks,
                               List<BlockedView> blockedTasks) { }

    /** 하달된 작업. */
    public record DispatchedView(String taskNo, String equipmentCode, String from, String to) {

        static DispatchedView of(EquipmentTask task) {
            return new DispatchedView(task.getTaskNo().value(), task.getEquipmentCode(),
                    task.getFrom().value(), task.getTo().value());
        }
    }

    /** 대기시킨 작업과 사유. */
    public record BlockedView(String taskNo, String equipmentCode, String reason, int retryCount) {

        static BlockedView of(EquipmentTask task) {
            return new BlockedView(task.getTaskNo().value(), task.getEquipmentCode(),
                    task.getReason(), task.getRetryCount());
        }
    }
}
