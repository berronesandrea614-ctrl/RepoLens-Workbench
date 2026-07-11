package com.repolens;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan({"com.repolens.mapper", "com.repolens.kernel.persistence.mapper"})
@EnableScheduling
public class RepoLensApplication {

    public static void main(String[] args) {
        SpringApplication.run(RepoLensApplication.class, args);
    }
}
