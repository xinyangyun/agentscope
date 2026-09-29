# AgentScope Spring Boot Demo

基于 Java 17+、Maven 3.9+、Spring Boot 和 AgentScope Harness 的示例项目。

## 环境要求

- JDK 17 或更高版本
- Maven 3.9 或更高版本

项目的 Maven Enforcer 会在构建开始时校验这两个版本要求。

## 模型配置

聊天入口默认使用 `openai:gpt-4o-mini`。启动前配置 OpenAI API Key：

```bash
export OPENAI_API_KEY="your-api-key"
mvn spring-boot:run
```

也可以通过环境变量调整模型和工作区：

```bash
export AGENTSCOPE_MODEL="openai:gpt-4o-mini"
export AGENTSCOPE_WORKSPACE=".agentscope/workspace"
```

`HarnessAgent` 会根据 `userId` 与 `sessionId` 自动隔离并持久化会话上下文。

## 启动

```bash
mvn spring-boot:run
```

## 接口说明

### 服务问候

```text
GET http://localhost:8080/api/hello
```

响应：

```json
{"message":"Hello, Spring Boot!"}
```

### Agent 对话

向大模型发送一条消息。相同的 `userId` 和 `sessionId` 会复用同一个会话上下文。

请求：

```bash
curl --request POST \
  --url http://localhost:8080/api/chat \
  --header 'Content-Type: application/json' \
  --data '{
    "message": "请用一句话介绍你自己",
    "userId": "user-001",
    "sessionId": "session-001"
  }'
```

请求字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `message` | `string` | 是 | 发给大模型的消息，不能为空 |
| `userId` | `string` | 是 | 用户标识，不能为空 |
| `sessionId` | `string` | 是 | 会话标识，不能为空 |

成功响应：

- HTTP 状态码：`200 OK`
- Content-Type：`application/json`

```json
{
  "message": "你好，我是一个 AI 助手。",
  "userId": "user-001",
  "sessionId": "session-001"
}
```

校验失败时返回 `400 Bad Request`。如果没有配置 `OPENAI_API_KEY`，或模型服务不可用，服务会返回 `5xx` 错误。

## 测试

```bash
mvn test
```
