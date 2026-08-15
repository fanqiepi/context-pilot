package io.github.fanqiepi.contextpilot.action;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class ActionParametersCodec {

    private final ObjectMapper objectMapper;

    public ActionParametersCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(ActionType actionType, ActionParameters parameters) {
        requireMatchingType(actionType, parameters);
        try {
            JsonNode tree = objectMapper.valueToTree(parameters);
            if (!(tree instanceof ObjectNode objectNode)) {
                throw new IllegalStateException("Action parameters must serialize as an object");
            }
            List<String> nullFields = new ArrayList<>();
            objectNode.properties().forEach(entry -> {
                if (entry.getValue().isNull()) {
                    nullFields.add(entry.getKey());
                }
            });
            objectNode.remove(nullFields);
            return objectMapper.writeValueAsString(objectNode);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Action parameters could not be serialized", exception);
        }
    }

    public ActionParameters read(ActionType actionType, String parametersJson) {
        if (parametersJson == null || parametersJson.isBlank()) {
            throw new IllegalStateException("Persisted action parameters must not be blank");
        }
        Class<? extends ActionParameters> parameterType = switch (actionType) {
            case CREATE_KNOWLEDGE_BASE -> CreateKnowledgeBaseActionParameters.class;
            case RETRY_DOCUMENT_PROCESSING -> RetryDocumentProcessingActionParameters.class;
            case REINDEX_DOCUMENT -> ReindexDocumentActionParameters.class;
        };
        try {
            ActionParameters parameters = objectMapper.readValue(parametersJson, parameterType);
            requireMatchingType(actionType, parameters);
            return parameters;
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Persisted parameters are invalid for action type " + actionType,
                    exception);
        }
    }

    private void requireMatchingType(ActionType actionType, ActionParameters parameters) {
        if (actionType == null || parameters == null || parameters.actionType() != actionType) {
            throw new IllegalArgumentException("Action type and parameter type must match");
        }
    }
}
