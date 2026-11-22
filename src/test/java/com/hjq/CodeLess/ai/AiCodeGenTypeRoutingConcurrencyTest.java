package com.hjq.CodeLess.ai;

import com.hjq.CodeLess.model.enums.CodeGenTypeEnum;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCodeGenTypeRoutingConcurrencyTest {
    private static final int REQUEST_COUNT = 24;
    private static final long TIMEOUT_SECONDS = 10;

    @ParameterizedTest(name = "使用默认路由 Bean = {0}")
    @ValueSource(booleans = {false, true})
    void concurrentRoutingUsesADedicatedModelPerRequest(boolean useDefaultBean) throws Exception {
        Set<ChatModel> modelsUsed = ConcurrentHashMap.newKeySet();
        var requestsInFlight = new CyclicBarrier(REQUEST_COUNT);
        var types = CodeGenTypeEnum.values();

        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("routingChatModelPrototype", ChatModel.class,
                    () -> routingModel(modelsUsed, requestsInFlight),
                    definition -> definition.setScope(ConfigurableBeanFactory.SCOPE_PROTOTYPE));
            context.register(AiCodeGenTypeRoutingServiceFactory.class);
            context.refresh();

            var factory = context.getBean(AiCodeGenTypeRoutingServiceFactory.class);
            var defaultRouter = context.getBean(AiCodeGenTypeRoutingService.class);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<CodeGenTypeEnum>> results = new ArrayList<>(REQUEST_COUNT);
                for (int index = 0; index < REQUEST_COUNT; index++) {
                    var requestedType = types[index % types.length];
                    results.add(executor.submit(() -> {
                        var router = useDefaultBean ? defaultRouter : factory.createAiCodeGenTypeRoutingService();
                        return router.routeCodeGenType(requestedType.name());
                    }));
                }

                for (int index = 0; index < REQUEST_COUNT; index++) {
                    assertEquals(types[index % types.length], results.get(index).get(TIMEOUT_SECONDS, SECONDS));
                }
            }
        }

        assertEquals(REQUEST_COUNT, modelsUsed.size(), "每次路由调用应使用独立的模型实例");
    }

    private static ChatModel routingModel(Set<ChatModel> modelsUsed, CyclicBarrier requestsInFlight) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                assertTrue(Thread.currentThread().isVirtual(), "模型应在虚拟线程中执行");
                modelsUsed.add(this);

                // 所有请求进入模型后才返回，确保测试覆盖同时执行的路由调用。
                try {
                    requestsInFlight.await(TIMEOUT_SECONDS, SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("并发路由调用被中断", e);
                } catch (BrokenBarrierException | TimeoutException e) {
                    throw new AssertionError("并发路由调用未全部进入模型", e);
                }

                var prompt = (UserMessage) request.messages().getLast();
                // LangChain4j 在测试提示后附加枚举输出约束。
                String requestedType = prompt.singleText().lines().findFirst().orElseThrow();
                return ChatResponse.builder().aiMessage(AiMessage.from(requestedType)).build();
            }
        };
    }
}
