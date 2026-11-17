package com.hjq.CodeLess;

import dev.langchain4j.community.store.embedding.redis.spring.RedisEmbeddingStoreAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {RedisEmbeddingStoreAutoConfiguration.class})
@EnableScheduling
@MapperScan({"com.hjq.CodeLess.mapper", "com.hjq.CodeLess.genresult.mapper"})
public class CodeLessApplication {

    public static void main(String[] args) {
        SpringApplication.run(com.hjq.CodeLess.CodeLessApplication.class, args);
    }

}
