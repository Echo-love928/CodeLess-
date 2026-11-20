package com.hjq.CodeLess.service;

import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One active generation per application in this backend instance. */
@Service
public class AppGenerationService {
    private final ConcurrentHashMap<Long, Task> tasks = new ConcurrentHashMap<>();

    public Flux<String> generate(Long appId, Supplier<Flux<String>> source, Consumer<String> writeStatus,
                                 boolean continueOnDisconnect) {
        Flux<String> generation = generate(appId, source, writeStatus);
        if (!continueOnDisconnect) return generation;

        return Flux.defer(() -> {
            // 浏览器订阅开始时启动生成，断开后由后台订阅继续消费工具分片。
            Flux<String> shared = generation.publish().autoConnect(2);
            shared.subscribe(ignored -> {}, error -> {
                // 上游已保存失败状态和对话记录。
            });
            return shared;
        });
    }

    public Flux<String> generate(Long appId, Supplier<Flux<String>> source, Consumer<String> writeStatus) {
        return Flux.defer(() -> {
            Task task = new Task(writeStatus);
            if (tasks.putIfAbsent(appId, task) != null) {
                return Flux.error(new BusinessException(ErrorCode.OPERATION_ERROR, "应用正在生成、构建或切换版本，请稍后重试"));
            }
            return Flux.defer(() -> {
                        writeStatus.accept("generating");
                        return source.get();
                    })
                    .takeUntilOther(task.stopSignal.asMono())
                    .concatWith(Flux.defer(() -> task.stopped
                            ? Flux.error(new CancellationException("生成已停止")) : Flux.empty()))
                    .doOnComplete(() -> finish(appId, task, "completed"))
                    .doOnError(error -> finish(appId, task, task.stopped ? "stopped" : "failed"))
                    .doFinally(signal -> {
                        if (signal == SignalType.CANCEL) finish(appId, task, "stopped");
                    });
        });
    }

    /** 在生成流结束后继续保留应用锁，直至构建不再写入当前版本目录。 */
    public void startVueBuild(long appId, Supplier<CompletableFuture<Boolean>> build) {
        Task task = tasks.get(appId);
        if (task == null) return;
        synchronized (task) {
            if (task.generation && !task.finished && !task.stopped && task.build == null) {
                task.build = build.get();
            }
        }
    }

    public boolean stop(Long appId) {
        Task task = tasks.get(appId);
        if (task == null || !task.generation) return false;
        synchronized (task) {
            if (task.finished) return false;
            task.stopped = true;
            task.stopSignal.tryEmitValue(true);
            return true;
        }
    }

    /** Use the same application lock for restores and deployments as for generation. */
    public <T> T withIdleApp(Long appId, Supplier<T> action) {
        Task task = new Task(ignored -> {});
        task.generation = false;
        if (tasks.putIfAbsent(appId, task) != null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "应用正在生成、构建或切换版本，请稍后重试");
        }
        try { return action.get(); }
        finally { tasks.remove(appId, task); }
    }

    private void finish(Long appId, Task task, String status) {
        synchronized (task) {
            if (task.finished) return;
            task.finished = true;
            try {
                task.writeStatus.accept(status);
            } finally {
                if (task.build == null) {
                    tasks.remove(appId, task);
                } else {
                    task.build.whenComplete((result, error) -> tasks.remove(appId, task));
                }
            }
        }
    }

    private static final class Task {
        private final Consumer<String> writeStatus;
        private final Sinks.One<Boolean> stopSignal = Sinks.one();
        private volatile boolean stopped;
        private boolean finished;
        private boolean generation = true;
        private CompletableFuture<Boolean> build;

        private Task(Consumer<String> writeStatus) { this.writeStatus = writeStatus; }
    }
}
