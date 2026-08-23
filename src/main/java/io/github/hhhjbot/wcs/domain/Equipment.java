package io.github.hhhjbot.wcs.domain;

/**
 * 설비 한 대.
 *
 * <p>설비마다 동시에 처리할 수 있는 화물 수가 다르다.
 * 스태커크레인은 포크가 하나라 1건이지만, 컨베이어는 존마다,
 * 소터는 캐리어마다 화물이 올라간다.
 *
 * <pre>
 *   SC-A01   스태커크레인   capacity 1
 *   CV-01    반출 컨베이어   capacity 8    (존 8개)
 *   SRT-01   소터           capacity 24   (캐리어 24개)
 * </pre>
 *
 * <h3>진행 중 작업 수를 필드로 두지 않는 이유</h3>
 * 이 클래스는 {@code capacity}만 알고, <b>현재 몇 건이 진행 중인지는 모른다.</b>
 * 판정할 때 인자로 받는다.
 *
 * <p>카운터를 필드로 두고 하달 시 증가·완료 시 감소시키면 작업 상태와 이중 관리가 된다.
 * 예외 경로에서 감소를 한 번만 놓쳐도 그 설비는 영구히 "가득 참"으로 남아
 * 이후 어떤 작업도 받지 못한다. 작업 목록에서 세어내면 진실의 원천이 하나뿐이라
 * 어긋날 수가 없다.
 *
 * <p>그 대가로 이 클래스는 저장소도 작업 목록도 모른다. 판정 로직만 갖는 순수 값이라
 * 프레임워크 없이 테스트된다.
 *
 * <p>record로 둔 이유는 설비 구성이 운영 중에 바뀌지 않기 때문이다.
 * 상태가 변하는 {@link EquipmentTask}와 대비된다.
 */
public record Equipment(String code, int capacity) {

    /**
     * 컴팩트 생성자. record는 필드 대입을 자동으로 하므로 검증만 적는다.
     */
    public Equipment {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("설비 코드는 필수입니다");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException(
                    "동시 처리 수는 1 이상이어야 합니다: " + capacity);
        }
        code = code.trim();
    }

    /**
     * 이 설비가 작업을 더 받을 수 있는지 판정한다.
     *
     * @param inFlightCount 현재 이 설비에서 진행 중인 작업 수.
     *                      호출하는 쪽이 작업 목록에서 세어 넘긴다.
     */
    public boolean canAccept(int inFlightCount) {
        requireNotNegative(inFlightCount);
        return inFlightCount < capacity;
    }

    /**
     * 지금 추가로 받을 수 있는 작업 수.
     * 대기열에서 한 번에 몇 건을 꺼낼지 정할 때 쓴다.
     */
    public int availableSlots(int inFlightCount) {
        requireNotNegative(inFlightCount);
        return Math.max(0, capacity - inFlightCount);
    }

    /** 한 번에 한 건만 처리하는 설비인지. 크레인이 여기 해당한다. */
    public boolean isSingleLoad() {
        return capacity == 1;
    }

    private static void requireNotNegative(int inFlightCount) {
        if (inFlightCount < 0) {
            throw new IllegalArgumentException(
                    "진행 중 작업 수는 음수일 수 없습니다: " + inFlightCount);
        }
    }
}
