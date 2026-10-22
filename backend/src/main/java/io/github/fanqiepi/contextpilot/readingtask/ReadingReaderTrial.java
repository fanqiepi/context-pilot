package io.github.fanqiepi.contextpilot.readingtask;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import io.github.fanqiepi.contextpilot.book.ReadingText;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static io.github.fanqiepi.contextpilot.readingtask.ReadingTaskPolicy.failure;

/** Operator-approved local reader session; never inherits a stopped evaluation allowance. */
final class ReadingReaderTrial {
    static final String SCOPE="READER_TRIAL_LOCAL";
    static final int GENERATION_CALLS=100, EMBEDDING_CALLS=200;
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static ReadingLiveBatch load(Path repository,Path approvalFile,String address,Clock clock) {
        try {
            require("127.0.0.1".equals(address));
            require(Files.size(approvalFile)<=16384);
            byte[] bytes=Files.readAllBytes(approvalFile);var a=JSON.readTree(bytes);
            require("reading-reader-trial-v1".equals(a.path("schema").asText()) && "APPROVED".equals(a.path("status").asText()));
            require(SCOPE.equals(a.path("executionScope").asText()) && ReadingLiveBatch.VERSION.equals(a.path("version").asText()));
            require(a.path("independentBudget").asBoolean() && a.path("accountConditionsConfirmed").asBoolean());
            require(!a.has("previousAuthorizationId") && ReadingLiveBatch.PRICE.equals(a.path("pricingVersion").asText()));
            require(ReadingProviderClient.GENERATION_MODEL.equals(a.path("generationModel").asText())
                    && ReadingProviderClient.EMBEDDING_MODEL.equals(a.path("embeddingModel").asText())
                    && ReadingProviderClient.GENERATION_URI.toString().equals(a.path("generationEndpoint").asText())
                    && ReadingProviderClient.EMBEDDING_URI.toString().equals(a.path("embeddingEndpoint").asText())
                    && a.path("embeddingDimensions").asInt()==1024);
            var start=OffsetDateTime.parse(a.path("authorizedAt").asText());var end=OffsetDateTime.parse(a.path("expiresAt").asText());
            var verified=OffsetDateTime.parse(a.path("pricingVerifiedAt").asText());var now=OffsetDateTime.now(clock);
            require(!start.isAfter(now) && end.isAfter(now) && end.isAfter(start)
                    && Duration.between(start,end).compareTo(Duration.ofHours(24))<=0
                    && !verified.isAfter(start) && Duration.between(verified,end).compareTo(Duration.ofHours(24))<=0);
            var limits=a.path("providerLimitsCny");
            var generation=new BigDecimal(limits.path("DEEPSEEK").asText());var embedding=new BigDecimal(limits.path("DASHSCOPE").asText());
            require(generation.signum()>0 && generation.compareTo(new BigDecimal("20"))<=0 && generation.scale()<=8
                    && embedding.signum()>0 && embedding.compareTo(new BigDecimal("10"))<=0 && embedding.scale()<=8);
            require(a.path("generationCallLimit").asInt()==GENERATION_CALLS && a.path("embeddingCallLimit").asInt()==EMBEDDING_CALLS);
            Path root=repository.toRealPath();byte[] lockBytes=Files.readAllBytes(root.resolve(ReadingLiveBatch.LOCK));
            String lockHash=ReadingText.hash(lockBytes);require(lockHash.equals(a.path("implementationLockSha256").asText()));
            var lock=JSON.readTree(lockBytes);require(ReadingLiveBatch.VERSION.equals(lock.path("version").asText()));
            for(String group:List.of("assetHashes","sourceHashes")) {
                var hashes=lock.path(group);require(hashes.isObject() && !hashes.isEmpty());
                for(String file:hashes.propertyNames()) {
                    Path target=root.resolve(file).toRealPath();require(target.startsWith(root));
                    require(ReadingText.hash(Files.readAllBytes(target)).equals(hashes.path(file).asText()));
                }
            }
            return new ReadingLiveBatch(UUID.fromString(a.path("authorizationId").asText()),ReadingText.hash(bytes),null,lockHash,
                    generation,embedding,start,end,Map.of());
        } catch(Exception invalid) { throw failure("READER_TRIAL_APPROVAL_INVALID"); }
    }
    private static void require(boolean valid) { if(!valid)throw failure("READER_TRIAL_APPROVAL_INVALID"); }
}
