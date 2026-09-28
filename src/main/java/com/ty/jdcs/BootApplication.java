package com.ty.jdcs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot启动类
 *
 * @Author Tommy
 * @Date 2026/9/25
 */
@SpringBootApplication // 默认是扫描当前包以及子包的所有类
@EnableScheduling // 启动定时任务
public class BootApplication {

    public static void main(String[] args) {
        SpringApplication.run(BootApplication.class, args);
    }
}
