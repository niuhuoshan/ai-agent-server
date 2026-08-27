package group.aitools.nhs.common.core.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * 线程工具
 *
 * @author Lion Li
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ThreadUtils {

    /**
     * 批量执行任务
     */
    public static void virtualInvokeAll(Runnable... runnableList) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        List<Future<?>> callableList = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Runnable runnable : runnableList) {
                callableList.add(executor.submit(runnable));
            }
            for (Future<?> future : callableList) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("线程执行被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new RuntimeException("线程执行异常：" + cause.getMessage(), cause);
        }

    }

    /**
     * 批量执行有返回值的任务
     *
     * @param supplierList 任务列表
     * @param <T>          返回值类型
     * @return 按提交顺序返回的任务结果
     */
    @SafeVarargs
    public static <T> List<T> virtualSubmitAll(Supplier<T>... supplierList) {
        return virtualSubmitAll(List.of(supplierList));
    }

    /**
     * 批量执行有返回值的任务
     *
     * @param supplierList 任务列表
     * @param <T>          返回值类型
     * @return 按提交顺序返回的任务结果
     */
    public static <T> List<T> virtualSubmitAll(Collection<? extends Supplier<T>> supplierList) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        List<Future<T>> futureList = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Supplier<T> supplier : supplierList) {
                futureList.add(executor.submit(supplier::get));
            }
            List<T> resultList = new ArrayList<>(futureList.size());
            for (Future<T> future : futureList) {
                resultList.add(future.get());
            }
            return resultList;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("线程执行被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new RuntimeException("线程执行异常：" + cause.getMessage(), cause);
        }
    }

}
