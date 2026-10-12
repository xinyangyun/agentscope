package com.example.springbootdemo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
//import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/** Fetches a current A-share quote from Eastmoney. */
public class StockQuoteTool {

    private static final Logger log = LoggerFactory.getLogger(StockQuoteTool.class);
    private static final String DEFAULT_ENDPOINT = "https://push2.eastmoney.com/api/qt/stock/get";
    private static final String FIELDS = "f43,f44,f45,f46,f47,f48,f57,f58,f59,f60,f169,f170";
    private static final int MAX_ATTEMPTS = 3;

    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final HttpClient httpClient;

    public StockQuoteTool(ObjectMapper objectMapper, String endpoint) {
        this(objectMapper, endpoint, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    StockQuoteTool(ObjectMapper objectMapper, String endpoint, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.trim();
        this.httpClient = httpClient;
    }

    @Tool(
            name = "fetch_stock_quote",
            description = "Fetch the latest A-share price, price change in CNY, and percentage change from Eastmoney.",
            readOnly = true,
            concurrencySafe = true)
    public String fetchStockQuote(
            @ToolParam(
                    name = "stock_code",
                    required = true,
                    description = "Six-digit A-share code such as 600519, or an Eastmoney secid such as 1.600519")
            String stockCode) {
        log.info("fetchStockQuote start");
        final String secid;
        try {
            secid = toSecid(stockCode);
        } catch (IllegalArgumentException exception) {
            return "股票行情获取失败：" + exception.getMessage();
        }

        HttpRequest request = HttpRequest.newBuilder(buildUri(secid))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();

        try {
            HttpResponse<String> response = sendWithRetry(request);
            log.info("fetchStockQuote response={}", response);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Eastmoney stock quote request failed [secid={}, status={}]",
                        secid, response.statusCode());
                return "股票行情获取失败，HTTP 状态码：" + response.statusCode();
            }
            return formatResponse(secid, response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "股票行情获取被中断。";
        } catch (IOException | RuntimeException exception) {
            log.error("Eastmoney stock quote request failed [secid={}]", secid, exception);
            return "股票行情获取失败：" + safeMessage(exception);
        }
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request)
            throws IOException, InterruptedException {
        HttpResponse<String> response = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (!isRetryable(response.statusCode()) || attempt == MAX_ATTEMPTS) {
                return response;
            }
            Thread.sleep(Math.min(500L * attempt, 2000L));
        }
        return response;
    }

    private boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private URI buildUri(String secid) {
        String separator = endpoint.contains("?") ? "&" : "?";
        return URI.create(endpoint + separator
                + "secid=" + URLEncoder.encode(secid, StandardCharsets.UTF_8)
                + "&fields=" + FIELDS);
    }

    private String formatResponse(String secid, String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode data = root.path("data");
        if (root.path("rc").asInt(-1) != 0 || !data.isObject() || data.isEmpty()) {
            return "未找到股票行情：" + secid.substring(secid.indexOf('.') + 1);
        }

        int precision = Math.max(0, Math.min(data.path("f59").asInt(2), 6));
        BigDecimal latestPrice = scaled(data.path("f43"), precision);
        BigDecimal previousClose = scaled(data.path("f60"), precision);
        BigDecimal priceChange = scaled(data.path("f169"), precision);
        BigDecimal percentChange = scaled(data.path("f170"), 2);
        if (latestPrice == null || priceChange == null || percentChange == null) {
            return "股票行情数据不完整：" + secid.substring(secid.indexOf('.') + 1);
        }

        String code = data.path("f57").asText(secid.substring(secid.indexOf('.') + 1));
        String name = data.path("f58").asText("未知股票");
        String direction = priceChange.signum() > 0 ? "上涨"
                : priceChange.signum() < 0 ? "下跌" : "平盘";

        StringBuilder output = new StringBuilder();
        output.append("EXTERNAL UNTRUSTED MARKET DATA\n")
                .append("查询时间: ").append(Instant.now()).append('\n')
                .append("股票: ").append(oneLine(name)).append(" (").append(code).append(")\n")
                .append("最新价: ").append(format(latestPrice, precision)).append(" 元\n")
                .append("涨跌额: ").append(formatSigned(priceChange, precision)).append(" 元\n")
                .append("涨跌幅: ").append(formatSigned(percentChange, 2)).append("%\n")
                .append("状态: ").append(direction);
        if (previousClose != null) {
            output.append('\n').append("昨收: ").append(format(previousClose, precision)).append(" 元");
        }
        return output.toString();
    }

    private BigDecimal scaled(JsonNode value, int scale) {
        if (!value.isNumber()) {
            return null;
        }
        return value.decimalValue().movePointLeft(scale);
    }

    private String format(BigDecimal value, int scale) {
//        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
        return value.movePointLeft(scale)
                .setScale(scale, BigDecimal.ROUND_HALF_UP)
                .toPlainString();
    }

    private String formatSigned(BigDecimal value, int scale) {
        String formatted = format(value, scale);
        return value.signum() > 0 ? "+" + formatted : formatted;
    }

    private String toSecid(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) {
            throw new IllegalArgumentException("股票代码不能为空。");
        }
        String normalized = stockCode.trim();
        if (normalized.matches("\\d+\\.\\d{6}")) {
            return normalized;
        }
        if (!normalized.matches("\\d{6}")) {
            throw new IllegalArgumentException("请输入六位 A 股代码，例如 600519。");
        }
        if (normalized.startsWith("6")) {
            return "1." + normalized;
        }
        if (normalized.startsWith("0") || normalized.startsWith("3")
                || normalized.startsWith("4") || normalized.startsWith("8")) {
            return "0." + normalized;
        }
        throw new IllegalArgumentException("无法判断股票市场，请使用东方财富 secid，例如 1.600519。");
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static String oneLine(String value) {
        return value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
    }
}
