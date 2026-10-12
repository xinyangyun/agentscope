package com.example.springbootdemo.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;

/** Fetches market-index data from Tencent's GBK quote endpoint. */
public class MarketIndexTool {

    private static final Logger log = LoggerFactory.getLogger(MarketIndexTool.class);
    private static final String DEFAULT_ENDPOINT = "http://qt.gtimg.cn/q";
    private static final String SHANGHAI_COMPOSITE = "sh000001";

    private final String endpoint;
    private final HttpClient httpClient;

    public MarketIndexTool(String endpoint) {
        this(endpoint, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    MarketIndexTool(String endpoint, HttpClient httpClient) {
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.trim();
        this.httpClient = httpClient;
    }

    @Tool(
            name = "fetch_market_index",
            description = "Fetch current Chinese market-index data. Currently supports the Shanghai Composite Index.",
            readOnly = true,
            concurrencySafe = true)
    public String fetchMarketIndex(
            @ToolParam(
                    name = "index",
                    required = false,
                    description = "Index name or code. Currently supports 上证指数, 000001, or sh000001")
            String index) {
        final String symbol;
        try {
            symbol = resolveSymbol(index);
        } catch (IllegalArgumentException exception) {
            return "指数行情获取失败：" + exception.getMessage();
        }

        HttpRequest request = HttpRequest.newBuilder(buildUri(symbol))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "*/*")
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "指数行情获取失败，HTTP 状态码：" + response.statusCode();
            }
            return formatResponse(new String(response.body(), Charset.forName("GBK")));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "指数行情获取被中断。";
        } catch (IOException | RuntimeException exception) {
            log.error("Tencent market-index request failed [symbol={}]", symbol, exception);
            return "指数行情获取失败：" + safeMessage(exception);
        }
    }

    private URI buildUri(String symbol) {
        if (endpoint.endsWith("q=")) {
            return URI.create(endpoint + symbol);
        }
        if (endpoint.endsWith("/q")) {
            return URI.create(endpoint + "=" + symbol);
        }
        String separator = endpoint.contains("?") ? "&" : "?";
        return URI.create(endpoint + separator + "q=" + symbol);
    }

    private String formatResponse(String body) throws IOException {
        int firstQuote = body.indexOf('"');
        int lastQuote = body.lastIndexOf('"');
        if (firstQuote < 0 || lastQuote <= firstQuote) {
            throw new IOException("腾讯指数响应格式无效");
        }
        String[] fields = body.substring(firstQuote + 1, lastQuote).split("~", -1);
        int timeIndex = findTimestampIndex(fields);
        if (fields.length < 36 || timeIndex < 0 || timeIndex + 39 >= fields.length) {
            throw new IOException("腾讯指数字段不完整");
        }

        BigDecimal current = requiredDecimal(fields, 3, "当前点位");
        BigDecimal previousClose = decimalAt(fields, 4);
        BigDecimal open = decimalAt(fields, 5);
        Long volume = longAt(fields, 6);
        BigDecimal change = requiredDecimal(fields, timeIndex + 1, "涨跌额");
        BigDecimal percent = requiredDecimal(fields, timeIndex + 2, "涨跌幅");
        BigDecimal high = decimalAt(fields, timeIndex + 3);
        BigDecimal low = decimalAt(fields, timeIndex + 4);
        BigDecimal turnover = exactTurnover(fields, timeIndex);
        String direction = change.signum() > 0 ? "上涨"
                : change.signum() < 0 ? "下跌" : "平盘";

        StringBuilder output = new StringBuilder();
        output.append("EXTERNAL UNTRUSTED MARKET DATA\n")
                .append("数据源: 腾讯行情\n")
                .append("查询时间: ").append(Instant.now()).append('\n')
                .append("行情时间: ").append(formatTime(fields[timeIndex])).append('\n')
                .append("指数: ").append(valueAt(fields, 1, "未知指数"))
                .append(" (").append(valueAt(fields, 2, "Unknown")).append(")\n")
                .append("当前点位: ").append(format(current, 2)).append(" 点\n")
                .append("涨跌点数: ").append(formatSigned(change, 2)).append(" 点\n")
                .append("涨跌幅: ").append(formatSigned(percent, 2)).append("%\n")
                .append("状态: ").append(direction);
        appendPoint(output, "今日最高", high);
        appendPoint(output, "今日最低", low);
        appendPoint(output, "今日开盘", open);
        appendPoint(output, "昨收", previousClose);
        if (volume != null) {
            output.append('\n').append("成交量: ").append(volume).append(" 手");
        }
        if (turnover != null) {
            output.append('\n').append("成交额: ").append(formatTurnover(turnover));
        }
        appendMetric(output, "换手率", decimalAt(fields, timeIndex + 8), "%");
        appendMetric(output, "市盈率", decimalAt(fields, timeIndex + 9), "");
        appendMetric(output, "振幅", decimalAt(fields, timeIndex + 13), "%");
        appendMetric(output, "流通市值", decimalAt(fields, timeIndex + 14), " 亿元");
        appendMetric(output, "总市值", decimalAt(fields, timeIndex + 15), " 亿元");
        appendMetric(output, "市净率", decimalAt(fields, timeIndex + 16), "");
        appendMetric(output, "量比", decimalAt(fields, timeIndex + 19), "");
        appendPoint(output, "均价", decimalAt(fields, timeIndex + 21));
        appendPoint(output, "52周最高", decimalAt(fields, timeIndex + 37));
        appendPoint(output, "52周最低", decimalAt(fields, timeIndex + 38));
        return output.toString();
    }

    private String resolveSymbol(String index) {
        if (index == null || index.isBlank()) {
            return SHANGHAI_COMPOSITE;
        }
        String normalized = index.trim().toLowerCase();
        if (normalized.equals("上证指数") || normalized.equals("上证综指")
                || normalized.equals("000001") || normalized.equals(SHANGHAI_COMPOSITE)) {
            return SHANGHAI_COMPOSITE;
        }
        throw new IllegalArgumentException("目前仅支持上证指数（000001）。");
    }

    private int findTimestampIndex(String[] fields) {
        for (int index = 0; index < fields.length; index++) {
            if (fields[index].matches("\\d{14}")) {
                return index;
            }
        }
        return -1;
    }

    private BigDecimal exactTurnover(String[] fields, int timeIndex) {
        String[] combined = valueAt(fields, timeIndex + 5, "").split("/", -1);
        if (combined.length >= 3) {
            BigDecimal exact = decimal(combined[2]);
            if (exact != null) {
                return exact;
            }
        }
        BigDecimal tenThousandYuan = decimalAt(fields, timeIndex + 27);
        if (tenThousandYuan == null) {
            tenThousandYuan = decimalAt(fields, timeIndex + 7);
        }
        return tenThousandYuan == null ? null : tenThousandYuan.movePointRight(4);
    }

    private BigDecimal requiredDecimal(String[] fields, int index, String name) throws IOException {
        BigDecimal value = decimalAt(fields, index);
        if (value == null) {
            throw new IOException("腾讯指数缺少" + name);
        }
        return value;
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

    private void appendPoint(StringBuilder output, String label, BigDecimal value) {
        if (value != null) {
            output.append('\n').append(label).append(": ").append(format(value, 2)).append(" 点");
        }
    }

    private void appendMetric(StringBuilder output, String label,
                              BigDecimal value, String suffix) {
        if (value != null) {
            output.append('\n').append(label).append(": ")
                    .append(value.stripTrailingZeros().toPlainString()).append(suffix);
        }
    }

    private String formatTurnover(BigDecimal turnover) {
        if (turnover.abs().compareTo(new BigDecimal("100000000")) >= 0) {
            return turnover.divide(new BigDecimal("100000000"), 2, RoundingMode.HALF_UP)
                    .toPlainString() + " 亿元";
        }
        return turnover.divide(new BigDecimal("10000"), 2, RoundingMode.HALF_UP)
                .toPlainString() + " 万元";
    }

    private String format(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    private String formatSigned(BigDecimal value, int scale) {
        String formatted = format(value, scale);
        return value.signum() > 0 ? "+" + formatted : formatted;
    }

    private String formatTime(String timestamp) {
        return timestamp.substring(0, 4) + "-" + timestamp.substring(4, 6) + "-"
                + timestamp.substring(6, 8) + " " + timestamp.substring(8, 10) + ":"
                + timestamp.substring(10, 12) + ":" + timestamp.substring(12, 14);
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
