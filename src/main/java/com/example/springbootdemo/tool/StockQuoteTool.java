package com.example.springbootdemo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/** Fetches a current A-share quote from Eastmoney with Tencent as fallback. */
public class StockQuoteTool {

    private static final Logger log = LoggerFactory.getLogger(StockQuoteTool.class);
    private static final String DEFAULT_ENDPOINT = "https://push2.eastmoney.com/api/qt/stock/get";
    private static final String DEFAULT_TENCENT_ENDPOINT = "http://qt.gtimg.cn/q";
    private static final String FIELDS = "f43,f44,f45,f46,f47,f48,f57,f58,f59,f60,f169,f170";
    private static final int MAX_ATTEMPTS = 3;

    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String tencentEndpoint;
    private final HttpClient httpClient;

    public StockQuoteTool(ObjectMapper objectMapper, String endpoint) {
        this(objectMapper, endpoint, DEFAULT_TENCENT_ENDPOINT);
    }

    public StockQuoteTool(ObjectMapper objectMapper, String endpoint, String tencentEndpoint) {
        this(objectMapper, endpoint, tencentEndpoint, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    StockQuoteTool(ObjectMapper objectMapper, String endpoint, String tencentEndpoint,
                   HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.trim();
        this.tencentEndpoint = tencentEndpoint == null || tencentEndpoint.isBlank()
                ? DEFAULT_TENCENT_ENDPOINT : tencentEndpoint.trim();
        this.httpClient = httpClient;
    }

    @Tool(
            name = "fetch_stock_quote",
            description = "Fetch the latest A-share quote from Eastmoney, falling back to Tencent when necessary.",
            readOnly = true,
            concurrencySafe = true)
    public String fetchStockQuote(
            @ToolParam(
                    name = "stock_code",
                    required = true,
                    description = "Six-digit A-share code such as 600519, or an Eastmoney secid such as 1.600519")
            String stockCode) {
        final String secid;
        try {
            secid = toSecid(stockCode);
        } catch (IllegalArgumentException exception) {
            return "股票行情获取失败：" + exception.getMessage();
        }

        HttpRequest request = HttpRequest.newBuilder(buildEastmoneyUri(secid))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();

        try {
            HttpResponse<String> response = sendWithRetry(request);
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String result = formatEastmoneyResponse(secid, response.body());
                if (result != null) {
                    return result;
                }
                log.warn("Eastmoney returned incomplete stock data [secid={}]", secid);
            } else {
                log.warn("Eastmoney stock quote request failed [secid={}, status={}]",
                        secid, response.statusCode());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "股票行情获取被中断。";
        } catch (IOException | RuntimeException exception) {
            log.warn("Eastmoney stock quote request failed; using Tencent fallback [secid={}]",
                    secid, exception);
        }

        try {
            return fetchTencentQuote(secid);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "股票行情获取被中断。";
        } catch (IOException | RuntimeException exception) {
            log.error("Tencent stock quote fallback failed [secid={}]", secid, exception);
            return "股票行情获取失败：东方财富和腾讯行情源均不可用（"
                    + safeMessage(exception) + "）。";
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

    private URI buildEastmoneyUri(String secid) {
        String separator = endpoint.contains("?") ? "&" : "?";
        return URI.create(endpoint + separator
                + "secid=" + URLEncoder.encode(secid, StandardCharsets.UTF_8)
                + "&fields=" + FIELDS);
    }

    private String formatEastmoneyResponse(String secid, String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode data = root.path("data");
        if (root.path("rc").asInt(-1) != 0 || !data.isObject() || data.isEmpty()) {
            return null;
        }

        int precision = Math.max(0, Math.min(data.path("f59").asInt(2), 6));
        BigDecimal latestPrice = scaled(data.path("f43"), precision);
        BigDecimal highPrice = scaled(data.path("f44"), precision);
        BigDecimal lowPrice = scaled(data.path("f45"), precision);
        BigDecimal openPrice = scaled(data.path("f46"), precision);
        BigDecimal previousClose = scaled(data.path("f60"), precision);
        BigDecimal priceChange = scaled(data.path("f169"), precision);
        BigDecimal percentChange = scaled(data.path("f170"), 2);
        Long volume = data.path("f47").isNumber() ? data.path("f47").asLong() : null;
        BigDecimal turnover = data.path("f48").isNumber() ? data.path("f48").decimalValue() : null;
        if (latestPrice == null || priceChange == null || percentChange == null) {
            return null;
        }

        String code = data.path("f57").asText(secid.substring(secid.indexOf('.') + 1));
        String name = data.path("f58").asText("未知股票");
        String direction = priceChange.signum() > 0 ? "上涨"
                : priceChange.signum() < 0 ? "下跌" : "平盘";

        StringBuilder output = new StringBuilder();
        output.append("EXTERNAL UNTRUSTED MARKET DATA\n")
                .append("数据源: 东方财富\n")
                .append("查询时间: ").append(Instant.now()).append('\n')
                .append("股票: ").append(oneLine(name)).append(" (").append(code).append(")\n")
                .append("最新价: ").append(format(latestPrice, precision)).append(" 元\n")
                .append("涨跌额: ").append(formatSigned(priceChange, precision)).append(" 元\n")
                .append("涨跌幅: ").append(formatSigned(percentChange, 2)).append("%\n")
                .append("状态: ").append(direction);
        appendPrice(output, "今日最高", highPrice, precision);
        appendPrice(output, "今日最低", lowPrice, precision);
        appendPrice(output, "今日开盘", openPrice, precision);
        if (previousClose != null) {
            output.append('\n').append("昨收: ").append(format(previousClose, precision)).append(" 元");
        }
        if (volume != null) {
            output.append('\n').append("成交量: ").append(volume).append(" 手");
        }
        if (turnover != null) {
            output.append('\n').append("成交额: ").append(formatTurnover(turnover));
        }
        return output.toString();
    }

    private String fetchTencentQuote(String secid) throws IOException, InterruptedException {
        String code = secid.substring(secid.indexOf('.') + 1);
        String symbol = toTencentSymbol(code);
        HttpRequest request = HttpRequest.newBuilder(buildTencentUri(symbol))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "*/*")
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("腾讯行情 HTTP 状态码 " + response.statusCode());
        }
        String body = new String(response.body(), Charset.forName("GBK"));
        return formatTencentResponse(code, body);
    }

    private String formatTencentResponse(String requestedCode, String body) throws IOException {
        int firstQuote = body.indexOf('"');
        int lastQuote = body.lastIndexOf('"');
        if (firstQuote < 0 || lastQuote <= firstQuote) {
            throw new IOException("腾讯行情响应格式无效");
        }
        String[] fields = body.substring(firstQuote + 1, lastQuote).split("~", -1);
        if (fields.length < 36) {
            throw new IOException("腾讯行情字段不完整");
        }

        int timeIndex = findTimestampIndex(fields);
        if (timeIndex < 0 || timeIndex + 18 >= fields.length) {
            throw new IOException("腾讯行情时间字段缺失");
        }

        BigDecimal latestPrice = decimalAt(fields, 3);
        BigDecimal previousClose = decimalAt(fields, 4);
        BigDecimal openPrice = decimalAt(fields, 5);
        Long volume = longAt(fields, 6);
        BigDecimal priceChange = decimalAt(fields, timeIndex + 1);
        BigDecimal percentChange = decimalAt(fields, timeIndex + 2);
        BigDecimal highPrice = decimalAt(fields, timeIndex + 3);
        BigDecimal lowPrice = decimalAt(fields, timeIndex + 4);
        BigDecimal turnover = exactTencentTurnover(fields, timeIndex);
        if (latestPrice == null || priceChange == null || percentChange == null) {
            throw new IOException("腾讯行情关键字段缺失");
        }

        String name = valueAt(fields, 1, "未知股票");
        String code = valueAt(fields, 2, requestedCode);
        String direction = priceChange.signum() > 0 ? "上涨"
                : priceChange.signum() < 0 ? "下跌" : "平盘";
        StringBuilder output = new StringBuilder();
        output.append("EXTERNAL UNTRUSTED MARKET DATA\n")
                .append("数据源: 腾讯行情（东方财富不可用，已自动切换）\n")
                .append("查询时间: ").append(Instant.now()).append('\n')
                .append("行情时间: ").append(formatTencentTime(fields[timeIndex])).append('\n')
                .append("股票: ").append(oneLine(name)).append(" (").append(code).append(")\n")
                .append("最新价: ").append(format(latestPrice, 2)).append(" 元\n")
                .append("涨跌额: ").append(formatSigned(priceChange, 2)).append(" 元\n")
                .append("涨跌幅: ").append(formatSigned(percentChange, 2)).append("%\n")
                .append("状态: ").append(direction);
        appendPrice(output, "今日最高", highPrice, 2);
        appendPrice(output, "今日最低", lowPrice, 2);
        appendPrice(output, "今日开盘", openPrice, 2);
        appendPrice(output, "昨收", previousClose, 2);
        if (volume != null) {
            output.append('\n').append("成交量: ").append(volume).append(" 手");
        }
        if (turnover != null) {
            output.append('\n').append("成交额: ").append(formatTurnover(turnover));
        }
        appendTencentMetric(output, "换手率", decimalAt(fields, timeIndex + 8), "%");
        appendTencentMetric(output, "市盈率", decimalAt(fields, timeIndex + 9), "");
        appendTencentMetric(output, "流通市值", decimalAt(fields, timeIndex + 14), " 亿元");
        appendTencentMetric(output, "总市值", decimalAt(fields, timeIndex + 15), " 亿元");
        appendTencentMetric(output, "市净率", decimalAt(fields, timeIndex + 16), "");
        appendPrice(output, "涨停价", decimalAt(fields, timeIndex + 17), 2);
        appendPrice(output, "跌停价", decimalAt(fields, timeIndex + 18), 2);
        return output.toString();
    }

    private URI buildTencentUri(String symbol) {
        if (tencentEndpoint.endsWith("q=")) {
            return URI.create(tencentEndpoint + symbol);
        }
        if (tencentEndpoint.endsWith("/q")) {
            return URI.create(tencentEndpoint + "=" + symbol);
        }
        String separator = tencentEndpoint.contains("?") ? "&" : "?";
        return URI.create(tencentEndpoint + separator + "q=" + symbol);
    }

    private void appendPrice(StringBuilder output, String label, BigDecimal price, int precision) {
        if (price != null) {
            output.append('\n').append(label).append(": ")
                    .append(format(price, precision)).append(" 元");
        }
    }

    private BigDecimal scaled(JsonNode value, int scale) {
        if (!value.isNumber()) {
            return null;
        }
        return value.decimalValue().movePointLeft(scale);
    }

    private String format(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    private String formatSigned(BigDecimal value, int scale) {
        String formatted = format(value, scale);
        return value.signum() > 0 ? "+" + formatted : formatted;
    }

    private String formatTurnover(BigDecimal turnover) {
        BigDecimal absolute = turnover.abs();
        if (absolute.compareTo(new BigDecimal("100000000")) >= 0) {
            return turnover.divide(new BigDecimal("100000000"), 2, RoundingMode.HALF_UP)
                    .toPlainString() + " 亿元";
        }
        if (absolute.compareTo(new BigDecimal("10000")) >= 0) {
            return turnover.divide(new BigDecimal("10000"), 2, RoundingMode.HALF_UP)
                    .toPlainString() + " 万元";
        }
        return turnover.setScale(2, RoundingMode.HALF_UP).toPlainString() + " 元";
    }

    private int findTimestampIndex(String[] fields) {
        for (int index = 0; index < fields.length; index++) {
            if (fields[index].matches("\\d{14}")) {
                return index;
            }
        }
        return -1;
    }

    private BigDecimal exactTencentTurnover(String[] fields, int timeIndex) {
        String combined = valueAt(fields, timeIndex + 5, "");
        String[] parts = combined.split("/", -1);
        if (parts.length >= 3) {
            BigDecimal exact = decimal(parts[2]);
            if (exact != null) {
                return exact;
            }
        }
        BigDecimal tenThousandYuan = decimalAt(fields, timeIndex + 7);
        return tenThousandYuan == null ? null : tenThousandYuan.movePointRight(4);
    }

    private BigDecimal decimalAt(String[] fields, int index) {
        return index >= 0 && index < fields.length ? decimal(fields[index]) : null;
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank() || "--".equals(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Long longAt(String[] fields, int index) {
        BigDecimal value = decimalAt(fields, index);
        return value == null ? null : value.longValue();
    }

    private String valueAt(String[] fields, int index, String fallback) {
        if (index < 0 || index >= fields.length || fields[index].isBlank()) {
            return fallback;
        }
        return fields[index].trim();
    }

    private String formatTencentTime(String timestamp) {
        if (timestamp == null || !timestamp.matches("\\d{14}")) {
            return timestamp == null ? "Unknown" : timestamp;
        }
        return timestamp.substring(0, 4) + "-" + timestamp.substring(4, 6) + "-"
                + timestamp.substring(6, 8) + " " + timestamp.substring(8, 10) + ":"
                + timestamp.substring(10, 12) + ":" + timestamp.substring(12, 14);
    }

    private void appendTencentMetric(StringBuilder output, String label,
                                     BigDecimal value, String suffix) {
        if (value != null) {
            output.append('\n').append(label).append(": ")
                    .append(value.stripTrailingZeros().toPlainString()).append(suffix);
        }
    }

    private String toTencentSymbol(String code) {
        if (code.startsWith("6")) {
            return "sh" + code;
        }
        if (code.startsWith("0") || code.startsWith("3")) {
            return "sz" + code;
        }
        if (code.startsWith("4") || code.startsWith("8")) {
            return "bj" + code;
        }
        throw new IllegalArgumentException("无法转换腾讯行情代码：" + code);
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
