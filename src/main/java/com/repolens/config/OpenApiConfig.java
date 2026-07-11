package com.repolens.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI repoLensOpenApi() {
        return new OpenAPI().info(
                new Info()
                        .title("RepoLens API")
                        .version("v0.1")
                        .description("RepoLens 第一阶段后端骨架接口文档")
        );
    }
}
