package io.github.fanqiepi.contextpilot.evaluation;

import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class V2EnvironmentEvaluationTests {

    @Test
    void requiresDockerWhenRunningTheV2EvaluationProfile() {
        assumeTrue(Boolean.getBoolean("contextpilot.evaluation.require-docker"));

        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as("The v2-evaluation profile requires Docker for persisted action safety cases")
                .isTrue();
    }
}
