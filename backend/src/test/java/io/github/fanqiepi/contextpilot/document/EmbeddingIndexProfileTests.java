package io.github.fanqiepi.contextpilot.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingIndexProfileTests {

    @Test
    void providesExplicitDefaultProductionProfile() {
        EmbeddingIndexProfile profile = new EmbeddingIndexProperties().currentProfile();

        assertThat(profile.id()).isEqualTo("dashscope_qwen3_7_1024_v1");
        assertThat(profile.model()).isEqualTo("qwen3.7-text-embedding");
        assertThat(profile.dimensions()).isEqualTo(1024);
    }

    @Test
    void rejectsUnsafeProfileIdentifier() {
        assertThatThrownBy(() -> new EmbeddingIndexProfile(
                "Qwen Profile", "DASHSCOPE", "qwen3.7-text-embedding", 1024, "v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profile id");
    }
}
