package io.github.fanqiepi.contextpilot.memory;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MemoryContextService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryContextService.class);

    private final MemoryContextPersistenceService persistenceService;

    public MemoryContextService(MemoryContextPersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    @Autowired
    public MemoryContextService(ObjectProvider<MemoryContextPersistenceService> persistenceService) {
        this.persistenceService = persistenceService.getIfAvailable();
    }

    public MemoryContext prepareForMessage(
            UUID assistantMessageId,
            MemoryMode memoryMode,
            String traceId) {
        if (memoryMode == MemoryMode.IGNORE) {
            return MemoryContext.empty();
        }
        if (persistenceService == null) {
            return MemoryContext.empty();
        }
        try {
            MemoryContext context = persistenceService.loadAndRecord(assistantMessageId);
            LOGGER.info(
                    "memory.context.prepared traceId={} assistantMessageId={} count={} characters={} truncated={} revisions={}",
                    safe(traceId), assistantMessageId, context.preferences().size(),
                    context.characterCount(), context.truncated(),
                    context.preferences().stream()
                            .map(item -> item.memoryId() + ":" + item.revision())
                            .toList());
            return context;
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "memory.context.degraded traceId={} assistantMessageId={} errorType={}",
                    safe(traceId), assistantMessageId, exception.getClass().getName());
            return MemoryContext.empty();
        }
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value.replaceAll("[\\r\\n\\t]", "_");
    }
}
