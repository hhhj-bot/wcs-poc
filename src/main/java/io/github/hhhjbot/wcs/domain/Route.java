package io.github.hhhjbot.wcs.domain;

import java.util.List;
import java.util.Objects;

/**
 * 화물이 거쳐 갈 설비 순서.
 *
 * <p>설비 순서를 조건문이 아니라 목록으로 둔다. 설비가 늘면 항목이 하나 는다. (ADR-0008)
 *
 * <pre>
 *   A-01-03-02  ──SC-A01──▶  PND-A01  ──CV-01──▶  IND-01  ──SRT-01──▶  CHUTE-3
 * </pre>
 *
 * <p>구간 셋이면 자리는 넷이다. 앞 구간의 목적지가 다음 구간의 출발지이며,
 * 이 연결이 끊기면 경로가 성립하지 않으므로 생성 시점에 검사한다.
 *
 * <p>경로를 만드는 일은 여기서 하지 않는다. 어느 크레인·컨베이어·소터가 서 있는지는
 * 설비 구성이며, 그 구성을 갖는 쪽이 구간을 만들어 넘긴다.
 */
public final class Route {

    /**
     * 구간 하나. 설비 한 대가 자리 하나에서 다음 자리로 화물을 옮긴다.
     *
     * <p>{@link EquipmentTask} 한 건이 구간 하나에 대응한다.
     */
    public record Move(Equipment equipment, LocationCode from, LocationCode to) {

        public Move {
            Objects.requireNonNull(equipment, "설비는 필수입니다");
            Objects.requireNonNull(from, "출발지는 필수입니다");
            Objects.requireNonNull(to, "목적지는 필수입니다");

            if (from.equals(to)) {
                throw new IllegalArgumentException(
                        "출발지와 목적지가 같습니다: " + from.value());
            }
        }

        @Override
        public String toString() {
            return "%s: %s → %s".formatted(equipment.code(), from, to);
        }
    }

    private final List<Move> moves;

    private Route(List<Move> moves) {
        this.moves = moves;
    }

    /**
     * 구간 목록으로 경로를 만든다.
     *
     * @throws IllegalArgumentException 구간이 없거나, 앞 구간의 목적지와 다음 구간의 출발지가 다를 때
     */
    public static Route of(List<Move> moves) {
        Objects.requireNonNull(moves, "구간 목록은 필수입니다");
        if (moves.isEmpty()) {
            throw new IllegalArgumentException("경로에는 구간이 하나 이상 있어야 합니다");
        }

        for (int i = 1; i < moves.size(); i++) {
            Move previous = moves.get(i - 1);
            Move current = moves.get(i);
            if (!previous.to().equals(current.from())) {
                throw new IllegalArgumentException(
                        "경로가 이어지지 않습니다: %s 의 목적지는 %s 인데 %s 는 %s 에서 시작합니다"
                                .formatted(previous.equipment().code(), previous.to(),
                                        current.equipment().code(), current.from()));
            }
        }
        return new Route(List.copyOf(moves));
    }

    /** 구간을 순서대로. */
    public List<Move> moves() {
        return moves;
    }

    /** 구간 수. 설비 작업이 이 수만큼 생긴다. */
    public int size() {
        return moves.size();
    }

    /**
     * {@code seq} 번째 구간. 작업 순번과 같은 1부터 센다.
     *
     * @throws IllegalArgumentException 순번이 범위를 벗어날 때
     */
    public Move at(int seq) {
        if (seq < 1 || seq > moves.size()) {
            throw new IllegalArgumentException(
                    "구간 순번이 범위를 벗어났습니다: %d (경로는 1~%d)".formatted(seq, moves.size()));
        }
        return moves.get(seq - 1);
    }

    /** 화물이 출발하는 자리. */
    public LocationCode origin() {
        return moves.get(0).from();
    }

    /** 화물이 도착하는 자리. */
    public LocationCode destination() {
        return moves.get(moves.size() - 1).to();
    }

    /** 이 경로가 지나는 자리 전체. 구간이 셋이면 넷이다. */
    public List<LocationCode> stops() {
        var stops = new java.util.ArrayList<LocationCode>();
        stops.add(origin());
        moves.forEach(move -> stops.add(move.to()));
        return List.copyOf(stops);
    }

    @Override
    public String toString() {
        return stops().stream().map(LocationCode::value).reduce((a, b) -> a + " → " + b).orElse("");
    }
}
