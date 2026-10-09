package com.example.springbootdemo.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.tool.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialNewsToolTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void financialNewsToolIsExecutedLocallyByAgentScope() throws NoSuchMethodException {
        Method method = FinancialNewsTool.class
                .getMethod("fetchLatestFinancialNews", String.class);
        Tool annotation = method.getAnnotation(Tool.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.externalTool()).isFalse();
    }

    @Test
    void fetchesAndFormatsLatestArticles() throws IOException {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> referer = new AtomicReference<>();
        AtomicReference<String> userAgent = new AtomicReference<>();
        server.createContext("/api/cache", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            referer.set(exchange.getRequestHeaders().getFirst("Referer"));
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            if (requests.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                exchange.sendResponseHeaders(429, -1);
                exchange.close();
                return;
            }
            byte[] response = """
                    {"errno":0,"data":{"roll_data":[
                      {"content":"央行发布最新金融政策","ctime":1791538200,"author":"财联社","assocArticleUrl":"https://www.cls.cn/example"}
                    ]}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        FinancialNewsTool tool = new FinancialNewsTool(
                new ObjectMapper(),
                "http://localhost:" + server.getAddress().getPort() + "/api/cache",
                "24h",
                8);

        String result = tool.fetchLatestFinancialNews("global stock markets");

        assertThat(result)
                .contains("央行发布最新金融政策")
                .contains("Source: 财联社")
                .contains("Time: 2026-10-09T09:30:00Z")
                .contains("https://www.cls.cn/example")
                .contains("EXTERNAL UNTRUSTED NEWS DATA");
        assertThat(URLDecoder.decode(rawQuery.get(), StandardCharsets.UTF_8))
                .contains("app=CailianpressWeb")
                .contains("name=telegraph")
                .contains("os=web")
                .contains("sv=8.7.9");
        assertThat(referer).hasValue("https://www.cls.cn/telegraph");
        assertThat(userAgent).hasValue("Mozilla/5.0");
        assertThat(requests).hasValue(2);
    }

    @Test
    void reportsWhenNoArticlesAreAvailable() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/cache", exchange -> {
            byte[] response = "{\"errno\":0,\"data\":{\"roll_data\":[]}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        FinancialNewsTool tool = new FinancialNewsTool(
                new ObjectMapper(),
                "http://localhost:" + server.getAddress().getPort() + "/api/cache",
                "24h",
                8);

        assertThat(tool.fetchLatestFinancialNews("rare finance topic"))
                .isEqualTo("财联社电报暂无可用快讯。");
    }
}
