package io.github.hhhjbot.wcs.app;

import io.github.hhhjbot.wcs.domain.Equipment;
import io.github.hhhjbot.wcs.domain.EquipmentTask;
import io.github.hhhjbot.wcs.domain.LocationCode;
import io.github.hhhjbot.wcs.domain.Route;
import io.github.hhhjbot.wcs.domain.TaskList;
import io.github.hhhjbot.wcs.domain.TaskNo;
import io.github.hhhjbot.wcs.domain.WarehouseLayout;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 조회 담당.
 *
 * <p>보관소({@link TaskList})와 창고 구성({@link WarehouseLayout})을 받아
 * 화면이 필요로 하는 형태로 합친다. 보관소는 자기 것만 알고,
 * 여러 개를 합치는 일은 이 계층이 맡는다.
 *
 * <p>상태를 갖지 않는다. 진행 상황은 이미 작업이 갖고 있으므로
 * 여기서 또 들고 있으면 두 값이 어긋날 수 있다. (ADR-0009와 같은 이유)
 */
@Service
public class TaskQueryService {

    private final TaskList tasks;
    private final WarehouseLayout layout;

    /**
     * 생성자 주입.
     *
     * <p>생성자가 하나뿐이면 {@code @Autowired} 를 붙이지 않아도 스프링이 주입한다.
     * 필드 주입 대신 생성자를 쓰는 이유는 두 가지다 —
     * 필드를 {@code final} 로 둘 수 있고, 스프링 없이도 {@code new} 로 만들어 테스트할 수 있다.
     */
    public TaskQueryService(TaskList tasks, WarehouseLayout layout) {
        this.tasks = tasks;
        this.layout = layout;
    }

    /** 등록된 작업 전체. */
    public List<EquipmentTask> allTasks() {
        return tasks.all();
    }

    /** 특정 설비의 작업. */
    public List<EquipmentTask> tasksOf(String equipmentCode) {
        return tasks.byEquipment(equipmentCode);
    }

    /** 한 출고 지시에서 나온 작업. 순번 순이다. */
    public List<EquipmentTask> tasksOfOrder(String orderNo) {
        return tasks.byOrder(orderNo);
    }

    /**
     * 작업 번호로 한 건.
     *
     * @throws NoSuchElementException 없는 번호일 때
     */
    public EquipmentTask task(String taskNo) {
        TaskNo no = TaskNo.parse(taskNo);
        return tasks.find(no)
                .orElseThrow(() -> new NoSuchElementException("작업을 찾을 수 없습니다: " + taskNo));
    }

    /**
     * 설비별 적재 현황.
     *
     * <p>진행 중 작업 수는 저장된 값이 아니라 목록에서 세어 낸 값이다. (ADR-0009)
     */
    public List<EquipmentLoad> equipmentLoads() {
        return layout.equipments().stream()
                .map(this::loadOf)
                .toList();
    }

    private EquipmentLoad loadOf(Equipment equipment) {
        int inFlight = tasks.inFlightCount(equipment.code());
        return new EquipmentLoad(
                equipment.code(),
                equipment.capacity(),
                inFlight,
                equipment.availableSlots(inFlight),
                equipment.canAccept(inFlight));
    }

    /**
     * 자리별 점유 현황.
     *
     * <p>설비 정원과 자리 정원은 다르다. 크레인 작업이 끝나도 화물은 P&amp;D에 남아 있으므로,
     * 설비는 비었는데 자리가 차서 하달이 막히는 상태가 있다. 화면에서 그 이유를 보이게 하려는 것이다.
     *
     * <p>자리 목록을 창고 구성이 아니라 작업의 목적지에서 뽑는다. 지금 쓰이고 있는 자리만
     * 보여주면 되고, 쓰이지 않는 자리를 나열해 봐야 화면만 길어지기 때문이다.
     */
    public List<StationLoad> stationLoads() {
        return tasks.all().stream()
                .map(EquipmentTask::getTo)
                .distinct()
                .sorted(Comparator.comparing(LocationCode::value))
                .map(station -> new StationLoad(
                        station.value(),
                        station.kind().name(),
                        tasks.occupancyOf(station),
                        layout.stationCapacity(station)))
                .toList();
    }

    /**
     * 출발 랙에서 목적 슈트까지의 경로.
     *
     * @throws IllegalArgumentException 자리 형식이 맞지 않거나 담당 설비가 없을 때
     */
    public Route route(String source, String chute) {
        return layout.routeFor(LocationCode.of(source), LocationCode.of(chute));
    }

    /** 설비 한 대의 적재 현황. */
    public record EquipmentLoad(String code, int capacity, int inFlight,
                                int available, boolean canAccept) { }

    /** 자리 하나의 점유 현황. */
    public record StationLoad(String code, String kind, int occupancy, int capacity) { }
}
