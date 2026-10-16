package io.github.fanqiepi.contextpilot.upgraderesearch;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@EnableScheduling
public class UpgradeRecoveryService {
    private final UpgradeRunMapper mapper;
    private final UpgradeRunStateService state;
    @org.springframework.beans.factory.annotation.Value("${contextpilot.upgrade-research.enabled:false}")
    private boolean enabled;
    public UpgradeRecoveryService(UpgradeRunMapper mapper,UpgradeRunStateService state) {
        this.mapper=mapper;this.state=state;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        for(var batch=mapper.active();!batch.isEmpty();batch=mapper.active())
            for(var id:batch) state.fail(id,"PROCESS_RESTARTED");
    }
    @Scheduled(fixedDelay=1000)
    public void expire() { if(enabled)for(var id:mapper.expired(UpgradeRunStateService.now())) state.expire(id); }
}
