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

    @Nested
    @DisplayName("콜백 메서드 테스트")
    public class callBackMethodTest {
        /** ====================================================================================================
         * 동기적 콜백 메서드 테스트 - thenApply, thenAccept, thenRun
         * - 셋 다 적용되는 개념이므로 thenApply만 예시로 작성함
         * ===================================================================================================== */
        @Test
        void 현재_호출_쓰레드에서_작업이_완료될_경우_콜백_메서드_thenApply도_호출_쓰레드에서_실행된다() {
            // given
            CompletableFuture<String> future = CompletableFuture.completedFuture("Already Completed Value");
            AtomicReference<String> callbackThreadName = new AtomicReference<>();
            String callerThreadName = Thread.currentThread().getName();
            System.out.println("호출 쓰레드: " + callerThreadName);

            // when
            CompletableFuture<String> transformed = future.thenApply(s -> {
                callbackThreadName.set(Thread.currentThread().getName());
                System.out.println("콜백이 수행된 쓰레드: " + Thread.currentThread().getName());
                return s.toUpperCase();
            });

            // then
            String result = transformed.join();
            assertThat(result).isEqualTo("Already Completed Value".toUpperCase());
            assertThat(callbackThreadName.get())
                    .isEqualTo(callerThreadName); // 이미 완료된 상태라 현재 스레드에서 실행
        }

        @Test
        void 이전_작업이_미완료된_상태에서_등록된_thenApply는_작업을_완료시키는_쓰레드와_동일한_쓰레드에서_수행된다() throws InterruptedException {
            // given
            // 작업이 완료되지 않는 CompletableFuture 생성
            CompletableFuture<String> future = new CompletableFuture<>();
            AtomicReference<String> callbackThreadName = new AtomicReference<>();
            AtomicReference<String> executorThreadName = new AtomicReference<>();

            // when
            // 1. 이전 작업이 미완료 상태이지만, thenApply() 등록
            CompletableFuture<String> transformedFuture = future.thenApply(str -> {
                callbackThreadName.set(Thread.currentThread().getName());
                System.out.println("콜백이 수행된 쓰레드명: " + Thread.currentThread().getName());
                return str.toUpperCase();
            });

            // 2. 새로운 쓰레드에서 CompletableFuture 완료시키기
            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.submit(() ->  {
                future.complete("완료처리할 값"); // 작업 완료 시키기
                System.out.println("작업을 완료시킨 쓰레드명: " + Thread.currentThread().getName());
                executorThreadName.set(Thread.currentThread().getName());
            });
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.SECONDS); // future.complete()가 실행될 때까지 기다려야 콜백이 실행되고 callbackThread.get()을 읽을 수 있음

            // then
            // 콜백이 완료시킨 쓰레드(newSingleThreadExecutor)에서 실행됐는지 확인
            assertThat(callbackThreadName.get())
                    .isEqualTo(executorThreadName.get());
        }

        /** ====================================================================================================
         * 비동기적 콜백 메서드 테스트 - thenApplyAsync, thenAcceptAsync, thenRunAsync
         * - 셋 다 적용되는 개념이므로 thenApplyAsync만 예시로 작성함
         * ===================================================================================================== */
        @Test
        void thenApplySync는_항상_기본풀에서_실행된다() throws InterruptedException {
            // given
            CompletableFuture<String> future = new CompletableFuture<>();
            AtomicReference<String> callbackThreadName = new AtomicReference<>();
            AtomicReference<String> executorThreadName = new AtomicReference<>();

            // when
            CompletableFuture<String> transformedFuture = future.thenApplyAsync(str -> {
                // 콜백이 실행되는 쓰레드명을 저장
                callbackThreadName.set(Thread.currentThread().getName());
                System.out.println("콜백이 수행된 쓰레드명: " + Thread.currentThread().getName());
                return str.toUpperCase();
            });

            // 별도의 스레드에서 future.complete() 호출 → 콜백 실행 트리거
            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.submit(() -> {
                future.complete("완료처리할 값");
                System.out.println("작업을 완료시킨 쓰레드명: " + Thread.currentThread().getName());
                executorThreadName.set(Thread.currentThread().getName());
            });
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.SECONDS);

            // then
            // Awaitility로 콜백이 실행될 때까지 기다린 후 검증 수행
            /**
             * Awaitility.await().atMost(1, TimeUnit.SECONDS).untilAsserted(...)
             * => 지정된 시간(1초) 동안 계속 polling 하면서 콜백이 실행되어 callbackThreadName이 값으로 채워질 때까지 기다림
             * => join()을 직접 호출해서 기다리는 대신 Awaitility가 비동기 완료를 보장.
             */
            await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> {
                // 1. 콜백이 future를 complete한 스레드에서 실행되지 않았는지 확인
                assertThat(callbackThreadName.get())
                        .isNotEqualTo(executorThreadName.get());

                // 2. 콜백이 ForkJoinPool의 워커 스레드에서 실행되었는지 확인
                assertThat(callbackThreadName.get())
                        .contains("ForkJoinPool");

                // 3. 반환값이 정상 변환되었는지 확인
                assertThat(transformedFuture.join())
                        .isEqualTo("완료처리할 값".toUpperCase());
            });
        }

        @Test
        void thenApplySync에_Executor_전달시_콜백_메서드가_전달한_Executor_쓰레드풀에서_수행된다() throws InterruptedException {
            // given
            // 작업이 완료되지 않는 CompletableFuture 생성
            CompletableFuture<String> future = new CompletableFuture<>();
            AtomicReference<String> callbackThreadName = new AtomicReference<>();
            AtomicReference<String> executorThreadName = new AtomicReference<>();
            String currentThreadName = Thread.currentThread().getName();
            System.out.println("외부 호출 쓰레드명(현재 작업 영역): " + Thread.currentThread().getName());

            // when
            // 1. 이전 작업이 미완료 상태이지만, thenApply() 등록 (Executor를 전달하여 해당 콜백이 Executor에서 실행되도록 함)
            CompletableFuture<String> transformedFuture = future.thenApplyAsync(str -> {
                callbackThreadName.set(Thread.currentThread().getName());
                System.out.println("콜백이 수행된 쓰레드명: " + Thread.currentThread().getName());
                return str.toUpperCase();
            }, Executors.newSingleThreadExecutor());

            // 2. 새로운 쓰레드에서 CompletableFuture 완료시키기
            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.submit(() ->  {
                future.complete("완료처리할 값");
                System.out.println("작업을 완료시킨 쓰레드명: " + Thread.currentThread().getName());
                executorThreadName.set(Thread.currentThread().getName());
            });
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.SECONDS); // future.complete()가 실행될 때까지 기다려야 콜백이 실행되고 callbackThread.get()을 읽을 수 있음

            // then
            // 콜백이 완료시킨 쓰레드(newSingleThreadExecutor)에서 실행됐는지 확인
            assertThat(callbackThreadName.get())
                    .isNotEqualTo(executorThreadName.get());

            // 콜백이 기본풀(ForkJoinPool)에서 실행됐는지 확인
            assertThat(callbackThreadName.get())
                    .isNotEqualTo(executorThreadName.get())
                    .isNotEqualTo(currentThreadName);
        }

        /** ====================================================================================================
         * thenApply() 테스트
         * ===================================================================================================== */
        @Test
        void thenApply는_반환값을_변환해준다() {
            // given
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> "hello");

            // when
            CompletableFuture<String> transformedFuture = future.thenApply(str -> str.toUpperCase());

            // then
            String result = transformedFuture.join();
            assertThat(result).isEqualTo("HELLO");
        }

        /** ====================================================================================================
         * thenAccept() 테스트
         * ===================================================================================================== */
        @Test
        void thenAccept는_반환값을_사용만한다() {
            // given
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> "hello");
            AtomicReference<Integer> consumedValue = new AtomicReference<>(); // 소비 결과 담기

            // when
            CompletableFuture<Void> consumer = future.thenAccept(str -> {
                consumedValue.set(str.hashCode());
            });

            // then
            consumer.join(); // 비동기 작업 완료 후 검증
            assertThat(consumedValue.get())
                    .isEqualTo("hello".hashCode());
        }

        /** ====================================================================================================
         * thenRun() 테스트
         * ===================================================================================================== */
        @Test
        void thenRun은_반환값을_처리하지_않고_전달받은_작업을_수행한다() {
            // given
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> "hello");
            AtomicInteger num = new AtomicInteger(1);

            // when
            CompletableFuture<Void> runnable = future.thenRun(() -> {
                System.out.println("현재 실행 쓰레드: " + Thread.currentThread().getName());
                num.incrementAndGet();
            });

            // then
            assertThat(num.get())
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("작업 결과 조합 메서드 테스트")
    public class CombineMethodTest {
        /** ====================================================================================================
         * thenApply() 테스트 (문제 발생 케이스)
         * ===================================================================================================== */
        @Test
        void thenApply는_중첩된_CompletableFuture를_반환한다() {
            // given
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> "test");

            // when
            CompletableFuture<CompletableFuture<String>> result = future.thenApply(str -> CompletableFuture.supplyAsync(() -> str.toUpperCase()));

            // then
            // 결과가 중첩된 구조 => CompletableFuture<CompletableFuture<T>>
            // 따라서 join을 두 번해야 값을 얻을 수 있는지 검증

            // 첫 번째 join() → CompletableFuture<String>
            CompletableFuture<String> innerFuture = result.join();
            assertThat(innerFuture).isInstanceOf(CompletableFuture.class);

            // 두 번째 join() → String
            String finalResult = innerFuture.join();
            assertThat(finalResult).isEqualTo("TEST");
        }

        /** ====================================================================================================
         * thenCompose() 테스트 (위 문제 해결 테스트)
         * ===================================================================================================== */
        @Test
        void thenCompose는_중첩되지_않은_평탄화된_CompletableFuture를_반환한다() {
            // given
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> "test");

            // when
            CompletableFuture<String> result = future.thenCompose(str -> CompletableFuture.supplyAsync(() -> str.toUpperCase()));

            // then
            // 결과가 중첩된 구조 => CompletableFuture<CompletableFuture<T>>
            // 따라서 join을 두 번해야 값을 얻을 수 있는지 검증

            // 첫 번째 join() → String
            String finalResult = result.join();
            assertThat(finalResult).isEqualTo("TEST");
        }
        /** ====================================================================================================
         * thenCombine() 테스트
         * ===================================================================================================== */
        @Test
        void thenCombine은_두개의_비동기결과를_조합한다() {
            // given
            CompletableFuture<String> future1 = CompletableFuture.supplyAsync(() -> "hello");
            CompletableFuture<String> future2 = CompletableFuture.supplyAsync(() -> "world");

            // when
            CompletableFuture<String> combined = future1.thenCombine(future2, (s1, s2) -> s1 + " " + s2);

            // then
            assertThat(combined.join())
                    .isEqualTo("hello world");
        }
    }

    @Nested
    @DisplayName("여러 작업을 묶어서 집합으로 관리하는 메서드 테스트")
    public class AggregateMethodTest {
        /** ====================================================================================================
         * allOf() 테스트
         * ===================================================================================================== */
        @Test
        void allOf는_전달된_여러_작업들_중_하나라도_완료되지_않을_경우_전체_작업의_상태는_미완료_상태여야_한다() {
            // given
            CompletableFuture<String> f1 = CompletableFuture.completedFuture("done1"); // 이미 완료
            CompletableFuture<String> f2 = CompletableFuture.completedFuture("done2"); // 이미 완료
            CompletableFuture<String> f3 = new CompletableFuture<>(); // 미완료

            // when
            CompletableFuture<Void> all = CompletableFuture.allOf(f1, f2, f3);

            // then
            assertThat(f1.isDone()).isTrue();
            assertThat(f2.isDone()).isTrue();
            assertThat(f3.isDone()).isFalse();
            assertThat(all.isDone()).isFalse();
        }

        @Test
        void allOf는_여러_작업_중_하나라도_예외_발생시_전체_작업은_완료되지만_결과는_CompletionException으로_감싸져서_던져진다() {
            // given
            CompletableFuture<String> f1 = CompletableFuture.completedFuture("done1");
            CompletableFuture<String> f2 = CompletableFuture.supplyAsync(() -> {
                throw new RuntimeException("예외 발생");
            });
            CompletableFuture<String> f3 = CompletableFuture.supplyAsync(() -> "hello");


            // when
            CompletableFuture<Void> all = CompletableFuture.allOf(f1, f2, f3);

            // then
            assertThat(all).isDone(); // allOf 자체는 완료됨
            assertThatThrownBy(() -> all.join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RuntimeException.class);
        }

        @Test
        void allOf의_join은_모든_작업이_끝날때까지_기다린다() {
            // given
            CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return "done1";
            });

            CompletableFuture<String> f2 = CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return "done2";
            });

            // when
            CompletableFuture<Void> all = CompletableFuture.allOf(f1, f2);
            long start = System.currentTimeMillis();
            all.join(); // 블로킹 발생 200ms + 300ms
            long end = System.currentTimeMillis();

            // then
            // f1은 200ms, f2는 300ms 후에 작업이 완료된다.
            // 따라서 블로킹 시간이 300ms이므로, 로직 수행 시간은 최소 300ms 이상이어야 한다.
            assertThat(end - start).isGreaterThanOrEqualTo(300);
            assertThat(all.isDone()).isTrue();
        }

        /** ====================================================================================================
         * anyOf() 테스트
         * ===================================================================================================== */
        @Test
        void anyOf는_여러_작업중_가장_먼저_끝난_작업의_결과만_반환한다() {
            // given
            CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return "f1";
            });

            CompletableFuture<String> f2 = CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return "f2";
            });

            CompletableFuture<String> f3 = CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return "f3";
            });

            // when
            CompletableFuture<Object> any = CompletableFuture.anyOf(f1, f2, f3);

            // then
            assertThat(any.join()).isEqualTo("f2");
        }

        @Test
        void anyOf는_가장_먼저_끝난_작업이_예외면_CompletionException으로_감싸서_반환한다() {
            // given
            CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return "f1";
            });

            CompletableFuture<String> f2 = CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                throw new RuntimeException("예외 발생");
            });

            CompletableFuture<String> f3 = CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return "f3";
            });

            // when
            CompletableFuture<Object> any = CompletableFuture.anyOf(f1, f2, f3);

            // then
            assertThatThrownBy(() -> any.join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RuntimeException.class);
        }

        @Test
        void anyOf의_join은_가장_빨리_끝난_작업까지만_기다리고_나머지_작업들은_기다리지_않는다() {
            // given
            CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return "f1";
            });

            CompletableFuture<String> f2 = CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return "f2";
            });

            // when
            CompletableFuture<Object> any = CompletableFuture.anyOf(f1, f2);
            long start = System.currentTimeMillis();
            Object result = any.join(); // f2가 더 빨리 끝남
            long end = System.currentTimeMillis();

            // then
            assertThat(result).isEqualTo("f2");
            assertThat(end - start).isLessThan(500); // f1이 끝날 때까지 기다리지 않음
        }

        @Test
        void anyOf는_특정_작업이_완료되더라도_나머지_작업들은_계속_실행된다() {
            // given
            CompletableFuture<String> slow = CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(500); // 느린 작업
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return "slow";
            });

            CompletableFuture<String> fast = CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(100); // 빠른 작업
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return "fast";
            });

            CompletableFuture<Object> any = CompletableFuture.anyOf(slow, fast);

            // when
            Object result = any.join(); // fast가 먼저 끝나므로 즉시 완료됨

            // then
            assertThat(result).isEqualTo("fast");
            assertThat(any.isDone()).isTrue();

            // 하지만 slow는 여전히 실행 중이어야 한다.
            assertThat(slow.isDone()).isFalse();

            // 잠시 대기한 뒤 slow도 정상적으로 끝나는지 확인
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            assertThat(slow.isDone()).isTrue();
            assertThat(slow.join()).isEqualTo("slow");
        }
    }

    @Nested
    @DisplayName("예외 처리 메서드 테스트")
    public class ExceptionHandlingMethodTest {
        /** ====================================================================================================
         * exceptionally() 테스트
         * ===================================================================================================== */
        @Test
        void excetionally는_작업_수행중_예외_발생시_콜백으로_실행되며_대체값을_반환한다() {
            // given
            CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> {
                throw new RuntimeException("예외 발생");
            });

            // exceptionally()가 반환하는 새로운 Future를 받아야 함
            CompletableFuture<String> exceptionFuture = f1.exceptionally(exception -> {
                return exception.getMessage();
            });

            // when
            String result = exceptionFuture.join();

            // then
            assertThat(result).isEqualTo("java.lang.RuntimeException: 예외 발생");
        }

        @Test
        void exceptionally는_예외가_없으면_콜백을_실행하지_않고_기본_결과를_반환한다() {
            //given
            CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> {
                // 예외없이 정상적으로 응답 반환
                return "test";
            });

            // exceptionally()는 새로운 CompletableFuture를 반환하는데,
            // 여기서는 예외가 발생하지 않기 때문에 exceptionally() 콜백은 실행되지 않는다.
            CompletableFuture<String> exceptionallyFuture = f1.exceptionally(ex -> {
                return "exception result";
            });

            // when
            String result = exceptionallyFuture.join();

            // then
            // 예외가 없으므로 원래 값 "test"가 그대로 반환된다
            assertThat(result)
                    .isEqualTo("test");
        }

        /** ====================================================================================================
         * handle() 테스트
         * ===================================================================================================== */
        @Test
        void handle은_정상_완료시_결과값을_받아_새로운_값으로_변환하여_반환한다() {
            // given
            CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> {
                return "test";
            });

            // handle()을 사용해 String을 Integer로 변환
            CompletableFuture<Integer> handleFuture = f1.handle((result, exception) -> {
                if (exception == null)
                    return result.length();
                else
                    return -1; // 예외발생 시 -1 반환
            });

            // when
            Integer result = handleFuture.join();

            // then
            assertThat(result)
                    .isEqualTo(4); // "test"의 길이
        }

        @Test
        void handle은_예외_발생시_예외를_받아_새로운_값으로_변환하여_반환한다() {
            // given
            CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> {
                throw new RuntimeException("예외 발생");
            });

            // handle()을 사용해 예외를 처리하고 String을 Integer로 변환함
            CompletableFuture<Integer> handleFuture = f1.handle((result, exception) -> {
                if(exception == null)
                    return result.length();
                else
                    return -1; // 예외가 발생하기 때문에 -1이 반환됨
            });

            // when
            Integer result = handleFuture.join();

            // then
            assertThat(result)
                    .isEqualTo(-1); // 예외 처리로 -1 반환
        }

        @Test
        void handle은_결과값의_반환타입을_원본_Future와_다르게_변경할_수_있다() {
            // given
            CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> {
                return "test";
            });

            // handle()을 사용해 String -> Boolean으로 변환
            CompletableFuture<Boolean> handleFuture = f1.handle((result, exception) -> {
                if (exception == null)
                    return result.startsWith("h");
                else
                    return false;
            });

            // when
            Boolean result = handleFuture.join();

            // then
            assertThat(result)
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("작업 완료시키는 메서드 테스트")
    public class CompletionMethodTest {
        /** ====================================================================================================
         * complete(), completeExceptionally() 테스트
         * ===================================================================================================== */
        @Test
        void complete는_비동기작업을_수동으로_정상_완료시킨다() {
            // given
            CompletableFuture<String> future = new CompletableFuture<>(); // 미완료 작업

            // 해당 비동기 작업이 미완료 상태인지 확인
            assertThat(future.isDone()).isFalse();

            // when
            boolean completed = future.complete("result");

            // then
            assertThat(completed).isTrue(); // 완료시키는 작업이 성공했는지
            assertThat(future.isDone()).isTrue(); // 비동기 작업 상태가 완료된 상태인지
            assertThat(future.join()).isEqualTo("result"); // 설정한 값을 반환하는지
        }

        @Test
        void completeExceptionally는_비동기작업을_수동으로_예외로_완료시킨다() {
            // given
            CompletableFuture<String> future = new CompletableFuture<>();
            RuntimeException ex = new RuntimeException("예외 발생");

            // when
            boolean completed = future.completeExceptionally(ex);

            // then
            assertThat(completed).isTrue(); // 완료시키는 작업이 성공했는지
            assertThat(future.isDone()).isTrue(); // 비동기 작업 상태가 완료된 상태인지
            assertThat(future.isCompletedExceptionally()).isTrue(); // 예외 완료 상태

            // 예외가 발생하는지 확인
            assertThatThrownBy(() -> future.join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasRootCauseMessage("예외 발생");
        }

        @Test
        void 이미_완료된_비동기작업에_complete를_호출하면_false를_반환한다() {
            // given
            CompletableFuture<String> future = new CompletableFuture<>();
            future.complete("first result"); // 먼저 완료

            // when
            boolean secondComplete = future.complete("second result");

            // then
            assertThat(secondComplete).isFalse(); // 완료시키는 작업은 실패해야 함 -> 이미 완료되어 있어서 false
            assertThat(future.join()).isEqualTo("first result"); // 첫 번째 값 유지
        }

        /** ====================================================================================================
         * obtrudeValue(), obtrudeException() 테스트
         * ===================================================================================================== */
        @Test
        void obtrudeValue는_미완료_비동기작업을_강제로_정상_완료시킨다() {
            // given
            CompletableFuture<String> future = new CompletableFuture<>(); // 미완료 작업

            // 해당 비동기 작업이 미완료 상태인지 확인
            assertThat(future.isDone()).isFalse();

            // when
            future.obtrudeValue("forced result");

            // then
            assertThat(future.isDone()).isTrue(); // 비동기 작업 상태가 완료된 상태인지
            assertThat(future.join()).isEqualTo("forced result"); // 설정한 값을 반환하는지
        }

        @Test
        void obtrudeException는_미완료_비동기작업을_강제로_예외_완료시킨다() {
            // given
            CompletableFuture<String> future = new CompletableFuture<>();
            RuntimeException ex = new RuntimeException("강제 예외");

            // when
            future.obtrudeException(ex);

            // then
            assertThat(future.isDone()).isTrue(); // 비동기 작업 상태가 완료된 상태인지
            assertThat(future.isCompletedExceptionally()).isTrue(); // 예외 완료 상태

            // 예외가 발생하는지 확인
            assertThatThrownBy(() -> future.join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasRootCauseMessage("강제 예외");
        }

        @Test
        void 이미_완료된_비동기작업에_obtrudeValue를_호출하면_결과값이_덮어쓰여진다() {
            // given
            CompletableFuture<String> future = new CompletableFuture<>();
            future.complete("original result"); // 먼저 완료

            // when
            future.obtrudeValue("overridden result");

            // then
            assertThat(future.isDone()).isTrue(); // 여전히 완료 상태
            assertThat(future.join()).isEqualTo("overridden result"); // 덮어쓴 값으로 변경됨
        }

        @Test
        void 예외로_완료된_비동기작업에_obtrudeValue를_호출하면_정상값으로_덮어쓰여진다() {
            // given
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(new RuntimeException("원본 예외")); // 예외로 완료

            // when
            future.obtrudeValue("recovered result");

            // then
            assertThat(future.isDone()).isTrue(); // 여전히 완료 상태
            assertThat(future.isCompletedExceptionally()).isFalse(); // 더 이상 예외 완료 상태가 아님
            assertThat(future.join()).isEqualTo("recovered result"); // 정상값으로 복구됨
        }

        /** ====================================================================================================
         * cancel() 테스트
         * ===================================================================================================== */
        @Test
        void cancel은_아직_미완료된_비동기작업을_취소_상태로_완료시킨다() {
            // given
            CompletableFuture<String> future = new CompletableFuture<>(); // 미완료 작업

            // 해당 비동기 작업이 미완료 상태인지 확인
            assertThat(future.isDone()).isFalse();
            assertThat(future.isCancelled()).isFalse();

            // when
            boolean cancelled = future.cancel(true);

            // then
            assertThat(cancelled).isTrue(); // 취소 성공인지
            assertThat(future.isDone()).isTrue(); // 비동기 작업 상태가 완료된 상태인지
            assertThat(future.isCancelled()).isTrue(); // 취소 상태인지
            assertThat(future.isCompletedExceptionally()).isTrue(); // 예외 완료 상태인지

            // CancellationException이 발생하는지 확인
            assertThatThrownBy(() -> future.join())
                    .isInstanceOf(CancellationException.class);
        }

        @Test
        void 이미_완료된_비동기작업에_cancel을_호출하면_false를_반환한다() {
            // given
            CompletableFuture<String> future = new CompletableFuture<>();
            future.complete("completed result"); // 먼저 완료

            // when
            boolean cancelled = future.cancel(true);

            // then
            assertThat(cancelled).isFalse(); // 취소 실패해야 함 -> 이미 완료되어 있어서 false
            assertThat(future.isCancelled()).isFalse(); // 취소 상태가 아님
            assertThat(future.join()).isEqualTo("completed result"); // 기존 값 유지
        }

        @Test
        void 이미_취소된_비동기작업에_cancel을_호출하면_true를_반환한다() {
            // given
            CompletableFuture<String> future = new CompletableFuture<>();
            future.cancel(true);

            // when
            boolean cancelled = future.cancel(true);

            // then
            assertThat(cancelled).isTrue(); // 이미 취소된 상태라서 true를 반환함
            assertThat(future.isCancelled()).isTrue(); // 취소 상태를 유지함

            // 여전히 CancellationException이 발생하는지 확인
            assertThatThrownBy(() -> future.join())
                    .isInstanceOf(CancellationException.class);
        }

        @Test
        void cancel의_mayInterruptIfRunning_매개변수는_동작에_영향을_주지_않는다() {
            // given
            CompletableFuture<String> future1 = new CompletableFuture<>();
            CompletableFuture<String> future2 = new CompletableFuture<>();

            // when
            boolean cancelled1 = future1.cancel(true);   // mayInterruptIfRunning = true
            boolean cancelled2 = future2.cancel(false);  // mayInterruptIfRunning = false

            // then
            assertThat(cancelled1).isTrue();
            assertThat(cancelled2).isTrue();
            assertThat(future1.isCancelled()).isTrue();
            assertThat(future2.isCancelled()).isTrue();

            // 둘 다 동일하게 CancellationException 발생
            assertThatThrownBy(() -> future1.join())
                    .isInstanceOf(CancellationException.class);
            assertThatThrownBy(() -> future2.join())
                    .isInstanceOf(CancellationException.class);
        }
    }

    @Nested
    @DisplayName("작업 결과를 가져오는 메서드 테스트")
    public class ResultMethodTest {
        /** ====================================================================================================
         * join() 테스트
         * ===================================================================================================== */
        @Test
        void join은_정상_완료된_비동기작업의_결과값을_반환한다() {
            // given
            CompletableFuture<String> future = CompletableFuture.completedFuture("success");

            // when & then
            String result = future.join(); // checked exception 없이 호출 가능
            assertThat(result).isEqualTo("success");
        }

        @Test
        void join은_예외로_완료된_비동기작업에_대해_CompletionException을_던진다() {
            // given
            CompletableFuture<String> future = new CompletableFuture<>();
            RuntimeException originalException = new RuntimeException("원본 예외");
            future.completeExceptionally(originalException);

            // when & then
            assertThatThrownBy(() -> future.join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasRootCauseMessage("원본 예외");
        }

        @Test
        void join은_취소된_비동기작업에_대해_CancellationException을_던진다() {
            // given
            CompletableFuture<String> future = new CompletableFuture<>();
            future.cancel(true);

            // when & then
            assertThatThrownBy(() -> future.join())
                    .isInstanceOf(CancellationException.class);
        }

        @Test
        void join은_미완료_비동기작업이_완료될_때까지_대기한다() throws InterruptedException {
            // given
            CompletableFuture<String> future = new CompletableFuture<>();
            AtomicReference<String> result = new AtomicReference<>();
            AtomicBoolean finished = new AtomicBoolean(false);

            // 별도 쓰레드에서 join() 호출
            Thread joinThread = new Thread(() -> {
                result.set(future.join()); // 완료까지 대기
                finished.set(true);
            });
            joinThread.start();

            // when
            Thread.sleep(100); // 잠시 대기해서 join()이 블로킹되는지 확인
            assertThat(finished.get()).isFalse(); // 아직 완료되지 않았어야 함

            future.complete("delayed result"); // 이제 완료
            joinThread.join(1000); // 쓰레드 완료 대기

            // then
            assertThat(finished.get()).isTrue(); // 이제 완료되어야 함
            assertThat(result.get()).isEqualTo("delayed result");
        }

        /** ====================================================================================================
         * getNow() 테스트
         * ===================================================================================================== */
        @Test
        void getNow는_정상_완료된_비동기작업의_결과값을_즉시_반환한다() {
            // given
            CompletableFuture<String> future = CompletableFuture.completedFuture("success");

            // when & then
            String result = future.getNow("default"); // 블로킹하지 않음
            assertThat(result).isEqualTo("success");
        }

        @Test
        void getNow는_미완료_비동기작업에_대해_기본값을_즉시_반환한다() {
            // given
            CompletableFuture<String> future = new CompletableFuture<>(); // 미완료 작업

            // when & then
            String result = future.getNow("default value"); // 블로킹하지 않음
            assertThat(result).isEqualTo("default value");
            assertThat(future.isDone()).isFalse(); // 여전히 미완료 상태
        }

        @Test
        void getNow는_예외로_완료된_비동기작업에_대해_CompletionException을_던진다() {
            // given
            CompletableFuture<String> future = new CompletableFuture<>();
            RuntimeException originalException = new RuntimeException("원본 예외");
            future.completeExceptionally(originalException);

            // when & then
            assertThatThrownBy(() -> future.getNow("default"))
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasRootCauseMessage("원본 예외");
        }

        @Test
        void getNow는_취소된_비동기작업에_대해_CancellationException을_던진다() {
            // given
            CompletableFuture<String> future = new CompletableFuture<>();
            future.cancel(true);

            // when & then
            assertThatThrownBy(() -> future.getNow("default"))
                    .isInstanceOf(CancellationException.class);
        }

        @Test
        void getNow는_블로킹하지_않고_즉시_반환된다() {
            // given
            CompletableFuture<String> future = new CompletableFuture<>(); // 미완료 작업
            long startTime = System.currentTimeMillis();

            // when
            String result = future.getNow("immediate");
            long endTime = System.currentTimeMillis();

            // then
            assertThat(result).isEqualTo("immediate");
            assertThat(endTime - startTime).isLessThan(10); // 거의 즉시 반환 (10ms 미만)
            assertThat(future.isDone()).isFalse(); // 여전히 미완료 상태
        }
    }
}
