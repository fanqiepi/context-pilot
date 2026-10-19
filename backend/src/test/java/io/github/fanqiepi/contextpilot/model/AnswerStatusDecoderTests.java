package io.github.fanqiepi.contextpilot.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.github.fanqiepi.contextpilot.common.InternalServiceException;
import static org.assertj.core.api.Assertions.*;

class AnswerStatusDecoderTests {
    @ParameterizedTest
    @ValueSource(strings = {"ANSWERED\n", "REFUSED\n", "ANSWERED\r\n", "REFUSED\r\n"})
    void decodesEverySplitWithoutLeakingHeader(String header) {
        String body = "Independent Solstice fixture: ANSWERED and REFUSED are body text.";
        String wire = header + body;
        for (int split = 0; split <= wire.length(); split++) {
            AnswerStatusDecoder decoder = new AnswerStatusDecoder();
            String actual = decoder.accept(wire.substring(0, split)) + decoder.accept(wire.substring(split));
            decoder.finish();
            assertThat(actual).isEqualTo(body);
            assertThat(decoder.refused()).isEqualTo(header.startsWith("REFUSED"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ANSWERED", "REFUSED\n ", "answer\nbody", " ANSWERED\nbody", "```\nREFUSED\nbody", "12345678901234567"})
    void rejectsMissingInvalidOrEmptyEnvelope(String wire) {
        AnswerStatusDecoder decoder = new AnswerStatusDecoder();
        assertThatThrownBy(() -> { decoder.accept(wire); decoder.finish(); })
                .isInstanceOf(InternalServiceException.class);
    }
}
