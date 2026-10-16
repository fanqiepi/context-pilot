package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReleaseNotesClient {
    private static final URI BASE=URI.create("https://api.github.com/repos/spring-projects/spring-ai/releases/tags/");
    private final HttpClient client;
    private final URI base;
    private final String token;
    private final Duration timeout;
    @Autowired
    public ReleaseNotesClient(@Value("${GITHUB_RELEASE_TOKEN:}") String token) {
        this(BASE,token,Duration.ofSeconds(UpgradeBudget.HTTP_SECONDS));
    }
    // Package-private injection seam for local HTTP fixtures, not a production configuration property.
    ReleaseNotesClient(URI base,String token,Duration timeout) {
        this.base=base;this.token=token;this.timeout=timeout;
        this.client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public record Release(long releaseId,String tag,String title,String body,boolean truncated,boolean prerelease,
            String url,String publishedAt,String fetchedAt,String contentHash) { }

    public Release read(String tag,Instant deadline) {
        if(tag==null || !tag.matches("[A-Za-z0-9._-]{1,100}") || tag.equals(".") || tag.equals("..")) throw new UpgradeToolException("TOOL_ARGUMENT_INVALID");
        long millis=Math.min(timeout.toMillis(),Duration.between(Instant.now(),deadline).toMillis());
        if(millis<=0) throw new UpgradeToolException("DEADLINE_EXCEEDED");
        var request=HttpRequest.newBuilder(URI.create(base.toString()+tag)).timeout(Duration.ofMillis(millis))
                .header("Accept","application/vnd.github+json").header("Accept-Encoding","identity")
                .header("User-Agent","ContextPilot-upgrade-research").GET();
        if(token!=null && !token.isBlank()) request.header("Authorization","Bearer "+token);
        var body=new LimitedBody();
        var future=client.sendAsync(request.build(),info->{
            String encoding=info.headers().firstValue("Content-Encoding").orElse("identity");
            if(!"identity".equalsIgnoreCase(encoding)) body.reject("RELEASE_ENCODING_UNSUPPORTED");
            return body;
        });
        try {
            var response=future.get(millis,TimeUnit.MILLISECONDS);
            if(response.statusCode()!=200) throw new UpgradeToolException(switch(response.statusCode()) {
                case 401 -> "RELEASE_UNAUTHORIZED";case 403 -> "RELEASE_FORBIDDEN";
                case 404 -> "RELEASE_NOT_FOUND";case 429 -> "RELEASE_RATE_LIMITED";
                default -> response.statusCode()>=300 && response.statusCode()<400?"RELEASE_REDIRECT_REJECTED":"RELEASE_SERVICE_FAILED";
            });
            var json=UpgradeJson.MAPPER.readTree(response.body());
            if(json==null || !json.isObject() || !json.path("id").isIntegralNumber() || json.path("id").asLong()<=0
                    || !tag.equals(json.path("tag_name").asText()) || !json.path("draft").isBoolean()
                    || !json.path("prerelease").isBoolean() || !json.path("body").isTextual()
                    || !json.path("published_at").isTextual()) throw new UpgradeToolException("RELEASE_RESPONSE_INVALID");
            if(json.path("draft").asBoolean()) throw new UpgradeToolException("RELEASE_DRAFT_REJECTED");
            String text=json.path("body").asText().strip();
            if(text.isEmpty()) throw new UpgradeToolException("RELEASE_BODY_EMPTY");
            String published=OffsetDateTime.parse(json.path("published_at").asText()).toInstant().toString();
            String title=json.path("name").isTextual()?json.path("name").asText():tag;
            if(title.length()>200) title=title.substring(0,200);
            return new Release(json.path("id").asLong(),tag,title,text.substring(0,Math.min(text.length(),6000)),
                    text.length()>6000,json.path("prerelease").asBoolean(),
                    "https://github.com/spring-projects/spring-ai/releases/tag/"+tag,published,Instant.now().toString(),UpgradeJson.hash(text));
        } catch(UpgradeToolException error) {throw error;}
        catch(TimeoutException error) {throw new UpgradeToolException("RELEASE_TIMEOUT");}
        catch(InterruptedException error) {Thread.currentThread().interrupt();throw new UpgradeToolException("RUN_INTERRUPTED");}
        catch(ExecutionException error) {
            if(error.getCause() instanceof UpgradeToolException safe) throw safe;
            throw new UpgradeToolException("RELEASE_SERVICE_FAILED");
        } catch(Exception error) {throw new UpgradeToolException("RELEASE_RESPONSE_INVALID");}
        finally {body.cancel();future.cancel(true);}
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private volatile Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription value){subscription=value;if(result.isDone())value.cancel();else value.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            if(result.isDone())return;
            for(var buffer:buffers){
                if(buffer.remaining()>UpgradeBudget.HTTP_MAX_BYTES-bytes.size()){reject("RELEASE_RESPONSE_TOO_LARGE");return;}
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
        void reject(String code){result.completeExceptionally(new UpgradeToolException(code));cancel();}
        void cancel(){if(subscription!=null)subscription.cancel();}
    }
}
