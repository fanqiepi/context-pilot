package io.github.fanqiepi.contextpilot.readingtask;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import io.github.fanqiepi.contextpilot.book.ReadingException;
import io.github.fanqiepi.contextpilot.book.ReadingText;
import io.netty.channel.ChannelOption;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.github.fanqiepi.contextpilot.readingtask.ReadingTaskPolicy.*;

/** Reading-only wire protocol. No tools, SDK batching, redirects, retries or authorization creation.
 * The application gateway remains closed; this transport is not a Spring bean or a public API.
 */
final class ReadingProviderClient {
    static final String GENERATION_MODEL="deepseek-v4-flash", EMBEDDING_MODEL="qwen3.7-text-embedding";
    static final URI GENERATION_URI=URI.create("https://api.deepseek.com/chat/completions");
    static final URI EMBEDDING_URI=URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings");
    private static final JsonMapper JSON=JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final URI generationUri,embeddingUri;
    private final String generationKey,embeddingKey;

    ReadingProviderClient(String generationKey,String embeddingKey) {
        this(GENERATION_URI,EMBEDDING_URI,generationKey,embeddingKey);
    }
    // Local HTTP fixtures are the only alternative endpoints. No application-supplied URL.
    ReadingProviderClient(URI generationUri,URI embeddingUri,String generationKey,String embeddingKey) {
        endpoint(generationUri,GENERATION_URI);endpoint(embeddingUri,EMBEDDING_URI);
        this.generationUri=generationUri;this.embeddingUri=embeddingUri;
        this.generationKey=generationKey;this.embeddingKey=embeddingKey;
    }
    private static void endpoint(URI uri,URI production) {
        if(uri==null || (!uri.equals(production) && !("http".equals(uri.getScheme())
                && "127.0.0.1".equals(uri.getHost()) && uri.getPort()>0 && uri.getUserInfo()==null
                && uri.getQuery()==null && uri.getFragment()==null)))throw failure("PROVIDER_CONFIGURATION_INVALID");
    }
    String generationInput(String prompt,int outputTokens) {
        if(!Set.of(512,2048,4096).contains(outputTokens) || prompt==null)throw failure("INPUT_LIMIT");
        boolean plan=outputTokens==512;
        limit(prompt,plan?4096:32768,plan?8192:65536,"INPUT_LIMIT");
        JsonNode input=parse(prompt);
        if(!input.isObject() || !input.path("system").isTextual() || input.path("system").asText().isBlank()
                || !input.path("question").isTextual() || !PROMPT_VERSION.equals(input.path("promptVersion").asText()))
            throw failure("INPUT_LIMIT");
        var user=new LinkedHashMap<String,Object>();
        user.put("promptVersion",PROMPT_VERSION);user.put("question",input.get("question"));
        if(!plan) {
            if(!input.path("evidence").isArray())throw failure("INPUT_LIMIT");
            user.put("evidence",input.get("evidence"));
        }
        var request=new LinkedHashMap<String,Object>();
        request.put("model",GENERATION_MODEL);
        request.put("messages",List.of(Map.of("role","system","content",input.path("system").asText()),
                Map.of("role","user","content",JSON.writeValueAsString(user))));
        request.put("stream",false);request.put("max_tokens",outputTokens);
        request.put("thinking",Map.of("type","disabled"));request.put("response_format",Map.of("type","json_object"));
        String serialized=JSON.writeValueAsString(request);
        limit(serialized,plan?4096:32768,plan?8192:65536,"INPUT_LIMIT");return serialized;
    }
    ReadingAiGateway.Response generate(String prompt,int outputTokens,Duration timeout) {
        JsonNode root=post(generationUri,generationKey,generationInput(prompt,outputTokens),timeout,32768);
        var choices=root.path("choices");
        if(!choices.isArray() || choices.size()!=1)throw failure("OUTPUT_INVALID");
        var choice=choices.get(0);var message=choice.path("message");
        if(!"assistant".equals(message.path("role").asText()) || !message.path("content").isTextual()
                || message.hasNonNull("tool_calls") || message.hasNonNull("function_call"))throw failure("OUTPUT_INVALID");
        String text=message.path("content").asText(),finish=choice.path("finish_reason").asText();
        if(text.isBlank() || finish.isBlank())throw failure("OUTPUT_INVALID");
        limit(text,32768,32768,"OUTPUT_INVALID");
        // Preserve length/content_filter and actual usage for audit. The runner refuses publication.
        return new ReadingAiGateway.Response(text,token(root.path("usage"),"prompt_tokens"),
                token(root.path("usage"),"completion_tokens"),finish);
    }
    ReadingAiGateway.EmbeddingResponse embed(List<String> texts,Duration timeout) {
        if(texts==null || texts.isEmpty() || texts.size()>16)throw failure("INPUT_LIMIT");
        int chars=0,bytes=0;
        for(String text:texts) {
            if(text==null || text.isBlank())throw failure("INPUT_LIMIT");
            limit(text,1000,4000,"INPUT_LIMIT");chars+=ReadingText.length(text);bytes+=bytes(text);
        }
        if(chars>16000 || bytes>64000)throw failure("INPUT_LIMIT");
        var request=new LinkedHashMap<String,Object>();request.put("model",EMBEDDING_MODEL);request.put("input",texts);
        request.put("dimensions",1024);request.put("encoding_format","float");
        // Binary vectors are larger than generated text; cap this distinct envelope at 1 MiB.
        var root=post(embeddingUri,embeddingKey,JSON.writeValueAsString(request),timeout,1024*1024);
        var data=root.path("data");if(!data.isArray() || data.size()!=texts.size())throw failure("OUTPUT_INVALID");
        float[][] ordered=new float[texts.size()][];
        for(var item:data) {
            var index=item.path("index");var values=item.path("embedding");
            if(!index.isIntegralNumber() || !index.canConvertToInt() || index.intValue()<0 || index.intValue()>=ordered.length
                    || ordered[index.intValue()]!=null || !values.isArray() || values.size()!=1024)throw failure("OUTPUT_INVALID");
            var vector=new float[1024];
            for(int i=0;i<1024;i++) {
                try { if(!values.get(i).isNumber())throw failure("OUTPUT_INVALID");vector[i]=values.get(i).floatValue(); }
                catch(RuntimeException invalidNumber) { throw failure("OUTPUT_INVALID"); }
            }
            vector(vector);ordered[index.intValue()]=vector;
        }
        return new ReadingAiGateway.EmbeddingResponse(List.copyOf(Arrays.asList(ordered)),token(root.path("usage"),"prompt_tokens"));
    }
    private static Long token(JsonNode usage,String name) {
        var value=usage.path(name);
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue()>=0?value.longValue():null;
    }
    private JsonNode post(URI uri,String key,String body,Duration timeout,int responseLimit) {
        if(Thread.currentThread().isInterrupted())throw new CancellationException();
        if(timeout==null || timeout.isZero() || timeout.isNegative())throw failure("TIMEOUT");
        if(key==null || key.isBlank() || key.indexOf('\r')>=0 || key.indexOf('\n')>=0)throw failure("PROVIDER_CONFIGURATION_INVALID");
        try {
            // A fresh connection avoids a pooled stale-connection replay. The total timeout includes body consumption.
            var http=HttpClient.newConnection().disableRetry(true).followRedirect(false).compress(false)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,(int)Math.max(1,Math.min(10000,timeout.toMillis())));
            var client=WebClient.builder().clientConnector(new ReactorClientHttpConnector(http))
                    .codecs(c->c.defaultCodecs().maxInMemorySize(responseLimit)).build();
            byte[] bytes=client.post().uri(uri).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .headers(h->h.setBearerAuth(key)).bodyValue(body)
                    .exchangeToMono(response->{
                        if(response.statusCode().value()!=200)return Mono.error(failure("PROVIDER_HTTP_"+response.statusCode().value()));
                        var type=response.headers().contentType().orElse(null);
                        if(type==null || !MediaType.APPLICATION_JSON.isCompatibleWith(type))return Mono.error(failure("OUTPUT_INVALID"));
                        return response.bodyToMono(byte[].class);
                    }).timeout(timeout).block();
            if(bytes==null || bytes.length>responseLimit)throw failure("OUTPUT_INVALID");
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return parse(text);
        } catch(Exception error) {
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
                if(cause instanceof ReadingException safe)throw safe;
                if(cause instanceof TimeoutException)throw failure("TIMEOUT");
                if(cause instanceof DataBufferLimitException)throw failure("OUTPUT_INVALID");
                if(cause instanceof java.nio.charset.CharacterCodingException)throw failure("OUTPUT_INVALID");
            }
            // Never retain provider exception bodies, request content or credentials in a cause/message.
            throw failure("PROVIDER_UNAVAILABLE");
        }
    }
    private static JsonNode parse(String text) {
        try { var node=JSON.readTree(text);if(node==null || !node.isObject())throw failure("OUTPUT_INVALID");return node; }
        catch(RuntimeException error) { throw failure("OUTPUT_INVALID"); }
    }
}
