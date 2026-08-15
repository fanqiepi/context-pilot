package io.github.fanqiepi.contextpilot.evaluation;

import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class V3EnvironmentEvaluationTests {

    @Test
    void requiresDockerWhenRunningTheV3EvaluationProfile() {
        assumeTrue(Boolean.getBoolean("contextpilot.evaluation.require-docker"));

        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as("The v3-evaluation profile requires Docker for PostgreSQL/pgvector lifecycle cases")
                .isTrue();
    }
}
