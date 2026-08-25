package io.github.hhhjbot.wcs.web;

import io.github.hhhjbot.wcs.app.TaskQueryService;
import io.github.hhhjbot.wcs.domain.EquipmentTask;
import io.github.hhhjbot.wcs.domain.Route;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 조회 API.
 *
 * <pre>
 *   GET /api/tasks                                   작업 전체
 *   GET /api/tasks?equipment=CV-01                   설비별
 *   GET /api/tasks?order=TO-00001                    지시별
 *   GET /api/tasks/TO-00001-2                        한 건
 *   GET /api/equipments                              설비별 적재 현황
 *   GET /api/routes?source=A-01-03-02&chute=CHUTE-3  경로 조회
 * </pre>
 *
 * <p>도메인 객체를 그대로 내보내지 않고 {@link TaskView}로 바꿔 응답한다.
 * 그대로 내보내면 화면의 요구가 도메인 구조를 끌고 다니게 된다.
 */
@RestController
@RequestMapping("/api")
public class TaskController {

    private final TaskQueryService service;

    public TaskController(TaskQueryService service) {
        this.service = service;
    }

    @GetMapping("/tasks")
    public List<TaskView> tasks(@RequestParam(required = false) String equipment,
                                @RequestParam(required = false) String order) {
        List<EquipmentTask> found;
        if (equipment != null) {
            found = service.tasksOf(equipment);
        } else if (order != null) {
            found = service.tasksOfOrder(order);
        } else {
            found = service.allTasks();
        }
        return found.stream().map(TaskView::of).toList();
    }

    @GetMapping("/tasks/{taskNo}")
    public TaskView task(@PathVariable String taskNo) {
        return TaskView.of(service.task(taskNo));
    }

    @GetMapping("/equipments")
    public List<TaskQueryService.EquipmentLoad> equipments() {
        return service.equipmentLoads();
    }

    @GetMapping("/routes")
    public RouteView route(@RequestParam String source, @RequestParam String chute) {
        return RouteView.of(service.route(source, chute));
    }

    // ------------------------------------------------------------------
    // 응답 형태
    // ------------------------------------------------------------------

    /** 작업 한 건의 응답 형태. */
    public record TaskView(String taskNo, String orderNo, int seq,
                           String equipmentCode, String loadId,
                           String from, String to,
                           String status, String reason, boolean inFlight) {

        static TaskView of(EquipmentTask task) {
            return new TaskView(
                    task.getTaskNo().value(),
                    task.getOrderNo(),
                    task.getTaskNo().seq(),
                    task.getEquipmentCode(),
                    task.getLoadId(),
                    task.getFrom().value(),
                    task.getTo().value(),
                    task.getStatus().name(),
                    task.getReason(),
                    task.isInFlight());
        }
    }

    /** 경로의 응답 형태. */
    public record RouteView(List<String> stops, List<LegView> legs) {

        static RouteView of(Route route) {
            return new RouteView(
                    route.stops().stream().map(stop -> stop.value()).toList(),
                    route.hops().stream()
                            .map(hop -> new LegView(hop.equipment().code(),
                                    hop.equipment().capacity(),
                                    hop.from().value(),
                                    hop.to().value()))
                            .toList());
        }
    }

    /** 구간 하나. */
    public record LegView(String equipmentCode, int capacity, String from, String to) { }
}
