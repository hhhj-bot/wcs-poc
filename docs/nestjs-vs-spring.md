# NestJS에서 Spring으로

NestJS로 백엔드를 다뤄본 상태에서 Spring Boot를 붙이며 정리한 대응 관계와 차이.
두 프레임워크 모두 DI 컨테이너와 데코레이터/어노테이션 기반 라우팅을 쓰므로
개념은 대부분 그대로 옮겨온다. 다른 지점만 따로 적는다.

## 대응 관계

| NestJS | Spring Boot |
|---|---|
| `@Injectable()` | `@Service` · `@Component` |
| `@Controller('api')` | `@RestController` + `@RequestMapping("/api")` |
| `@Get('tasks')` | `@GetMapping("/tasks")` |
| `@Param('id')` | `@PathVariable` |
| `@Query('equipment')` | `@RequestParam` |
| `@Body()` | `@RequestBody` |
| `constructor(private readonly svc: Svc)` | 생성자 주입 (문법까지 거의 같다) |
| `providers: [...]` | `@Bean` 또는 컴포넌트 스캔 |
| `ExceptionFilter` | `@RestControllerAdvice` |
| `main.ts` `bootstrap()` | `SpringApplication.run(...)` |

기본 스코프가 싱글턴인 것도 같다. NestJS가 Spring의 구조를 참고해 만들어졌기 때문에
전이 비용이 크지 않다.

## 실제로 다른 것

### 1. 모듈 경계

NestJS는 `@Module`로 경계를 명시하고, 다른 모듈의 프로바이더를 쓰려면
`exports`와 `imports`를 적어야 한다. 의존 그래프가 코드에 드러난다.

Spring에는 그 단위가 없다. 패키지와 빈 등록으로만 나뉘고, 같은 컨텍스트 안의 빈은
서로 주입받을 수 있다. **경계를 프레임워크가 강제하지 않으므로 설계로 지켜야 한다.**

이 저장소에서는 도메인 패키지에 어노테이션을 하나도 붙이지 않는 방식으로 경계를 그었다.
`wcs.domain`은 스프링을 모르고, 조립은 `wcs.config`가 한다.

### 2. 타입 정보가 런타임에 남는가

NestJS는 TypeScript 타입이 컴파일 후 사라지므로 `reflect-metadata`로 주입 대상을 알아낸다.
그래서 인터페이스를 그대로 주입하지 못하고 토큰을 쓴다.

```ts
constructor(@Inject('TASK_REPO') private readonly repo: TaskRepository) {}
```

Java는 타입이 바이트코드에 남아 있어 인터페이스를 그대로 받는다.

```java
public TaskQueryService(TaskList tasks, WarehouseLayout layout) { ... }
```

### 3. 값 객체가 실제로 값을 하는 정도

TypeScript는 구조적 타이핑이라 `type LocationCode = string` 으로 두면
일반 문자열과 섞인다. 막으려면 브랜딩 같은 기법이 필요하다.

Java는 명목적 타이핑이라 `LocationCode`와 `String`이 절대 섞이지 않는다.

```java
new EquipmentTask(taskNo, code, loadId, from, to)
//                                      ↑ String 을 넣으면 컴파일이 안 된다
```

이번에 출발지·목적지를 문자열에서 `LocationCode`로 바꿨을 때
호출부가 전부 컴파일 오류로 드러났다. 사람이 찾아다니지 않아도 됐다.
**타입을 도입하는 이유의 절반이 이 지점이다.**

### 4. 예외를 HTTP로 옮기는 위치

NestJS는 `HttpException` 계층이 프레임워크에 있어 서비스에서 바로 던지기 쉽다.
편하지만 서비스가 HTTP를 알게 된다.

Spring은 도메인이 표준 예외를 던지고, 그것을 상태 코드로 옮기는 일을
`@RestControllerAdvice`가 맡는다.

```java
IllegalArgumentException  → 400
IllegalStateException     → 409
NoSuchElementException    → 404
```

도메인은 여전히 HTTP를 모른다. 이 저장소의 도메인 계층이 프레임워크 없이
테스트되는 것도 같은 이유다.

### 5. 비동기가 기본인가

NestJS는 `async`/`Promise`가 기본이고 이벤트 루프 위에서 돈다.
Spring MVC는 요청마다 스레드를 쓰는 동기 모델이다. (리액티브는 WebFlux로 따로)

설비 상태를 주기적으로 읽어야 하는 이 프로젝트에서는 이 차이가 설계에 영향을 준다.
Spring에서는 `@Scheduled`가 별도 스레드에서 돌고, 그 스레드와 웹 요청 스레드가
같은 작업 목록을 보게 되므로 동시성 처리가 필요해진다.

### 6. 테스트를 프레임워크 없이 할 수 있는가

NestJS는 `Test.createTestingModule`로 컨테이너를 띄워야 하는 경우가 많다.

Spring에도 `@SpringBootTest`가 있지만, 이 저장소는 도메인에 어노테이션이 없어서
`new` 하나로 테스트한다.

```java
new Equipment("SC-A01", 1).canAccept(0)
```

테스트 147개 중 컨테이너를 띄우는 것은 하나도 없다.

### 7. 진입점이 하나가 아니다

전이하며 가장 오래 헤맨 지점. NestJS에서는 **모든 것이 요청에서 시작**한다.
컨트롤러를 찾으면 흐름이 잡힌다.

Spring Boot에서 `SpringApplication.run()`이 반환된 뒤에는 시작점이 셋이다.

```
① 기동 1회        CommandLineRunner       →  demo.load()
② 스케줄러 스레드  EquipmentPoller.tick()  →  1초마다, 영원히
③ 요청 스레드      컨트롤러                →  화면을 누를 때마다
```

셋이 같은 `TaskList`를 만진다. **요청이 없어도 ②가 계속 돌기 때문에**
"실행하면 어떤 흐름"이라는 질문에 답이 하나로 안 나온다.

`run()`이 반환되면 main 스레드는 할 일이 끝난다. 그래도 앱이 안 죽는 것은
Tomcat 스레드와 스케줄러 스레드가 데몬이 아니라 JVM을 붙잡고 있기 때문이다.

코드를 따라갈 때는 갈래를 나눠서 본다.

| 갈래 | 시작 지점 | 무엇이 나오나 |
|---|---|---|
| 접수 | `DemoScenario.load()` | 값 객체 전부 · 경로 생성 |
| 판단 | `EquipmentPoller.tick()` | 인터록 · 설비 통신 · 상태 전이 |
| 조회 | `TaskController.tasks()` | 화면까지 |

### 8. 빈은 나열이 아니라 스캔으로 등록된다

NestJS는 `@Module`에 목록을 적으므로 "누가 등록했나"가 파일 하나에 보인다.

```ts
@Module({ providers: [OutboundFlow, TaskList], controllers: [TaskController] })
```

Spring은 목록이 없다. `@SpringBootApplication`에 딸린 `@ComponentScan`이
**그 클래스의 패키지 아래를 통째로 훑어** 애너테이션이 붙은 것을 찾는다.

```
io.github.hhhjbot.wcs          ← WcsApplication 이 여기 있으니 여기부터
  ├─ config/WarehouseConfig      @Configuration   → 찾음
  ├─ app/EquipmentPoller         @Component       → 찾음
  ├─ infra/JpaOrderRepository    @Repository      → 찾음
  ├─ web/*Controller             @RestController  → 찾음
  └─ domain/*                    애너테이션 없음   → 안 걸림
```

`domain`이 스캔에 안 걸리는 것이 이 저장소의 경계다.
그 대신 `WarehouseConfig`의 `@Bean` 메서드가 등록한다.

**`@Bean` 메서드의 인자가 곧 의존 선언**이고, 소스 순서가 아니라 그 그래프 순서로 생성된다.

```java
public OutboundFlow outboundFlow(WarehouseLayout layout, OrderRepository orders,
                                 TaskList tasks, EquipmentGateway gateway)
//                               └── 이 넷이 먼저 만들어진다
```

기동 순서를 눈으로 보려면 로그 수준을 한 줄 올린다.

```yaml
logging:
  level:
    org.springframework.beans.factory.support: DEBUG
```

```
Creating shared instance of singleton bean 'warehouseLayout'
Creating shared instance of singleton bean 'taskList'
Creating shared instance of singleton bean 'outboundFlow'
...
```

### 9. 빈이냐 값이냐 — 주입 사슬이 끝나는 곳

"주입받은 것도 클래스인데 그것도 주입받지 않나"가 무한히 이어질 것 같지만 바닥이 있다.

```java
@Bean
public WarehouseLayout warehouseLayout() {
    return new WarehouseLayout(
            List.of(new Equipment("SC-A01", 1), ...),   // ← new. 주입이 아니다
            LocationCode.of("IND-01"), "CV-01", "SRT-01");
}
```

`Equipment`도 `LocationCode`도 빈이 아니다. 컨테이너는 이것들의 존재를 모른다.
**모든 사슬의 끝은 리터럴이거나 설정 파일**이다.

가르는 기준은 하나다.

| | 기준 | 예 |
|---|---|---|
| 빈 | 앱 전체가 하나를 공유해야 한다 | `WarehouseLayout` · `TaskList` · `OutboundFlow` |
| 값 | 업무 데이터마다 생긴다 | `EquipmentTask` · `OutboundOrder` · `Route` · `TaskNo` |

패키지로는 안 갈린다. `domain` 안에 둘 다 있다.

`OutboundFlow`가 빈이어야 하는 이유는 상태 때문이 아니다. 상태가 없는데도 빈이어야 한다.

```java
public synchronized DispatchResult dispatch() { ... }
```

`synchronized`는 **그 객체의 잠금**이라, 요청마다 새로 만들면 잠금이 각각이라 서로를 못 막는다.
폴러 스레드와 요청 스레드가 같은 인스턴스를 써야 잠금이 일한다.

### 10. 인터페이스는 두 번째 구현이 생길 때 만든다

무엇을 인터페이스로 빼는지가 처음에 헷갈렸다. 기준은 **행동이 다른가, 값이 다른가**다.

| | 처리 | 이 저장소 |
|---|---|---|
| 행동이 다르다 | 인터페이스로 뺀다 | `OrderRepository` 메모리 vs SQL<br>`EquipmentGateway` 시뮬레이터 vs 실물 |
| 값이 다르다 | 생성자 인자로 받는다 | `WarehouseLayout` 설비 목록만 현장마다 다르다 |

`WarehouseLayout`은 어느 현장에서든 하는 일이 같다 — 출발지에서 담당 크레인을 뽑고 구간을 만든다.
**방식이 아니라 데이터가 다른 것**이라 인터페이스가 필요 없다.
설비 목록을 `application.yml`로 옮기더라도 이 클래스는 한 글자도 안 바뀐다.

이것도 인터페이스가 되어야 할 때는 **경로를 만드는 방식 자체가 달라질 때**다.
규칙으로 도출하는 대신 경로표를 조회하는 현장이 생기면 그때 뺀다.

두 번째 구현이 없는데 미리 인터페이스를 만들면 추측이고,
추측한 인터페이스는 정작 두 번째가 왔을 때 안 맞는 경우가 많다.
`OrderRepository`와 `EquipmentGateway`가 정당한 것은 두 번째 구현이 이미 있어서다.

### 11. 감싸기 — 인터셉터에 해당하는 것

NestJS는 인터셉터로 앞뒤에 끼어든다.

```ts
@UseInterceptors(LoggingInterceptor)
```

Spring에도 AOP가 있지만, 인터페이스로 받아 두면 **프레임워크 없이 같은 일**을 할 수 있다.

```java
public class LoggingOrderRepository implements OrderRepository {

    private final OrderRepository delegate;   // 진짜를 품는다

    @Override
    public void add(OutboundOrder order) {
        log.info("지시 접수 {}", order.orderNo());
        delegate.add(order);                  // 넘긴다
    }
}
```

```java
@Bean
public OrderRepository orders(JpaOrderRepository jpa) {
    return new LoggingOrderRepository(jpa);
}
```

`OutboundFlow`는 로깅이 끼었는지 모른다. 감싼 것도 `OrderRepository`라서
원래 자리에 그대로 꽂히기 때문이다. 캐시 · 재시도 · 성능 측정 · 이중 쓰기가 같은 방식이다.

**교체만이 인터페이스의 이득이 아니다.** 감싸기가 되는 것도 같은 값어치다.

### 12. `final` 은 `const` 와 같다

```java
private final List<EquipmentTask> tasks = new CopyOnWriteArrayList<>();

tasks = new ArrayList<>();   // ✗ 참조 재할당
tasks.add(task);             // ✓ 내용 추가
```

TypeScript의 `const arr = []`과 같다. **참조를 못 바꾼다는 뜻이지 불변이라는 뜻이 아니다.**

그래서 `final`이 스레드 안전을 주지 않는다. 이 필드에는 셋이 각각 다른 일을 한다.

| | 무엇을 지키나 |
|---|---|
| `final` | 목록이 통째로 바뀌어 끼워지지 않음 |
| `CopyOnWriteArrayList` | 순회 중 추가돼도 예외가 안 남 |
| `synchronized add` | "없는지 보고 → 넣기"가 안 끊김 |

내용까지 막으려면 밖으로 낼 때 복사한다. `all()`이 `List.copyOf(tasks)`를 돌려주는 이유다.

## 정리

전이 비용이 큰 쪽은 문법이 아니라 두 가지였다.

**경계를 지키는 방식.** NestJS는 모듈이 경계를 강제하지만 Spring은 그러지 않으므로,
어디에 어노테이션을 붙이지 **않을지**를 정하는 것이 설계의 일부가 된다.

**시작점이 요청뿐이 아니라는 것.** 요청에서 출발해 따라가는 습관으로는
주기가 도는 시스템의 흐름이 잡히지 않는다. 진입점을 먼저 세고 갈래를 나눠 읽어야 한다.
