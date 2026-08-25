package io.github.hhhjbot.wcs.config;

import io.github.hhhjbot.wcs.domain.Equipment;
import io.github.hhhjbot.wcs.domain.EquipmentTask;
import io.github.hhhjbot.wcs.domain.LocationCode;
import io.github.hhhjbot.wcs.domain.OutboundOrder;
import io.github.hhhjbot.wcs.domain.TaskList;
import io.github.hhhjbot.wcs.domain.WarehouseLayout;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
     * 작업 저장소. 지금은 메모리 위의 목록이다.
     *
     * <p>싱글턴 빈이므로 애플리케이션 전체가 같은 목록을 본다.
     * 3단계에서 DB 구현으로 바꿔 끼울 자리다.
     */
    @Bean
    public TaskList taskList() {
        return new TaskList();
    }

    /**
     * 뜰 때 샘플 지시 두 건을 작업으로 펼쳐 넣는다.
     *
     * <p>조회 API를 확인하기 위한 임시 코드다.
     * {@code OutboundFlow}가 생기면 이 부분이 그 호출로 바뀐다.
     */
    @Bean
    public CommandLineRunner sampleData(WarehouseLayout layout, TaskList tasks) {
        return args -> {
            LocalDateTime cutoff = LocalDateTime.of(LocalDate.now(), LocalTime.of(16, 0));

            expand(layout, tasks, new OutboundOrder(
                    "TO-00001", "CS-9001",
                    LocationCode.of("A-01-03-02"), LocationCode.of("CHUTE-3"), cutoff));

            expand(layout, tasks, new OutboundOrder(
                    "TO-00002", "CS-9002",
                    LocationCode.of("A-02-01-04"), LocationCode.of("CHUTE-1"),
                    cutoff.plusHours(1)));
        };
    }

    /** 지시 하나를 홉 수만큼의 설비 작업으로 펼친다. */
    private static void expand(WarehouseLayout layout, TaskList tasks, OutboundOrder order) {
        var route = layout.routeFor(order.source(), order.plannedChute());
        for (int seq = 1; seq <= route.size(); seq++) {
            tasks.add(EquipmentTask.of(order, seq, route.at(seq)));
        }
    }
}
