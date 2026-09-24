# Spring Boot Demo

基于 Java 17+、Maven 3.9+ 和 Spring Boot 的示例项目。

## 环境要求

- JDK 17 或更高版本
- Maven 3.9 或更高版本

项目的 Maven Enforcer 会在构建开始时校验这两个版本要求。

## 启动

```bash
mvn spring-boot:run
```

启动后访问：

```text
GET http://localhost:8080/api/hello
```

预期响应：

```json
{"message":"Hello, Spring Boot!"}
```

## 测试

```bash
mvn test
```
