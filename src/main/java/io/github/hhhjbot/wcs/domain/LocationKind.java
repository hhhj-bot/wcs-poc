package io.github.hhhjbot.wcs.domain;

/**
 * 자리의 종류.
 *
 * <pre>
 *   RACK        A-01-03-02   존 · 통로 · 열 · 단
 *   PND         PND-A01      존 · 통로. 크레인과 컨베이어가 화물을 주고받는 자리
 *   INDUCTION   IND-01       컨베이어에서 소터로 화물을 태우는 자리
 *   CHUTE       CHUTE-3      소터가 화물을 배출하는 자리
 * </pre>
 *
 * <p>RACK과 PND는 통로에 속하므로 담당 크레인이 정해진다.
 * INDUCTION과 CHUTE는 어느 통로에도 속하지 않는다. (ADR-0010)
 */
public enum LocationKind {

    /** 랙 로케이션. 좌표를 갖는 유일한 종류다. */
    RACK,

    /** P&D 스테이션. 통로마다 하나씩 붙는다. */
    PND,

    /** 소터 인덕션. */
    INDUCTION,

    /** 배출 슈트. */
    CHUTE;

    /** 열·단까지 갖는 종류인지. */
    public boolean hasRackAddress() {
        return this == RACK;
    }

    /** 통로에 속해 담당 크레인이 정해지는 종류인지. */
    public boolean belongsToAisle() {
        return this == RACK || this == PND;
    }
}
