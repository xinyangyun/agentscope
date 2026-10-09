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
        server.createContext("/api/v2/doc/doc", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            if (requests.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                exchange.sendResponseHeaders(429, -1);
                exchange.close();
                return;
            }
            byte[] response = """
                    {
                      "articles": [
                        {
                          "url": "https://example.com/markets-rally",
                          "title": "Global markets rally after rate decision",
                          "seendate": "20261008T103000Z",
                          "domain": "example.com",
                          "language": "English",
                          "sourcecountry": "United States"
                        }
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        FinancialNewsTool tool = new FinancialNewsTool(
                new ObjectMapper(),
                "http://localhost:" + server.getAddress().getPort() + "/api/v2/doc/doc",
                "24h",
                8);

        String result = tool.fetchLatestFinancialNews("global stock markets");

        assertThat(result)
                .contains("Global markets rally after rate decision")
                .contains("Source: example.com")
                .contains("Observed at UTC: 20261008T103000Z")
                .contains("https://example.com/markets-rally")
                .contains("EXTERNAL UNTRUSTED NEWS DATA");
        assertThat(URLDecoder.decode(rawQuery.get(), StandardCharsets.UTF_8))
                .contains("query=global stock markets")
                .contains("mode=ArtList")
                .contains("format=json")
                .contains("sort=DateDesc")
                .contains("timespan=24h")
                .contains("maxrecords=8");
        assertThat(requests).hasValue(2);
    }

    @Test
    void reportsWhenNoArticlesAreAvailable() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v2/doc/doc", exchange -> {
            byte[] response = "{\"articles\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        FinancialNewsTool tool = new FinancialNewsTool(
                new ObjectMapper(),
                "http://localhost:" + server.getAddress().getPort() + "/api/v2/doc/doc",
                "24h",
                8);

        assertThat(tool.fetchLatestFinancialNews("rare finance topic"))
                .isEqualTo("No financial news articles were found for query: rare finance topic");
    }
}
