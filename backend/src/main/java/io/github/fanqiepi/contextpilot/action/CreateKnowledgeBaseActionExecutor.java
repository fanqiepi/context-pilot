package io.github.fanqiepi.contextpilot.action;

import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseCreateRequest;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseResponse;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreateKnowledgeBaseActionExecutor {

    private final KnowledgeBaseService knowledgeBaseService;

    public CreateKnowledgeBaseActionExecutor(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Transactional(propagation = Propagation.NESTED)
    public KnowledgeBaseResponse execute(CreateKnowledgeBaseActionParameters parameters) {
        return knowledgeBaseService.create(new KnowledgeBaseCreateRequest(
                parameters.name(), parameters.description()));
    }
}
