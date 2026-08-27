-- Durable private-chat branches. Historical messages remain in their original conversation.

BEGIN;

ALTER TABLE agent_conversation
    ADD COLUMN IF NOT EXISTS branch_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS parent_conversation_id BIGINT,
    ADD COLUMN IF NOT EXISTS fork_message_id BIGINT,
    ADD COLUMN IF NOT EXISTS context_cutoff_sequence INTEGER;

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_conversation_branch_id
    ON agent_conversation (branch_id)
    WHERE branch_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_conversation_parent_branch
    ON agent_conversation (parent_conversation_id, create_time DESC)
    WHERE parent_conversation_id IS NOT NULL AND del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_conversation_fork_message
    ON agent_conversation (fork_message_id)
    WHERE fork_message_id IS NOT NULL;

COMMENT ON COLUMN agent_conversation.branch_id IS '会话分支幂等标识；根会话和派生分支均唯一';
COMMENT ON COLUMN agent_conversation.parent_conversation_id IS '派生分支的父会话ID，根会话为空';
COMMENT ON COLUMN agent_conversation.fork_message_id IS '创建分支时选中的用户消息ID，历史消息不删除';
COMMENT ON COLUMN agent_conversation.context_cutoff_sequence IS '分支执行上下文只读取父链中小于等于此序号的消息';

COMMIT;
