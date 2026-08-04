package io.github.fanqiepi.contextpilot.common.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI contextPilotOpenApi() {
        return new OpenAPI().info(new Info()
                .title("ContextPilot API")
                .description("知识库与文档管理接口")
                .version("0.0.1"));
    }
}
