package com.hjq.CodeLess.core;

import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Bridges Reactor cancellation to the provider connection, including cancellation before the first token. */
public final class CancellableTokenStream {
    private CancellableTokenStream() {}

    public static Flux<String> toFlux(TokenStream stream) {
        return Flux.create(sink -> {
            AtomicBoolean cancelled = new AtomicBoolean();
            AtomicReference<StreamingHandle> handle = new AtomicReference<>();
            sink.onCancel(() -> {
                cancelled.set(true);
                StreamingHandle current = handle.getAndSet(null);
                if (current != null) current.cancel();
            });
            java.util.function.Consumer<StreamingHandle> rememberHandle = current -> {
                handle.set(current);
                if (cancelled.get()) {
                    StreamingHandle pending = handle.getAndSet(null);
                    if (pending != null) pending.cancel();
                }
            };
            stream.onPartialResponseWithContext((response, context) -> {
                        rememberHandle.accept(context.streamingHandle());
                        if (!cancelled.get()) sink.next(response.text());
                    })
                    .onPartialThinkingWithContext((thinking, context) -> rememberHandle.accept(context.streamingHandle()))
                    .onCompleteResponse(ignored -> sink.complete())
                    .onError(sink::error)
                    .start();
        });
    }
}
