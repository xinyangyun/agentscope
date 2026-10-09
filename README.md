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
export AGENTSCOPE_ENABLE_THINKING="false"
export AGENTSCOPE_WORKSPACE=".agentscope/workspace"
export FINANCIAL_NEWS_TIMESPAN="24h"
export FINANCIAL_NEWS_MAX_RECORDS="8"
```

`AGENTSCOPE_BASE_URL` 只填写接口根地址，不要拼接 `/chat/completions`。AgentScope 会自动追加聊天接口路径。
工具调用默认关闭思考模式，避免 OpenAI 兼容接口在多轮工具调用时因缺少
`reasoning_content` 返回 `400`。仅在模型和接口都完整支持思考内容回传时，才将
`AGENTSCOPE_ENABLE_THINKING` 设置为 `true`。

需要更强模型时，可以切换为：

```bash
export AGENTSCOPE_MODEL="deepseek-v4-pro"
```

`HarnessAgent` 使用进程内短期记忆，并根据 `userId` 与 `sessionId` 隔离会话上下文。
同一组 `userId` 和 `sessionId` 的后续请求会携带此前对话，不同会话之间不会串话；
应用重启后短期记忆会自动清空。

### 短期记忆容量

当前未自定义会话压缩参数，使用 AgentScope 2.0.4 的默认策略：

- 累积到 50 条消息时触发上下文压缩。普通的一问一答约为 2 条消息，因此通常约 25 轮后触发。
- 压缩后保留最近 20 条原始消息，普通问答约为最近 10 轮。
- 更早的对话会被整理为摘要并继续放入当前会话上下文，因此会话可以继续进行，但较早内容可能丢失细节。
- 工具调用及其结果也会占用消息数量，所以包含工具调用时可能少于 25 轮就触发压缩。
- 应用进程重启后，所有短期记忆都会清空。

### 长期记忆

长期记忆使用 AgentScope Harness 的工作区记忆机制，并保存在 `AGENTSCOPE_WORKSPACE` 指定的目录中：

- Agent 会自动从对话中提取值得长期保留的信息，写入按日期组织的记忆文件。
- 每日记忆会定期整理到 `MEMORY.md`，并在后续对话中作为上下文重新加载。
- `memory_search`、`memory_get` 和 `memory_save` 工具已启用，Agent 可以搜索、读取和主动保存记忆。
- 长期记忆按 `userId` 隔离，同一用户更换 `sessionId` 后仍可复用，不同用户之间不会共享。
- 长期记忆保存在磁盘中，因此应用重启后仍然存在。
- 默认每日记忆保留 90 天，会话记录保留 180 天，记忆整理的最小间隔为 30 分钟。

删除 `AGENTSCOPE_WORKSPACE` 对应目录中的记忆文件即可清除长期记忆。短期记忆与长期记忆相互独立。

### 财经新闻子 Agent

主 Agent 注册了名为 `financial-news` 的子 Agent，用于查询和整理最新财经新闻。它通过 GDELT DOC API 获取数据，不需要额外的新闻 API Key。

- 子 Agent 会根据用户的问题生成英文财经搜索词，并调用 `fetch_latest_financial_news` 工具。
- 新闻默认覆盖最近 24 小时，每次最多返回 8 条，可通过 `FINANCIAL_NEWS_TIMESPAN` 和 `FINANCIAL_NEWS_MAX_RECORDS` 调整。
- 结果按 GDELT 首次发现文章的时间倒序排列，并包含标题、来源域名、来源国家、语言、发现时间和原文链接。
- 遇到 `429` 或临时网关错误时会进行有上限的自动重试；持续失败时会明确返回错误，不会伪造新闻结果。
- 新闻标题和元数据被视为外部不可信数据，子 Agent 不会执行其中包含的指令。
- 新闻内容仅用于信息整理，不构成个性化投资建议。

可以直接通过聊天接口提出类似问题：

```text
请整理最近 24 小时最重要的全球股市新闻，并附上来源链接。
```

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
