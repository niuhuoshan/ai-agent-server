-- Durable browser human handoff state. AI operations are blocked until the owner returns control.
BEGIN;

ALTER TABLE agent_browser_session
    ADD COLUMN IF NOT EXISTS handoff_status VARCHAR(32) NOT NULL DEFAULT 'none',
    ADD COLUMN IF NOT EXISTS handoff_reason VARCHAR(255),
    ADD COLUMN IF NOT EXISTS handoff_user_id BIGINT,
    ADD COLUMN IF NOT EXISTS handoff_requested_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS handoff_started_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS handoff_returned_at TIMESTAMP;

ALTER TABLE agent_browser_session
    DROP CONSTRAINT IF EXISTS ck_agent_browser_session_handoff_status;
ALTER TABLE agent_browser_session
    ADD CONSTRAINT ck_agent_browser_session_handoff_status
    CHECK (handoff_status IN ('none', 'requested', 'human_control', 'returned', 'expired'));

CREATE INDEX IF NOT EXISTS idx_agent_browser_session_handoff
    ON agent_browser_session (owner_id, handoff_status, updated_at DESC);

COMMENT ON COLUMN agent_browser_session.handoff_status IS '浏览器控制权：none/requested/human_control/returned/expired';
COMMENT ON COLUMN agent_browser_session.handoff_reason IS '人工接管原因，不得包含 Cookie、密码或 Token';
COMMENT ON COLUMN agent_browser_session.handoff_user_id IS '实际接管浏览器的用户';
COMMENT ON COLUMN agent_browser_session.handoff_requested_at IS '请求人工接管时间';
COMMENT ON COLUMN agent_browser_session.handoff_started_at IS '用户开始接管时间';
COMMENT ON COLUMN agent_browser_session.handoff_returned_at IS '用户明确交还 AI 时间';

COMMIT;
