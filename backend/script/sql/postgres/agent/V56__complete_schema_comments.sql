-- agent platform schema V56: complete Chinese comments added after the V11 baseline

BEGIN;

COMMENT ON TABLE agent_report_delivery_job IS '报表订阅投递作业表';

CREATE OR REPLACE FUNCTION pg_temp.agent_missing_column_comment(column_name TEXT)
RETURNS TEXT
LANGUAGE plpgsql
AS $$
DECLARE
    exact_comment TEXT;
    token TEXT;
    translated_token TEXT;
    result_comment TEXT := '';
    token_dictionary JSONB := '{
        "acceptance":"验收","accepted":"接受","access":"访问","account":"账号",
        "action":"操作","active":"活跃","agent":"智能体","ai":"AI","allowed":"允许",
        "answer":"回答","application":"应用","approval":"审批","assigned":"分配",
        "at":"时间","attempt":"尝试","attempts":"尝试次数","authorization":"授权",
        "available":"可用","bindings":"绑定","business":"业务","by":"人",
        "bytes":"字节数","call":"调用","capabilities":"能力","catchup":"补偿",
        "category":"分类","citation":"引用","code":"代码","columns":"字段列表",
        "command":"命令","completed":"完成","concurrency":"并发数","connection":"连接",
        "connector":"连接器","consumed":"消耗","content":"内容","context":"上下文",
        "conversation":"会话","count":"数量","cpu":"CPU","create":"创建","created":"创建",
        "credential":"凭证","criteria":"条件","cron":"定时表达式","data":"数据",
        "dataset":"数据集","del":"删除","deliverables":"交付物","delivery":"投递",
        "departments":"部门","depends":"依赖","description":"描述","detail":"详情",
        "dispatched":"分发","display":"显示","disposition":"处置方式","duration":"耗时",
        "effect":"效果","enabled":"是否启用","endpoint":"端点","enhance":"增强",
        "entity":"实体","error":"错误","event":"事件","exit":"退出","expires":"过期",
        "expr":"表达式","external":"外部","failed":"失败","failure":"失败",
        "feedback":"反馈","file":"文件","finished":"结束","fire":"触发","flag":"标志",
        "goals":"目标","hash":"哈希","heartbeat":"心跳","hosts":"主机列表","http":"HTTP",
        "id":"ID","idempotency":"幂等","included":"包含","info":"信息","input":"输入",
        "inserted":"新增","install":"安装","instance":"实例","interval":"间隔",
        "issue":"问题","job":"作业","json":"JSON","key":"标识","kind":"类型",
        "label":"标签","last":"最近","latency":"延迟","lease":"租约","length":"长度",
        "level":"级别","limit":"上限","local":"本地","manifest":"清单","mapped":"映射",
        "markdown":"Markdown","maturity":"成熟度","max":"最大","mb":"MB","memory":"内存",
        "message":"消息","metadata":"元数据","method":"方法","migration":"迁移",
        "millis":"毫秒数","mime":"MIME","minutes":"分钟数","misfire":"错过执行",
        "ms":"毫秒","name":"名称","names":"名称列表","network":"网络","next":"下次",
        "no":"序号","nonce":"随机数","notify":"通知","on":"目标","order":"顺序",
        "original":"原始","outcome":"结果","output":"输出","owner":"所有者",
        "payload":"载荷","phase":"阶段","physical":"物理","pids":"进程数",
        "policy":"策略","precheck":"预检","present":"是否存在","principal":"主体","priority":"优先级",
        "protocol":"协议","query":"查询","questions":"问题列表","reason":"原因",
        "recipient":"接收人","recommended":"是否推荐","ref":"引用","refined":"优化后",
        "registered":"注册","reply":"回复","report":"报表","request":"请求",
        "requested":"请求","required":"必需","resource":"资源","resources":"资源列表",
        "result":"结果","resume":"恢复","reused":"复用","revision":"修订",
        "revoked":"撤销","risk":"风险","role":"角色","rotated":"轮换","row":"行",
        "rows":"数据行","run":"运行","runner":"执行器","runtime":"运行时",
        "sample":"示例","schedule":"调度","scheduled":"计划","schema":"Schema",
        "scope":"范围","seconds":"秒数","secret":"密钥","server":"服务端",
        "service":"服务","session":"会话","severity":"严重性","sha256":"SHA256摘要",
        "size":"大小","skill":"技能","skipped":"跳过","snapshot":"快照","sort":"排序",
        "source":"来源","sql":"SQL","start":"开始","started":"开始","statement":"语句",
        "status":"状态","stderr":"标准错误","stdout":"标准输出","step":"步骤",
        "steps":"步骤列表","stop":"停止","storage":"存储","subscription":"订阅",
        "summary":"摘要","sync":"同步","synced":"同步","system":"系统","tags":"标签",
        "target":"目标","task":"任务","template":"模板","test":"测试","text":"文本",
        "time":"时间","timeout":"超时","timestamp":"时间戳","title":"标题","token":"令牌",
        "tool":"工具","trace":"链路","trigger":"触发器","truncated":"截断","turn":"轮次",
        "type":"类型","until":"截止","update":"更新","updated":"更新","usage":"用量",
        "use":"使用","used":"使用","user":"用户","verification":"校验","version":"版本",
        "wait":"等待","window":"窗口","worker":"工作节点","workspace":"工作空间"
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
        WHEN 'updated_at' THEN '最后更新时间'
        WHEN 'started_at' THEN '开始时间'
        WHEN 'finished_at' THEN '结束时间'
        WHEN 'completed_at' THEN '完成时间'
        WHEN 'expires_at' THEN '过期时间'
        WHEN 'del_flag' THEN '逻辑删除标志：0正常，1删除'
        WHEN 'status' THEN '业务状态'
        WHEN 'name' THEN '名称'
        WHEN 'description' THEN '描述'
        WHEN 'title' THEN '标题'
        ELSE NULL
    END;

    IF exact_comment IS NOT NULL THEN
        RETURN exact_comment;
    END IF;

    FOREACH token IN ARRAY string_to_array(column_name, '_') LOOP
        translated_token := token_dictionary ->> token;
        IF translated_token IS NULL THEN
            RAISE EXCEPTION '字段 % 包含未登记的注释词根：%', column_name, token;
        END IF;
        result_comment := result_comment || translated_token;
    END LOOP;

    RETURN result_comment;
END;
$$;

DO $$
DECLARE
    column_record RECORD;
    missing_count INTEGER;
    schema_name TEXT := current_schema();
BEGIN
    FOR column_record IN
        SELECT pc.relname AS table_name, pa.attname AS column_name
        FROM pg_class pc
        JOIN pg_namespace pn ON pn.oid = pc.relnamespace
        JOIN pg_attribute pa ON pa.attrelid = pc.oid
            AND pa.attnum > 0
            AND NOT pa.attisdropped
        LEFT JOIN pg_description pd ON pd.objoid = pc.oid AND pd.objsubid = pa.attnum
        WHERE pn.nspname = schema_name
          AND pc.relkind = 'r'
          AND (
              pc.relname LIKE 'agent\_%' ESCAPE '\'
              OR pc.relname LIKE 'iam\_%' ESCAPE '\'
              OR pc.relname = 'task_access_rule'
          )
          AND (pd.description IS NULL OR pd.description !~ '[一-龥]')
        ORDER BY pc.relname, pa.attnum
    LOOP
        EXECUTE format(
            'COMMENT ON COLUMN %I.%I.%I IS %L',
            schema_name,
            column_record.table_name,
            column_record.column_name,
            pg_temp.agent_missing_column_comment(column_record.column_name)
        );
    END LOOP;

    SELECT count(*)
    INTO missing_count
    FROM pg_class pc
    JOIN pg_namespace pn ON pn.oid = pc.relnamespace
    LEFT JOIN pg_description pd ON pd.objoid = pc.oid AND pd.objsubid = 0
    WHERE pn.nspname = schema_name
      AND pc.relkind = 'r'
      AND (
          pc.relname LIKE 'agent\_%' ESCAPE '\'
          OR pc.relname LIKE 'iam\_%' ESCAPE '\'
          OR pc.relname = 'task_access_rule'
      )
      AND (pd.description IS NULL OR pd.description !~ '[一-龥]');

    IF missing_count > 0 THEN
        RAISE EXCEPTION '仍有 % 张平台表缺少中文注释', missing_count;
    END IF;

    SELECT count(*)
    INTO missing_count
    FROM pg_class pc
    JOIN pg_namespace pn ON pn.oid = pc.relnamespace
    JOIN pg_attribute pa ON pa.attrelid = pc.oid
        AND pa.attnum > 0
        AND NOT pa.attisdropped
    LEFT JOIN pg_description pd ON pd.objoid = pc.oid AND pd.objsubid = pa.attnum
    WHERE pn.nspname = schema_name
      AND pc.relkind = 'r'
      AND (
          pc.relname LIKE 'agent\_%' ESCAPE '\'
          OR pc.relname LIKE 'iam\_%' ESCAPE '\'
          OR pc.relname = 'task_access_rule'
      )
      AND (pd.description IS NULL OR pd.description !~ '[一-龥]');

    IF missing_count > 0 THEN
        RAISE EXCEPTION '仍有 % 个平台字段缺少中文注释', missing_count;
    END IF;
END;
$$;

COMMIT;
