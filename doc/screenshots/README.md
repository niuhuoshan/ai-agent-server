# 模块截图归档

本目录用于保存 `nhs` 项目业务模块截图，图片由根目录 [`README.md`](../../README.md) 直接引用。

## 截图清单

| 文件名 | 模块 | 画面内容 |
|---|---|---|
| `01-client-chat.png` | 智能对话工作台 | 项目、会话历史、对话区域和输入区 |
| `02-client-task-kanban.png` | 任务状态看板 | 就绪、进行中和已阻塞状态列 |
| `03-client-task-quadrant.png` | 任务四象限 | 重要性与紧急度组合视图 |
| `04-client-projects.png` | 项目中心 | 项目状态、任务、智能体与协作空间 |
| `05-admin-home.png` | 管理工作台 | 个人数据概览、推荐场景和最近任务 |
| `06-agent-create-wizard.png` | Agent 创建向导 | 五步式智能体创建流程 |
| `07-model-configuration.png` | 模型配置 | Provider、能力开关和推理参数表单 |
| `08-mcp-connector.png` | MCP 连接器 | 地址、传输、鉴权和超时配置 |
| `09-knowledge-base.png` | 知识库 | 知识库目录、可见范围与检索入口 |
| `10-data-source.png` | 数据接入 | PostgreSQL 数据源列表与连接状态 |
| `11-risk-control.png` | 风控中心 | 审批工作台与其他风控入口 |
| `12-open-api-embed.png` | 开放接口 | Embed 调试与宿主页面接入示例 |
| `13-user-permissions.png` | 成员与权限 | 权限包、个人覆盖、临时授权和参考复制 |
| `14-token-statistics.png` | Token 统计 | 用量趋势、排行和调用明细 |

## 截图规范

- 优先使用 `PNG`，保持同一批截图的浏览器窗口、缩放比例和主题一致。
- 推荐桌面端视口为 `1440 x 900` 或 `1280 x 800`，避免截入浏览器书签、系统通知和无关窗口。
- 截图内容应能说明模块用途，避免只截空状态；可以使用明确标记的演示数据。
- 不得包含 API Key、Access Token、Cookie、数据库密码、连接字符串中的凭证或内部服务密钥。
- 不得包含真实客户数据、私有会话、个人手机号、邮箱、身份证号或其他敏感信息。
- 截图开放接口、模型、数据源和系统配置时，应对地址、账号及密钥做脱敏或使用演示环境。
- 图片更新后应同步检查根 README 的标题和说明是否仍与当前界面一致。

如需增加图片，沿用两位数字前缀，例如 `15-agent-debug.png`，并同步更新根 README 的产品界面章节。
