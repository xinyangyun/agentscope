package com.example.springbootdemo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.StringJoiner;

public class FinancialNewsTool {

    private static final Logger log = LoggerFactory.getLogger(FinancialNewsTool.class);
    static final String DEFAULT_QUERY = "financial markets";
    private static final int MAX_ATTEMPTS = 3;

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String timespan;
    private final int maxRecords;
    private final HttpClient httpClient;

    public FinancialNewsTool(ObjectMapper objectMapper, String baseUrl, String timespan, int maxRecords) {
        this(objectMapper, baseUrl, timespan, maxRecords, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    FinancialNewsTool(
            ObjectMapper objectMapper,
            String baseUrl,
            String timespan,
            int maxRecords,
            HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.timespan = timespan == null || timespan.isBlank() ? "24h" : timespan.trim();
        this.maxRecords = Math.max(1, Math.min(maxRecords, 25));
        this.httpClient = httpClient;
    }

    @Tool(
            name = "fetch_latest_financial_news",
            description = "Fetch the latest financial and business news from GDELT. The query must use concise English keywords. Results are ordered by the time GDELT first observed each article.",
            readOnly = true,
            concurrencySafe = true)
    public String fetchLatestFinancialNews(
            @ToolParam(
                    name = "query",
                    required = false,
                    description = "English financial-news search terms, such as global stock markets, Federal Reserve, or gold prices")
            String query) {
        log.info("Fetching financial news [timespan={}, maxRecords={}]", timespan, maxRecords);
        String effectiveQuery = query == null || query.isBlank() ? DEFAULT_QUERY : query.trim();
        if (effectiveQuery.length() > 300) {
            effectiveQuery = effectiveQuery.substring(0, 300);
        }
        String logQuery = sanitizeForLog(effectiveQuery);
        log.info("Fetching financial news [query={}, timespan={}, maxRecords={}]",
                logQuery, timespan, maxRecords);

        HttpRequest request = HttpRequest.newBuilder(buildUri(effectiveQuery))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", "agentscope-financial-news/1.0")
                .GET()
                .build();

        try {
            HttpResponse<String> response = sendWithRetry(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Financial news request failed [query={}, status={}]",
                        logQuery, response.statusCode());
                return "Financial news retrieval failed with HTTP status " + response.statusCode() + ".";
            }
            return formatResponse(effectiveQuery, response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Financial news request interrupted [query={}]", logQuery);
            return "Financial news retrieval was interrupted.";
        } catch (IOException | RuntimeException exception) {
            log.error("Financial news request failed [query={}]", logQuery, exception);
            return "Financial news retrieval failed: " + safeMessage(exception);
        }
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (!isRetryable(response.statusCode()) || attempt == MAX_ATTEMPTS) {
                return response;
            }
            long delayMillis = retryDelayMillis(response, attempt);
            log.warn("Retrying financial news request [status={}, attempt={}/{}, delayMs={}]",
                    response.statusCode(), attempt, MAX_ATTEMPTS, delayMillis);
            Thread.sleep(delayMillis);
        }
        return response;
    }

    private boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private long retryDelayMillis(HttpResponse<String> response, int attempt) {
        Optional<String> retryAfter = response.headers().firstValue("Retry-After");
        if (retryAfter.isPresent()) {
            try {
                long seconds = Long.parseLong(retryAfter.get());
                return Math.min(Math.max(seconds, 0), 3) * 1000;
            } catch (NumberFormatException ignored) {
                // Fall through to a short bounded backoff for HTTP-date or invalid values.
            }
        }
        return Math.min(750L * attempt, 3000L);
    }

    private URI buildUri(String query) {
        StringJoiner parameters = new StringJoiner("&");
        parameters.add("query=" + encode(query));
        parameters.add("mode=ArtList");
        parameters.add("format=json");
        parameters.add("sort=DateDesc");
        parameters.add("timespan=" + encode(timespan));
        parameters.add("maxrecords=" + maxRecords);
        return URI.create(baseUrl + "?" + parameters);
    }

    private String formatResponse(String query, String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode articles = root.path("articles");
        if (!articles.isArray() || articles.isEmpty()) {
            log.info("No financial news found [query={}]", sanitizeForLog(query));
            return "No financial news articles were found for query: " + query;
        }

        StringBuilder output = new StringBuilder();
        output.append("EXTERNAL UNTRUSTED NEWS DATA\n")
                .append("Retrieved at UTC: ").append(Instant.now()).append('\n')
                .append("Query: ").append(query).append('\n')
                .append("Timespan: ").append(timespan).append('\n')
                .append("The observed time is when GDELT detected the article, not necessarily its publication time.\n");

        int count = 0;
        for (JsonNode article : articles) {
            if (count >= maxRecords) {
                break;
            }
            count++;
            output.append('\n').append(count).append(". ")
                    .append(text(article, "title", "Untitled article")).append('\n')
                    .append("Source: ").append(text(article, "domain", "Unknown source")).append('\n')
                    .append("Observed at UTC: ").append(text(article, "seendate", "Unknown")).append('\n')
                    .append("Source country: ").append(text(article, "sourcecountry", "Unknown")).append('\n')
                    .append("Language: ").append(text(article, "language", "Unknown")).append('\n')
                    .append("URL: ").append(text(article, "url", "Unavailable")).append('\n');
        }
        log.info("Financial news retrieved [query={}, articleCount={}]",
                sanitizeForLog(query), count);
        return output.toString().trim();
    }

    private String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("").replace('\n', ' ').replace('\r', ' ').trim();
        return value.isEmpty() ? fallback : value;
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String sanitizeForLog(String value) {
        return value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }

    private static String stripTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Financial news base URL must not be blank");
        }
        return normalized;
    }
}
