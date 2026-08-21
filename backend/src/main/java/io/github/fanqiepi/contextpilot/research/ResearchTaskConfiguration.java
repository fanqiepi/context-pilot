package io.github.fanqiepi.contextpilot.research;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@ConditionalOnProperty(prefix = "contextpilot.research", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ResearchTaskConfiguration {
    @Bean(name = "researchTaskExecutor")
    Executor researchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("research-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean(name = "researchIoExecutor")
    AsyncTaskExecutor researchIoExecutor(
            @Value("${contextpilot.research.retrieval-parallelism:4}") int retrievalParallelism) {
        if (retrievalParallelism < 1 || retrievalParallelism > 5) {
            throw new IllegalArgumentException("Research retrieval parallelism must be between 1 and 5");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("research-io-");
        executor.setCorePoolSize(retrievalParallelism);
        executor.setMaxPoolSize(retrievalParallelism);
        executor.setQueueCapacity(40);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
