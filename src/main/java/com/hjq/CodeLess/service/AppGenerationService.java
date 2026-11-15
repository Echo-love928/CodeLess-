package com.hjq.CodeLess.service;

import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One active generation per application in this backend instance. */
@Service
public class AppGenerationService {
    private final ConcurrentHashMap<Long, Task> tasks = new ConcurrentHashMap<>();

    public Flux<String> generate(Long appId, Supplier<Flux<String>> source, Consumer<String> writeStatus) {
        return Flux.defer(() -> {
            Task task = new Task(writeStatus);
            if (tasks.putIfAbsent(appId, task) != null) {
                return Flux.error(new BusinessException(ErrorCode.OPERATION_ERROR, "应用正在生成中，请先停止当前生成"));
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

    public boolean stop(Long appId) {
        Task task = tasks.get(appId);
        if (task == null) return false;
        synchronized (task) {
            if (task.finished) return false;
            task.stopped = true;
            task.stopSignal.tryEmitValue(true);
            return true;
        }
    }

    private void finish(Long appId, Task task, String status) {
        synchronized (task) {
            if (task.finished) return;
            task.finished = true;
            try {
                task.writeStatus.accept(status);
            } finally {
                tasks.remove(appId, task);
            }
        }
    }

    private static final class Task {
        private final Consumer<String> writeStatus;
        private final Sinks.One<Boolean> stopSignal = Sinks.one();
        private volatile boolean stopped;
        private boolean finished;

        private Task(Consumer<String> writeStatus) { this.writeStatus = writeStatus; }
    }
}
