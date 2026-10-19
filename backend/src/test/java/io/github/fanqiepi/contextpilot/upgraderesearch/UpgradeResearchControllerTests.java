package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.util.*;
import io.github.fanqiepi.contextpilot.common.*;
import io.github.fanqiepi.contextpilot.researchreport.ResearchReportProposalService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UpgradeResearchControllerTests {
    private final UpgradeQueryService query=mock(UpgradeQueryService.class);
    private final UpgradeRunStateService state=mock(UpgradeRunStateService.class);
    private final ResearchReportProposalService proposals=mock(ResearchReportProposalService.class);
    private UpgradeResearchController controller;
    private MockMvc mvc;
    private ObjectProvider<UpgradeResearchStartService> provider;
    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        provider=mock(ObjectProvider.class);
        controller=new UpgradeResearchController(provider,state,query,proposals);
        mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }
    @AfterEach void shutdown(){controller.shutdown();}
    @Test void disabledFeatureStillExposesExistingRecordsButCannotStart() throws Exception {
        mvc.perform(get("/api/upgrade-research/repositories")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        String input=UpgradeJson.write(Map.of("clientRequestId",UUID.randomUUID(),"knowledgeBaseId",UUID.randomUUID(),
            "documentIds",List.of(UUID.randomUUID()),"repositoryKey","spring-ai","releaseTags",List.of("v2.0.0"),"question","Assess upgrade"));
        mvc.perform(post("/api/upgrade-research/runs").contentType("application/json").content(input))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UPGRADE_RESEARCH_DISABLED"));
        verifyNoInteractions(state,proposals);
    }
    @Test void terminalStreamReplaysSnapshotAndDoneWithoutCancelling() throws Exception {
        UUID id=UUID.randomUUID();var run=new UpgradeRunEntity();run.setId(id);run.setStatus(UpgradeRunStatus.CANCELLED);
        run.setRevision(4L);run.setTraceId("stream-trace");
        when(query.get(id)).thenReturn(new UpgradeQueryService.View(run,List.of(),List.of(),List.of(),null));
        var request=mvc.perform(get("/api/upgrade-research/runs/{id}/events",id)).andExpect(request().asyncStarted()).andReturn();
        request.getAsyncResult(3000);
        mvc.perform(asyncDispatch(request)).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("event:snapshot"),
                    org.hamcrest.Matchers.containsString("event:done"),org.hamcrest.Matchers.containsString("stream-trace"))));
        verifyNoInteractions(state,proposals);
    }
    @Test void missingRunReturns404BeforeOpeningStream() throws Exception {
        UUID id=UUID.randomUUID();when(query.get(id)).thenThrow(new ResourceNotFoundException("UPGRADE_RUN_NOT_FOUND","Missing"));
        mvc.perform(get("/api/upgrade-research/runs/{id}/events",id)).andExpect(status().isNotFound());
        verifyNoInteractions(state);
    }

    @Test void requestAndProposalConflictsExpose409AndExactCodes() throws Exception {
        var start=mock(UpgradeResearchStartService.class);when(provider.getIfAvailable()).thenReturn(start);
        when(start.start(any(),anyString())).thenThrow(new ConflictException("UPGRADE_REQUEST_ID_CONFLICT","conflict"));
        String input=UpgradeJson.write(Map.of("clientRequestId",UUID.randomUUID(),"knowledgeBaseId",UUID.randomUUID(),
                "documentIds",List.of(UUID.randomUUID()),"repositoryKey","spring-ai","releaseTags",List.of("v2"),"question","Assess"));
        mvc.perform(post("/api/upgrade-research/runs").contentType("application/json").content(input))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UPGRADE_REQUEST_ID_CONFLICT"));
        UUID run=UUID.randomUUID();when(proposals.propose(eq(run),any()))
                .thenThrow(new ConflictException("REPORT_PROPOSAL_CONFLICT","conflict"));
        mvc.perform(post("/api/upgrade-research/runs/{id}/save-proposals",run).contentType("application/json")
                .content(UpgradeJson.write(Map.of("clientRequestId",UUID.randomUUID(),"title","different"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPORT_PROPOSAL_CONFLICT"));
    }
}
