package io.github.hhhjbot.wcs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 애플리케이션 진입점.
 *
 * <p>{@code @SpringBootApplication} 하나에 세 가지가 들어 있다.
 *
 * <pre>
 *   @Configuration       이 클래스 자체가 설정이 될 수 있다
 *   @ComponentScan       이 패키지 아래에서 @Service · @RestController 를 찾는다
 *   @EnableAutoConfiguration  클래스패스를 보고 웹 서버 등을 알아서 띄운다
 * </pre>
 *
 * <p>도메인 계층({@code wcs.domain})에는 이 어노테이션들이 하나도 없다.
 * 스프링을 걷어내도 도메인은 그대로 동작한다.
 */
@SpringBootApplication
public class WcsApplication {

    public static void main(String[] args) {
        SpringApplication.run(WcsApplication.class, args);
    }
}
