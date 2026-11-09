package com.example.springbootdemo.service;

import com.example.springbootdemo.model.IndexPushTaskRequest;
import com.example.springbootdemo.model.IndexPushTaskResponse;
import com.example.springbootdemo.tool.MarketIndexTool;
import com.example.springbootdemo.tool.PushPlusTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecoverableIndexPushTaskServiceTest {

    @TempDir
    Path taskDirectory;

    @Test
    void resumesAfterRestartWithoutQueryingIndexAgain() {
        MarketIndexTool marketIndexTool = mock(MarketIndexTool.class);
        PushPlusTool pushPlusTool = mock(PushPlusTool.class);
        String indexResult = """
                EXTERNAL UNTRUSTED MARKET DATA
                指数: 上证指数 (000001)
                当前点位: 3800.00 点
                """;
        when(marketIndexTool.fetchMarketIndex("上证指数")).thenReturn(indexResult);
        when(pushPlusTool.pushWechatMessage(
                "今日上证指数", "## 今日上证指数\n\n```text\n" + indexResult + "\n```", "markdown"))
                .thenReturn("微信消息推送成功：请求成功");

        String taskId;
        try (RecoverableIndexPushTaskService service = new RecoverableIndexPushTaskService(
                marketIndexTool, pushPlusTool, new ObjectMapper(), taskDirectory)) {
            IndexPushTaskResponse submitted = service.submit(
                    new IndexPushTaskRequest("上证指数", "今日上证指数", true));
            taskId = submitted.taskId();

            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                IndexPushTaskResponse task = service.get(taskId);
                assertThat(task.status()).isEqualTo("INTERRUPTED");
                assertThat(task.indexResult()).isEqualTo(indexResult);
            });
            verify(pushPlusTool, never()).pushWechatMessage(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString());
        }

        try (RecoverableIndexPushTaskService restarted = new RecoverableIndexPushTaskService(
                marketIndexTool, pushPlusTool, new ObjectMapper(), taskDirectory)) {
            assertThat(restarted.get(taskId).status()).isEqualTo("INTERRUPTED");
            restarted.resume(taskId);

            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(restarted.get(taskId).status()).isEqualTo("COMPLETED"));
            assertThat(restarted.get(taskId).pushResult()).startsWith("微信消息推送成功");
        }

        verify(marketIndexTool).fetchMarketIndex("上证指数");
        verify(pushPlusTool).pushWechatMessage(
                "今日上证指数", "## 今日上证指数\n\n```text\n" + indexResult + "\n```", "markdown");
    }
}
