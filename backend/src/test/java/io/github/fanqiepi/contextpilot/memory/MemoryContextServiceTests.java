package io.github.fanqiepi.contextpilot.memory;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MemoryContextServiceTests {

    @Test
    void ignoreModeNeverReadsOrRecordsMemory() {
        MemoryContextPersistenceService persistence = mock(MemoryContextPersistenceService.class);
        MemoryContextService service = new MemoryContextService(persistence);

        MemoryContext context = service.prepareForMessage(
                UUID.randomUUID(), MemoryMode.IGNORE, "trace-ignore");

        assertThat(context.isEmpty()).isTrue();
        verifyNoInteractions(persistence);
    }

    @Test
    void dependencyFailureSafelyDegradesToNoMemory() {
        MemoryContextPersistenceService persistence = mock(MemoryContextPersistenceService.class);
        UUID messageId = UUID.randomUUID();
        when(persistence.loadAndRecord(messageId)).thenThrow(new RuntimeException("private preference"));
        MemoryContextService service = new MemoryContextService(persistence);

        MemoryContext context = service.prepareForMessage(messageId, MemoryMode.USE, "trace-degrade");

        assertThat(context.isEmpty()).isTrue();
    }
}
