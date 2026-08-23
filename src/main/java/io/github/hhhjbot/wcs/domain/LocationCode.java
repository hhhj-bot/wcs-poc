package io.github.hhhjbot.wcs.domain;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 화물이 놓이는 자리.
 *
 * <p>출발지·목적지를 문자열로 다루면 형식 검사가 어디에서도 이루어지지 않고,
 * {@code A-1-3-2}와 {@code A-01-03-02}가 같은 자리인지 판단할 수 없다.
 * 값 객체로 두어 형식 검사와 해석을 한곳에 모은다.
 *
 * <h3>두 가지 종류</h3>
 * <pre>
 *   랙(RACK)        A-01-03-02   존 A, 통로 01, 열 03, 단 02
 *   스테이션(STATION) PND-A01     크레인과 컨베이어가 화물을 주고받는 자리
 *                    IND-01       소터 인덕션
 *                    CHUTE-3      슈트
 * </pre>
 *
 * 랙은 좌표를 갖고 스테이션은 이름만 갖지만 <b>타입은 하나로 둔다.</b>
 * {@link EquipmentTask}의 출발지·목적지에는 둘 다 올 수 있고
 * (크레인은 랙→스테이션, 컨베이어는 스테이션→스테이션),
 * 타입을 나누면 작업 쪽에서 매번 분기해야 하기 때문이다.
 * 좌표가 필요한 쪽만 {@link #rack()}으로 꺼낸다.
 *
 * <h3>담당 크레인 도출</h3>
 * 랙 주소에 존과 통로가 들어 있고, 통로마다 크레인이 한 대 선다.
 * 따라서 {@code A-01-03-02}의 담당 설비는 {@code SC-A01}로 결정된다.
 * 별도 매핑 표를 두면 주소 체계와 표가 어긋날 수 있다. (ADR-0010)
 *
 * <h3>형식이 틀리면 예외</h3>
 * 잘못된 자리는 되돌릴 방법이 없다. 빈 값을 돌려주면 호출한 쪽이 검사를 잊었을 때
 * 화물이 없는 자리로 크레인이 출발한다. 생성 시점에 끊는다.
 */
public final class LocationCode {

    /** 자리의 종류. */
    public enum Kind { RACK, STATION }

    /** 랙 좌표. 스테이션에는 없다. */
    public record RackAddress(String zone, int aisle, int bay, int level) { }

    /** A-01-03-02 — 존 한 글자, 나머지는 두 자리 고정. */
    private static final Pattern RACK_FORMAT =
            Pattern.compile("^([A-Z])-(\\d{2})-(\\d{2})-(\\d{2})$");

    /** PND-A01, IND-01, CHUTE-3 — 두 글자 이상의 이름과 번호. */
    private static final Pattern STATION_FORMAT =
            Pattern.compile("^[A-Z]{2,5}-[A-Z]?\\d{1,3}$");

    private final String value;
    private final Kind kind;
    private final RackAddress rack;   // STATION이면 null

    private LocationCode(String value, Kind kind, RackAddress rack) {
        this.value = value;
        this.kind = kind;
        this.rack = rack;
    }

    /**
     * 문자열을 해석해 자리를 만든다. 생성자를 막고 이 메서드만 열어 둔 것은
     * 형식 검사를 거치지 않은 자리가 생기지 않게 하기 위한 것이다.
     *
     * @throws IllegalArgumentException 랙도 스테이션도 아닌 형식일 때
     */
    public static LocationCode of(String raw) {
        Objects.requireNonNull(raw, "위치 코드는 필수입니다");
        String code = raw.trim().toUpperCase();

        Matcher m = RACK_FORMAT.matcher(code);
        if (m.matches()) {
            int aisle = Integer.parseInt(m.group(2));
            int bay = Integer.parseInt(m.group(3));
            int level = Integer.parseInt(m.group(4));
            requirePositive(aisle, "통로", code);
            requirePositive(bay, "열", code);
            requirePositive(level, "단", code);
            return new LocationCode(code, Kind.RACK,
                    new RackAddress(m.group(1), aisle, bay, level));
        }

        if (STATION_FORMAT.matcher(code).matches()) {
            return new LocationCode(code, Kind.STATION, null);
        }

        throw new IllegalArgumentException(
                "위치 코드 형식이 아닙니다: '%s' (랙은 A-01-03-02, 스테이션은 PND-A01 형태)"
                        .formatted(raw));
    }

    // ------------------------------------------------------------------
    // 조회
    // ------------------------------------------------------------------

    public String value() { return value; }

    public Kind kind() { return kind; }

    public boolean isRack() { return kind == Kind.RACK; }

    /** 설비끼리 화물을 주고받는 자리인지. P&amp;D, 인덕션, 슈트가 여기 해당한다. */
    public boolean isStation() { return kind == Kind.STATION; }

    /**
     * 랙 좌표.
     *
     * @throws IllegalStateException 스테이션일 때
     */
    public RackAddress rack() {
        if (rack == null) {
            throw new IllegalStateException("랙 좌표가 없는 자리입니다: " + value);
        }
        return rack;
    }

    /**
     * 이 자리를 담당하는 크레인 코드. {@code A-01-03-02} → {@code SC-A01}.
     *
     * @throws IllegalStateException 스테이션일 때. 스테이션은 어느 크레인에도 속하지 않는다.
     */
    public String craneCode() {
        RackAddress r = rack();
        return "SC-%s%02d".formatted(r.zone(), r.aisle());
    }

    /** 같은 크레인이 담당하는 자리인지. 작업 순서를 정할 때 쓴다. */
    public boolean sharesCraneWith(LocationCode other) {
        Objects.requireNonNull(other, "비교할 자리는 필수입니다");
        return isRack() && other.isRack() && craneCode().equals(other.craneCode());
    }

    // ------------------------------------------------------------------
    // 값 비교
    // ------------------------------------------------------------------

    /**
     * 코드 문자열이 같으면 같은 자리다. 종류와 좌표는 코드에서 나온 값이라
     * 따로 비교할 필요가 없다.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LocationCode other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() { return value.hashCode(); }

    @Override
    public String toString() { return value; }

    // ------------------------------------------------------------------

    private static void requirePositive(int number, String field, String code) {
        if (number < 1) {
            throw new IllegalArgumentException(
                    "%s 번호는 1 이상이어야 합니다: %s".formatted(field, code));
        }
    }
}
