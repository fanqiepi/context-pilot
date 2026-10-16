package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix="contextpilot.upgrade-research",name="enabled",havingValue="true")
public class UpgradeResearchStartService {
    private final UpgradeRunMapper mapper;
    private final UpgradeRunStateService state;
    private final CurrentSubjectProvider subjects;
    private final UpgradeRunWorker worker;
    private final TransactionTemplate transaction;
    private final Semaphore capacity=new Semaphore(3);
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2),r->{Thread t=new Thread(r,"upgrade-research");t.setDaemon(true);return t;},
            new ThreadPoolExecutor.AbortPolicy());

    public UpgradeResearchStartService(UpgradeRunMapper mapper,UpgradeRunStateService state,
            CurrentSubjectProvider subjects,UpgradeRunWorker worker,PlatformTransactionManager transactions) {
        this.mapper=mapper;this.state=state;this.subjects=subjects;this.worker=worker;
        this.transaction=new TransactionTemplate(transactions);
    }
    public UpgradeRunStateService.Registration start(UpgradeStartRequest request,String traceId) {
        UpgradeRunStateService.validate(request);
        var reserved=new AtomicBoolean();
        UpgradeRunStateService.Registration result;
        try {
            result=transaction.execute(status->{
                var owner=subjects.currentSubject().id();
                mapper.lockKey("upgrade:"+owner+":"+request.clientRequestId());
                if(mapper.byRequest(owner,request.clientRequestId())!=null) return state.register(request,traceId);
                if(!capacity.tryAcquire()) throw new UpgradeQueueFullException();
                reserved.set(true);
                return state.register(request,traceId);
            });
        }catch(RuntimeException failure){if(reserved.get())capacity.release();throw failure;}
        if(result==null) {if(reserved.get())capacity.release();throw new IllegalStateException("Missing registration");}
        if(result.replay()) {if(reserved.get())capacity.release();return result;}
        // The transaction is committed before a worker can see the run.
        try {
            executor.execute(()->{
                try {worker.execute(result.run().getId());}
                catch(RuntimeException failure){state.fail(result.run().getId(),"EXECUTION_FAILED");}
                finally{capacity.release();}
            });
        }catch(RejectedExecutionException failure){
            capacity.release();state.fail(result.run().getId(),"DISPATCH_FAILED");
            return new UpgradeRunStateService.Registration(state.get(result.run().getId()),false);
        }
        return result;
    }
    @PreDestroy public void shutdown(){executor.shutdownNow();}
}
