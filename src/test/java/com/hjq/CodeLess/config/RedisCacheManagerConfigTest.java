package com.hjq.CodeLess.config;

import dev.langchain4j.community.store.embedding.redis.spring.RedisEmbeddingStoreAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;

class RedisCacheManagerConfigTest {

    @Test
    void bootProvidesTheRedisConnectionFactoryUsedByTheCacheManager() {
        new ApplicationContextRunner()
                .withUserConfiguration(RedisStartupConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RedisConnectionFactory.class);
                    assertThat(context).hasSingleBean(RedisCacheManager.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {RedisEmbeddingStoreAutoConfiguration.class, DataSourceAutoConfiguration.class})
    @Import(RedisCacheManagerConfig.class)
    static class RedisStartupConfiguration {
    }
}
