package com.example.springbootdemo.service;

import com.example.springbootdemo.model.IndexPushTaskRequest;
import com.example.springbootdemo.model.IndexPushTaskResponse;
import com.example.springbootdemo.tool.MarketIndexTool;
import com.example.springbootdemo.tool.PushPlusTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class RecoverableIndexPushTaskService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RecoverableIndexPushTaskService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final MarketIndexTool marketIndexTool;
    private final PushPlusTool pushPlusTool;
    private final ObjectMapper objectMapper;
    private final Path taskDirectory;
    private final ExecutorService executor;
    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final Map<String, Object> taskLocks = new ConcurrentHashMap<>();
    private volatile boolean closing;

    @Autowired
    public RecoverableIndexPushTaskService(
            MarketIndexTool marketIndexTool,
            PushPlusTool pushPlusTool,
            ObjectMapper objectMapper,
            @Value("${agentscope.recoverable-task.directory:.agentscope/recoverable-tasks}")
            String taskDirectory) {
        this(marketIndexTool, pushPlusTool, objectMapper, Path.of(taskDirectory));
    }

    RecoverableIndexPushTaskService(
            MarketIndexTool marketIndexTool,
            PushPlusTool pushPlusTool,
            ObjectMapper objectMapper,
            Path taskDirectory) {
        this.marketIndexTool = marketIndexTool;
        this.pushPlusTool = pushPlusTool;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
        this.taskDirectory = taskDirectory.toAbsolutePath().normalize();
        AtomicInteger threadSequence = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(
                    runnable, "recoverable-index-push-" + threadSequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        loadTasks();
    }

    public IndexPushTaskResponse submit(IndexPushTaskRequest request) {
        Instant now = Instant.now();
        String taskId = UUID.randomUUID().toString();
        String title = request.title() == null || request.title().isBlank()
                ? LocalDate.now(BUSINESS_ZONE) + " " + request.index().trim() + "行情"
                : request.title().trim();
        TaskState task = new TaskState(
                taskId,
                request.index().trim(),
                title,
                Status.QUEUED,
                false,
                request.interruptAfterQuery(),
                null,
                null,
                null,
                now,
                now);
        tasks.put(taskId, task);
        persist(task);
        schedule(taskId);
        return toResponse(task);
    }

    public IndexPushTaskResponse get(String taskId) {
        return toResponse(requireTask(taskId));
    }

    public IndexPushTaskResponse interrupt(String taskId) {
        synchronized (lock(taskId)) {
            TaskState task = requireTask(taskId);
            if (task.status() == Status.COMPLETED || task.status() == Status.FAILED
                    || task.status() == Status.PUSH_RESULT_UNKNOWN) {
                throw new TaskConflictException("Task cannot be interrupted from status " + task.status());
            }
            if (task.status() == Status.PUSHING) {
                throw new TaskConflictException(
                        "PushPlus request has already started and cannot be safely interrupted");
            }
            if (task.status() == Status.INTERRUPTED) {
                return toResponse(task);
            }
            Status status = task.status() == Status.QUEUED ? Status.INTERRUPTED : task.status();
            TaskState updated = task.withControl(status, true, task.interruptAfterQuery());
            save(updated);
            return toResponse(updated);
        }
    }

    public IndexPushTaskResponse resume(String taskId) {
        TaskState updated;
        synchronized (lock(taskId)) {
            TaskState task = requireTask(taskId);
            if (task.status() != Status.INTERRUPTED) {
                throw new TaskConflictException("Only an interrupted task can be resumed");
            }
            updated = task.withControl(Status.QUEUED, false, false);
            save(updated);
        }
        schedule(taskId);
        return toResponse(updated);
    }

    private void schedule(String taskId) {
        executor.submit(() -> execute(taskId));
    }

    private void execute(String taskId) {
        try {
            TaskState task;
            synchronized (lock(taskId)) {
                task = requireTask(taskId);
                if (task.status() != Status.QUEUED || task.interruptRequested()) {
                    return;
                }
                if (task.indexResult() == null) {
                    task = task.withStatus(Status.QUERYING);
                    save(task);
                }
            }

            if (task.indexResult() == null) {
                String indexResult = marketIndexTool.fetchMarketIndex(task.index());
                synchronized (lock(taskId)) {
                    task = requireTask(taskId);
                    if (closing) {
                        if (!isFailure(indexResult, "指数行情获取失败", "指数行情获取被中断")) {
                            task = task.withIndexResult(indexResult);
                            save(task.withControl(Status.INTERRUPTED, true, false));
                        }
                        return;
                    }
                    if (isFailure(indexResult, "指数行情获取失败", "指数行情获取被中断")) {
                        save(task.failed(indexResult));
                        return;
                    }
                    task = task.withIndexResult(indexResult);
                    if (task.interruptRequested() || task.interruptAfterQuery()) {
                        save(task.withControl(Status.INTERRUPTED, true, task.interruptAfterQuery()));
                        return;
                    }
                    save(task);
                }
            }

            synchronized (lock(taskId)) {
                task = requireTask(taskId);
                if (closing || task.interruptRequested()) {
                    save(task.withControl(Status.INTERRUPTED, true, task.interruptAfterQuery()));
                    return;
                }
                // Persist before the external side effect. A crash from here until the response is
                // stored leaves PUSH_RESULT_UNKNOWN on restart instead of causing a duplicate push.
                task = task.withStatus(Status.PUSHING);
                save(task);
            }

            String pushResult = pushPlusTool.pushWechatMessage(
                    task.title(), formatPushContent(task), "markdown");
            synchronized (lock(taskId)) {
                task = requireTask(taskId);
                if (pushResult.startsWith("微信消息推送成功")) {
                    save(task.completed(pushResult));
                } else {
                    save(task.failed(pushResult));
                }
            }
        } catch (RuntimeException exception) {
            log.error("Recoverable index push task failed [taskId={}]", taskId, exception);
            synchronized (lock(taskId)) {
                TaskState task = tasks.get(taskId);
                if (task != null) {
                    if (task.status() == Status.PUSHING) {
                        save(task.withStatus(Status.PUSH_RESULT_UNKNOWN)
                                .withError("PushPlus result is unknown: " + safeMessage(exception)));
                    } else {
                        save(task.failed(safeMessage(exception)));
                    }
                }
            }
        }
    }

    private String formatPushContent(TaskState task) {
        return "## " + task.title() + "\n\n```text\n" + task.indexResult() + "\n```";
    }

    private boolean isFailure(String result, String... prefixes) {
        if (result == null || result.isBlank()) {
            return true;
        }
        for (String prefix : prefixes) {
            if (result.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void loadTasks() {
        try {
            Files.createDirectories(taskDirectory);
            try (var files = Files.list(taskDirectory)) {
                files.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .forEach(this::loadTask);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to initialize recoverable task store", exception);
        }
    }

    private void loadTask(Path path) {
        try {
            TaskState task = objectMapper.readValue(path.toFile(), TaskState.class);
            if (task.status() == Status.QUERYING || task.status() == Status.QUEUED) {
                task = task.withControl(Status.INTERRUPTED, true, false);
                persist(task);
            } else if (task.status() == Status.PUSHING) {
                task = task.withStatus(Status.PUSH_RESULT_UNKNOWN)
                        .withError("Service stopped while PushPlus result was unknown; automatic retry is disabled");
                persist(task);
            }
            tasks.put(task.taskId(), task);
        } catch (IOException | RuntimeException exception) {
            log.error("Failed to load recoverable task [{}]", path, exception);
        }
    }

    private TaskState requireTask(String taskId) {
        TaskState task = tasks.get(taskId);
        if (task == null) {
            throw new TaskNotFoundException("Task not found: " + taskId);
        }
        return task;
    }

    private Object lock(String taskId) {
        return taskLocks.computeIfAbsent(taskId, ignored -> new Object());
    }

    private void save(TaskState task) {
        TaskState updated = task.withUpdatedAt(Instant.now());
        persist(updated);
        tasks.put(updated.taskId(), updated);
    }

    private void persist(TaskState task) {
        try {
            Files.createDirectories(taskDirectory);
            Path target = taskDirectory.resolve(task.taskId() + ".json");
            Path temporary = Files.createTempFile(taskDirectory, task.taskId(), ".tmp");
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), task);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist task " + task.taskId(), exception);
        }
    }

    private IndexPushTaskResponse toResponse(TaskState task) {
        return new IndexPushTaskResponse(
                task.taskId(), task.index(), task.title(), task.status().name(),
                task.interruptRequested(), task.indexResult(), task.pushResult(), task.error(),
                task.createdAt(), task.updatedAt());
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    @Override
    @PreDestroy
    public void close() {
        closing = true;
        executor.shutdownNow();
    }

    enum Status {
        QUEUED,
        QUERYING,
        INTERRUPTED,
        PUSHING,
        COMPLETED,
        FAILED,
        PUSH_RESULT_UNKNOWN
    }

    record TaskState(
            String taskId,
            String index,
            String title,
            Status status,
            boolean interruptRequested,
            boolean interruptAfterQuery,
            String indexResult,
            String pushResult,
            String error,
            Instant createdAt,
            Instant updatedAt) {

        TaskState withStatus(Status value) {
            return new TaskState(taskId, index, title, value, interruptRequested,
                    interruptAfterQuery, indexResult, pushResult, error, createdAt, updatedAt);
        }

        TaskState withControl(Status value, boolean requested, boolean afterQuery) {
            return new TaskState(taskId, index, title, value, requested, afterQuery,
                    indexResult, pushResult, error, createdAt, updatedAt);
        }

        TaskState withIndexResult(String value) {
            return new TaskState(taskId, index, title, status, interruptRequested,
                    interruptAfterQuery, value, pushResult, error, createdAt, updatedAt);
        }

        TaskState completed(String value) {
            return new TaskState(taskId, index, title, Status.COMPLETED, false,
                    false, indexResult, value, null, createdAt, updatedAt);
        }

        TaskState failed(String value) {
            return new TaskState(taskId, index, title, Status.FAILED, false,
                    false, indexResult, pushResult, value, createdAt, updatedAt);
        }

        TaskState withError(String value) {
            return new TaskState(taskId, index, title, status, interruptRequested,
                    interruptAfterQuery, indexResult, pushResult, value, createdAt, updatedAt);
        }

        TaskState withUpdatedAt(Instant value) {
            return new TaskState(taskId, index, title, status, interruptRequested,
                    interruptAfterQuery, indexResult, pushResult, error, createdAt, value);
        }
    }

    public static class TaskNotFoundException extends RuntimeException {

        public TaskNotFoundException(String message) {
            super(message);
        }
    }

    public static class TaskConflictException extends RuntimeException {

        public TaskConflictException(String message) {
            super(message);
        }
    }
}
