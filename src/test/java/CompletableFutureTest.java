import org.junit.jupiter.api.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

public class CompletableFutureTest {

    @Nested
    @DisplayName("비동기 작업 실행 및 쓰레드 풀 관련 테스트")
    public class AsyncTaskExecutionAndThreadPoolTest {
        /** ====================================================================================================
         * runAsync(), supplyAsync() 둘 다 적용되는 테스트
         * ===================================================================================================== */
        @Test
        void CompletableFuture는_별도의_쓰레드에서_작업을_수행한다() {
            // given
            String callerThreadName = Thread.currentThread().getName();
            System.out.println("외부 호출 쓰레드명: " + callerThreadName);

            // when
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                String workerThreadName = Thread.currentThread().getName();
                System.out.println("CompletableFuture 내에서 작업을 수행하는 쓰레드명: " + workerThreadName); // Executor를 넘기고 있으므로, 해당 쓰레드 풀에서 수행됨
                return workerThreadName;
            }, Executors.newSingleThreadExecutor());

            // then
            // 비동기 작업이 완료된 후 결과를 처리
            future.thenAccept(workerThreadName -> {
                // 작업 결과를 받아서 검증
                assertThat(workerThreadName).isNotEqualTo(callerThreadName);
            });
        }

        @Test
        void Executor를_넘기지_않으면_ForkJoinPool에서_실행된다() {
            // given
            // 메인 스레드 이름 (호출자 스레드 이름 저장)
            String callerThreadName = Thread.currentThread().getName();
            System.out.println("외부 호출 쓰레드명: " + callerThreadName);

            // when
            // Executor를 지정하지 않고 supplyAsync 실행
            String workerThreadName = CompletableFuture.supplyAsync(() -> {
                System.out.println("작업 스레드명: " + Thread.currentThread().getName()); // ForkJoinPool은 ForkJoinPool.commonPool-worker-1 이런식으로 네이밍됨
                return Thread.currentThread().getName();
            }).join();  // CompletableFuture가 끝날 때까지 현재 스레드가 블록(blocking)해서 기다림 => Future.get()과 유사함 (단, join은 try-catch 없이 사용 가능)

            // then
            // 1. 작업 스레드 이름은 호출자 스레드 이름과 달라야 한다 (비동기 실행 확인)
            assertThat(workerThreadName)
                    .as("호출자 스레드와 다른 스레드에서 실행되어야 한다")
                    .isNotEqualTo(callerThreadName);

            // 2. Executor를 넘기지 않았으므로 기본적으로 ForkJoinPool.commonPool() 사용
            assertThat(workerThreadName)
                    .as("ForkJoinPool.commonPool()에서 실행되어야 한다")
                    .contains("ForkJoinPool");
        }

        @Test
        void runAsync에서_예외발생시_CompletionException으로_전파된다() {
            // when & then
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                throw new RuntimeException("예외 발생");
            });

            assertThatThrownBy(future::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RuntimeException.class);
        }

        @Test
        void supplyAsync에서_예외발생시_CompletionException으로_전파된다() {
            // when & then
            CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> {
                throw new RuntimeException("예외 발생");
            });

            assertThatThrownBy(future::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("runAsync 및 supplyAsync 메서드 테스트")
    public class runAsyncAndSupplyAsyncMethodTest {
        /** ====================================================================================================
         * runAsync() 테스트
         * ===================================================================================================== */
        @Test
        void runAsync는_반환값이_없다() {
            // given
            String callerThreadName = Thread.currentThread().getName();
            System.out.println("외부 호출 쓰레드명: " + callerThreadName);

            // when
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                System.out.println("작업 스레드명: " + Thread.currentThread().getName());
                // 반환값 없음 → Runnable
            });

            Void result = future.join();  // join() 호출 가능, 하지만 항상 null 반환

            // then
            assertThat(result)
                    .as("runAsync는 반환값이 없으므로 join() 결과는 null")
                    .isNull();
        }

        /** ====================================================================================================
         * supplyAsync() 테스트
         * ===================================================================================================== */
        @Test
        void supplyAsync는_반환값이_존재해야_한다() {
            // given
            String callerThreadName = Thread.currentThread().getName();
            System.out.println("외부 호출 쓰레드명: " + callerThreadName);

            // when
            String result = CompletableFuture.supplyAsync(() -> {
                System.out.println("작업 스레드명: " + Thread.currentThread().getName());
                return "작업 결과";  // 반드시 무언가 반환해야 함
            }).join();

            // then
            assertThat(result)
                    .as("supplyAsync는 반환값을 가져야 한다 (null이든 값이든)")
                    .isNotNull()
                    .isEqualTo("작업 결과");
        }
    }
}
