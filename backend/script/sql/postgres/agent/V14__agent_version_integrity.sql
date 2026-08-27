-- Agent definition/version lifecycle invariants and immutable published bindings.

BEGIN;

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_definition_one_published_version
    ON agent_definition_version (agent_id)
    WHERE status = 'published';

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_definition_one_active_default
    ON agent_definition (is_default)
    WHERE is_default = TRUE AND status = 'active' AND del_flag = '0';

CREATE OR REPLACE FUNCTION agent_guard_definition_version_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('published', 'archived') THEN
            RAISE EXCEPTION 'published or archived agent versions are immutable';
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.status = 'archived' THEN
        RAISE EXCEPTION 'archived agent versions are immutable';
    END IF;
    IF OLD.status = 'published' THEN
        IF NEW.status <> 'archived'
           OR (to_jsonb(NEW) - 'status') IS DISTINCT FROM (to_jsonb(OLD) - 'status') THEN
            RAISE EXCEPTION 'published agent versions may only transition unchanged to archived';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_agent_definition_version_immutable ON agent_definition_version;
CREATE TRIGGER trg_agent_definition_version_immutable
BEFORE UPDATE OR DELETE ON agent_definition_version
FOR EACH ROW EXECUTE FUNCTION agent_guard_definition_version_mutation();

CREATE OR REPLACE FUNCTION agent_guard_version_binding_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    bound_version_id BIGINT;
    bound_status VARCHAR(16);
BEGIN
    bound_version_id := CASE WHEN TG_OP = 'DELETE'
        THEN OLD.agent_version_id ELSE NEW.agent_version_id END;
    SELECT status INTO bound_status
    FROM agent_definition_version
    WHERE id = bound_version_id;

    IF bound_status IS NULL THEN
        RAISE EXCEPTION 'agent version does not exist';
    END IF;
    IF bound_status <> 'draft' THEN
        RAISE EXCEPTION 'published or archived agent version bindings are immutable';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

DROP TRIGGER IF EXISTS trg_agent_version_tool_immutable ON agent_agent_version_tool;
CREATE TRIGGER trg_agent_version_tool_immutable
BEFORE INSERT OR UPDATE OR DELETE ON agent_agent_version_tool
FOR EACH ROW EXECUTE FUNCTION agent_guard_version_binding_mutation();

DROP TRIGGER IF EXISTS trg_agent_version_skill_immutable ON agent_agent_version_skill;
CREATE TRIGGER trg_agent_version_skill_immutable
BEFORE INSERT OR UPDATE OR DELETE ON agent_agent_version_skill
FOR EACH ROW EXECUTE FUNCTION agent_guard_version_binding_mutation();

DROP TRIGGER IF EXISTS trg_agent_version_knowledge_immutable ON agent_agent_version_knowledge;
CREATE TRIGGER trg_agent_version_knowledge_immutable
BEFORE INSERT OR UPDATE OR DELETE ON agent_agent_version_knowledge
FOR EACH ROW EXECUTE FUNCTION agent_guard_version_binding_mutation();

COMMIT;
