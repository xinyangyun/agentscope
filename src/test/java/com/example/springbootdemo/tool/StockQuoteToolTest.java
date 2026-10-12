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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StockQuoteToolTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void stockQuoteToolIsARegisteredLocalTool() throws NoSuchMethodException {
        Method method = StockQuoteTool.class.getMethod("fetchStockQuote", String.class);
        Tool annotation = method.getAnnotation(Tool.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.externalTool()).isFalse();
    }

    @Test
    void fetchesAndScalesLatestStockQuote() throws IOException {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        AtomicReference<String> userAgent = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/qt/stock/get", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] response = """
                    {"rc":0,"data":{"f43":127651,"f44":128444,"f45":126100,
                    "f46":126295,"f47":9549,"f48":1219211538.0,"f57":"600519",
                    "f58":"贵州茅台","f59":2,"f60":126300,"f169":1351,"f170":107}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        StockQuoteTool tool = new StockQuoteTool(
                new ObjectMapper(),
                "http://localhost:" + server.getAddress().getPort() + "/api/qt/stock/get");

        String result = tool.fetchStockQuote("600519");

        assertThat(result)
                .contains("股票: 贵州茅台 (600519)")
                .contains("最新价: 1276.51 元")
                .contains("涨跌额: +13.51 元")
                .contains("涨跌幅: +1.07%")
                .contains("状态: 上涨")
                .contains("昨收: 1263.00 元")
                .contains("EXTERNAL UNTRUSTED MARKET DATA");
        String query = URLDecoder.decode(rawQuery.get(), StandardCharsets.UTF_8);
        assertThat(query)
                .contains("secid=1.600519")
                .contains("fields=f43,f44,f45,f46,f47,f48,f57,f58,f59,f60,f169,f170");
        assertThat(userAgent).hasValue("Mozilla/5.0");
    }

    @Test
    void convertsShenzhenCodeAndFormatsADecline() throws IOException {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/qt/stock/get", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] response = """
                    {"rc":0,"data":{"f43":1025,"f57":"000001","f58":"平安银行",
                    "f59":2,"f60":1040,"f169":-15,"f170":-144}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        StockQuoteTool tool = new StockQuoteTool(
                new ObjectMapper(),
                "http://localhost:" + server.getAddress().getPort() + "/api/qt/stock/get");

        assertThat(tool.fetchStockQuote("000001"))
                .contains("涨跌额: -0.15 元")
                .contains("涨跌幅: -1.44%")
                .contains("状态: 下跌");
        assertThat(URLDecoder.decode(rawQuery.get(), StandardCharsets.UTF_8))
                .contains("secid=0.000001");
    }

    @Test
    void rejectsInvalidStockCodeWithoutCallingTheApi() {
        StockQuoteTool tool = new StockQuoteTool(
                new ObjectMapper(), "https://example.com/api/qt/stock/get");

        assertThat(tool.fetchStockQuote("MOUTAI"))
                .isEqualTo("股票行情获取失败：请输入六位 A 股代码，例如 600519。");
    }
}
