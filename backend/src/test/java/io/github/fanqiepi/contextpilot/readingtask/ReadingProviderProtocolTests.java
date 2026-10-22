package io.github.fanqiepi.contextpilot.readingtask;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import io.github.fanqiepi.contextpilot.book.ReadingException;
import io.github.fanqiepi.contextpilot.book.ReadingText;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Every request goes to loopback, with synthetic credentials and text. No provider account involved. */
@Timeout(15)
class ReadingProviderProtocolTests {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final Duration TIMEOUT=Duration.ofSeconds(5);
    HttpServer server;
    ExecutorService serverThreads;
    ReadingProviderClient client;
    final List<JsonNode> requests=new CopyOnWriteArrayList<>();
    final List<String> paths=new CopyOnWriteArrayList<>(),auth=new CopyOnWriteArrayList<>();
    volatile int status=200;
    volatile String response,contentType="application/json";
    volatile byte[] raw;
    volatile boolean disconnect=false;
    volatile CountDownLatch gate;
    CountDownLatch received;

    @BeforeEach void start() throws Exception {
        received=new CountDownLatch(1);response=chat("stop",Map.of("prompt_tokens",20,"completion_tokens",9));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        serverThreads=Executors.newCachedThreadPool();server.setExecutor(serverThreads);
        server.createContext("/",exchange->{
            try {
                requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
                paths.add(exchange.getRequestURI().getPath());auth.add(exchange.getRequestHeaders().getFirst("Authorization"));received.countDown();
                var wait=gate;if(wait!=null)wait.await(10,TimeUnit.SECONDS);
                if(disconnect)return;
                byte[] data=raw==null?response.getBytes(StandardCharsets.UTF_8):raw;
                exchange.getResponseHeaders().set("Content-Type",contentType);
                exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/redirect-target");
                exchange.sendResponseHeaders(status,data.length);exchange.getResponseBody().write(data);
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            catch(java.io.IOException ignored) { /* Expected after client cancellation. */ }
            finally { exchange.close(); }
        });server.start();
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        client=new ReadingProviderClient(URI.create(base+"/chat/completions"),URI.create(base+"/embeddings"),"fixture-generation","fixture-embedding");
    }
    @AfterEach void stop() {
        if(gate!=null)gate.countDown();server.stop(0);serverThreads.shutdownNow();
    }
    static String prompt(int limit) {
        var p=new ReadingWork.Payload();p.question="林舟是谁？";p.evidence=List.of();
        return ReadingTaskPolicy.prompt(p,limit==512);
    }
    static String chat(String finish,Object usage) {
        var root=new LinkedHashMap<String,Object>();
        root.put("choices",List.of(Map.of("index",0,"message",Map.of("role","assistant","content","{\"facts\":[],\"partial\":false}"),"finish_reason",finish)));
        if(usage!=null)root.put("usage",usage);return JSON.writeValueAsString(root);
    }
    static Map<String,Object> embedding(int index,double first,int dimensions) {
        var values=new double[dimensions];values[0]=first;return Map.of("index",index,"embedding",values);
    }
    static String embeddings(List<Map<String,Object>> data,Object usage) {
        var root=new LinkedHashMap<String,Object>();root.put("data",data);if(usage!=null)root.put("usage",usage);
        return JSON.writeValueAsString(root);
    }
    @ParameterizedTest @ValueSource(ints={512,2048,4096})
    void sendsExactReadingEnvelopeAndActualUsage(int limit) {
        var result=client.generate(prompt(limit),limit,TIMEOUT);
        assertThat(requests).hasSize(1);assertThat(paths).containsExactly("/chat/completions");
        assertThat(auth).containsExactly("Bearer fixture-generation");var request=requests.getFirst();
        assertThat(request).isEqualTo(JSON.readTree(client.generationInput(prompt(limit),limit)));
        assertThat(request.path("model").asText()).isEqualTo("deepseek-v4-flash");
        assertThat(request.path("max_tokens").asInt()).isEqualTo(limit);
        assertThat(request.path("stream").asBoolean()).isFalse();
        assertThat(request.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(request.has("tools")).isFalse();
        assertThat(request.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(JSON.readTree(request.path("messages").get(1).path("content").asText()).path("question").asText()).isEqualTo("林舟是谁？");
        assertThat(result.inputTokens()).isEqualTo(20);assertThat(result.outputTokens()).isEqualTo(9);assertThat(result.finishReason()).isEqualTo("stop");
    }
    @Test void batchIsOneRequestWithFixedDimensionsAndOrderedIndices() {
        var rows=new ArrayList<Map<String,Object>>();for(int i=15;i>=0;i--)rows.add(embedding(i,i+1,1024));
        response=embeddings(rows,Map.of("prompt_tokens",123,"total_tokens",123));
        var result=client.embed(Collections.nCopies(16,"已读片段"),TIMEOUT);
        assertThat(requests).hasSize(1);assertThat(paths).containsExactly("/embeddings");
        assertThat(auth).containsExactly("Bearer fixture-embedding");
        var request=requests.getFirst();assertThat(request.path("model").asText()).isEqualTo("qwen3.7-text-embedding");
        assertThat(request.path("input").size()).isEqualTo(16);assertThat(request.path("dimensions").asInt()).isEqualTo(1024);
        assertThat(request.path("encoding_format").asText()).isEqualTo("float");
        for(int i=0;i<16;i++)assertThat(result.vectors().get(i)[0]).isEqualTo(i+1);
        assertThat(result.usage().inputTokens()).isEqualTo(123);assertThat(result.usage().outputTokens()).isZero();
    }
    @Test void singleQueryEmbeddingHasOneRequestAndUnknownUsageStaysUnknown() {
        response=embeddings(List.of(embedding(0,1,1024)),null);
        var result=client.embed(List.of("人物"),TIMEOUT);
        assertThat(requests).hasSize(1);assertThat(result.usage().inputTokens()).isNull();assertThat(result.usage().outputTokens()).isNull();
    }
    @Test void selectedEmbeddingModelUsesTotalTokensWithoutPromptTokens() {
        response=embeddings(List.of(embedding(0,1,1024)),Map.of("total_tokens",71));
        var result=client.embed(List.of("人物"),TIMEOUT);
        assertThat(result.inputTokens()).isEqualTo(71);assertThat(result.usage().outputTokens()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"prompt_tokens\":71}","{\"total_tokens\":-1}","{\"total_tokens\":1.5}",
            "{\"total_tokens\":\"71\"}","{\"total_tokens\":71,\"prompt_tokens\":70}","{\"total_tokens\":71,\"prompt_tokens\":null}"})
    void missingOrConflictingEmbeddingUsageRemainsUnknown(String usage) {
        response=embeddings(List.of(embedding(0,1,1024)),JSON.readTree(usage));
        assertThat(client.embed(List.of("人物"),TIMEOUT).inputTokens()).isNull();
    }
    @Test void productionEndpointIsExactWorkspaceAllowlist() {
        assertThat(ReadingProviderClient.EMBEDDING_URI.toString()).isEqualTo("https://llm-90adsh26dbyar5po.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/embeddings");
        assertThatThrownBy(()->new ReadingProviderClient(ReadingProviderClient.GENERATION_URI,
                URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings"),"fixture","fixture")).isInstanceOf(ReadingException.class);
    }
    @ParameterizedTest @ValueSource(strings={"length","content_filter","tool_calls"})
    void preservesNonSuccessFinishForRunnerRejectionAndUsageAudit(String finish) {
        response=chat(finish,Map.of("prompt_tokens",30,"completion_tokens",512));
        var result=client.generate(prompt(512),512,TIMEOUT);
        assertThat(result.finishReason()).isEqualTo(finish);assertThat(result.outputTokens()).isEqualTo(512);assertThat(requests).hasSize(1);
    }
    @Test void absentAndMalformedUsageNeverBecomeFreeCalls() {
        for(Object usage:Arrays.asList(null,Map.of("prompt_tokens",-1,"completion_tokens","9"),Map.of("prompt_tokens",1.5))) {
            response=chat("stop",usage);var result=client.generate(prompt(512),512,TIMEOUT);
            assertThat(result.inputTokens()).isNull();assertThat(result.outputTokens()).isNull();
        }
        assertThat(requests).hasSize(3);
    }
    @ParameterizedTest @ValueSource(ints={302,401,402,429,500,503})
    void httpFailureNeverRetriesOrFollowsRedirectAndDoesNotExposeBody(int code) {
        status=code;response="private-provider-body";
        assertThatThrownBy(()->client.generate(prompt(512),512,TIMEOUT))
                .isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.code()).isEqualTo("PROVIDER_HTTP_"+code))
                .hasMessageNotContaining("private-provider-body").hasMessageNotContaining("fixture-generation").hasNoCause();
        assertThat(requests).hasSize(1);
    }
    @Test void droppedConnectionIsNotReplayed() {
        disconnect=true;
        assertThatThrownBy(()->client.generate(prompt(512),512,TIMEOUT)).isInstanceOf(ReadingException.class);
        assertThat(requests).hasSize(1);
    }
    @Test void totalTimeoutAndInterruptCancelInFlightRequest() throws Exception {
        gate=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var timed=pool.submit(()->client.generate(prompt(512),512,Duration.ofMillis(500)));
            assertThat(received.await(3,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->timed.get(2,TimeUnit.SECONDS)).hasCauseInstanceOf(ReadingException.class)
                    .satisfies(e->assertThat(((ReadingException)e.getCause()).code()).isEqualTo("TIMEOUT"));
            assertThat(requests).hasSize(1);
        }
        gate.countDown();gate=new CountDownLatch(1);received=new CountDownLatch(1);
        var exited=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        Thread thread=new Thread(()->{try{client.generate(prompt(512),512,TIMEOUT);}catch(Throwable e){failure.set(e);}finally{exited.countDown();}});
        thread.start();assertThat(received.await(3,TimeUnit.SECONDS)).isTrue();thread.interrupt();
        assertThat(exited.await(2,TimeUnit.SECONDS)).isTrue();assertThat(failure.get()).isInstanceOf(CancellationException.class);
        assertThat(requests).hasSize(2);
    }
    @Test void slowBodyIsCoveredByTotalDeadline() throws Exception {
        // An alternate local endpoint sends headers then stalls; responseTimeout alone would be insufficient.
        var bodyGate=new CountDownLatch(1);var headers=new CountDownLatch(1);
        server.createContext("/slow",exchange->{
            exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(' ');exchange.getResponseBody().flush();headers.countDown();
            try{bodyGate.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}
        });
        var slow=new ReadingProviderClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/slow"),ReadingProviderClient.EMBEDDING_URI,"fixture","");
        try {
            assertThatThrownBy(()->slow.generate(prompt(512),512,Duration.ofMillis(500)))
                    .isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.code()).isEqualTo("TIMEOUT"));
            assertThat(headers.getCount()).isZero();
        } finally {bodyGate.countDown();}
    }
    @ParameterizedTest @ValueSource(strings={"{}","not-json","{\"choices\":[],\"choices\":[]}","{} {}"})
    void malformedOrAmbiguousResponseFailsClosed(String body) {
        response=body;assertThatThrownBy(()->client.generate(prompt(512),512,TIMEOUT)).isInstanceOf(ReadingException.class);
        assertThat(requests).hasSize(1);
    }
    @Test void oversizedResponseAndWrongContentTypeAreRejected() {
        response=" ".repeat(32769);
        assertThatThrownBy(()->client.generate(prompt(512),512,TIMEOUT)).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.code()).isEqualTo("OUTPUT_INVALID"));
        contentType="text/event-stream";response="data: {}";
        assertThatThrownBy(()->client.generate(prompt(512),512,TIMEOUT)).isInstanceOf(ReadingException.class);
        assertThat(requests).hasSize(2);
    }
    @Test void malformedUtf8IsNotReplacedSilently() {
        raw=new byte[]{(byte)0xc3,(byte)0x28};
        assertThatThrownBy(()->client.generate(prompt(512),512,TIMEOUT)).isInstanceOf(ReadingException.class);
    }
    @Test void badEmbeddingIndicesDimensionsAndValuesFailEntireBatch() {
        var invalid=List.of(List.of(embedding(0,1,1024),embedding(0,1,1024)),List.of(embedding(2,1,1024)),
                List.of(embedding(0,1,3)),List.of(embedding(0,0,1024)),List.of(embedding(0,1e300,1024)));
        for(var rows:invalid) {
            response=embeddings(rows,Map.of("prompt_tokens",8));
            assertThatThrownBy(()->client.embed(Collections.nCopies(rows.size(),"片段"),TIMEOUT)).isInstanceOf(ReadingException.class);
        }
        assertThat(requests).hasSize(invalid.size());
    }
    @Test void invalidInputsAreRejectedBeforeAnyRequestIncludingWireEnvelopeOverflow() {
        assertThatThrownBy(()->client.embed(Collections.nCopies(17,"片段"),TIMEOUT)).isInstanceOf(ReadingException.class);
        assertThatThrownBy(()->client.embed(List.of("字".repeat(1001)),TIMEOUT)).isInstanceOf(ReadingException.class);
        assertThatThrownBy(()->client.generate(prompt(512),1024,TIMEOUT)).isInstanceOf(ReadingException.class);
        String oversized=ReadingText.json(Map.of("promptVersion",ReadingTaskPolicy.PROMPT_VERSION,"system","JSON","question","x".repeat(3900)));
        assertThat(oversized.length()).isLessThan(4096);
        assertThatThrownBy(()->client.generate(oversized,512,TIMEOUT)).isInstanceOf(ReadingException.class);
        assertThatThrownBy(()->client.generate(prompt(512),512,Duration.ZERO)).isInstanceOf(ReadingException.class);
        assertThat(requests).isEmpty();
    }
    @Test void productionGatewayStaysClosedEvenWithCredentialsAndTransportPresent() {
        var gateway=new ReadingAiConfiguration().readingAiGateway("fixture-generation","fixture-embedding",null);
        assertThat(gateway.offline()).isFalse();
        assertThatThrownBy(gateway::requireAuthorized).isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.code()).isEqualTo("LIVE_AI_NOT_AUTHORIZED"));
        assertThatThrownBy(()->gateway.generate(prompt(512),512,TIMEOUT)).isInstanceOf(ReadingException.class);
        assertThatThrownBy(()->gateway.embed(List.of("片段"),TIMEOUT)).isInstanceOf(ReadingException.class);
        assertThatThrownBy(()->gateway.pricing("ANSWER")).isInstanceOf(ReadingException.class);
        assertThat(requests).isEmpty();
    }
}
