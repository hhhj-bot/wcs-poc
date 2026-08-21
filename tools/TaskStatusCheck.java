import io.github.hhhjbot.wcs.domain.TaskStatus;

/**
 * 상태 전이 규칙을 표로 출력한다.
 *
 * <p>검증은 {@code TaskStatusTest}가 담당하고, 이 파일은 규칙 전체를 한눈에 보기 위한 용도다.
 * 빌드 도구 없이 표준 JDK만으로 실행된다.
 *
 * <pre>
 *   javac -encoding UTF-8 -d out src\main\java\io\github\hhhjbot\wcs\domain\TaskStatus.java
 *   javac -encoding UTF-8 -cp out -d out tools\TaskStatusCheck.java
 *   java -Dfile.encoding=UTF-8 -cp out TaskStatusCheck
 * </pre>
 */
public class TaskStatusCheck {

    public static void main(String[] args) {
        TaskStatus[] all = TaskStatus.values();

        System.out.println();
        System.out.println("상태 전이 가능 여부   행 = 현재 상태,  열 = 다음 상태");
        System.out.println("O = 허용,  . = 거부");
        System.out.println();

        System.out.printf("%-12s", "");
        for (TaskStatus to : all) {
            System.out.printf("%-11s", to.name());
        }
        System.out.println();

        for (TaskStatus from : all) {
            System.out.printf("%-12s", from.name());
            for (TaskStatus to : all) {
                System.out.printf("%-11s", from.canTransitionTo(to) ? "O" : ".");
            }
            System.out.println();
        }

        System.out.println();
        System.out.println("설비 점유 구간 : SENT · ACKED · EXECUTING");
        System.out.println("재시도 가능    : BLOCKED · FAILED");
        System.out.println("종료 상태      : COMPLETED");
        System.out.println();
    }
}
