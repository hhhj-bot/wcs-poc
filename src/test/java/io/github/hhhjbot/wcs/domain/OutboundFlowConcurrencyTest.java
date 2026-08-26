package io.github.hhhjbot.wcs.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 하달 주기가 여러 스레드에서 동시에 돌 때도 인터록이 지켜지는지 검증.
 *
 * <h3>왜 이 테스트가 필요한가</h3>
 * 운영에서 {@code dispatch()}를 부르는 곳은 하나가 아니다.
 *
 * <pre>
 *   Tomcat 요청 스레드     POST /api/dispatch
 *   스케줄러 스레드        설비 폴링 주기
 * </pre>
 *
 * <p>둘은 같은 {@link TaskList}를 만진다. 목록도 작업도 잠겨 있지 않으면
 * 두 스레드가 같은 순간에 "설비가 비었다"고 판단할 수 있다.
 *
 * <pre>
 *   스레드 A                          스레드 B
 *   inFlightCount("SC-A01") → 0
 *                                     inFlightCount("SC-A01") → 0
 *   canAccept(0) → true
 *                                     canAccept(0) → true
 *   SENT                              SENT      ← 포크 하나에 명령 두 건
 * </pre>
 *
 * <p>세는 것과 쓰는 것이 나뉘어 있으면 각 조각이 안전해도 조각 사이가 안전하지 않다.
 * 이를 check-then-act 이라 부르며, 잠가야 하는 단위는 메서드가 아니라 주기 전체다.
 *
 * <h3>실제로 관측된 것</h3>
 * 잠그기 전 상태에서 3000판을 돌린 결과다.
 *
 * <pre>
 *   예외가 난 판        1721 / 3000   (57%)
 *   정원이 뚫린 판         0 / 3000
 *   예외 종류           IllegalStateException 2001건
 * </pre>
 *
 * <p>정원 초과보다 먼저 나는 것은 <b>같은 작업을 두 스레드가 집는 것</b>이다.
 * 앞선 스레드가 이미 {@code SENT}로 옮긴 작업을 뒤 스레드가 다시 하달하려다
 * {@link TaskStatus#canTransitionTo(TaskStatus)}에 걸린다.
 *
 * <p>즉 상태 기계가 최악(정원 초과)을 상당 부분 막고 있었다. 의도한 것이 아니라
 * 단계 건너뛰기를 막으려고 넣은 규칙이 우연히 그 역할을 한 것이다.
 * 다만 막아준다고 괜찮은 것은 아니다. 이 예외가 스케줄러 스레드로 튀어나가면
 * 그 주기가 통째로 죽는다. 1초 주기 폴러가 절반 넘는 주기에서 실패하는 셈이다.
 *
 * <p>500판으로 줄여 돌리면 정원 초과도 2판 관측된다. 초반 판은 JIT 컴파일 전이라
 * 느리게 돌아 창이 넓어지기 때문이다. 경쟁 조건이 타이밍에 얼마나 매달려 있는지를
 * 보여주는 대목이고, 동시에 "몇 판 돌려서 통과했다"가 안전의 증거가 못 되는 이유다.
 *
 * <h3>읽는 법</h3>
 * 이 테스트는 경쟁을 <b>유도</b>한다. 여러 판을 돌려 한 번이라도 어긋나면 실패다.
 * 통과했다고 안전이 증명되지는 않지만, 실패하면 확실히 위험한 것이다.
 */
class OutboundFlowConcurrencyTest {

    /** 경쟁은 확률로 일어나므로 여러 판을 돌린다. */
    private static final int ROUNDS = 500;

    /** 같은 순간 하달을 시도하는 스레드 수. */
    private static final int THREADS = 4;

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 8, 28, 16, 0);

    private static WarehouseLayout layout() {
        return new WarehouseLayout(
                List.of(new Equipment("SC-A01", 1),
                        new Equipment("SC-A02", 1),
                        new Equipment("CV-01", 8),
                        new Equipment("SRT-01", 24)),
                LocationCode.of("IND-01"), "CV-01", "SRT-01");
    }

    private static OutboundOrder order(String orderNo, String loadId, String source) {
        return new OutboundOrder(orderNo, loadId,
                LocationCode.of(source), LocationCode.of("CHUTE-3"), CUTOFF);
    }

    @Test
    @DisplayName("여러 스레드가 동시에 하달해도 크레인 정원을 넘지 않는다")
    void concurrentDispatchRespectsEquipmentCapacity() throws InterruptedException {
        var breaches = new AtomicInteger();     // 정원 초과가 일어난 판 수
        var errors = new CopyOnWriteArrayList<Throwable>();

        for (int round = 0; round < ROUNDS; round++) {
            var tasks = new TaskList();
            var flow = new OutboundFlow(layout(), new InMemoryOrderRepository(), tasks);

            // 같은 통로에서 나가는 두 건. 담당 크레인이 SC-A01 로 같다.
            flow.accept(order("TO-00001", "CS-9001", "A-01-03-02"));
            flow.accept(order("TO-00002", "CS-9002", "A-01-05-01"));

            runTogether(flow, errors);

            long inFlight = tasks.byEquipment("SC-A01").stream()
                    .filter(EquipmentTask::isInFlight)
                    .count();
            if (inFlight > 1) {
                breaches.incrementAndGet();
            }
        }

        assertEquals(0, breaches.get(),
                "크레인 정원은 1인데 %d/%d 판에서 두 건이 동시에 나갔다".formatted(breaches.get(), ROUNDS));
        assertEquals(0, errors.size(),
                errors.isEmpty() ? "" : "동시 실행 중 예외: " + errors.get(0));
    }

    /**
     * 여러 스레드를 같은 순간에 출발시킨다.
     *
     * <p>스레드를 만들자마자 일을 시키면 먼저 만들어진 쪽이 먼저 끝나 버려 경쟁이 안 난다.
     * 빗장({@link CountDownLatch})을 두고 전부 준비된 뒤에 한꺼번에 풀어야 겹친다.
     */
    private static void runTogether(OutboundFlow flow, List<Throwable> errors)
            throws InterruptedException {
        var gate = new CountDownLatch(1);
        var finished = new CountDownLatch(THREADS);

        for (int i = 0; i < THREADS; i++) {
            Thread worker = new Thread(() -> {
                try {
                    gate.await();
                    flow.dispatch();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    finished.countDown();
                }
            });
            worker.start();
        }

        gate.countDown();   // 동시에 출발
        finished.await();
    }
}
