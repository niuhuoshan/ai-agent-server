-- agent platform schema V11: Chinese comments for every platform table and column

BEGIN;

CREATE TEMP TABLE agent_schema_table_comment (
    table_name          TEXT PRIMARY KEY,
    table_comment       TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO agent_schema_table_comment (table_name, table_comment) VALUES
    ('agent_model', '模型配置表'),
    ('agent_definition', '智能体定义表'),
    ('agent_definition_version', '智能体版本表'),
    ('agent_connector', '连接器配置表'),
    ('agent_tool', '智能体工具表'),
    ('agent_skill', '智能体技能表'),
    ('agent_skill_version', '智能体技能版本表'),
    ('agent_agent_version_tool', '智能体版本与工具关联表'),
    ('agent_agent_version_skill', '智能体版本与技能关联表'),
    ('agent_agent_version_knowledge', '智能体版本与知识库关联表'),
    ('agent_project', '项目表'),
    ('agent_project_member', '项目成员表'),
    ('agent_project_rule', '项目规则版本表'),
    ('agent_task', '正式任务表'),
    ('agent_task_version', '任务不可变版本表'),
    ('agent_task_participant', '任务参与人表'),
    ('agent_task_resource', '任务资源授权快照表'),
    ('agent_workflow_definition', '工作流定义表'),
    ('agent_workflow_version', '工作流版本表'),
    ('agent_conversation', '个人智能体会话表'),
    ('agent_conversation_message', '会话消息表'),
    ('agent_task_run', '任务运行表'),
    ('agent_run_step', '任务运行步骤表'),
    ('agent_execution_event', '统一执行事件表'),
    ('agent_run_checkpoint', '任务运行检查点表'),
    ('agent_artifact', '任务文件与制品表'),
    ('agent_acceptance_record', '任务验收记录表'),
    ('agent_approval_request', '高风险工具审批表'),
    ('agent_knowledge_base', '知识库表'),
    ('agent_knowledge_document', '知识库文档表'),
    ('agent_knowledge_chunk', '知识文档切片表'),
    ('agent_memory', '智能体记忆表'),
    ('agent_data_source', '数据源配置表'),
    ('agent_data_dataset', '数据集表'),
    ('agent_data_table', '数据表元数据表'),
    ('agent_data_column', '数据字段元数据表'),
    ('agent_data_metric', '数据指标定义表'),
    ('agent_data_relation', '数据表关系提示表'),
    ('agent_data_query', '数据查询执行记录表'),
    ('agent_report', '数据报表定义表'),
    ('agent_report_run', '数据报表运行记录表'),
    ('agent_report_subscription', '数据报表订阅表'),
    ('agent_service_account', '服务账号表'),
    ('agent_api_application', '开放接口应用表'),
    ('agent_api_credential', '开放接口凭证表'),
    ('agent_automation_trigger', '自动化触发器表'),
    ('agent_job_queue', '平台后台作业队列表'),
    ('agent_notification', '平台通知收件箱表'),
    ('agent_outbox_event', '业务事件发件箱表'),
    ('agent_audit_event', '平台审计事件表'),
    ('agent_migration_run', '数据迁移批次表'),
    ('agent_migration_mapping', '数据迁移主键映射表'),
    ('agent_legacy_execution_archive', '旧系统执行记录归档表'),
    ('iam_permission_profile', '用户基础权限包版本表'),
    ('iam_permission_profile_entry', '权限包授权条目表'),
    ('iam_user_permission_binding', '用户基础权限绑定表'),
    ('iam_user_permission_override', '用户权限覆盖项表'),
    ('iam_user_agent_policy', '用户与智能体专项权限策略表'),
    ('iam_temporary_grant', '用户临时授权表'),
    ('iam_permission_copy_record', '参考用户权限复制记录表'),
    ('task_access_rule', '敏感任务与制品访问规则表');

CREATE OR REPLACE FUNCTION pg_temp.agent_column_comment(column_name TEXT)
RETURNS TEXT
LANGUAGE plpgsql
AS $$
DECLARE
    exact_comment TEXT;
    token TEXT;
    translated_token TEXT;
    result_comment TEXT := '';
    token_dictionary JSONB := '{
        "acceptance":"验收","account":"账号","action":"动作","actor":"操作主体","address":"地址",
        "after":"变更后","agent":"智能体","aggregate":"聚合对象","app":"应用","application":"应用",
        "approval":"审批","archived":"归档","artifact":"制品","at":"时间","attempt":"尝试",
        "attempts":"尝试次数","authorization":"授权","available":"可执行","avatar":"头像","background":"背景",
        "base":"基础","before":"变更前","binding":"绑定","biz":"业务","budget":"预算","by":"人",
        "bytes":"字节数","calculation":"计算","callback":"回调","cancel":"取消","capability":"能力",
        "category":"业务类别","check":"检查","checkpoint":"检查点","checksum":"校验和","chunk":"切片",
        "ciphertext":"密文","code":"代码","column":"字段","comment":"意见","completion":"输出",
        "condition":"条件","confidence":"置信度","config":"配置","connector":"连接器","content":"内容",
        "context":"上下文","conversation":"会话","copy":"复制","count":"数量","create":"创建",
        "created":"创建","credential":"凭证","cron":"定时表达式","current":"当前","cursor":"事件游标",
        "data":"数据","database":"数据库","dataset":"数据集","db":"数据库","decided":"决策",
        "decision":"决策","default":"默认","del":"删除","description":"描述","diff":"差异",
        "dimension":"维度","display":"显示","document":"文档","effect":"授权效果","effective":"生效",
        "embedding":"向量","enable":"启用","endpoint":"端点","engine":"运行引擎","enum":"枚举",
        "error":"错误","event":"事件","excluded":"排除项","executed":"已执行","execution":"执行",
        "expires":"过期","expr":"表达式","external":"外部","extra":"扩展","filter":"过滤",
        "finished":"结束","flag":"标志","grant":"授权","graph":"流程图","hash":"哈希",
        "id":"ID","idempotency":"幂等","ids":"ID列表","impact":"影响","importance":"重要性",
        "input":"输入","ip":"IP","is":"是否","job":"作业","join":"关联","joined":"加入",
        "json":"JSON","key":"业务标识","knowledge":"知识库","last":"最近","latest":"最新",
        "lease":"租约","level":"级别","lifecycle":"生命周期","logic":"逻辑","manifest":"清单",
        "max":"最大","member":"成员","memory":"记忆","message":"消息","metadata":"元数据",
        "metric":"指标","migration":"迁移","mime":"MIME","mode":"模式","model":"模型",
        "name":"名称","next":"下次","no":"序号","notification":"通知","notify":"通知",
        "objective":"任务目标","occurred":"发生","operator":"操作人","orchestration":"编排","order":"排序","output":"输出",
        "owner":"负责人","parameter":"参数","params":"参数","parent":"父级","parser":"解析器",
        "participant":"参与人","payload":"载荷","permission":"权限","physical":"物理","plan":"计划",
        "policy":"策略","prefix":"前缀","primary":"主键","principal":"主体","priority":"优先级","profile":"权限包",
        "project":"项目","prompt":"提示词","provider":"提供方","published":"发布","query":"查询",
        "queue":"队列","read":"读取","readonly":"只读","reason":"原因","reasoning":"推理",
        "ref":"引用","refs":"引用列表","report":"报表","request":"请求","requested":"发起请求",
        "required":"是否必需","requirements":"运行要求","resolved":"解析后","resource":"资源",
        "result":"结果","retry":"重试","review":"审核","reviewer":"审核人","revoked":"撤销",
        "rework":"返工","risk":"风险","role":"角色","routing":"路由","row":"数据行",
        "rule":"规则","run":"运行","runtime":"运行时","sample":"样例","schema":"结构定义",
        "scope":"范围","secret":"密钥","sensitive":"敏感","seq":"顺序","sequence":"顺序",
        "service":"服务","session":"会话","size":"大小","skill":"技能","snapshot":"快照",
        "sort":"排序","source":"来源","sql":"SQL","start":"计划开始","started":"开始",
        "state":"状态数据","status":"状态","step":"步骤","storage":"存储","subject":"授权主体",
        "summary":"摘要","synonyms":"同义词","synthesis":"汇总","system":"系统","table":"数据表",
        "tags":"标签","target":"目标","task":"任务","template":"模板","text":"文本",
        "time":"时间","timezone":"时区","title":"标题","token":"Token","tokens":"Token数",
        "tool":"工具","total":"总计","trace":"链路追踪","trigger":"触发器","type":"类型",
        "unit":"单位","until":"截止时间","update":"更新","updated":"更新","urgency":"紧急性",
        "url":"地址","usage":"用量","used":"使用","user":"用户","values":"取值",
        "version":"版本","visibility":"可见性","wait":"等待","welcome":"欢迎页","worker":"执行节点",
        "workflow":"工作流","workspace":"工作空间"
    }'::JSONB;
BEGIN
    exact_comment := CASE column_name
        WHEN 'id' THEN '主键ID'
        WHEN 'create_by' THEN '创建人用户ID'
        WHEN 'create_time' THEN '创建时间'
        WHEN 'update_by' THEN '最后更新人用户ID'
        WHEN 'update_time' THEN '最后更新时间'
        WHEN 'created_by' THEN '创建人用户ID'
        WHEN 'created_at' THEN '创建时间'
        WHEN 'occurred_at' THEN '事件发生时间'
        WHEN 'updated_at' THEN '最后更新时间'
        WHEN 'del_flag' THEN '逻辑删除标志：0正常，1删除'
        WHEN 'extra_json' THEN '预留扩展信息JSON'
        WHEN 'metadata_json' THEN '业务元数据JSON'
        WHEN 'config_json' THEN '业务配置JSON'
        WHEN 'is_system' THEN '是否系统内置'
        WHEN 'is_default' THEN '是否默认项'
        WHEN 'is_primary' THEN '是否主键字段'
        WHEN 'is_sensitive' THEN '是否敏感字段'
        WHEN 'readonly' THEN '是否仅允许只读访问'
        WHEN 'requested_by' THEN '请求发起人ID'
        WHEN 'reviewer_id' THEN '审核人用户ID'
        WHEN 'owner_id' THEN '负责人用户ID'
        WHEN 'user_id' THEN '用户ID'
        WHEN 'status' THEN '业务状态'
        WHEN 'name' THEN '名称'
        WHEN 'description' THEN '描述'
        WHEN 'content' THEN '正文内容'
        WHEN 'summary' THEN '摘要'
        WHEN 'visibility' THEN '数据可见性'
        ELSE NULL
    END;

    IF exact_comment IS NOT NULL THEN
        RETURN exact_comment;
    END IF;

    FOREACH token IN ARRAY string_to_array(column_name, '_') LOOP
        translated_token := token_dictionary ->> token;
        IF translated_token IS NULL THEN
            -- Later migrations can add new English tokens before this baseline is replayed.
            -- Preserve a readable Chinese alias instead of making a recovery replay fail.
            translated_token := '字段' || token;
        END IF;
        result_comment := result_comment || translated_token;
    END LOOP;

    RETURN result_comment;
END;
$$;

DO $$
DECLARE
    table_record RECORD;
    column_record RECORD;
    schema_name TEXT := current_schema();
    missing_count INTEGER;
BEGIN
    FOR table_record IN
        SELECT table_name, table_comment
        FROM agent_schema_table_comment
        ORDER BY table_name
    LOOP
        IF to_regclass(format('%I.%I', schema_name, table_record.table_name)) IS NULL THEN
            RAISE EXCEPTION '无法为不存在的平台表添加注释：%', table_record.table_name;
        END IF;

        EXECUTE format(
            'COMMENT ON TABLE %I.%I IS %L',
            schema_name,
            table_record.table_name,
            table_record.table_comment
        );
    END LOOP;

    FOR column_record IN
        SELECT c.table_name, c.column_name
        FROM information_schema.columns c
        JOIN agent_schema_table_comment t ON t.table_name = c.table_name
        WHERE c.table_schema = schema_name
        ORDER BY c.table_name, c.ordinal_position
    LOOP
        EXECUTE format(
            'COMMENT ON COLUMN %I.%I.%I IS %L',
            schema_name,
            column_record.table_name,
            column_record.column_name,
            pg_temp.agent_column_comment(column_record.column_name)
        );
    END LOOP;

    SELECT count(*)
    INTO missing_count
    FROM agent_schema_table_comment t
    JOIN pg_class pc ON pc.relname = t.table_name AND pc.relkind = 'r'
    JOIN pg_namespace pn ON pn.oid = pc.relnamespace AND pn.nspname = schema_name
    LEFT JOIN pg_description pd ON pd.objoid = pc.oid AND pd.objsubid = 0
    WHERE pd.description IS NULL OR pd.description !~ '[一-龥]';

    IF missing_count > 0 THEN
        RAISE EXCEPTION '仍有 % 张平台表缺少中文注释', missing_count;
    END IF;

    SELECT count(*)
    INTO missing_count
    FROM agent_schema_table_comment t
    JOIN pg_class pc ON pc.relname = t.table_name AND pc.relkind = 'r'
    JOIN pg_namespace pn ON pn.oid = pc.relnamespace AND pn.nspname = schema_name
    JOIN pg_attribute pa ON pa.attrelid = pc.oid AND pa.attnum > 0 AND NOT pa.attisdropped
    LEFT JOIN pg_description pd ON pd.objoid = pc.oid AND pd.objsubid = pa.attnum
    WHERE pd.description IS NULL OR pd.description !~ '[一-龥]';

    IF missing_count > 0 THEN
        RAISE EXCEPTION '仍有 % 个平台字段缺少中文注释', missing_count;
    END IF;
END;
$$;

COMMIT;
