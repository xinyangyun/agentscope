package com.example.springbootdemo.controller;

import com.example.springbootdemo.model.IndexPushTaskRequest;
import com.example.springbootdemo.model.IndexPushTaskResponse;
import com.example.springbootdemo.service.RecoverableIndexPushTaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/index-push-tasks")
public class RecoverableIndexPushTaskController {

    private final RecoverableIndexPushTaskService taskService;

    public RecoverableIndexPushTaskController(RecoverableIndexPushTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IndexPushTaskResponse submit(@Valid @RequestBody IndexPushTaskRequest request) {
        return taskService.submit(request);
    }

    @GetMapping(value = "/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public IndexPushTaskResponse get(@PathVariable String taskId) {
        return taskService.get(taskId);
    }

    @PostMapping(value = "/{taskId}/interrupt", produces = MediaType.APPLICATION_JSON_VALUE)
    public IndexPushTaskResponse interrupt(@PathVariable String taskId) {
        return taskService.interrupt(taskId);
    }

    @PostMapping(value = "/{taskId}/resume", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IndexPushTaskResponse resume(@PathVariable String taskId) {
        return taskService.resume(taskId);
    }
}
