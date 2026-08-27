-- agent platform schema V86: durable Agent-initiated user questions

BEGIN;

CREATE TABLE IF NOT EXISTS agent_runtime_user_question (
    id                       BIGINT PRIMARY KEY,
    question_id              VARCHAR(128) NOT NULL,
    owner_id                 BIGINT NOT NULL,
    conversation_id          BIGINT NOT NULL,
    execution_id             VARCHAR(128),
    conversation_turn_id     BIGINT,
    tool_call_id             VARCHAR(128),
    idempotency_key          VARCHAR(128) NOT NULL,
    question                 VARCHAR(2000) NOT NULL,
    options_json             JSONB NOT NULL DEFAULT '[]'::jsonb,
    multi_select             BOOLEAN NOT NULL DEFAULT FALSE,
    allow_custom_input       BOOLEAN NOT NULL DEFAULT TRUE,
    context                  VARCHAR(2000),
    purpose                  VARCHAR(128),
    status                   VARCHAR(32) NOT NULL DEFAULT 'pending',
    selected_option_ids_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    custom_input             VARCHAR(4000),
    answer_idempotency_key   VARCHAR(128),
    decision_key_hash        CHAR(64),
    expires_at               TIMESTAMP NOT NULL,
    answered_at              TIMESTAMP,
    cancelled_at             TIMESTAMP,
    created_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_runtime_user_question_question_id UNIQUE (question_id),
    CONSTRAINT uk_agent_runtime_user_question_create_key
        UNIQUE (owner_id, conversation_id, idempotency_key),
    CONSTRAINT ck_agent_runtime_user_question_owner CHECK (owner_id > 0),
    CONSTRAINT ck_agent_runtime_user_question_conversation CHECK (conversation_id > 0),
    CONSTRAINT ck_agent_runtime_user_question_status CHECK (
        status IN ('pending', 'submitted', 'cancelled', 'expired', 'superseded')
    ),
    CONSTRAINT ck_agent_runtime_user_question_options CHECK (
        jsonb_typeof(options_json) = 'array'
        AND jsonb_array_length(options_json) BETWEEN 2 AND 12
    ),
    CONSTRAINT ck_agent_runtime_user_question_selected CHECK (
        jsonb_typeof(selected_option_ids_json) = 'array'
    ),
    CONSTRAINT ck_agent_runtime_user_question_decision_hash CHECK (
        decision_key_hash IS NULL OR decision_key_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX IF NOT EXISTS idx_agent_runtime_user_question_pending
    ON agent_runtime_user_question (owner_id, conversation_id, created_at DESC, id DESC)
    WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS idx_agent_runtime_user_question_expiry
    ON agent_runtime_user_question (expires_at)
    WHERE status = 'pending';

COMMENT ON TABLE agent_runtime_user_question IS 'Agent主动提问的用户交互卡持久状态';
COMMENT ON COLUMN agent_runtime_user_question.id IS '内部雪花业务主键';
COMMENT ON COLUMN agent_runtime_user_question.question_id IS '对外暴露的问题唯一标识';
COMMENT ON COLUMN agent_runtime_user_question.owner_id IS '问题所属的人类用户ID';
COMMENT ON COLUMN agent_runtime_user_question.conversation_id IS '问题所属的个人会话ID';
COMMENT ON COLUMN agent_runtime_user_question.execution_id IS '产生问题的运行实例标识';
COMMENT ON COLUMN agent_runtime_user_question.conversation_turn_id IS '产生问题的会话回合ID';
COMMENT ON COLUMN agent_runtime_user_question.tool_call_id IS 'ask_user_question 工具调用标识';
COMMENT ON COLUMN agent_runtime_user_question.idempotency_key IS '创建问题的幂等键';
COMMENT ON COLUMN agent_runtime_user_question.question IS '展示给用户的问题文本';
COMMENT ON COLUMN agent_runtime_user_question.options_json IS '服务端冻结的问题选项快照';
COMMENT ON COLUMN agent_runtime_user_question.multi_select IS '是否允许多选';
COMMENT ON COLUMN agent_runtime_user_question.allow_custom_input IS '是否允许用户补充文本';
COMMENT ON COLUMN agent_runtime_user_question.context IS '问题上下文说明';
COMMENT ON COLUMN agent_runtime_user_question.purpose IS '问题业务用途标识';
COMMENT ON COLUMN agent_runtime_user_question.status IS '问题状态：pending/submitted/cancelled/expired/superseded';
COMMENT ON COLUMN agent_runtime_user_question.selected_option_ids_json IS '服务端校验后的选项ID快照';
COMMENT ON COLUMN agent_runtime_user_question.custom_input IS '用户补充输入内容';
COMMENT ON COLUMN agent_runtime_user_question.answer_idempotency_key IS '回答或取消操作的幂等键';
COMMENT ON COLUMN agent_runtime_user_question.decision_key_hash IS '回答或取消请求的内容哈希';
COMMENT ON COLUMN agent_runtime_user_question.expires_at IS '问题失效时间';
COMMENT ON COLUMN agent_runtime_user_question.answered_at IS '回答提交时间';
COMMENT ON COLUMN agent_runtime_user_question.cancelled_at IS '用户取消时间';
COMMENT ON COLUMN agent_runtime_user_question.created_at IS '创建时间';
COMMENT ON COLUMN agent_runtime_user_question.updated_at IS '最后更新时间';

COMMIT;
