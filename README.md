# Hermes Android

一个原生 Android 客户端，用来连接自建的 **Hermes Agent**，UI 参考 RikkaHub 的设计语言。
它解决的核心痛点是：

> 派完任务 → 关掉 App → 任务在服务端继续跑 → 回来（或收到通知）就能拿到**完整结果**。

标准 OpenAI 客户端只会用 `/v1/chat/completions`（同步、关应用即断流），本应用改用 Hermes 的
**Runs API**（`POST /v1/runs` 异步发起 + `GET /v1/runs/{id}` 轮询 + `/events` SSE 可断可续），
从而做到真正的“断线可恢复”。

## 核心机制

```
用户发送消息
  └─ 本地先落库用户消息 + 助手占位消息 (Room)
  └─ POST /v1/runs  (Idempotency-Key 防重)  → 立刻拿到 run_id
  └─ run_id 持久化到 Room (runs 表)
  └─ 连接 GET /v1/runs/{id}/events 收流式进度
         message.delta / tool.started / tool.completed / run.completed ...
  └─ 每一批事件写回 Room → UI 实时渲染

用户杀掉进程 / 断网 / 切后台
  └─ 服务端继续执行任务（run 不依赖客户端连接）

重新打开 App
  └─ MainActivity.onCreate / onResume → RunManager.reconcileAll()
  └─ 遍历本地未终态 run → GET /v1/runs/{id}
        · 已终态 → 取 output 落库 + 发通知
        · 仍 running → 重新 attach /events
  └─ WorkManager 周期任务（15 分钟）在后台兜底对账并补发通知
```

- `: keepalive` 注释行由 `okhttp-sse` 自动忽略，不会误判断线。
- 事件里 `event` 字段是事件名（服务端把事件名放在 JSON 内，而不是 SSE 的 `event:` 行）。

## 目录结构

```
app/src/main/java/com/hermes/client/
├─ HermesApp.kt                 # @HiltAndroidApp + WorkManager Configuration.Provider
├─ MainActivity.kt              # 入口；通知深链；前台对账
├─ di/                          # Hilt 模块（网络、数据库、协程作用域）
├─ data/
│  ├─ model/                    # 领域模型 / 枚举
│  ├─ remote/                   # Retrofit API、DTO、动态 URL 拦截器、SSE 客户端
│  ├─ local/                    # Room 实体 / DAO / 数据库 / TypeConverter
│  ├─ prefs/                    # DataStore 设置
│  └─ repository/               # SessionRepository / ChatRepository / RunManager
├─ notifications/               # 完成通知
├─ worker/                      # WorkManager 对账 Worker
└─ ui/
   ├─ theme/                    # Material 3 深/浅色、动态取色
   ├─ markdown/                 # 自研 Markdown 渲染 + 代码高亮
   ├─ sessions/                 # 会话列表
   ├─ chat/                     # 聊天页
   └─ settings/                 # 设置页
```

## 技术栈

| 维度 | 选择 |
|---|---|
| 语言 / UI | Kotlin + Jetpack Compose + Material 3 |
| 架构 | MVVM + Repository（异步任务由 `RunManager` 单例统筹） |
| DI | Hilt |
| 网络 | Retrofit + OkHttp + okhttp-sse |
| 存储 | Room（会话/消息/run）+ DataStore（设置） |
| 后台 | WorkManager（周期对账 + 通知兜底） |
| 图片 | Coil |
| Markdown | 自研解析 + 渲染，代码块内置轻量语法高亮 |

minSdk 26 / targetSdk 35。

## 构建

前置：JDK 17+、Android SDK（platform 35、build-tools 35.0.0）。`local.properties` 指向 SDK：

```properties
sdk.dir=/path/to/Android/Sdk
```

```bash
./gradlew :app:assembleDebug      # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest  # 单元测试
```

## 使用

1. 打开 **设置**，填写 Hermes API 的 `Base URL`（可带路径前缀，如 `https://host/hermes-api`）和 `API Key`，点 **测试连接**（打 `GET /v1/models`）。
2. 回到会话列表，点 **新会话** 开始对话。
3. 长任务可以直接发，返回或杀进程都不影响；完成时收到系统通知，点通知直达该会话。
4. 聊天页右上角可 **同步服务端历史**、重命名；长按用户消息可编辑重发 / 重新生成。
5. 发送中的任务可点 **停止** 中断；输入框支持图片 / 文件附件。

## 已实现的接口

| 端点 | 用途 |
|---|---|
| `GET /health`、`GET /v1/models`、`GET /v1/capabilities` | 连接与能力探测 |
| `POST /v1/uploads/token` | 获取短时效、仅限上传的 scoped token（文件回传） |
| `POST /v1/runs` | 异步发起任务（带 `Idempotency-Key`） |
| `GET /v1/runs/{id}` | 轮询状态（断线恢复核心） |
| `GET /v1/runs/{id}/events` | SSE 事件流 |
| `POST /v1/runs/{id}/stop` / `/steer` | 中断 / 插话 |
| `GET /api/sessions/{id}/messages` | 服务端权威历史兜底 |

## 安全模型（2026-10 入侵复盘后的加固）

本节记录一次真实入侵（服务端 SSH 凭据被改写、系统重装）后的客户端加固。**核心教训：持有
API key 即等于持有服务端的远程命令执行能力**（Hermes agent 能跑 terminal），因此 key 的任何
泄漏面都必须消除：

1. **Run prompt 中永不嵌入主 API key。** 旧实现把主 key 拼进 instructions 教 agent 用 curl
   回传文件——prompt 会落盘到服务端会话记录，且与不可信内容（网页、上传文件）一起被处理，
   一次 prompt injection 即可外传 key。现在客户端先调 `POST /v1/uploads/token` 换取短时效、
   仅限 `POST /upload` 的 scoped token（约定前缀 `uat_`），只把它写进 prompt；服务端未部署
   该端点时，文件回传能力自动降级（不回退到主 key）。
2. **凭据只在 TLS 上发送。** 所有 OkHttp 拦截器仅在最终 URL 为 `https://` 时附加
   `Authorization` 头；`http://` Base URL 只会产生匿名请求（设置页同时给出明文警告）。
3. **密钥静态加密。** API key 与 ntfy push token 经 Android Keystore（AES-256-GCM）加密后
   才写入 DataStore，旧明文条目首启自动迁移；备份规则继续把它们排除出云备份。
4. **不内置任何默认服务器地址。** 首次启动必须自行填写 Base URL。

### 服务端契约：`POST /v1/uploads/token`

需要主 API key 认证（走 `Authorization: Bearer <API_KEY>`），返回：

```json
{ "token": "uat_<random>", "expires_in": 600 }
```

- token 仅被文件服务的 `POST /upload` 接受，**不能**调用 Runs API 或读写其它资源；
- TTL 建议 ≤ 10 分钟，一次性使用更佳；
- 客户端每次发消息前现取现用，不做缓存。

### 重新部署服务端时的清单

- 更换全部密钥；`API_SERVER_KEY` 只存 `~/.hermes/.env`（`chmod 600`），并支持轮换；
- SSH：`PasswordAuthentication no`、`PermitRootLogin no`，安全组把 22 限制到自己的出口 IP；
- Hermes 服务以独立非特权用户运行，其 `~/.ssh` 不可写，terminal 工具加敏感路径黑名单
  （`~/.ssh`、`~/.hermes/.env`、`/etc`）；
- 文件服务 `/files/{id}` 的 `id` 必须是查表 ID 而非磁盘路径（防 path traversal），upload
  落盘目录独立且无执行权限；
- nginx 加 `limit_req`，留存访问日志并定期审计 401 聚集的来源 IP；
- ntfy topic 视为秘密或强制 token 订阅。

## 许可

本项目以 **AGPL-3.0** 开源（UI 设计参考 RikkaHub，后者同样是 AGPL-3.0）。
