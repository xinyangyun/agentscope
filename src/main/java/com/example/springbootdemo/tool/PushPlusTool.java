package com.example.springbootdemo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Sends WeChat notifications through the PushPlus API. */
public class PushPlusTool {

    private static final Logger log = LoggerFactory.getLogger(PushPlusTool.class);
    private static final String DEFAULT_ENDPOINT = "https://www.pushplus.plus/send";
    private static final int MAX_CONTENT_LENGTH = 10000;

    private final ObjectMapper objectMapper;
    private final String token;
    private final String endpoint;
    private final HttpClient httpClient;

    public PushPlusTool(ObjectMapper objectMapper, String token, String endpoint) {
        this(objectMapper, token, endpoint, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    PushPlusTool(ObjectMapper objectMapper, String token, String endpoint, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.token = token == null ? "" : token.trim();
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.trim();
        this.httpClient = httpClient;
    }

    @Tool(
            name = "push_wechat_message",
            description = "Send a notification to WeChat through PushPlus. Requires PUSHPLUS_TOKEN configuration.",
            readOnly = false,
            concurrencySafe = true)
    public String pushWechatMessage(
            @ToolParam(name = "title", required = true, description = "Notification title") String title,
            @ToolParam(name = "content", required = true, description = "Notification body") String content,
            @ToolParam(name = "template", required = false,
                    description = "PushPlus template, usually markdown or txt") String template) {
        if (token.isBlank()) {
            return "微信消息推送失败：未配置 PUSHPLUS_TOKEN。";
        }
        if (title == null || title.isBlank()) {
            return "微信消息推送失败：标题不能为空。";
        }
        if (content == null || content.isBlank()) {
            return "微信消息推送失败：内容不能为空。";
        }

        String effectiveTitle = limit(title.trim(), 200);
        String effectiveContent = limit(content.trim(), MAX_CONTENT_LENGTH);
        String effectiveTemplate = template == null || template.isBlank() ? "markdown" : template.trim();

        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("token", token);
            payload.put("title", effectiveTitle);
            payload.put("content", effectiveContent);
            payload.put("template", effectiveTemplate);

            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("PushPlus request failed [status={}]", response.statusCode());
                return "微信消息推送失败，HTTP 状态码：" + response.statusCode();
            }
            return formatResponse(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "微信消息推送被中断。";
        } catch (IOException | RuntimeException exception) {
            log.error("PushPlus request failed", exception);
            return "微信消息推送失败：" + safeMessage(exception);
        }
    }

    private String formatResponse(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        int code = root.path("code").asInt(-1);
        String message = root.path("msg").asText("");
        if (code == 200 || code == 0) {
            return "微信消息推送成功" + (message.isBlank() ? "。" : "：" + message);
        }
        return "微信消息推送失败" + (message.isBlank() ? "。" : "：" + message);
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static String limit(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
