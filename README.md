# agent-server

企业级智能体工作平台的临时工程目录。

## 目录

- `backend/`：基于 RuoYi-Vue-Plus 6.X，Java 21 / Spring Boot 4.1.x 的后端基座。
- `frontend/`：基于 SoybeanAdmin main 分支的 Vue 3 / TypeScript 前端基座。

## 上游基线

| 目录 | 上游 | 分支 | 当前提交 |
|---|---|---|---|
| `backend` | `https://gitee.com/dromara/RuoYi-Vue-Plus.git` | `6.X` | `1df89481db2400b8e8231e77ed9843f4a2d23bcd` |
| `frontend` | `https://github.com/soybeanjs/soybean-admin.git` | `main` | `3d3613f20cd4add3cd20fd6cc884abead165c6d2` |

## 当前约定

- 项目临时名称：`agent-server`。
- 后端 Maven 坐标已切换为 `com.agentserver:agent-server`；内部模块使用 `com.agentserver:agent-*`。
- Java 包名和目录名暂时保留上游值，避免把包重命名与 Maven 坐标变更混在同一阶段。
- 数据库目标基线为 PostgreSQL 16+ / pgvector；RuoYi 原始 MySQL 脚本仅用于上游兼容，平台表按 [数据库设计方案](../doc/企业级智能体工作平台数据库设计方案.md) 的版本化 PostgreSQL 脚本落地。
- 平台数据库脚本位于 [`backend/script/sql/postgres/agent/`](backend/script/sql/postgres/agent/README.md)，当前包含 V1-V9、54 张平台表和完整查询索引。
- 上游 `LICENSE` 文件必须随交付保留。
- AgentScope Java 运行时、任务/项目/制品/验收和平台授权模块将在 `backend` 中新增独立业务模块。

## 运行基线

- 后端：JDK 21、Maven Wrapper、RuoYi-Vue-Plus 6.X。
- 前端：Node.js >= 20.19.0、pnpm >= 10.5.0。
