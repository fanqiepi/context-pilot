package io.github.fanqiepi.contextpilot.projectsource;

import io.github.fanqiepi.contextpilot.common.*;
import io.github.fanqiepi.contextpilot.document.StorageService;
import io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ProjectSourcePreviewService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ProjectPreviewMapper mapper;
    private final CurrentSubjectProvider subjects;
    private final StorageService storage;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final String endpoint;
    private final java.time.Duration executionLimit;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), Thread.ofPlatform().daemon().name("project-preview-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("project-preview-deadline").factory());
    public record CreateRequest(String sourceId, String ref, List<String> paths, UUID clientRequestId) { }
    public record ItemResponse(String path, String mediaType, int byteCount, String sha256, String fetchedAt, String content) { }
    public record PreviewResponse(UUID id, String repository, String requestedRef, String resolvedCommit, String status,
            String errorCode, String errorPath, String expiresAt, String traceId, List<ItemResponse> items) { }
    @org.springframework.beans.factory.annotation.Autowired
    public ProjectSourcePreviewService(ProjectPreviewMapper mapper, CurrentSubjectProvider subjects, StorageService storage,
            TransactionTemplate tx, @Value("${PROJECT_SOURCE_ENABLED:false}") boolean enabled,
            @Value("${PROJECT_SOURCE_MCP_URL:http://127.0.0.1:8091}") String endpoint) {
        this(mapper,subjects,storage,tx,enabled,endpoint,java.time.Duration.ofSeconds(90));
    }
    ProjectSourcePreviewService(ProjectPreviewMapper mapper, CurrentSubjectProvider subjects, StorageService storage,
            TransactionTemplate tx, boolean enabled, String endpoint, java.time.Duration executionLimit) {
        this.mapper=mapper;this.subjects=subjects;this.storage=storage;this.tx=tx;this.enabled=enabled;this.endpoint=endpoint;
        this.executionLimit=executionLimit;
    }
    public boolean enabled() { return enabled; }
    public PreviewResponse create(CreateRequest request) {
        if (!enabled) throw new ConflictException("PROJECT_SOURCE_DISABLED","项目资料接入未启用");
        if (request==null || !ProjectSourcePolicy.SOURCE_ID.equals(request.sourceId()) || request.clientRequestId()==null)
            throw new BadRequestException("SOURCE_REQUEST_INVALID","资料预览参数不完整");
        String ref; List<String> paths;
        try { ref=ProjectSourcePolicy.ref(request.ref()); paths=ProjectSourcePolicy.paths(request.paths()); }
        catch(ProjectSourceException e) { throw new BadRequestException(e.code(),"版本或文件路径不合法"); }
        UUID owner=subjects.currentSubject().id();
        String hash=ProjectSourcePolicy.hash(JSON.writeValueAsBytes(List.of(ref,paths)));
        boolean[] created={false};
        var entity=tx.execute(status -> {
            mapper.lockKey("source-preview:"+owner+":"+request.clientRequestId());
            var previous=mapper.byKey(owner,request.clientRequestId());
            if(previous!=null){
                if(previous.deleted!=0 || !hash.equals(previous.requestHash)) throw new ConflictException("SOURCE_REQUEST_ID_CONFLICT","请求编号已使用");
                return previous;
            }
            var value=new ProjectPreviewEntity();var now=OffsetDateTime.now(ZoneOffset.UTC);
            value.id=UUID.randomUUID();value.ownerId=owner;value.clientRequestId=request.clientRequestId();value.requestHash=hash;
            value.repository=ProjectSourcePolicy.REPOSITORY;value.requestedRef=ref;value.pathsJson=JSON.writeValueAsString(paths);
            value.status="PENDING";value.traceId=UUID.randomUUID().toString();value.createdAt=now;value.updatedAt=now;value.expiresAt=now.plusHours(24);
            mapper.insert(value);created[0]=true;return value;
        });
        if(created[0]) {
            try { workers.execute(() -> fetch(entity)); }
            catch(RejectedExecutionException e) { mapper.fail(entity.id,"SOURCE_QUEUE_FULL"); }
        }
        return get(entity.id);
    }
    public PreviewResponse get(UUID id) {
        var row=mapper.selectById(id);
        if(row==null || !subjects.currentSubject().id().equals(row.ownerId)) throw new ResourceNotFoundException("SOURCE_PREVIEW_NOT_FOUND","资料预览不存在");
        List<ItemResponse> items=new ArrayList<>();
        if("READY".equals(row.status)) for(var item:mapper.items(id)) {
            String content=readSnapshot(item);
            items.add(new ItemResponse(item.path(),item.mediaType(),item.byteCount(),item.sha256(),item.fetchedAt().toString(),content));
        }
        return new PreviewResponse(row.id,row.repository,row.requestedRef,row.resolvedCommit,row.status,row.errorCode,row.errorPath,
                row.expiresAt.toString(),row.traceId,List.copyOf(items));
    }
    String readSnapshot(ProjectPreviewMapper.Item item) {
        try(var input=storage.open(item.snapshotStorageKey())) {
            byte[] bytes=input.readNBytes(ProjectSourcePolicy.FILE_BYTES+1);
            if(bytes.length!=item.byteCount() || !ProjectSourcePolicy.hash(bytes).equals(item.sha256()))
                throw new ProjectSourceException("SOURCE_SNAPSHOT_INVALID");
            return ProjectSourcePolicy.text(bytes);
        } catch(ProjectSourceException e) { throw e; }
        catch(Exception e) { throw new ProjectSourceException("SOURCE_SNAPSHOT_UNAVAILABLE"); }
    }
    private void fetch(ProjectPreviewEntity row) {
        if(mapper.claim(row.id)!=1)return;
        Instant deadline=Instant.now().plus(executionLimit);
        Thread running=Thread.currentThread();
        Object completionGuard=new Object();boolean[] finished={false};
        var timeout=deadlines.schedule(()->{synchronized(completionGuard){
            if(!finished[0]){mapper.fail(row.id,"SOURCE_TIMEOUT");running.interrupt();}
        }},executionLimit.toMillis(),TimeUnit.MILLISECONDS);
        try(var client=new ProjectSourceMcpClient(endpoint,deadline)) {
            String commit=client.resolveRef(row.requestedRef,deadline);
            List<ProjectDocumentSnapshot> snapshots=new ArrayList<>();int total=0;
            for(String path:JSON.readValue(row.pathsJson,String[].class)) {
                ProjectDocumentSnapshot snapshot;
                try { snapshot=client.readDocument(commit,path,deadline); }
                catch(ProjectSourceException e) { mapper.failPath(row.id,e.code(),path); throw e; }
                total+=snapshot.byteCount();
                if(total>ProjectSourcePolicy.BATCH_BYTES)throw new ProjectSourceException("SOURCE_BATCH_TOO_LARGE");
                snapshots.add(snapshot);
            }
            List<ProjectPreviewMapper.Item> items=new ArrayList<>();
            for(var snapshot:snapshots){
                UUID id=UUID.randomUUID();String key="project-previews/"+row.id+"/"+id+".txt";
                var stored=storage.store(key,new ByteArrayInputStream(snapshot.content().getBytes(StandardCharsets.UTF_8)));
                if(!snapshot.sha256().equals(stored.sha256()) || snapshot.byteCount()!=stored.sizeBytes())throw new ProjectSourceException("SOURCE_SNAPSHOT_INVALID");
                items.add(new ProjectPreviewMapper.Item(id,row.id,snapshot.path(),snapshot.mediaType(),snapshot.byteCount(),snapshot.sha256(),key,
                        OffsetDateTime.parse(snapshot.fetchedAt()),0));
            }
            tx.executeWithoutResult(status -> {
                if(!Instant.now().isBefore(deadline) || mapper.ready(row.id,commit)!=1)throw new ProjectSourceException("SOURCE_TIMEOUT");
                for(var item:items)mapper.insertItem(item);
            });
        } catch(ProjectSourceException e) { mapper.fail(row.id,e.code()); }
        catch(RuntimeException e) { mapper.fail(row.id,"SOURCE_PREVIEW_FAILED"); }
        finally { synchronized(completionGuard){finished[0]=true;timeout.cancel(false);Thread.interrupted();} }
    }
    @EventListener(ApplicationReadyEvent.class) public void recover() { mapper.recover(); }
    @PreDestroy public void close() { workers.shutdownNow();deadlines.shutdownNow(); }
}
