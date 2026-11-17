package com.hjq.CodeLess.core;

import com.hjq.CodeLess.ai.AiCodeGeneratorService;
import com.hjq.CodeLess.ai.model.HtmlCodeResult;
import com.hjq.CodeLess.ai.model.MultiFileCodeResult;
import com.hjq.CodeLess.model.enums.CodeGenTypeEnum;
import com.hjq.CodeLess.service.AppVersionService;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.time.Duration;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiCodeGeneratorFacadeMemoryTest {
    private static final Long APP_ID = 1990000000000000001L;
    private final AiCodeGeneratorService ai = mock(AiCodeGeneratorService.class);
    private final AppVersionService versions = mock(AppVersionService.class);
    private final AiCodeGeneratorFacade facade = new AiCodeGeneratorFacade();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(facade, "aiCodeGeneratorService", ai);
        ReflectionTestUtils.setField(facade, "appVersionService", versions);
        when(versions.generationPrompt(APP_ID, "request")).thenReturn("current version context");
    }

    @ParameterizedTest
    @EnumSource(CodeGenTypeEnum.class)
    void synchronousGenerationPassesAppIdAndKeepsVersionContext(CodeGenTypeEnum type) {
        Object result;
        if (type == CodeGenTypeEnum.HTML) {
            result = new HtmlCodeResult();
            when(ai.generateHtmlCode(APP_ID, "current version context")).thenReturn((HtmlCodeResult) result);
        } else {
            result = new MultiFileCodeResult();
            when(ai.generateMultiFileCode(APP_ID, "current version context")).thenReturn((MultiFileCodeResult) result);
        }
        var directory = new File("unused-unit-test-output");
        when(versions.saveGeneratedCode(result, type, APP_ID, "request")).thenReturn(directory);

        assertSame(directory, facade.generateAndSaveCode("request", type, APP_ID));
        if (type == CodeGenTypeEnum.HTML) verify(ai).generateHtmlCode(APP_ID, "current version context");
        else verify(ai).generateMultiFileCode(APP_ID, "current version context");
        verify(versions).saveGeneratedCode(result, type, APP_ID, "request");
    }

    @ParameterizedTest
    @EnumSource(CodeGenTypeEnum.class)
    void streamingGenerationIsLazyAndPassesTheAppId(CodeGenTypeEnum type) {
        var tokens = mock(TokenStream.class, RETURNS_SELF);
        if (type == CodeGenTypeEnum.HTML) {
            when(ai.generateHtmlCodeTokenStream(APP_ID, "current version context")).thenReturn(tokens);
        } else {
            when(ai.generateMultiFileCodeTokenStream(APP_ID, "current version context")).thenReturn(tokens);
        }
        doAnswer(call -> {
            Consumer<ChatResponse> callback = call.getArgument(0);
            doAnswer(start -> { callback.accept(null); return null; }).when(tokens).start();
            return tokens;
        }).when(tokens).onCompleteResponse(any());
        when(versions.saveGeneratedCode(any(), eq(type), eq(APP_ID), eq("request")))
                .thenReturn(new File("unused-unit-test-output"));

        var stream = facade.generateAndSaveCodeStream("request", type, APP_ID);
        verifyNoInteractions(ai, versions);
        stream.blockLast(Duration.ofSeconds(5));
        if (type == CodeGenTypeEnum.HTML) verify(ai).generateHtmlCodeTokenStream(APP_ID, "current version context");
        else verify(ai).generateMultiFileCodeTokenStream(APP_ID, "current version context");
        verify(versions).saveGeneratedCode(any(), eq(type), eq(APP_ID), eq("request"));
    }
}
