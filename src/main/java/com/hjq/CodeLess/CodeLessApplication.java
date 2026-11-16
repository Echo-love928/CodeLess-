package com.hjq.CodeLess;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.hjq.CodeLess.mapper")
public class CodeLessApplication {

    public static void main(String[] args) {
        SpringApplication.run(com.hjq.CodeLess.CodeLessApplication.class, args);
    }

}
