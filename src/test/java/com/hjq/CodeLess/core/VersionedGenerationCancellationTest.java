package com.hjq.CodeLess.core;

import com.hjq.CodeLess.ai.AiCodeGeneratorService;
import com.hjq.CodeLess.model.enums.CodeGenTypeEnum;
import com.hjq.CodeLess.service.AppVersionService;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VersionedGenerationCancellationTest {
    @Test
    void stoppedGenerationNeverCreatesSnapshotEvenIfProviderCompletesLate() {
        var ai = mock(AiCodeGeneratorService.class);
        var versions = mock(AppVersionService.class);
        var tokens = mock(TokenStream.class, RETURNS_SELF);
        var handle = mock(StreamingHandle.class);
        var partial = new AtomicReference<BiConsumer<PartialResponse, PartialResponseContext>>();
        var completed = new AtomicReference<Consumer<ChatResponse>>();
        when(versions.generationPrompt(1L, "request")).thenReturn("context");
        when(ai.generateHtmlCodeTokenStream("context")).thenReturn(tokens);
        doAnswer(call -> { partial.set(call.getArgument(0)); return tokens; }).when(tokens).onPartialResponseWithContext(any());
        doAnswer(call -> { completed.set(call.getArgument(0)); return tokens; }).when(tokens).onCompleteResponse(any());
        var facade = new AiCodeGeneratorFacade();
        ReflectionTestUtils.setField(facade, "aiCodeGeneratorService", ai);
        ReflectionTestUtils.setField(facade, "appVersionService", versions);
        var subscription = facade.generateAndSaveCodeStream("request", CodeGenTypeEnum.HTML, 1L).subscribe();
        partial.get().accept(new PartialResponse("```html\n<h1>unfinished"), new PartialResponseContext(handle));
        subscription.dispose();
        completed.get().accept(null);
        verify(handle).cancel();
        verify(versions, never()).saveGeneratedCode(any(), any(), anyLong(), anyString());
    }
}
