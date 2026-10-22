package io.github.fanqiepi.contextpilot.readingtask;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import io.github.fanqiepi.contextpilot.book.ReadingText;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import static io.github.fanqiepi.contextpilot.readingtask.ReadingTaskPolicy.failure;

/** Fixed local evaluation contract, not an HTTP authorization API or arbitrary job registry. */
final class ReadingLiveBatch {
    static final String VERSION="reading-acceptance-v9", PRICE="reading-cny-20261022-v1", SCOPE="DEBUG_RETEST";
    static final String LOCK="evals/configs/reading/acceptance-v9.lock.json", DATA="evals/datasets/reading/acceptance-v1.json";
    static final int GENERATION_INPUT_BOUND=1_048_576, EMBEDDING_LINE_BOUND=128_000, CALL_LIMIT=346;
    static final BigDecimal INPUT=new BigDecimal("0.000002"),OUTPUT=new BigDecimal("0.000008"),EMBEDDING=new BigDecimal("0.0000005");
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    final UUID id;
    final String digest,planHash,lockHash;
    final BigDecimal limit,embeddingLimit;
    final OffsetDateTime authorizedAt,expiresAt;
    final Map<UUID,Entry> entries;
    final Previous previous;
    record Previous(UUID id,String digest) {
        UUID embeddingId() { return key(id,"DASHSCOPE_LEDGER",0); }
        String embeddingDigest() { return ReadingText.hash(digest+":DASHSCOPE"); }
    }
    record Entry(String caseId,int repeat,String split,UUID versionId,UUID bookId,String fileHash,String parserId,
                 int boundary,String type,String mode,String question,int chapters,int from,int target,List<Paragraph> paragraphs) {}
    record Paragraph(UUID id,int ordinal,String hash) {}

    ReadingLiveBatch(UUID id,String digest,String planHash,String lockHash,BigDecimal limit,OffsetDateTime authorizedAt,
                             OffsetDateTime expiresAt,Map<UUID,Entry> entries) {
        this(id,digest,planHash,lockHash,limit,limit,authorizedAt,expiresAt,entries);
    }
    ReadingLiveBatch(UUID id,String digest,String planHash,String lockHash,BigDecimal limit,BigDecimal embeddingLimit,OffsetDateTime authorizedAt,
                     OffsetDateTime expiresAt,Map<UUID,Entry> entries) {
        this(id,digest,planHash,lockHash,limit,embeddingLimit,authorizedAt,expiresAt,entries,null);
    }
    ReadingLiveBatch(UUID id,String digest,String planHash,String lockHash,BigDecimal limit,BigDecimal embeddingLimit,OffsetDateTime authorizedAt,
                     OffsetDateTime expiresAt,Map<UUID,Entry> entries,Previous previous) {
        this.previous=previous;
        this.embeddingLimit=embeddingLimit;this.id=id;this.digest=digest;this.planHash=planHash;this.lockHash=lockHash;this.limit=limit;
        this.authorizedAt=authorizedAt;this.expiresAt=expiresAt;this.entries=Map.copyOf(entries);
    }
    UUID embeddingId() { return key(id,"DASHSCOPE_LEDGER",0); }
    String embeddingDigest() { return ReadingText.hash(digest+":DASHSCOPE"); }
    boolean debugRetest() { return previous!=null; }
    int generationCalls() { return debugRetest()?16:154; }
    int embeddingCalls() { return debugRetest()?16:192; }
    static ReadingLiveBatch load(Path repository,Path planFile,Path approvalFile,Clock clock) {
        try {
            Path root=repository.toRealPath();byte[] planBytes=read(planFile),approvalBytes=read(approvalFile);
            var plan=JSON.readTree(planBytes);var approval=JSON.readTree(approvalBytes);
            String planHash=ReadingText.hash(planBytes);
            require(VERSION.equals(plan.path("version").asText()) && "reading-live-plan-v1".equals(plan.path("schema").asText()));
            require("APPROVED".equals(approval.path("status").asText()) && "reading-live-approval-v1".equals(approval.path("schema").asText()));
            require(planHash.equals(approval.path("planSha256").asText()) && approval.path("accountConditionsConfirmed").asBoolean());
            require(SCOPE.equals(plan.path("executionScope").asText()) && SCOPE.equals(approval.path("executionScope").asText()));
            require(PRICE.equals(approval.path("pricingVersion").asText()) && approval.path("maxCalls").asInt()==32);
            var previous=new Previous(UUID.fromString(approval.path("previousAuthorizationId").asText()),approval.path("previousApprovalDigest").asText());
            require(previous.digest().matches("[a-f0-9]{64}"));
            OffsetDateTime start=OffsetDateTime.parse(approval.path("authorizedAt").asText()),end=OffsetDateTime.parse(approval.path("expiresAt").asText());
            OffsetDateTime verified=OffsetDateTime.parse(approval.path("pricingVerifiedAt").asText()),now=OffsetDateTime.now(clock);
            require(!start.isAfter(now) && end.isAfter(now) && end.isAfter(start) && Duration.between(start,end).compareTo(Duration.ofHours(24))<=0);
            require(!verified.isAfter(start) && Duration.between(verified,end).compareTo(Duration.ofHours(24))<=0);
            var limits=approval.path("providerLimitsCny");
            BigDecimal amount=new BigDecimal(limits.path("DEEPSEEK").asText()),embeddingAmount=new BigDecimal(limits.path("DASHSCOPE").asText());
            require(amount.signum()>0 && amount.compareTo(new BigDecimal("20"))<=0 && amount.scale()<=8);
            require(embeddingAmount.signum()>0 && embeddingAmount.compareTo(new BigDecimal("10"))<=0 && embeddingAmount.scale()<=8);
            require(amount.add(embeddingAmount).compareTo(new BigDecimal(approval.path("limitCny").asText()))==0);
            require(ReadingProviderClient.GENERATION_MODEL.equals(plan.path("generationModel").asText())
                    && ReadingProviderClient.EMBEDDING_MODEL.equals(plan.path("embeddingModel").asText())
                    && ReadingProviderClient.GENERATION_URI.toString().equals(plan.path("generationEndpoint").asText())
                    && ReadingProviderClient.EMBEDDING_URI.toString().equals(plan.path("embeddingEndpoint").asText())
                    && plan.path("embeddingDimensions").asInt()==1024);
            byte[] lockBytes=read(root.resolve(LOCK));String lockHash=ReadingText.hash(lockBytes);
            require(lockHash.equals(plan.path("implementationLockSha256").asText()));
            var lock=JSON.readTree(lockBytes);require(VERSION.equals(lock.path("version").asText()));
            for(String group:List.of("assetHashes","sourceHashes")) {
                var hashes=lock.path(group);require(hashes.isObject() && !hashes.isEmpty());
                for(String file:hashes.propertyNames()) {
                    Path target=root.resolve(file).toRealPath();require(target.startsWith(root));
                    require(ReadingText.hash(Files.readAllBytes(target)).equals(hashes.path(file).asText()));
                }
            }
            byte[] datasetBytes=read(root.resolve(DATA));require(ReadingText.hash(datasetBytes).equals(plan.path("datasetSha256").asText()));
            var dataset=JSON.readTree(datasetBytes);
            UUID id=UUID.fromString(approval.path("authorizationId").asText());
            require(!id.equals(previous.id()) && !id.equals(previous.embeddingId()));
            UUID batch=UUID.fromString(plan.path("batchId").asText());
            var bindings=plan.path("bindings");require(bindings.isArray() && bindings.size()==4);
            var entries=new LinkedHashMap<UUID,Entry>();var versions=new HashSet<UUID>();
            for(var corpus:dataset.path("corpora")) {
                String corpusId=corpus.path("id").asText();JsonNode binding=null;
                for(var candidate:bindings)if(corpusId.equals(candidate.path("corpusId").asText())){require(binding==null);binding=candidate;}
                require(binding!=null && corpus.path("sha256").asText().equals(binding.path("corpusSha256").asText()));
                UUID version=UUID.fromString(binding.path("versionId").asText()),book=UUID.fromString(binding.path("bookId").asText());require(versions.add(version));
                var paragraphs=new ArrayList<Paragraph>();var ids=new HashSet<UUID>();
                var bp=binding.path("paragraphs");require(bp.size()==corpus.path("paragraphs").size());
                for(int i=0;i<bp.size();i++) {
                    var p=bp.get(i);var expected=corpus.path("paragraphs").get(i);UUID pid=UUID.fromString(p.path("id").asText());
                    require(ids.add(pid) && p.path("ordinal").asInt()==i+1 && expected.path("sha256").asText().equals(p.path("sha256").asText()));
                    paragraphs.add(new Paragraph(pid,i+1,p.path("sha256").asText()));
                }
                String fileHash=corpus.path("sha256").asText(),parser=corpus.path("parserId").asText();
                var indexBoundaries=new TreeSet<Integer>();
                for(var c:dataset.path("cases"))if(corpusId.equals(c.path("corpusId").asText())) {
                    String split=c.path("split").asText(),type=c.path("taskType").asText(),caseId=c.path("caseId").asText();int boundary=c.path("boundary").asInt();
                    boolean recap="RECAP".equals(type);if(!recap)indexBoundaries.add(boundary);
                    for(int repeat=1;repeat<=("TEST".equals(split)?2:1);repeat++) {
                        var entry=new Entry(caseId,repeat,split,version,book,fileHash,parser,boundary,type,recap?null:c.path("mode").asText(),
                                recap?null:c.path("input").asText(),recap?c.path("chapterCount").asInt():3,1,boundary,List.copyOf(paragraphs));
                        require(entries.put(key(batch,caseId,repeat),entry)==null);
                    }
                }
                int from=1;
                for(int target:indexBoundaries) {
                    String entryId="index:"+corpusId+":"+target;
                    entries.put(key(batch,entryId,1),new Entry(entryId,1,corpus.path("split").asText(),version,book,fileHash,parser,target,"INDEX",null,null,3,from,target,List.copyOf(paragraphs)));
                    from=target+1;
                }
            }
            require(entries.size()==135);
            entries.values().removeIf(e->!"DEBUG".equals(e.split()) || "INDEX".equals(e.type()));
            require(entries.size()==12);
            return new ReadingLiveBatch(id,ReadingText.hash(approvalBytes),planHash,lockHash,amount,embeddingAmount,start,end,entries,previous);
        } catch(Exception invalid) { throw failure("LIVE_APPROVAL_INVALID"); }
    }
    static UUID key(UUID batch,String entry,int repeat) { return UUID.nameUUIDFromBytes((batch+":"+entry+":"+repeat).getBytes(StandardCharsets.UTF_8)); }
    private static byte[] read(Path file) throws java.io.IOException { require(Files.size(file)<=2*1024*1024);return Files.readAllBytes(file); }
    private static void require(boolean valid) { if(!valid)throw failure("LIVE_APPROVAL_INVALID"); }
}
