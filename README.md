# AgentScope Spring Boot Demo

基于 Java 17+、Maven 3.9+、Spring Boot 和 AgentScope Harness 的示例项目。

## 环境要求

- JDK 17 或更高版本
- Maven 3.9 或更高版本

项目的 Maven Enforcer 会在构建开始时校验这两个版本要求。

## 模型配置

聊天入口默认使用 DeepSeek 的 `deepseek-flash` 模型。启动前配置 DeepSeek API Key：

```bash
export DEEPSEEK_API_KEY="your-api-key"
mvn spring-boot:run
```

也可以通过环境变量调整模型和工作区：

```bash
export AGENTSCOPE_MODEL="deepseek-flash"
export AGENTSCOPE_BASE_URL="https://api.deepseek.com"
export AGENTSCOPE_WORKSPACE=".agentscope/workspace"
```

`AGENTSCOPE_BASE_URL` 只填写接口根地址，不要拼接 `/chat/completions`。AgentScope 会自动追加聊天接口路径。

需要更强模型时，可以切换为：

```bash
export AGENTSCOPE_MODEL="deepseek-v4-pro"
```

`HarnessAgent` 使用进程内短期记忆，并根据 `userId` 与 `sessionId` 隔离会话上下文。
同一组 `userId` 和 `sessionId` 的后续请求会携带此前对话，不同会话之间不会串话；
应用重启后短期记忆会自动清空。长期记忆工具与自动记忆提取均已关闭，会话上下文不会写入磁盘。

### 短期记忆容量

当前未自定义会话压缩参数，使用 AgentScope 2.0.1 的默认策略：

- 累积到 50 条消息时触发上下文压缩。普通的一问一答约为 2 条消息，因此通常约 25 轮后触发。
- 压缩后保留最近 20 条原始消息，普通问答约为最近 10 轮。
- 更早的对话会被整理为摘要并继续放入当前会话上下文，因此会话可以继续进行，但较早内容可能丢失细节。
- 工具调用及其结果也会占用消息数量，所以包含工具调用时可能少于 25 轮就触发压缩。
- 应用进程重启后，所有短期记忆都会清空。

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

校验失败时返回 `400 Bad Request`。模型服务调用失败时返回 `502 Bad Gateway`，响应中的 `message` 会包含上游错误原因。

## 测试

```bash
mvn test
```
