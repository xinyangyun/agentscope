package com.example.springbootdemo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.tool.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PushPlusToolTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void pushPlusToolIsARegisteredLocalTool() throws NoSuchMethodException {
        Method method = PushPlusTool.class.getMethod(
                "pushWechatMessage", String.class, String.class, String.class);
        Tool annotation = method.getAnnotation(Tool.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.externalTool()).isFalse();
    }

    @Test
    void postsPushPlusPayloadAndReportsSuccess() throws IOException {
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/send", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"code\":200,\"msg\":\"请求成功\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        PushPlusTool tool = new PushPlusTool(
                new ObjectMapper(), "test-token", "http://localhost:" + server.getAddress().getPort() + "/send");

        assertThat(tool.pushWechatMessage("测试标题", "这是一条消息", "markdown"))
                .isEqualTo("微信消息推送成功：请求成功");
        assertThat(contentType).hasValue("application/json");

        JsonNode payload = new ObjectMapper().readTree(body.get());
        assertThat(payload.path("token").asText()).isEqualTo("test-token");
        assertThat(payload.path("title").asText()).isEqualTo("测试标题");
        assertThat(payload.path("content").asText()).isEqualTo("这是一条消息");
        assertThat(payload.path("template").asText()).isEqualTo("markdown");
    }

    @Test
    void doesNotSendWhenTokenIsMissing() {
        PushPlusTool tool = new PushPlusTool(
                new ObjectMapper(), "", "https://example.com/send");

        assertThat(tool.pushWechatMessage("标题", "内容", null))
                .isEqualTo("微信消息推送失败：未配置 PUSHPLUS_TOKEN。");
    }
}
