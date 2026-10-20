package io.github.fanqiepi.contextpilot.readingtask;

import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.github.fanqiepi.contextpilot.readingtask.ReadingTaskPolicy.*;

/** No public authorization-creation API. An operator must install a separately approved batch. */
@Service
public class ReadingSpendService {
    private final ReadingTaskMapper mapper;
    public ReadingSpendService(ReadingTaskMapper mapper) { this.mapper=mapper; }
    @Transactional(timeout=5)
    public void reserve(UUID owner,UUID call,ReadingAiGateway.Pricing price,int inputBytes,int outputLimit) {
        if(price==null || price.authorizationId()==null || price.version()==null || price.inputTokenRate()==null || price.outputTokenRate()==null
                || price.inputTokenRate().signum()<0 || price.outputTokenRate().signum()<0)throw failure("PRICING_UNKNOWN");
        // One UTF-8 byte per token is the conservative upper estimate, never billed as actual usage.
        BigDecimal upper=price.inputTokenRate().multiply(BigDecimal.valueOf(inputBytes))
                .add(price.outputTokenRate().multiply(BigDecimal.valueOf(outputLimit))).setScale(8,RoundingMode.CEILING);
        if(upper.signum()<=0 || mapper.ledger(price.authorizationId(),owner)==null)throw failure("LIVE_AI_NOT_AUTHORIZED");
        if(mapper.reserveSpend(price.authorizationId(),upper,price.version())!=1)throw failure("BUDGET_EXHAUSTED");
        mapper.priceCall(call,price.authorizationId(),upper,price.inputTokenRate(),price.outputTokenRate());
    }
    @Transactional(timeout=5)
    public void settle(UUID owner,UUID call,ReadingAiGateway.Response usage) {
        var row=mapper.callRow(call);
        if(row==null || row.get("authorization_id")==null || usage==null || usage.inputTokens()==null || usage.outputTokens()==null
                || usage.inputTokens()<0 || usage.outputTokens()<0)return;
        UUID authorization=(UUID)row.get("authorization_id");
        if(mapper.ledger(authorization,owner)==null)return;
        mapper.lockCall(call);row=mapper.callRow(call);if(row.get("settled_amount")!=null)return;
        BigDecimal reserved=(BigDecimal)row.get("reserved_amount");
        BigDecimal actual=((BigDecimal)row.get("input_token_rate")).multiply(BigDecimal.valueOf(usage.inputTokens()))
                .add(((BigDecimal)row.get("output_token_rate")).multiply(BigDecimal.valueOf(usage.outputTokens()))).setScale(8,RoundingMode.CEILING);
        if(actual.compareTo(reserved)>0) {
            // An invalid bound cannot release money or permit another call. Preserve the reservation and actual usage.
            mapper.freezeSpend(authorization);return;
        }
        if(mapper.settleCall(call,actual)==1)mapper.settleSpend(authorization,reserved,actual);
    }
}
