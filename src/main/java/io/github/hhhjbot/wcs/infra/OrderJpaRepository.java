package io.github.hhhjbot.wcs.infra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA 저장소. <b>구현 클래스를 쓰지 않는다.</b>
 *
 * <p>인터페이스만 선언해 두면 Spring이 기동 시점에 구현체를 만들어 빈으로 등록한다.
 * {@code JpaRepository}를 상속하는 것만으로 아래가 이미 생긴다.
 *
 * <pre>
 *   save(entity)        insert 또는 update
 *   findById(id)        Optional 로 반환
 *   findAll()           전체
 *   existsById(id)      존재 여부
 *   count()             건수
 *   deleteById(id)
 * </pre>
 *
 * <p>두 번째 타입 인자 {@code String}은 기본 키의 타입이다.
 * 지시번호를 그대로 키로 쓰므로 별도의 일련번호 컬럼을 두지 않았다.
 *
 * <h3>메서드 이름이 곧 질의다</h3>
 * 아래 메서드는 몸통이 없지만 동작한다. Spring이 이름을 파싱해
 * {@code order by cutoff_at asc, order_no asc} 를 만들어 준다.
 *
 * <p>이름을 쪼개면 이렇게 읽힌다.
 * <pre>
 *   findAll  By        OrderBy  CutoffAsc   OrderNoAsc
 *   전체     조건없음   정렬     컷오프 오름  지시번호 오름
 * </pre>
 *
 * <p>기준이 되는 것은 컬럼명이 아니라 <b>엔티티의 필드명</b>이다.
 * 컬럼은 {@code cutoff_at}이지만 필드가 {@code cutoff}이므로 {@code Cutoff}라고 쓴다.
 * 틀리면 기동 시점에 예외가 나므로, 오타가 런타임까지 살아남지는 않는다.
 *
 * <p>정렬을 자바에서 하지 않고 여기에 맡기는 이유는, 건수가 늘었을 때
 * 전부 읽어 와서 줄 세우는 대신 데이터베이스가 색인으로 처리하기 때문이다.
 */
public interface OrderJpaRepository extends JpaRepository<OrderEntity, String> {

    /** 컷오프 이른 순, 같으면 지시번호 순. */
    List<OrderEntity> findAllByOrderByCutoffAscOrderNoAsc();
}
