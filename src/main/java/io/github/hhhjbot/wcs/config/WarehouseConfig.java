package io.github.hhhjbot.wcs.config;

import io.github.hhhjbot.wcs.domain.Equipment;
import io.github.hhhjbot.wcs.domain.EquipmentGateway;
import io.github.hhhjbot.wcs.domain.LocationCode;
import io.github.hhhjbot.wcs.domain.OrderRepository;
import io.github.hhhjbot.wcs.domain.OutboundFlow;
import io.github.hhhjbot.wcs.domain.OutboundOrder;
import io.github.hhhjbot.wcs.domain.TaskList;
import io.github.hhhjbot.wcs.domain.WarehouseLayout;
import io.github.hhhjbot.wcs.infra.SimulatedEquipmentGateway;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 창고 구성과 저장소를 스프링 빈으로 등록한다.
 *
 * <p>도메인 클래스에 어노테이션을 붙이지 않고 여기서 조립하는 이유는,
 * 도메인이 스프링을 몰라야 프레임워크 없이 테스트되기 때문이다.
 * 조립은 바깥에서 한다.
 *
 * <p>설비 구성 값은 다음 단계에서 {@code application.yml}로 옮긴다.
 * 지금은 어디서 오는지만 분리해 두었다.
 */
@Configuration
@EnableScheduling   // EquipmentPoller 의 @Scheduled 를 켠다
public class WarehouseConfig {

    /**
     * 창고 배치. 어느 통로에 어느 설비가 서 있는지를 안다.
     *
     * <p>{@link WarehouseLayout}을 생성자 주입형으로 만들어 두었기 때문에
     * 여기서 값만 넣어주면 된다. 상수로 박았다면 이 단계에서 클래스를 뜯어야 했다.
     */
    @Bean
    public WarehouseLayout warehouseLayout() {
        return new WarehouseLayout(
                List.of(
                        new Equipment("SC-A01", 1),    // 스태커크레인 · 포크 1개
                        new Equipment("SC-A02", 1),
                        new Equipment("CV-01", 8),     // 반출 컨베이어 · 존 8개
                        new Equipment("SRT-01", 24)    // 소터 · 캐리어 24개
                ),
                LocationCode.of("IND-01"),
                "CV-01",
                "SRT-01");
    }

    /**
     * 작업 저장소. 아직 메모리 위의 목록이다.
     *
     * <p>싱글턴 빈이므로 애플리케이션 전체가 같은 목록을 본다.
     *
     * <p>지시는 데이터베이스로 옮겼는데 작업은 두고 있다. 작업은 지시와 창고 구성에서
     * 다시 펼칠 수 있는 파생물이라, 원본인 지시를 먼저 지킨 것이다.
     * 작업까지 옮기려면 기동 시 작업이 없는 지시를 찾아 다시 펼치는 규칙이 필요하다.
     */
    @Bean
    public TaskList taskList() {
        return new TaskList();
    }

    // 지시 보관소는 여기서 만들지 않는다.
    // JpaOrderRepository 에 @Repository 가 붙어 있어 스프링이 직접 등록한다.
    // 여기에 또 만들면 OrderRepository 타입 빈이 둘이 되어 기동할 때 죽는다.

    /**
     * 설비와 주고받는 통로. 지금은 설비가 없어 흉내 내는 구현이 꽂힌다.
     *
     * <p>실물 PLC를 붙일 때 이 메서드 하나만 바꾼다. {@code OutboundFlow}도
     * {@code EquipmentPoller}도 무엇이 꽂혔는지 모른다.
     *
     * <p>{@code readsToDone = 3} 은 한 구간이 세 주기(기본 1초 × 3)에 걸쳐
     * ACK → RUNNING → DONE 으로 나아간다는 뜻이다. 화면에서 눈으로 따라갈 수 있는 속도다.
     */
    @Bean
    public EquipmentGateway equipmentGateway() {
        return new SimulatedEquipmentGateway(3, null);
    }

    /**
     * 지시를 작업으로 펼치고 하달을 판단한다.
     *
     * <p>상태를 갖지 않으므로 싱글턴 빈 하나를 모두가 나눠 써도 안전하다.
     * 진행 상황은 작업 목록에만 있다.
     *
     * <p>{@code orders} 자리에 무엇이 꽂히는지는 이 클래스도 모른다.
     * 지금은 {@code JpaOrderRepository}가, 테스트에서는
     * {@code InMemoryOrderRepository}가 들어간다.
     */
    @Bean
    public OutboundFlow outboundFlow(WarehouseLayout layout, OrderRepository orders,
                                     TaskList tasks, EquipmentGateway gateway) {
        return new OutboundFlow(layout, orders, tasks, gateway);
    }

    /**
     * 뜰 때 샘플 지시 두 건을 받아 둔다.
     *
     * <p>하달은 하지 않는다. {@link io.github.hhhjbot.wcs.app.EquipmentPoller}가
     * 주기마다 알아서 가져간다.
     *
     * <p>폴러를 끄고 손으로 한 칸씩 보고 싶으면 {@code wcs.polling.enabled=false} 로 두고
     * {@code POST /api/dispatch} 를 누르면 된다.
     */
    @Bean
    public CommandLineRunner sampleData(OutboundFlow flow) {
        return args -> {
            LocalDateTime cutoff = LocalDateTime.of(LocalDate.now(), LocalTime.of(16, 0));

            flow.accept(new OutboundOrder(
                    "TO-00001", "CS-9001",
                    LocationCode.of("A-01-03-02"), LocationCode.of("CHUTE-3"), cutoff));

            flow.accept(new OutboundOrder(
                    "TO-00002", "CS-9002",
                    LocationCode.of("A-01-05-01"), LocationCode.of("CHUTE-3"),
                    cutoff.plusHours(1)));
        };
    }
}
