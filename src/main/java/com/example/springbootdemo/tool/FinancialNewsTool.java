package com.example.springbootdemo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;

public class FinancialNewsTool {

    private static final Logger log = LoggerFactory.getLogger(FinancialNewsTool.class);
    private static final String DEFAULT_ENDPOINT = "https://www.cls.cn/api/cache";
    private static final String REFERER = "https://www.cls.cn/telegraph";
    private static final String USER_AGENT = "Mozilla/5.0";
    private static final int MAX_ATTEMPTS = 3;

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final int maxRecords;
    private final HttpClient httpClient;

    public FinancialNewsTool(ObjectMapper objectMapper, String baseUrl, int maxRecords) {
        this(objectMapper, baseUrl, null, maxRecords);
    }

    /** Kept for source compatibility with the previous GDELT-backed configuration. */
    public FinancialNewsTool(ObjectMapper objectMapper, String baseUrl, String ignoredTimespan,
                             int maxRecords) {
        this(objectMapper, baseUrl, maxRecords, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    FinancialNewsTool(
            ObjectMapper objectMapper,
            String baseUrl,
            int maxRecords,
            HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.maxRecords = Math.max(1, Math.min(maxRecords, 20));
        this.httpClient = httpClient;
    }

    public static String defaultEndpoint() {
        return DEFAULT_ENDPOINT;
    }

    @Tool(
            name = "fetch_latest_financial_news",
            description = "Fetch the latest financial messages from the CLS (财联社) telegraph feed.",
            readOnly = true,
            concurrencySafe = true)
    public String fetchLatestFinancialNews(
            @ToolParam(
                    name = "query",
                    required = false,
                    description = "Optional topic hint used only to label the returned feed")
            String query) {
        String topic = query == null || query.isBlank() ? "财经快讯" : query.trim();
        log.info("Fetching CLS telegraph feed [maxRecords={}]", maxRecords);

        HttpRequest request = HttpRequest.newBuilder(buildUri())
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Referer", REFERER)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        try {
            HttpResponse<String> response = sendWithRetry(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("CLS telegraph request failed [status={}]", response.statusCode());
                return "财经快讯获取失败，HTTP 状态码：" + response.statusCode();
            }
            return formatResponse(topic, response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "财经快讯获取被中断。";
        } catch (IOException | RuntimeException exception) {
            log.error("CLS telegraph request failed", exception);
            return "财经快讯获取失败：" + safeMessage(exception);
        }
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (!isRetryable(response.statusCode()) || attempt == MAX_ATTEMPTS) {
                return response;
            }
            Thread.sleep(Math.min(750L * attempt, 3000L));
        }
        return response;
    }

    private boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private URI buildUri() {
        String separator = baseUrl.contains("?") ? "&" : "?";
        return URI.create(baseUrl + separator
                + "app=CailianpressWeb"
                + "&name=telegraph"
                + "&os=web"
                + "&sv=8.7.9");
    }

    private String formatResponse(String topic, String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode feed = root.path("data").path("roll_data");
        if (!feed.isArray() || feed.isEmpty()) {
            return "财联社电报暂无可用快讯。";
        }

        StringBuilder output = new StringBuilder();
        output.append("EXTERNAL UNTRUSTED NEWS DATA\n")
                .append("Retrieved at: ").append(Instant.now()).append('\n')
                .append("Topic: ").append(oneLine(topic)).append('\n')
                .append("Source: CLS 财联社\n");

        int count = 0;
        for (JsonNode item : feed) {
            if (count >= maxRecords) {
                break;
            }
            count++;
            String content = firstText(item, "content", "brief", "title");
            String timestamp = formatTimestamp(item.path("ctime"));
            String source = firstText(item, "author");
            String url = firstText(item, "assocArticleUrl", "url", "link");
            output.append('\n').append(count).append(". ")
                    .append(content.isBlank() ? "未提供内容" : content).append('\n')
                    .append("Time: ").append(timestamp.isBlank() ? "Unknown" : timestamp).append('\n')
                    .append("Source: ").append(source.isBlank() ? "财联社" : source).append('\n');
            if (!url.isBlank()) {
                output.append("URL: ").append(url).append('\n');
            }
        }
        return output.toString().trim();
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) {
                return oneLine(value);
            }
        }
        return "";
    }

    private String formatTimestamp(JsonNode timestamp) {
        if (timestamp.isNumber()) {
            return Instant.ofEpochSecond(timestamp.asLong()).toString();
        }
        return oneLine(timestamp.asText(""));
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static String oneLine(String value) {
        return value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
    }

    private static String stripTrailingSlash(String value) {
        String normalized = value == null || value.isBlank() ? DEFAULT_ENDPOINT : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
