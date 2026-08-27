-- ChatBI SQL self-heal attempts and dependency-aware task plans.

BEGIN;

CREATE TABLE IF NOT EXISTS agent_chatbi_sql_repair_attempt (
    id                  BIGINT PRIMARY KEY,
    owner_id            BIGINT NOT NULL,
    conversation_id     BIGINT NOT NULL,
    dataset_id          BIGINT NOT NULL,
    trace_id            VARCHAR(64) NOT NULL,
    failed_query_id     BIGINT,
    retry_query_id      BIGINT,
    attempt_no          INTEGER NOT NULL,
    max_attempts        INTEGER NOT NULL,
    error_category      VARCHAR(64) NOT NULL,
    error_summary       VARCHAR(1000) NOT NULL,
    failed_sql          TEXT NOT NULL,
    repaired_sql        TEXT,
    repair_model_id     BIGINT,
    repair_reason       VARCHAR(2000),
    status              VARCHAR(32) NOT NULL DEFAULT 'planning',
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at         TIMESTAMP,
    CONSTRAINT uk_agent_chatbi_sql_repair_trace_attempt UNIQUE (trace_id, attempt_no),
    CONSTRAINT ck_agent_chatbi_sql_repair_owner CHECK (owner_id > 0),
    CONSTRAINT ck_agent_chatbi_sql_repair_attempt CHECK (
        attempt_no BETWEEN 1 AND 5 AND max_attempts BETWEEN 1 AND 5
        AND attempt_no <= max_attempts
    ),
    CONSTRAINT ck_agent_chatbi_sql_repair_status CHECK (
        status IN ('planning', 'executing', 'succeeded', 'failed', 'rejected')
    )
);

CREATE INDEX IF NOT EXISTS idx_agent_chatbi_sql_repair_owner_trace
    ON agent_chatbi_sql_repair_attempt (owner_id, trace_id, attempt_no);
CREATE INDEX IF NOT EXISTS idx_agent_chatbi_sql_repair_failed_query
    ON agent_chatbi_sql_repair_attempt (failed_query_id)
    WHERE failed_query_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_chatbi_sql_repair_retry_query
    ON agent_chatbi_sql_repair_attempt (retry_query_id)
    WHERE retry_query_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS agent_chatbi_task_plan (
    id                  BIGINT PRIMARY KEY,
    plan_key            VARCHAR(64) NOT NULL,
    owner_id            BIGINT NOT NULL,
    conversation_id     BIGINT,
    dataset_id          BIGINT NOT NULL,
    request_question    VARCHAR(4000) NOT NULL,
    status              VARCHAR(32) NOT NULL DEFAULT 'pending',
    task_count          INTEGER NOT NULL,
    current_task_key    VARCHAR(64),
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    CONSTRAINT uk_agent_chatbi_task_plan_key UNIQUE (plan_key),
    CONSTRAINT ck_agent_chatbi_task_plan_owner CHECK (owner_id > 0),
    CONSTRAINT ck_agent_chatbi_task_plan_count CHECK (task_count BETWEEN 1 AND 12),
    CONSTRAINT ck_agent_chatbi_task_plan_status CHECK (
        status IN ('pending', 'running', 'succeeded', 'failed', 'clarification_required')
    )
);

CREATE INDEX IF NOT EXISTS idx_agent_chatbi_task_plan_owner_created
    ON agent_chatbi_task_plan (owner_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_chatbi_task_plan_conversation
    ON agent_chatbi_task_plan (conversation_id, created_at DESC)
    WHERE conversation_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS agent_chatbi_task_plan_item (
    id                  BIGINT PRIMARY KEY,
    plan_id             BIGINT NOT NULL,
    task_key            VARCHAR(64) NOT NULL,
    sequence_no         INTEGER NOT NULL,
    operation           VARCHAR(32) NOT NULL,
    query_text          VARCHAR(4000) NOT NULL,
    depends_on_json     JSONB NOT NULL DEFAULT '[]'::jsonb,
    status              VARCHAR(32) NOT NULL DEFAULT 'pending',
    trace_id            VARCHAR(64),
    result_query_id     BIGINT,
    error_summary       VARCHAR(1000),
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_chatbi_task_plan_item_key UNIQUE (plan_id, task_key),
    CONSTRAINT uk_agent_chatbi_task_plan_item_sequence UNIQUE (plan_id, sequence_no),
    CONSTRAINT ck_agent_chatbi_task_plan_item_sequence CHECK (sequence_no BETWEEN 1 AND 12),
    CONSTRAINT ck_agent_chatbi_task_plan_item_operation CHECK (
        operation IN ('query', 'analyze', 'present')
    ),
    CONSTRAINT ck_agent_chatbi_task_plan_item_status CHECK (
        status IN ('pending', 'running', 'succeeded', 'failed', 'skipped')
    )
);

CREATE INDEX IF NOT EXISTS idx_agent_chatbi_task_plan_item_status
    ON agent_chatbi_task_plan_item (plan_id, status, sequence_no);
CREATE INDEX IF NOT EXISTS idx_agent_chatbi_task_plan_item_result
    ON agent_chatbi_task_plan_item (result_query_id)
    WHERE result_query_id IS NOT NULL;

COMMENT ON TABLE agent_chatbi_sql_repair_attempt IS 'ChatBI SQL自动修复与受限重试记录';
COMMENT ON COLUMN agent_chatbi_sql_repair_attempt.id IS '修复尝试ID';
COMMENT ON COLUMN agent_chatbi_sql_repair_attempt.owner_id IS '发起查询的用户ID';
COMMENT ON COLUMN agent_chatbi_sql_repair_attempt.conversation_id IS '所属私有会话ID';
COMMENT ON COLUMN agent_chatbi_sql_repair_attempt.dataset_id IS '查询数据集ID';
COMMENT ON COLUMN agent_chatbi_sql_repair_attempt.trace_id IS '同一轮ChatBI查询追踪标识';
COMMENT ON COLUMN agent_chatbi_sql_repair_attempt.failed_query_id IS '触发本次修复的失败查询ID';
COMMENT ON COLUMN agent_chatbi_sql_repair_attempt.retry_query_id IS '本次修复SQL对应的重试查询ID';
COMMENT ON COLUMN agent_chatbi_sql_repair_attempt.attempt_no IS '当前自动修复次数';
COMMENT ON COLUMN agent_chatbi_sql_repair_attempt.max_attempts IS '本轮允许的最大自动修复次数';
COMMENT ON COLUMN agent_chatbi_sql_repair_attempt.error_category IS '脱敏后的SQL错误分类';
COMMENT ON COLUMN agent_chatbi_sql_repair_attempt.error_summary IS '提供给修复模型的有界错误摘要';
COMMENT ON COLUMN agent_chatbi_sql_repair_attempt.failed_sql IS '上一次未通过校验或执行失败的SQL';
COMMENT ON COLUMN agent_chatbi_sql_repair_attempt.repaired_sql IS '模型返回且准备重新校验的SQL';
COMMENT ON COLUMN agent_chatbi_sql_repair_attempt.repair_model_id IS '生成修复SQL的模型ID';
COMMENT ON COLUMN agent_chatbi_sql_repair_attempt.repair_reason IS '模型返回的修复原因摘要';
COMMENT ON COLUMN agent_chatbi_sql_repair_attempt.status IS '修复状态：规划中、执行中、成功、失败或拒绝';
COMMENT ON COLUMN agent_chatbi_sql_repair_attempt.created_at IS '修复尝试创建时间';
COMMENT ON COLUMN agent_chatbi_sql_repair_attempt.finished_at IS '修复尝试结束时间';

COMMENT ON TABLE agent_chatbi_task_plan IS 'ChatBI显式混合请求的持久任务计划';
COMMENT ON COLUMN agent_chatbi_task_plan.id IS '任务计划ID';
COMMENT ON COLUMN agent_chatbi_task_plan.plan_key IS '对外稳定任务计划标识';
COMMENT ON COLUMN agent_chatbi_task_plan.owner_id IS '任务计划所属用户ID';
COMMENT ON COLUMN agent_chatbi_task_plan.conversation_id IS '执行后绑定的私有会话ID';
COMMENT ON COLUMN agent_chatbi_task_plan.dataset_id IS '任务计划使用的数据集ID';
COMMENT ON COLUMN agent_chatbi_task_plan.request_question IS '用户原始分析请求';
COMMENT ON COLUMN agent_chatbi_task_plan.status IS '任务计划状态';
COMMENT ON COLUMN agent_chatbi_task_plan.task_count IS '任务节点数量';
COMMENT ON COLUMN agent_chatbi_task_plan.current_task_key IS '当前执行的任务节点标识';
COMMENT ON COLUMN agent_chatbi_task_plan.created_at IS '任务计划创建时间';
COMMENT ON COLUMN agent_chatbi_task_plan.started_at IS '任务计划开始时间';
COMMENT ON COLUMN agent_chatbi_task_plan.finished_at IS '任务计划结束时间';

COMMENT ON TABLE agent_chatbi_task_plan_item IS 'ChatBI任务计划节点及依赖执行状态';
COMMENT ON COLUMN agent_chatbi_task_plan_item.id IS '任务节点ID';
COMMENT ON COLUMN agent_chatbi_task_plan_item.plan_id IS '所属任务计划ID';
COMMENT ON COLUMN agent_chatbi_task_plan_item.task_key IS '对外稳定任务节点标识';
COMMENT ON COLUMN agent_chatbi_task_plan_item.sequence_no IS '任务节点执行顺序';
COMMENT ON COLUMN agent_chatbi_task_plan_item.operation IS '任务操作类型：查询、分析或呈现';
COMMENT ON COLUMN agent_chatbi_task_plan_item.query_text IS '任务节点使用的自然语言问题';
COMMENT ON COLUMN agent_chatbi_task_plan_item.depends_on_json IS '前置任务节点标识列表';
COMMENT ON COLUMN agent_chatbi_task_plan_item.status IS '任务节点状态';
COMMENT ON COLUMN agent_chatbi_task_plan_item.trace_id IS '任务节点实际查询追踪标识';
COMMENT ON COLUMN agent_chatbi_task_plan_item.result_query_id IS '任务节点成功结果查询ID';
COMMENT ON COLUMN agent_chatbi_task_plan_item.error_summary IS '任务节点失败或跳过原因';
COMMENT ON COLUMN agent_chatbi_task_plan_item.started_at IS '任务节点开始时间';
COMMENT ON COLUMN agent_chatbi_task_plan_item.finished_at IS '任务节点结束时间';
COMMENT ON COLUMN agent_chatbi_task_plan_item.created_at IS '任务节点创建时间';

COMMIT;
