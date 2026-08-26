package io.github.hhhjbot.wcs.domain;

import java.util.Optional;

/**
 * 설비와 주고받는 통로.
 *
 * <p>{@link OrderRepository}가 "지시를 어디에 담느냐"를 감췄듯, 이 인터페이스는
 * "설비와 어떻게 통신하느냐"를 감춘다. 도메인은 미쓰비시인지 지멘스인지 모른다.
 *
 * <pre>
 *   OutboundFlow ──▶ EquipmentGateway
 *                         ▲        ▲
 *      SimulatedEquipmentGateway   (미구현) Plc4xEquipmentGateway
 *          (테스트·데모)                      MC Protocol / S7 / Modbus
 * </pre>
 *
 * <h3>왜 이 선이 필요한가</h3>
 * 실물 PLC는 이더넷 너머에 있고, 태그 주소는 설비마다 다르며, 통신은 끊긴다.
 * 그것들이 도메인 안으로 들어오면 인터록 규칙 하나를 시험하는 데도
 * 설비가 필요해진다. 이 선이 있어서 규칙은 규칙대로 검증된다.
 *
 * <h3>세 가지 일</h3>
 * <pre>
 *   send(task)              CMD 태그에 값을 쓰고 TRIGGER 를 올린다
 *   read(taskNo)            STS 태그에서 이 명령의 진행만 뽑아 읽는다
 *   readStatus(code)        STS 태그 블록을 통째로 읽는다
 *   release(taskNo)         TRIGGER 를 내린다. 핸드셰이크를 닫는다
 * </pre>
 *
 * <p>{@code send}가 즉시 완료를 뜻하지 않는다는 점이 중요하다. 명령을 적어 놓았을 뿐이고,
 * 설비가 받았는지는 {@code read}로 확인한다. 이벤트가 아니라 폴링인 이유다.
 *
 * <h3>실패는 예외로 알린다</h3>
 * 통신이 끊기거나 쓰기가 거부되면 {@code send}는 {@link RuntimeException}을 던진다.
 * 반환값으로 알리면 부르는 쪽이 확인을 잊을 수 있는데, 명령이 안 나갔는데
 * 나간 줄 아는 것은 현물과 시스템이 어긋나는 가장 나쁜 경우다.
 */
public interface EquipmentGateway {

    /**
     * 설비에 명령을 내린다.
     *
     * @throws RuntimeException 통신 실패. 부르는 쪽이 작업을 실패로 처리한다
     */
    void send(EquipmentTask task);

    /**
     * 설비 상태를 읽는다.
     *
     * @return 아직 응답이 없으면 비어 있다
     */
    Optional<EquipmentSignal> read(TaskNo taskNo);

    /**
     * 설비 상태 블록을 통째로 읽는다.
     *
     * <p>{@link #read(TaskNo)}가 "이 명령이 어디까지 갔나"라면 이쪽은
     * "설비가 지금 어떤 상태인가"다. 실물 PLC는 태그 블록을 통째로 내주므로
     * 하달 판단에 안 쓰는 값(위치·적재·사이클 카운트)도 같이 딸려 온다.
     * 나누는 것은 읽은 뒤의 일이다.
     *
     * <p>등록되지 않은 설비 코드면 대기 상태를 돌려준다. 조회 때문에 예외가 나면
     * 화면 한 칸이 비는 대신 화면 전체가 죽는다.
     */
    EquipmentStatus readStatus(String equipmentCode);

    /**
     * 핸드셰이크를 닫는다. 완료나 이상으로 끝난 뒤 트리거를 내린다.
     *
     * <p>이걸 빠뜨리면 설비 쪽 명령 슬롯이 물린 채 남아 다음 명령을 못 받는다.
     */
    void release(TaskNo taskNo);

    /**
     * 아무것도 하지 않는 게이트웨이.
     *
     * <p>하달 판단만 시험하고 설비 응답은 손으로 흉내 내는 테스트에서 쓴다.
     * 운영 조립에서는 절대 쓰지 않는다. 명령이 나가지 않는데 나간 것처럼 보인다.
     */
    EquipmentGateway NOOP = new EquipmentGateway() {

        @Override
        public void send(EquipmentTask task) {
            // 보내지 않는다
        }

        @Override
        public Optional<EquipmentSignal> read(TaskNo taskNo) {
            return Optional.empty();
        }

        @Override
        public EquipmentStatus readStatus(String equipmentCode) {
            return EquipmentStatus.idle(equipmentCode);
        }

        @Override
        public void release(TaskNo taskNo) {
            // 닫을 것이 없다
        }

        @Override
        public String toString() {
            return "게이트웨이 없음";
        }
    };
}
