package com.hjq.CodeLess.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;

/**
 * AI代码生成类型路由服务工厂
 *
 *
 */
@Slf4j
@Configuration
public class AiCodeGenTypeRoutingServiceFactory {

    private final ObjectFactory<ChatModel> routingChatModelFactory;

    public AiCodeGenTypeRoutingServiceFactory(
            @Qualifier("routingChatModelPrototype") ObjectFactory<ChatModel> routingChatModelFactory) {
        this.routingChatModelFactory = routingChatModelFactory;
    }

    /**
     * 创建AI代码生成类型路由服务实例
     */
    public AiCodeGenTypeRoutingService createAiCodeGenTypeRoutingService() {
        return AiServices.builder(AiCodeGenTypeRoutingService.class)
                .chatModel(routingChatModelFactory.getObject())
                .build();
    }

    /**
     * 默认入口在每次路由调用时创建服务和模型实例
     */
    @Bean
    @Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE, proxyMode = ScopedProxyMode.INTERFACES)
    public AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService() {
        return createAiCodeGenTypeRoutingService();
    }
}
