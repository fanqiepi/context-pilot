package io.github.fanqiepi.contextpilot.readingtask;

import java.util.Map;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Budget metadata only. Never log prompts, credentials, provider bodies or account balances. */
final class ReadingBudgetLog {
    private static final org.slf4j.Logger LOG=LoggerFactory.getLogger(ReadingBudgetLog.class);
    private ReadingBudgetLog() {}
    static void workStopped(ReadingWork work,String reason) {
        String message="event=READING_WORK_STOPPED workId="+work.id+" traceId="+work.traceId
                +" type="+safeReason(work.type)+" status="+safeReason(work.status)+" code="+safeReason(work.errorCode)
                +" reason="+safeReason(reason)+" retry=false";
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { LOG.warn(message); }
            });
        } else LOG.warn(message);
    }
    static String safeReason(String reason) {
        return reason!=null && reason.matches("[A-Z0-9_]{1,64}")?reason:"BATCH_STOPPED";
    }
    static void committed(String event,UUID authorization,UUID call,Map<String,Object> row,String reason) {
        String message=message(event,authorization,call,row,reason);
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { LOG.warn(message); }
            });
        } else LOG.warn(message);
    }
    static void blocked(UUID authorization,UUID call,Map<String,Object> row,String reason) {
        // A rejected attempt is real even if its surrounding work transaction rolls back.
        LOG.warn(message("READING_BUDGET_BLOCKED",authorization,call,row,reason));
    }
    static void providerRejected(UUID work,String code) {
        if("PROVIDER_HTTP_402".equals(code) || "PROVIDER_HTTP_429".equals(code))
            LOG.warn("event=READING_PROVIDER_REJECTED workId={} reason={} retry=false",work,code);
    }
    static void stop(ReadingTaskMapper mapper,UUID authorization,UUID call,Map<String,Object> row,String reason) {
        String safe=safeReason(reason);
        if(mapper.stopSpend(authorization,safe)==1)committed("READING_BUDGET_STOPPED",authorization,call,row,safe);
    }
    private static String message(String event,UUID authorization,UUID call,Map<String,Object> row,String reason) {
        return "event="+event+" authorizationId="+authorization+" callId="+call+" reason="+safeReason(reason)
                +" reservedCny="+row.get("reserved_amount")+" budgetValuedCny="+row.get("settled_amount")
                +" limitCny="+row.get("limit_amount")+" callsStarted="+row.get("calls_started")
                +" callLimit="+row.get("call_limit")+" costBasis=PEAK_UNCACHED_BUDGET_VALUE_NOT_INVOICE";
    }
}
