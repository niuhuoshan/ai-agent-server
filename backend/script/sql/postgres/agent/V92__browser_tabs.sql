-- Persist the active tab snapshot so a refreshed workbench does not lose browser state.
BEGIN;
ALTER TABLE agent_browser_session ADD COLUMN IF NOT EXISTS active_tab_id VARCHAR(255);
ALTER TABLE agent_browser_session ADD COLUMN IF NOT EXISTS tab_state_json JSONB;
COMMENT ON COLUMN agent_browser_session.active_tab_id IS '当前浏览器标签页标识';
COMMENT ON COLUMN agent_browser_session.tab_state_json IS '受限的浏览器标签页状态快照';
COMMIT;
