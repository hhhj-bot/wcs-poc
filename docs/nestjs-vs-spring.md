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

테스트 126개 중 컨테이너를 띄우는 것은 하나도 없다.

## 정리

전이 비용이 큰 쪽은 문법이 아니라 **경계를 지키는 방식**이었다.
NestJS는 모듈이 경계를 강제하지만 Spring은 그러지 않으므로,
어디에 어노테이션을 붙이지 않을지를 정하는 것이 설계의 일부가 된다.
