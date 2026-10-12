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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
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
                    {"rc":0,"rt":4,"svr":183119932,"lt":1,"full":1,"dlmkts":"","dsc":"0",
                    "data":{"f43":128400,"f44":128648,"f45":126100,"f46":126295,
                    "f47":18594,"f48":2378703599.0,"f57":"600519","f58":"贵州茅台",
                    "f59":2,"f60":126300,"f169":2100,"f170":166}}
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
                .contains("最新价: 1284.00 元")
                .contains("涨跌额: +21.00 元")
                .contains("涨跌幅: +1.66%")
                .contains("状态: 上涨")
                .contains("今日最高: 1286.48 元")
                .contains("今日最低: 1261.00 元")
                .contains("今日开盘: 1262.95 元")
                .contains("昨收: 1263.00 元")
                .contains("成交量: 18594 手")
                .contains("成交额: 23.79 亿元")
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

    @Test
    void fallsBackToGbkTencentQuoteWhenEastmoneyFails() throws IOException {
        AtomicInteger eastmoneyRequests = new AtomicInteger();
        AtomicReference<String> tencentPath = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/eastmoney", exchange -> {
            eastmoneyRequests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/q=sh600519", exchange -> {
            tencentPath.set(exchange.getRequestURI().toString());
            String response = "v_sh600519=\"1~贵州茅台~600519~1286.16~1263.00~1262.95~"
                    + "29032~17507~11521~1286.11~1~1286.10~14~1286.09~6~1286.08~3~"
                    + "1286.02~2~1286.16~2~1286.18~6~1286.19~3~1286.20~10~1286.22~1~~"
                    + "20261012145251~23.16~1.83~1286.48~1261.00~"
                    + "1286.16/29032/3718040251~29032~371804~0.23~19.74~~1286.48~1261.00~"
                    + "2.02~16078.05~16078.05~6.40~1389.30~1136.70~0.98\";";
            byte[] bytes = response.getBytes(Charset.forName("GBK"));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        StockQuoteTool tool = new StockQuoteTool(
                new ObjectMapper(), baseUrl + "/eastmoney", baseUrl + "/q");

        assertThat(tool.fetchStockQuote("600519"))
                .contains("数据源: 腾讯行情（东方财富不可用，已自动切换）")
                .contains("行情时间: 2026-10-12 14:52:51")
                .contains("股票: 贵州茅台 (600519)")
                .contains("最新价: 1286.16 元")
                .contains("涨跌额: +23.16 元")
                .contains("涨跌幅: +1.83%")
                .contains("今日最高: 1286.48 元")
                .contains("今日最低: 1261.00 元")
                .contains("成交量: 29032 手")
                .contains("成交额: 37.18 亿元")
                .contains("换手率: 0.23%")
                .contains("市盈率: 19.74")
                .contains("流通市值: 16078.05 亿元")
                .contains("总市值: 16078.05 亿元")
                .contains("市净率: 6.4")
                .contains("涨停价: 1389.30 元")
                .contains("跌停价: 1136.70 元");
        assertThat(eastmoneyRequests).hasValue(1);
        assertThat(tencentPath).hasValue("/q=sh600519");
    }
}
