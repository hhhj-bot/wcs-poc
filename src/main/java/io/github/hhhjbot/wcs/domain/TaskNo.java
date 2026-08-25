package io.github.hhhjbot.wcs.domain;

import java.util.Objects;

/**
 * 설비 작업 번호. 지시번호와 순번으로 이루어진다.
 *
 * <pre>
 *   TO-00001-1     지시 TO-00001 의 1번째 구간
 *   TO-00001-2                    2번째 구간
 *   TO-00001-3                    3번째 구간
 * </pre>
 *
 * <p>문자열 하나로 다루면 지시번호를 꺼낼 때마다 자르기가 필요하고,
 * 자르는 규칙이 여러 곳에 흩어진다. 조립과 해석을 여기 한곳에 모은다.
 *
 * <h3>이 번호가 필요한 이유</h3>
 * 작업은 자기 구간만 안다. 크레인 작업의 목적지는 P&amp;D이므로,
 * 그 화물이 결국 어느 슈트로 가는지는 작업만 봐서는 알 수 없다.
 * 지시를 찾아가야 하고, 그 열쇠가 {@link #orderNo()}다.
 *
 * <h3>지시번호 형식을 규정하지 않는 이유</h3>
 * 지시번호는 상위 시스템(WMS)이 정한다. 여기서 형식을 고정하면
 * 상대 시스템이 바뀔 때 이 값 객체부터 깨진다.
 * 비어 있지 않은지만 확인하고, 순번만 이 저장소의 규칙으로 검사한다.
 *
 * <h3>순번을 한 자리로 두는 이유</h3>
 * {@link LocationCode}의 랙 주소는 두 자리 고정이다. 통로·열·단이 수십 단위라
 * 자릿수가 다르면 문자열 정렬이 물리적 순서와 어긋나기 때문이다.
 * 순번은 설비 종류 수만큼만 늘어나므로 한 자리로 충분하다.
 * 구간이 아구간을 넘는 구성이 생기면 그때 두 자리로 바꾼다.
 */
public record TaskNo(String orderNo, int seq) implements Comparable<TaskNo> {

    public TaskNo {
        Objects.requireNonNull(orderNo, "지시번호는 필수입니다");
        orderNo = orderNo.trim().toUpperCase();
        if (orderNo.isEmpty()) {
            throw new IllegalArgumentException("지시번호는 비어 있을 수 없습니다");
        }
        if (seq < 1) {
            throw new IllegalArgumentException("작업 순번은 1 이상이어야 합니다: " + seq);
        }
    }

    /** 지시번호와 순번으로 만든다. */
    public static TaskNo of(String orderNo, int seq) {
        return new TaskNo(orderNo, seq);
    }

    /**
     * {@code TO-00001-2} 형태의 문자열을 해석한다.
     *
     * <p>마지막 하이픈을 기준으로 자른다. 지시번호에 하이픈이 몇 개 들어가든
     * 순번은 항상 맨 뒤에 있기 때문이다.
     *
     * @throws IllegalArgumentException 순번 자리가 숫자가 아닐 때
     */
    public static TaskNo parse(String raw) {
        Objects.requireNonNull(raw, "작업 번호는 필수입니다");
        String text = raw.trim();

        int cut = text.lastIndexOf('-');
        if (cut < 1 || cut == text.length() - 1) {
            throw new IllegalArgumentException(
                    "작업 번호 형식이 아닙니다: '%s' (지시번호-순번, 예 TO-00001-2)".formatted(raw));
        }

        String orderNo = text.substring(0, cut);
        String tail = text.substring(cut + 1);
        if (!tail.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(
                    "작업 순번이 숫자가 아닙니다: '%s'".formatted(raw));
        }
        return new TaskNo(orderNo, Integer.parseInt(tail));
    }

    /** 문자열 표현. {@code TO-00001-2} */
    public String value() {
        return "%s-%d".formatted(orderNo, seq);
    }

    /** 같은 출고 지시에서 나온 작업인지. 지시 단위로 묶을 때 쓴다. */
    public boolean sameOrder(TaskNo other) {
        Objects.requireNonNull(other, "비교할 작업 번호는 필수입니다");
        return orderNo.equals(other.orderNo);
    }

    /** 같은 지시의 다음 구간 번호. */
    public TaskNo next() {
        return new TaskNo(orderNo, seq + 1);
    }

    /** 지시번호 순, 같으면 순번 순. 목록을 사람이 읽을 순서로 정렬할 때 쓴다. */
    @Override
    public int compareTo(TaskNo other) {
        int byOrder = orderNo.compareTo(other.orderNo);
        return byOrder != 0 ? byOrder : Integer.compare(seq, other.seq);
    }

    @Override
    public String toString() {
        return value();
    }
}
