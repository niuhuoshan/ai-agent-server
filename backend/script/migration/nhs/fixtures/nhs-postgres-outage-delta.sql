-- Rows created after the online preload and before the source write freeze.

INSERT INTO ai_agent_users VALUES
    (103, 'late_member', '停写前新增成员', 'user', 'ENC-LATE-KEY-DO-NOT-COPY', 'LATE-KEY-HASH', '$2b$late-password', 'outage delta user', 1, '2026-01-12 07:55:00', '2026-01-12 07:55:00');

INSERT INTO ai_models VALUES
    ('model-late', 'Late Chat', 'late-model', 'openai', 'llm', 'https://late.example.invalid/v1', 'sk-late-do-not-copy', 16384, 2048, false, false, true, NULL, true, '2026-01-12 07:56:00', '2026-01-12 07:56:00');

INSERT INTO ai_agents VALUES
    ('agent-late', 'late-researcher', 'Late Research Agent', 'Created immediately before the write freeze', NULL, '["research"]', 'GENERAL', false, 2, true, 'LOCAL', '{}', 'late_member', '2026-01-12 07:57:00', '2026-01-12 07:57:00');

INSERT INTO ai_agent_versions VALUES
    ('agent-late-v1', 'agent-late', 1, 'late-model', 0.1, NULL, NULL, 'You are a controlled research agent.', '[]', false, '[]', '{}', 'PUBLISHED', 'outage delta version', '2026-01-12 07:58:00');

INSERT INTO ai_agent_execution_history VALUES
    (1103, 'agent-late', 'trace-late-001', 'conversation-late-001', '103', 'late_member', 'Summarize cutover status', 'Cutover is ready', NULL, 20, 10, 30, 250, 'success', '1', 'late-model', 'model-late', NULL, '2026-01-12 07:59:00');
