-- agent platform schema V5: knowledge and scoped memory

BEGIN;

CREATE TABLE IF NOT EXISTS agent_knowledge_base (
    id                  BIGINT PRIMARY KEY,
    knowledge_key       VARCHAR(128) NOT NULL,
    name                VARCHAR(255) NOT NULL,
    description         TEXT,
    provider_type       VARCHAR(24) NOT NULL DEFAULT 'postgres_pgvector',
    connector_id        BIGINT,
    external_id         VARCHAR(128),
    visibility          VARCHAR(24) NOT NULL DEFAULT 'private',
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    config_json         JSONB,
    owner_id            BIGINT,
    create_by           BIGINT,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT,
    update_time         TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    extra_json          JSONB,
    CONSTRAINT ck_agent_knowledge_provider CHECK (provider_type IN ('postgres_pgvector', 'ragflow', 'external')),
    CONSTRAINT ck_agent_knowledge_visibility CHECK (visibility IN ('private', 'enterprise_shared', 'restricted')),
    CONSTRAINT ck_agent_knowledge_status CHECK (status IN ('active', 'syncing', 'disabled', 'missing'))
);

CREATE TABLE IF NOT EXISTS agent_knowledge_document (
    id                  BIGINT PRIMARY KEY,
    knowledge_base_id   BIGINT NOT NULL,
    document_key        VARCHAR(128) NOT NULL,
    name                VARCHAR(255) NOT NULL,
    artifact_id         BIGINT,
    external_id         VARCHAR(128),
    content_hash        CHAR(64),
    parser_type         VARCHAR(32),
    status              VARCHAR(16) NOT NULL DEFAULT 'pending',
    chunk_count         INTEGER NOT NULL DEFAULT 0,
    metadata_json       JSONB,
    error_summary       TEXT,
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP,
    del_flag            CHAR(1) NOT NULL DEFAULT '0',
    CONSTRAINT ck_agent_knowledge_document_status CHECK (status IN ('pending', 'processing', 'ready', 'failed', 'deleted'))
);

CREATE TABLE IF NOT EXISTS agent_knowledge_chunk (
    id                  BIGINT PRIMARY KEY,
    knowledge_base_id   BIGINT NOT NULL,
    document_id         BIGINT NOT NULL,
    chunk_no            INTEGER NOT NULL,
    content             TEXT NOT NULL,
    content_hash        CHAR(64) NOT NULL,
    token_count         INTEGER NOT NULL DEFAULT 0,
    embedding_model_id  BIGINT,
    embedding_dimension INTEGER,
    embedding           VECTOR,
    metadata_json       JSONB,
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_knowledge_chunk UNIQUE (document_id, chunk_no),
    CONSTRAINT ck_agent_knowledge_chunk_status CHECK (status IN ('active', 'deleted')),
    CONSTRAINT ck_agent_knowledge_chunk_dimension CHECK (embedding_dimension IS NULL OR embedding_dimension > 0),
    CONSTRAINT ck_agent_knowledge_chunk_vector CHECK ((embedding IS NULL) = (embedding_dimension IS NULL)),
    CONSTRAINT ck_agent_knowledge_chunk_model CHECK (embedding IS NULL OR embedding_model_id IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS agent_memory (
    id                  BIGINT PRIMARY KEY,
    scope_type          VARCHAR(16) NOT NULL,
    scope_id            BIGINT NOT NULL,
    memory_type         VARCHAR(24) NOT NULL,
    content             TEXT NOT NULL,
    source_type         VARCHAR(24) NOT NULL,
    source_id           BIGINT,
    confidence          NUMERIC(5,4),
    sensitive_level     VARCHAR(12) NOT NULL DEFAULT 'internal',
    review_status       VARCHAR(16) NOT NULL DEFAULT 'pending',
    embedding_model_id  BIGINT,
    embedding_dimension INTEGER,
    embedding           VECTOR,
    expires_at          TIMESTAMP,
    created_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP,
    CONSTRAINT ck_agent_memory_scope CHECK (scope_type IN ('user', 'project', 'task', 'conversation', 'agent')),
    CONSTRAINT ck_agent_memory_type CHECK (memory_type IN ('summary', 'preference', 'fact', 'feedback', 'candidate')),
    CONSTRAINT ck_agent_memory_source CHECK (source_type IN ('conversation', 'task', 'artifact', 'manual')),
    CONSTRAINT ck_agent_memory_confidence CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    CONSTRAINT ck_agent_memory_sensitive CHECK (sensitive_level IN ('public', 'internal', 'sensitive', 'secret')),
    CONSTRAINT ck_agent_memory_review CHECK (review_status IN ('pending', 'approved', 'rejected', 'expired')),
    CONSTRAINT ck_agent_memory_dimension CHECK (embedding_dimension IS NULL OR embedding_dimension > 0),
    CONSTRAINT ck_agent_memory_vector CHECK ((embedding IS NULL) = (embedding_dimension IS NULL)),
    CONSTRAINT ck_agent_memory_model CHECK (embedding IS NULL OR embedding_model_id IS NOT NULL)
);

COMMIT;
