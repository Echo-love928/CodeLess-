package com.hjq.CodeLess.core;

import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CancellableTokenStreamTest {
    @Test
    void cancellationReachesModelHandle() {
        TokenStream stream = mock(TokenStream.class, RETURNS_SELF);
        AtomicReference<BiConsumer<PartialResponse, PartialResponseContext>> callback = new AtomicReference<>();
        doAnswer(invocation -> { callback.set(invocation.getArgument(0)); return stream; })
                .when(stream).onPartialResponseWithContext(any());
        StreamingHandle handle = mock(StreamingHandle.class);
        var flux = CancellableTokenStream.toFlux(stream);
        verify(stream, never()).start();
        Disposable subscription = flux.subscribe();
        callback.get().accept(new PartialResponse("hello"), new PartialResponseContext(handle));
        subscription.dispose();
        verify(handle).cancel();
    }

    @Test
    void cancellationBeforeFirstTokenCancelsWhenHandleArrives() {
        TokenStream stream = mock(TokenStream.class, RETURNS_SELF);
        AtomicReference<BiConsumer<PartialResponse, PartialResponseContext>> callback = new AtomicReference<>();
        doAnswer(invocation -> { callback.set(invocation.getArgument(0)); return stream; })
                .when(stream).onPartialResponseWithContext(any());
        StreamingHandle handle = mock(StreamingHandle.class);
        Disposable subscription = CancellableTokenStream.toFlux(stream).subscribe();
        subscription.dispose();
        callback.get().accept(new PartialResponse("late"), new PartialResponseContext(handle));
        verify(handle).cancel();
    }
}
