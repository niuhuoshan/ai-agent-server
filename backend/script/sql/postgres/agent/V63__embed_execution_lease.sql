-- agent platform schema V63: durable Embed execution ownership and orphan recovery

BEGIN;

ALTER TABLE agent_embed_turn
    ADD COLUMN IF NOT EXISTS execution_owner VARCHAR(128);

ALTER TABLE agent_embed_turn
    ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_agent_embed_turn_active_lease
    ON agent_embed_turn (heartbeat_at, started_at)
    WHERE status IN ('running', 'stopping');

COMMENT ON COLUMN agent_embed_turn.execution_owner IS '当前执行节点租约标识，回合结束后清空';
COMMENT ON COLUMN agent_embed_turn.heartbeat_at IS '当前执行节点最近心跳时间，用于服务异常退出后的回合回收';

COMMIT;
