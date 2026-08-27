-- agent platform schema V75: unified dashboard token facts and task-run usage

BEGIN;

-- Model-call projections are persisted before task-run terminal state changes.
-- Keeping this reducer in PostgreSQL makes terminal usage updates idempotent and
-- also lets the dashboard backfill historical runs whose usage_json is still {}.
CREATE OR REPLACE FUNCTION agent_task_run_token_usage(p_run_id BIGINT)
RETURNS JSONB
LANGUAGE SQL
STABLE
AS $$
WITH model_calls AS (
    SELECT
        CASE
            WHEN COALESCE(query_projection_json ->> 'promptTokens',
                          query_projection_json ->> 'prompt_tokens', '') ~ '^[0-9]+$'
            THEN COALESCE(query_projection_json ->> 'promptTokens',
                          query_projection_json ->> 'prompt_tokens')::BIGINT
            ELSE 0
        END AS prompt_tokens,
        CASE
            WHEN COALESCE(query_projection_json ->> 'completionTokens',
                          query_projection_json ->> 'completion_tokens', '') ~ '^[0-9]+$'
            THEN COALESCE(query_projection_json ->> 'completionTokens',
                          query_projection_json ->> 'completion_tokens')::BIGINT
            ELSE 0
        END AS completion_tokens,
        CASE
            WHEN COALESCE(query_projection_json ->> 'totalTokens',
                          query_projection_json ->> 'total_tokens', '') ~ '^[0-9]+$'
            THEN COALESCE(query_projection_json ->> 'totalTokens',
                          query_projection_json ->> 'total_tokens')::BIGINT
            ELSE 0
        END AS declared_total_tokens
    FROM agent_execution_event
    WHERE run_id = p_run_id
      AND event_type = 'model_call_finished'
), normalized AS (
    SELECT prompt_tokens,
           completion_tokens,
           CASE
               WHEN declared_total_tokens > 0 THEN declared_total_tokens
               ELSE prompt_tokens + completion_tokens
           END AS total_tokens
    FROM model_calls
)
SELECT jsonb_build_object(
    'source', 'agent_execution_event',
    'modelCalls', COUNT(*),
    'promptTokens', COALESCE(SUM(prompt_tokens), 0),
    'completionTokens', COALESCE(SUM(completion_tokens), 0),
    'totalTokens', COALESCE(SUM(total_tokens), 0),
    'model_calls', COUNT(*),
    'prompt_tokens', COALESCE(SUM(prompt_tokens), 0),
    'completion_tokens', COALESCE(SUM(completion_tokens), 0),
    'total_tokens', COALESCE(SUM(total_tokens), 0)
)
FROM normalized;
$$;

COMMENT ON FUNCTION agent_task_run_token_usage(BIGINT)
    IS '从已持久化的模型完成事件聚合任务运行 Token 用量';

CREATE OR REPLACE VIEW agent_dashboard_token_fact AS
WITH task_usage AS (
    SELECT
        r.id,
        r.trace_id,
        r.created_by AS user_id,
        r.created_at,
        av.agent_id,
        d.name AS agent_name,
        av.model_id,
        model.display_name AS model_name,
        r.status,
        CASE
            WHEN COALESCE(r.usage_json ->> 'promptTokens',
                          r.usage_json ->> 'prompt_tokens', '') ~ '^[0-9]+$'
            THEN COALESCE(r.usage_json ->> 'promptTokens',
                          r.usage_json ->> 'prompt_tokens')::BIGINT
            ELSE (event_usage.value ->> 'promptTokens')::BIGINT
        END AS prompt_tokens,
        CASE
            WHEN COALESCE(r.usage_json ->> 'completionTokens',
                          r.usage_json ->> 'completion_tokens', '') ~ '^[0-9]+$'
            THEN COALESCE(r.usage_json ->> 'completionTokens',
                          r.usage_json ->> 'completion_tokens')::BIGINT
            ELSE (event_usage.value ->> 'completionTokens')::BIGINT
        END AS completion_tokens,
        CASE
            WHEN COALESCE(r.usage_json ->> 'totalTokens',
                          r.usage_json ->> 'total_tokens', '') ~ '^[0-9]+$'
            THEN COALESCE(r.usage_json ->> 'totalTokens',
                          r.usage_json ->> 'total_tokens')::BIGINT
            ELSE (event_usage.value ->> 'totalTokens')::BIGINT
        END AS declared_total_tokens
    FROM agent_task_run r
    JOIN agent_task_version tv ON tv.id = r.task_version_id
    LEFT JOIN agent_definition_version av ON av.id = tv.agent_version_id
    LEFT JOIN agent_definition d ON d.id = av.agent_id
    LEFT JOIN agent_model model ON model.id = av.model_id
    LEFT JOIN LATERAL (
        SELECT agent_task_run_token_usage(r.id) AS value
    ) event_usage ON TRUE
), normalized_task_usage AS (
    SELECT id, trace_id, user_id, created_at, agent_id, agent_name, model_id, model_name,
           prompt_tokens, completion_tokens,
           CASE
               WHEN declared_total_tokens > 0 THEN declared_total_tokens
               ELSE prompt_tokens + completion_tokens
           END AS total_tokens,
           status
    FROM task_usage
)
SELECT
    'conversation_message'::VARCHAR(32) AS source,
    m.id,
    c.user_id,
    m.created_at,
    m.agent_id,
    d.name AS agent_name,
    m.model_id,
    model.display_name AS model_name,
    m.prompt_tokens::BIGINT AS prompt_tokens,
    m.completion_tokens::BIGINT AS completion_tokens,
    CASE
        WHEN m.total_tokens > 0 THEN m.total_tokens::BIGINT
        ELSE (m.prompt_tokens + m.completion_tokens)::BIGINT
    END AS total_tokens,
    m.status
FROM agent_conversation_message m
JOIN agent_conversation c ON c.id = m.conversation_id
LEFT JOIN agent_definition d ON d.id = m.agent_id
LEFT JOIN agent_model model ON model.id = m.model_id
WHERE m.role = 'assistant'
  AND (
      m.trace_id IS NULL
      OR NOT EXISTS (
          SELECT 1 FROM normalized_task_usage linked_run
          WHERE linked_run.trace_id = m.trace_id
      )
      OR (
          CASE
              WHEN m.total_tokens > 0 THEN m.total_tokens::BIGINT
              ELSE (m.prompt_tokens + m.completion_tokens)::BIGINT
          END > 0
          AND NOT EXISTS (
              SELECT 1 FROM normalized_task_usage linked_run
              WHERE linked_run.trace_id = m.trace_id
                AND linked_run.total_tokens > 0
          )
      )
  )

UNION ALL

SELECT
    'agent_task_run'::VARCHAR(32) AS source,
    id,
    user_id,
    created_at,
    agent_id,
    agent_name,
    model_id,
    model_name,
    prompt_tokens,
    completion_tokens,
    total_tokens,
    status
FROM normalized_task_usage task_usage
WHERE task_usage.total_tokens > 0
   OR NOT EXISTS (
       SELECT 1
       FROM agent_conversation_message linked_message
       WHERE linked_message.trace_id = task_usage.trace_id
         AND linked_message.role = 'assistant'
         AND CASE
                 WHEN linked_message.total_tokens > 0
                 THEN linked_message.total_tokens::BIGINT
                 ELSE (linked_message.prompt_tokens + linked_message.completion_tokens)::BIGINT
             END > 0
   );

COMMENT ON VIEW agent_dashboard_token_fact IS
    'Dashboard统一Token事实：会话assistant消息与任务运行模型调用用量';

CREATE INDEX IF NOT EXISTS idx_agent_api_call_dashboard_created
    ON agent_api_call (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_task_run_dashboard_usage
    ON agent_task_run (created_by, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_message_dashboard_tokens
    ON agent_conversation_message (created_at DESC)
    WHERE role = 'assistant';

COMMIT;
