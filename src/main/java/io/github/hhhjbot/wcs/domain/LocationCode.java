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
 * <h3>네 가지 종류</h3>
 * <pre>
 *   RACK        A-01-03-02   존 A · 통로 1 · 열 3 · 단 2
 *   PND         PND-A01      존 A · 통로 1
 *   INDUCTION   IND-01       번호만
 *   CHUTE       CHUTE-3      번호만
 * </pre>
 *
 * 종류마다 갖는 값이 다르지만 <b>타입은 하나로 둔다.</b>
 * {@link EquipmentTask}의 출발지·목적지에는 어느 종류든 올 수 있어,
 * 타입을 나누면 작업 쪽에서 매번 분기해야 한다. (ADR-0010)
 *
 * <h3>통로와 담당 크레인</h3>
 * 통로마다 크레인이 한 대 서고, P&amp;D는 통로마다 하나씩 붙는다.
 * 따라서 {@code A-01-03-02}와 {@code PND-A01}은 모두 {@code SC-A01}이 담당한다.
 * 인덕션과 슈트는 통로에 속하지 않으므로 담당 크레인이 없다. (ADR-0010)
 *
 * <h3>형식이 틀리면 예외</h3>
 * 잘못된 자리는 되돌릴 방법이 없다. 빈 값을 돌려주면 호출한 쪽이 검사를 잊었을 때
 * 화물이 없는 자리로 크레인이 출발한다. 생성 시점에 끊는다.
 */
public final class LocationCode {

    /** 존과 통로. 담당 크레인이 여기서 정해진다. */
    public record AisleRef(String zone, int aisle) {

        /** 이 통로에 서는 크레인. (ADR-0010) */
        public String craneCode() {
            return "SC-%s%02d".formatted(zone, aisle);
        }

        /** 이 통로 끝의 P&amp;D 스테이션. 통로마다 하나씩 붙는다. */
        public String pndCode() {
            return "PND-%s%02d".formatted(zone, aisle);
        }
    }

    /** 랙 좌표. 통로 안의 열과 단. */
    public record RackAddress(AisleRef aisleRef, int bay, int level) {
        public String zone()  { return aisleRef.zone(); }
        public int    aisle() { return aisleRef.aisle(); }
    }

    /** A-01-03-02 — 존 한 글자, 나머지는 두 자리 고정. */
    private static final Pattern RACK_FORMAT =
            Pattern.compile("^([A-Z])-(\\d{2})-(\\d{2})-(\\d{2})$");

    /** PND-A01 — 랙과 같은 존·통로 표기를 따른다. */
    private static final Pattern PND_FORMAT =
            Pattern.compile("^PND-([A-Z])(\\d{2})$");

    /** IND-01 */
    private static final Pattern INDUCTION_FORMAT =
            Pattern.compile("^IND-(\\d{1,3})$");

    /** CHUTE-3 — 번호만 갖는다. 용도(정상·리젝트)는 슈트 속성이다. (ADR-0005) */
    private static final Pattern CHUTE_FORMAT =
            Pattern.compile("^CHUTE-(\\d{1,3})$");

    private final String value;
    private final LocationKind kind;
    private final AisleRef aisleRef;   // INDUCTION · CHUTE 이면 null
    private final RackAddress rack;    // RACK 이 아니면 null

    private LocationCode(String value, LocationKind kind, AisleRef aisleRef, RackAddress rack) {
        this.value = value;
        this.kind = kind;
        this.aisleRef = aisleRef;
        this.rack = rack;
    }

    /**
     * 문자열을 해석해 자리를 만든다. 생성자를 막고 이 메서드만 열어 둔 것은
     * 형식 검사를 거치지 않은 자리가 생기지 않게 하기 위한 것이다.
     *
     * @throws IllegalArgumentException 네 종류 어디에도 맞지 않는 형식일 때
     */
    public static LocationCode of(String raw) {
        Objects.requireNonNull(raw, "위치 코드는 필수입니다");
        String code = raw.trim().toUpperCase();

        Matcher rackFormat = RACK_FORMAT.matcher(code);
        if (rackFormat.matches()) {
            var ref = new AisleRef(rackFormat.group(1), positive(rackFormat.group(2), "통로", code));
            int bay   = positive(rackFormat.group(3), "열", code);
            int level = positive(rackFormat.group(4), "단", code);
            return new LocationCode(code, LocationKind.RACK, ref, new RackAddress(ref, bay, level));
        }

        Matcher pndFormat = PND_FORMAT.matcher(code);
        if (pndFormat.matches()) {
            var ref = new AisleRef(pndFormat.group(1), positive(pndFormat.group(2), "통로", code));
            return new LocationCode(code, LocationKind.PND, ref, null);
        }

        Matcher inductionFormat = INDUCTION_FORMAT.matcher(code);
        if (inductionFormat.matches()) {
            positive(inductionFormat.group(1), "인덕션 번호", code);
            return new LocationCode(code, LocationKind.INDUCTION, null, null);
        }

        Matcher chuteFormat = CHUTE_FORMAT.matcher(code);
        if (chuteFormat.matches()) {
            positive(chuteFormat.group(1), "슈트 번호", code);
            return new LocationCode(code, LocationKind.CHUTE, null, null);
        }

        throw new IllegalArgumentException(
                ("위치 코드 형식이 아닙니다: '%s' "
                        + "(랙 A-01-03-02 · P&D PND-A01 · 인덕션 IND-01 · 슈트 CHUTE-3)")
                        .formatted(raw));
    }

    // ------------------------------------------------------------------
    // 조회
    // ------------------------------------------------------------------

    public String value() { return value; }

    public LocationKind kind() { return kind; }

    public boolean isRack() { return kind == LocationKind.RACK; }

    /** 설비끼리 화물을 주고받는 자리인지. P&amp;D, 인덕션, 슈트가 여기 해당한다. */
    public boolean isStation() { return kind != LocationKind.RACK; }

    /** 통로에 속해 담당 크레인이 정해지는 자리인지. 랙과 P&amp;D가 여기 해당한다. */
    public boolean belongsToAisle() { return aisleRef != null; }

    /**
     * 랙 좌표.
     *
     * @throws IllegalStateException 랙이 아닐 때
     */
    public RackAddress rack() {
        if (rack == null) {
            throw new IllegalStateException("랙 좌표가 없는 자리입니다: " + value);
        }
        return rack;
    }

    /**
     * 이 자리가 속한 존과 통로.
     *
     * @throws IllegalStateException 통로에 속하지 않는 자리일 때
     */
    public AisleRef aisleRef() {
        if (aisleRef == null) {
            throw new IllegalStateException("통로에 속하지 않는 자리입니다: " + value);
        }
        return aisleRef;
    }

    /**
     * 이 자리를 담당하는 크레인 코드. {@code A-01-03-02} · {@code PND-A01} → {@code SC-A01}.
     *
     * @throws IllegalStateException 통로에 속하지 않는 자리일 때
     */
    public String craneCode() {
        return aisleRef().craneCode();
    }

    /**
     * 이 자리가 속한 통로의 P&amp;D 스테이션.
     *
     * <p>랙에서 꺼낸 화물은 같은 통로의 P&amp;D로 나온다. 주소가 정해지면
     * 인계 지점도 함께 정해지므로 별도 표를 두지 않는다. (ADR-0010)
     *
     * @throws IllegalStateException 통로에 속하지 않는 자리일 때
     */
    public LocationCode pnd() {
        return LocationCode.of(aisleRef().pndCode());
    }

    /** 같은 크레인이 담당하는 자리인지. 작업 순서를 정할 때 쓴다. */
    public boolean sharesCraneWith(LocationCode other) {
        Objects.requireNonNull(other, "비교할 자리는 필수입니다");
        return belongsToAisle() && other.belongsToAisle()
                && craneCode().equals(other.craneCode());
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

    private static int positive(String digits, String field, String code) {
        int number = Integer.parseInt(digits);
        if (number < 1) {
            throw new IllegalArgumentException(
                    "%s 번호는 1 이상이어야 합니다: %s".formatted(field, code));
        }
        return number;
    }
}
