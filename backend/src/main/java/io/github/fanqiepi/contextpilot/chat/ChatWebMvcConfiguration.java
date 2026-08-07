package io.github.fanqiepi.contextpilot.chat;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class ChatWebMvcConfiguration {

    @Bean(name = "chatStreamTaskExecutor")
    ThreadPoolTaskExecutor chatStreamTaskExecutor(ChatProperties properties) {
        if (properties.getStreamMaxPoolSize() < properties.getStreamCorePoolSize()) {
            throw new IllegalStateException(
                    "contextpilot.chat.stream-max-pool-size must not be smaller than stream-core-pool-size");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getStreamCorePoolSize());
        executor.setMaxPoolSize(properties.getStreamMaxPoolSize());
        executor.setQueueCapacity(properties.getStreamQueueCapacity());
        executor.setThreadNamePrefix("chat-stream-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    @Bean
    WebMvcConfigurer chatAsyncWebMvcConfigurer(
            @Qualifier("chatStreamTaskExecutor") AsyncTaskExecutor executor,
            ChatProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                configurer.setTaskExecutor(executor);
                configurer.setDefaultTimeout(properties.getStreamTimeoutMillis());
            }
        };
    }
}
