-- agent platform schema V9: uniqueness and query-path indexes
-- High-volume tables remain non-partitioned for the Lite baseline. Convert
-- execution_event and audit_event to monthly partitions only after retention
-- automation and a tested online migration procedure are available.

BEGIN;

-- Model, agent, connector and skill definitions.
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_model_key_active
    ON agent_model (model_key) WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_model_provider_status
    ON agent_model (provider_type, model_type, status);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_definition_key_active
    ON agent_definition (agent_key) WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_definition_owner_status
    ON agent_definition (owner_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_definition_version_status
    ON agent_definition_version (agent_id, status, created_at);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_connector_key_active
    ON agent_connector (connector_key) WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_connector_provider_status
    ON agent_connector (provider_type, status);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_tool_key_version_active
    ON agent_tool (tool_key, version_no) WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_tool_connector_status
    ON agent_tool (connector_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_tool_risk_status
    ON agent_tool (risk_level, status);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_skill_key_active
    ON agent_skill (skill_key) WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_skill_scope_status
    ON agent_skill (scope_type, scope_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_version_tool_resource
    ON agent_agent_version_tool (resource_id);
CREATE INDEX IF NOT EXISTS idx_agent_version_skill_resource
    ON agent_agent_version_skill (resource_id);
CREATE INDEX IF NOT EXISTS idx_agent_version_knowledge_resource
    ON agent_agent_version_knowledge (resource_id);

-- Project, task and workflow control plane.
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_project_key_active
    ON agent_project (project_key) WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_project_owner_status
    ON agent_project (owner_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_project_member_active
    ON agent_project_member (project_id, user_id) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS idx_agent_project_member_user
    ON agent_project_member (user_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_project_rule_lookup
    ON agent_project_rule (project_id, rule_type, status);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_task_key_active
    ON agent_task (task_key) WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_task_project_status
    ON agent_task (project_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_task_owner_status
    ON agent_task (owner_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_task_queue
    ON agent_task (status, start_at, queue_priority DESC);
CREATE INDEX IF NOT EXISTS idx_agent_task_visibility_status
    ON agent_task (visibility, status, create_time DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_task_source_conversation_active
    ON agent_task (source_conversation_id)
    WHERE source_conversation_id IS NOT NULL AND del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_task_version_created
    ON agent_task_version (task_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_task_participant_active
    ON agent_task_participant (task_id, user_id, participant_type) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS idx_agent_task_participant_user
    ON agent_task_participant (user_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_task_resource_target
    ON agent_task_resource (resource_type, resource_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_workflow_key_active
    ON agent_workflow_definition (workflow_key) WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_workflow_status
    ON agent_workflow_definition (status, owner_id);
CREATE INDEX IF NOT EXISTS idx_agent_workflow_version_status
    ON agent_workflow_version (workflow_id, status, created_at DESC);

-- Conversation and execution query paths.
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_conversation_session
    ON agent_conversation (session_key);
CREATE INDEX IF NOT EXISTS idx_agent_conversation_user_recent
    ON agent_conversation (user_id, status, last_message_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_conversation_task
    ON agent_conversation (task_id) WHERE task_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_message_trace
    ON agent_conversation_message (trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_message_conversation_created
    ON agent_conversation_message (conversation_id, created_at);

CREATE INDEX IF NOT EXISTS idx_agent_task_run_lease
    ON agent_task_run (status, lease_until);
CREATE INDEX IF NOT EXISTS idx_agent_task_run_task_created
    ON agent_task_run (task_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_run_step_status
    ON agent_run_step (run_id, status, sequence_no);
CREATE INDEX IF NOT EXISTS idx_agent_run_step_key
    ON agent_run_step (step_key);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_execution_event_run_cursor
    ON agent_execution_event (run_id, cursor) WHERE run_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_execution_event_conv_cursor
    ON agent_execution_event (conversation_id, cursor)
    WHERE run_id IS NULL AND conversation_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_execution_event_trace
    ON agent_execution_event (trace_id, created_at);
CREATE INDEX IF NOT EXISTS idx_agent_execution_event_run_created
    ON agent_execution_event (run_id, created_at) WHERE run_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_execution_event_created
    ON agent_execution_event (created_at);
CREATE INDEX IF NOT EXISTS idx_agent_checkpoint_run_step
    ON agent_run_checkpoint (run_id, step_id);

-- Artifact, acceptance and approval.
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_artifact_task_version
    ON agent_artifact (task_id, name, version_no) WHERE task_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_artifact_run_type
    ON agent_artifact (run_id, artifact_type) WHERE run_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_artifact_hash
    ON agent_artifact (content_hash) WHERE content_hash IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_artifact_task_visibility
    ON agent_artifact (task_id, visibility, status) WHERE task_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_acceptance_task_created
    ON agent_acceptance_record (task_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_acceptance_run_result
    ON agent_acceptance_record (run_id, result);
CREATE INDEX IF NOT EXISTS idx_agent_approval_expiry
    ON agent_approval_request (status, expires_at);
CREATE INDEX IF NOT EXISTS idx_agent_approval_run_status
    ON agent_approval_request (run_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_approval_reviewer
    ON agent_approval_request (reviewer_id, status) WHERE reviewer_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_approval_decision_token
    ON agent_approval_request (decision_token_hash) WHERE decision_token_hash IS NOT NULL;

-- Knowledge and memory. Vector indexes are created per fixed dimension later.
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_knowledge_key_active
    ON agent_knowledge_base (knowledge_key) WHERE del_flag = '0';
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_knowledge_external_active
    ON agent_knowledge_base (provider_type, external_id)
    WHERE del_flag = '0' AND external_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_knowledge_owner_status
    ON agent_knowledge_base (owner_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_knowledge_document_active
    ON agent_knowledge_document (knowledge_base_id, document_key) WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_knowledge_document_hash
    ON agent_knowledge_document (content_hash) WHERE content_hash IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_knowledge_document_status
    ON agent_knowledge_document (knowledge_base_id, status, updated_at);
CREATE INDEX IF NOT EXISTS idx_agent_knowledge_chunk_lookup
    ON agent_knowledge_chunk (knowledge_base_id, status, document_id, chunk_no);
CREATE INDEX IF NOT EXISTS idx_agent_memory_scope
    ON agent_memory (scope_type, scope_id, review_status);
CREATE INDEX IF NOT EXISTS idx_agent_memory_source
    ON agent_memory (source_type, source_id) WHERE source_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_memory_expiry
    ON agent_memory (expires_at) WHERE expires_at IS NOT NULL;

-- Data catalog, ChatBI and reports.
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_data_source_key_active
    ON agent_data_source (source_key) WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_data_source_status
    ON agent_data_source (db_type, status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_data_dataset_key_active
    ON agent_data_dataset (dataset_key) WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_data_dataset_source
    ON agent_data_dataset (data_source_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_data_table_key_active
    ON agent_data_table (dataset_id, table_key) WHERE del_flag = '0';
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_data_table_physical_active
    ON agent_data_table (dataset_id, physical_name) WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_data_table_status
    ON agent_data_table (dataset_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_data_column_status
    ON agent_data_column (table_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_data_metric_status
    ON agent_data_metric (dataset_id, status, metric_key);
CREATE INDEX IF NOT EXISTS idx_agent_data_relation_lookup
    ON agent_data_relation (dataset_id, status, source_table_id, target_table_id);
CREATE INDEX IF NOT EXISTS idx_agent_data_query_run
    ON agent_data_query (run_id) WHERE run_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_data_query_dataset_created
    ON agent_data_query (dataset_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_data_query_hash
    ON agent_data_query (sql_hash) WHERE sql_hash IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_report_key_active
    ON agent_report (report_key) WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_report_dataset_status
    ON agent_report (dataset_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_report_run_recent
    ON agent_report_run (report_id, created_at DESC, status);
CREATE INDEX IF NOT EXISTS idx_agent_report_subscription_next
    ON agent_report_subscription (status, next_run_at);

-- Machine identity and automation.
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_service_account_key_active
    ON agent_service_account (account_key) WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_service_account_owner
    ON agent_service_account (owner_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_api_application_key_active
    ON agent_api_application (app_key) WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_api_application_owner
    ON agent_api_application (owner_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_api_credential_account
    ON agent_api_credential (service_account_id, revoked_at, expires_at);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_automation_trigger_key_active
    ON agent_automation_trigger (trigger_key) WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_agent_automation_trigger_next
    ON agent_automation_trigger (status, next_run_at);
CREATE INDEX IF NOT EXISTS idx_agent_job_queue_claim
    ON agent_job_queue (status, available_at, priority DESC);
CREATE INDEX IF NOT EXISTS idx_agent_job_queue_lease
    ON agent_job_queue (worker_id, lease_until) WHERE status = 'running';
CREATE INDEX IF NOT EXISTS idx_agent_notification_user_unread
    ON agent_notification (user_id, read_at, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_notification_resource
    ON agent_notification (resource_type, resource_id) WHERE resource_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_outbox_publish
    ON agent_outbox_event (status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_agent_outbox_aggregate
    ON agent_outbox_event (aggregate_type, aggregate_id, created_at);

-- Audit and migration traceability.
CREATE INDEX IF NOT EXISTS idx_agent_audit_created
    ON agent_audit_event (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_audit_trace
    ON agent_audit_event (trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_audit_actor
    ON agent_audit_event (actor_type, actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_audit_resource
    ON agent_audit_event (resource_type, resource_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_audit_task_run
    ON agent_audit_event (task_id, run_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_migration_run_source
    ON agent_migration_run (source_system, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_migration_run_status
    ON agent_migration_run (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_migration_mapping_source
    ON agent_migration_mapping (source_type, source_id);
CREATE INDEX IF NOT EXISTS idx_agent_migration_mapping_target
    ON agent_migration_mapping (target_type, target_id) WHERE target_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_legacy_execution_trace
    ON agent_legacy_execution_archive (source_system, source_trace_id)
    WHERE source_trace_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_legacy_execution_id
    ON agent_legacy_execution_archive (source_system, source_execution_id)
    WHERE source_execution_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_legacy_execution_user
    ON agent_legacy_execution_archive (source_user_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_legacy_execution_conversation
    ON agent_legacy_execution_archive (source_conversation_id);

COMMIT;
