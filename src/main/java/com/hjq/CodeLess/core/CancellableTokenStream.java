package com.hjq.CodeLess.core;

import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Bridges Reactor cancellation to the provider connection, including cancellation before the first token. */
public final class CancellableTokenStream {
    private CancellableTokenStream() {}

    public static Flux<String> toFlux(TokenStream stream) {
        return Flux.create(sink -> {
            var rememberHandle = trackCancellation(sink);
            stream.onPartialResponseWithContext((response, context) -> {
                        rememberHandle.accept(context.streamingHandle());
                        sink.next(response.text());
                    })
                    .onPartialThinkingWithContext((thinking, context) -> rememberHandle.accept(context.streamingHandle()))
                    .onPartialToolCallWithContext((toolCall, context) -> rememberHandle.accept(context.streamingHandle()))
                    .onCompleteResponse(ignored -> sink.complete())
                    .onError(sink::error)
                    .start();
        });
    }

    static Consumer<StreamingHandle> trackCancellation(FluxSink<?> sink) {
        AtomicReference<StreamingHandle> handle = new AtomicReference<>();
        Runnable cancel = () -> {
            StreamingHandle current = handle.getAndSet(null);
            if (current != null) current.cancel();
        };
        sink.onCancel(cancel::run);
        return current -> {
            handle.set(current);
            if (sink.isCancelled()) cancel.run();
        };
    }
}
