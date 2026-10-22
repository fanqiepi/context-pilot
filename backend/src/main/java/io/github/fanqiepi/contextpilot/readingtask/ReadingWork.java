package io.github.fanqiepi.contextpilot.readingtask;

import java.time.OffsetDateTime;
import java.util.*;
import io.github.fanqiepi.contextpilot.readingstate.ReadingScopeService.Snapshot;

/** Persistence representation; never serialized as an HTTP response. */
public class ReadingWork {
    public UUID id,ownerId,bookId,versionId,clientRequestId,executionId,traceId;
    public String requestHash,type,status,payloadJson,errorCode;
    public long revision,activeMillis;
    public int requiredBoundary,deleted;
    public OffsetDateTime queuedAt,startedAt,updatedAt;
    public static final Set<String> TERMINAL=Set.of("CANCELLED","INVALIDATED","FAILED","COMPLETED","PARTIAL","INSUFFICIENT_EVIDENCE","READY");
    public boolean terminal() { return TERMINAL.contains(status); }

    public record Span(UUID paragraphId,UUID chapterId,int paragraphOrdinal,int startOffset,int endOffset,String textHash,String text) {}
    public record Evidence(String id,List<Span> sources,String text) {}
    public record Fact(String text,List<String> evidenceIds) {}
    public record Result(List<Fact> facts,boolean partial) {}
    public static class Payload {
        public Snapshot scope;
        public String liveApprovalDigest;
        public String mode,question,profile="dashscope_qwen3_7_1024_v1";
        public int chapterCount=3,fromOrdinal=1,targetBoundary,completedParagraph,searches,modelCalls,embeddingCalls,inputCharacters,inputBytes;
        public List<String> queries;
        public List<Evidence> evidence=new ArrayList<>();
        public Result result;
        public boolean evidenceReady;
    }
}
