package com.example.springbootdemo.tool;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.tool.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MarketIndexToolTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void marketIndexToolIsARegisteredLocalTool() throws NoSuchMethodException {
        Method method = MarketIndexTool.class.getMethod("fetchMarketIndex", String.class);
        Tool annotation = method.getAnnotation(Tool.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.externalTool()).isFalse();
    }

    @Test
    void fetchesAndParsesShanghaiCompositeIndexFromGbkResponse() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/q=sh000001", exchange -> {
            path.set(exchange.getRequestURI().toString());
            String response = "v_sh000001=\"1~上证指数~000001~3797.24~3813.79~3806.98~"
                    + "461448981~0~0~0.00~0~0.00~0~0.00~0~0.00~0~0.00~0~0.00~0~"
                    + "0.00~0~0.00~0~0.00~0~0.00~0~~20261012161400~-16.55~-0.43~"
                    + "3828.22~3791.00~3797.24/461448981/714909910722~461448981~"
                    + "71490991~0.95~16.51~~3828.22~3791.00~0.98~595342.44~668853.58~"
                    + "0.00~-1~-1~1.01~0~3807.98~~~~~~71490991.0722~0.0000~0~ ~ZS~"
                    + "-4.32~-0.69~~~~4258.86~3741.11~-2.93~-3.38~-4.98~4861621705847~"
                    + "~-15.93~-7.00~4861621705847~~~-2.56~0.10~~CNY~0~~0.00~0~\";";
            byte[] bytes = response.getBytes(Charset.forName("GBK"));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        MarketIndexTool tool = new MarketIndexTool(
                "http://localhost:" + server.getAddress().getPort() + "/q");

        assertThat(tool.fetchMarketIndex("上证指数"))
                .contains("数据源: 腾讯行情")
                .contains("行情时间: 2026-10-12 16:14:00")
                .contains("指数: 上证指数 (000001)")
                .contains("当前点位: 3797.24 点")
                .contains("涨跌点数: -16.55 点")
                .contains("涨跌幅: -0.43%")
                .contains("状态: 下跌")
                .contains("今日最高: 3828.22 点")
                .contains("今日最低: 3791.00 点")
                .contains("今日开盘: 3806.98 点")
                .contains("昨收: 3813.79 点")
                .contains("成交量: 461448981 手")
                .contains("成交额: 7149.10 亿元")
                .contains("换手率: 0.95%")
                .contains("市盈率: 16.51")
                .contains("振幅: 0.98%")
                .contains("流通市值: 595342.44 亿元")
                .contains("总市值: 668853.58 亿元")
                .contains("市净率: 0")
                .contains("量比: 1.01")
                .contains("均价: 3807.98 点")
                .contains("52周最高: 4258.86 点")
                .contains("52周最低: 3741.11 点");
        assertThat(path).hasValue("/q=sh000001");
    }

    @Test
    void defaultsToShanghaiCompositeAndRejectsUnsupportedIndex() {
        MarketIndexTool tool = new MarketIndexTool("https://example.com/q");

        assertThat(tool.fetchMarketIndex("沪深300"))
                .isEqualTo("指数行情获取失败：目前仅支持上证指数（000001）。");
    }
}
