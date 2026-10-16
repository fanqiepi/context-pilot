package io.github.fanqiepi.contextpilot.upgraderesearch;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class UpgradeReadToolsTests {
    @Test void strictArgumentsRejectUnknownFieldsDuplicateKeysAndScopeChanges() {
        UUID doc=UUID.randomUUID();Set<UUID> documents=Set.of(doc);Set<String> tags=Set.of("v2");
        var valid=UpgradeToolArguments.parse("search_selected_documents",
                "{\"query\":\" upgrade \",\"documentIds\":[\""+doc+"\"]}",documents,tags);
        assertThat(((UpgradeToolArguments.Search)valid).query()).isEqualTo("upgrade");
        for(String json:List.of("{\"tag\":\"v2\",\"ownerId\":\"other\"}","{\"tag\":\"v2\",\"tag\":\"v2\"}",
                "{\"tag\":123}","{\"tag\":\"../keys\"}","{\"tag\":\"not-selected\"}"))
            assertThatThrownBy(()->UpgradeToolArguments.parse("get_release_notes",json,documents,tags)).isInstanceOf(UpgradeToolException.class);
        assertThatThrownBy(()->UpgradeToolArguments.parse("confirm_report","{}",documents,tags))
                .extracting("code").isEqualTo("UNKNOWN_TOOL");
        assertThatThrownBy(()->UpgradeToolArguments.parse("search_selected_documents",
                "{\"query\":\"x\",\"documentIds\":[\""+UUID.randomUUID()+"\"]}",documents,tags))
                .extracting("code").isEqualTo("TOOL_SCOPE_VIOLATION");
    }

    @Test void releaseResultUsesCanonicalSourceAndBoundedExcerpt() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger requests=new AtomicInteger();
        server.createContext("/releases/tags/v2",exchange->{
            requests.incrementAndGet();
            byte[] data=release("x".repeat(6100)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);exchange.close();
        });server.start();
        try {
            var result=client(server,Duration.ofSeconds(2)).read("v2",Instant.now().plusSeconds(3));
            assertThat(result.body()).hasSize(6000);assertThat(result.truncated()).isTrue();
            assertThat(result.url()).isEqualTo("https://github.com/spring-projects/spring-ai/releases/tag/v2");
            assertThat(result.contentHash()).hasSize(64);assertThat(requests).hasValue(1);
        }finally{server.stop(0);}
    }

    @Test void statusCodesAreDistinctAndNeverRetriedOrRedirected() throws Exception {
        for(int status:List.of(301,401,403,404,429,500)) {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger count=new AtomicInteger();
            server.createContext("/",exchange->{count.incrementAndGet();exchange.getResponseHeaders().add("Location","/followed");
                exchange.sendResponseHeaders(status,-1);exchange.close();});server.start();
            try {
                String expected=switch(status){case 301->"RELEASE_REDIRECT_REJECTED";case 401->"RELEASE_UNAUTHORIZED";
                    case 403->"RELEASE_FORBIDDEN";case 404->"RELEASE_NOT_FOUND";case 429->"RELEASE_RATE_LIMITED";default->"RELEASE_SERVICE_FAILED";};
                assertThatThrownBy(()->client(server,Duration.ofSeconds(2)).read("v2",Instant.now().plusSeconds(3)))
                        .extracting("code").isEqualTo(expected);assertThat(count).hasValue(1);
            }finally{server.stop(0);}
        }
    }

    @Test void rejectsOversizedMalformedEmptyDraftAndCompressedResponses() throws Exception {
        for(String kind:List.of("large","json","empty","draft","gzip")) {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                String text=switch(kind){case "large"->"x".repeat(UpgradeBudget.HTTP_MAX_BYTES+1);case "json"->"not json";
                    case "empty"->release("");case "draft"->release("facts").replace("\"draft\":false","\"draft\":true");default->release("facts");};
                if(kind.equals("gzip"))exchange.getResponseHeaders().set("Content-Encoding","gzip");
                byte[] data=text.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,data.length);
                try{exchange.getResponseBody().write(data);}finally{exchange.close();}
            });server.start();
            try {
                String code=switch(kind){case "large"->"RELEASE_RESPONSE_TOO_LARGE";case "json"->"RELEASE_RESPONSE_INVALID";
                    case "empty"->"RELEASE_BODY_EMPTY";case "draft"->"RELEASE_DRAFT_REJECTED";default->"RELEASE_ENCODING_UNSUPPORTED";};
                assertThatThrownBy(()->client(server,Duration.ofSeconds(2)).read("v2",Instant.now().plusSeconds(3)))
                        .extracting("code").isEqualTo(code);
            }finally{server.stop(0);}
        }
    }

    @Test void bodyStallIsBoundedAndDotSegmentsNeverReachServer() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger count=new AtomicInteger();
        server.createContext("/",exchange->{count.incrementAndGet();exchange.sendResponseHeaders(200,0);
            exchange.getResponseBody().write('{');exchange.getResponseBody().flush();
            try{Thread.sleep(600);}catch(InterruptedException error){Thread.currentThread().interrupt();}finally{exchange.close();}
        });server.start();
        try {
            var client=client(server,Duration.ofMillis(150));
            for(String tag:List.of(".","..","../keys","v2/keys","%2e"))
                assertThatThrownBy(()->client.read(tag,Instant.now().plusSeconds(3))).extracting("code").isEqualTo("TOOL_ARGUMENT_INVALID");
            assertThat(count).hasValue(0);
            long start=System.nanoTime();
            assertThatThrownBy(()->client.read("v2",Instant.now().plusSeconds(3))).isInstanceOf(UpgradeToolException.class);
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
        }finally{server.stop(0);}
    }
    private static ReleaseNotesClient client(HttpServer server,Duration timeout){return new ReleaseNotesClient(
            URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/releases/tags/"),"",timeout);}
    private static String release(String body){return UpgradeJson.write(Map.of("id",42,"tag_name","v2","draft",false,
            "prerelease",false,"name","Synthetic","body",body,"published_at","2026-01-01T00:00:00Z",
            "html_url","https://evil.invalid/not-a-source"));}
}
