package group.aitools.nhs.platform.integration;

import group.aitools.nhs.runtime.spi.RuntimeEvent;
import group.aitools.nhs.runtime.spi.RuntimeEventStatus;
import group.aitools.nhs.runtime.spi.RuntimeEventType;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeSensitiveLevel;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.domain.PermissionSource;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.management.mapper.PermissionAdministrationMapper;
import group.aitools.nhs.platform.iam.management.service.PermissionAdministrationService;
import group.aitools.nhs.platform.iam.management.web.CopyPermissionRequest;
import group.aitools.nhs.platform.iam.management.web.CreatePermissionProfileRequest;
import group.aitools.nhs.platform.iam.management.web.CreateTemporaryGrantRequest;
import group.aitools.nhs.platform.iam.management.web.PatchPermissionOverridesRequest;
import group.aitools.nhs.platform.iam.management.web.PermissionOverrideMutation;
import group.aitools.nhs.platform.iam.management.web.PermissionRuleInput;
import group.aitools.nhs.platform.iam.management.web.PutPermissionBindingRequest;
import group.aitools.nhs.platform.iam.persistence.mapper.PermissionRuleQueryMapper;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.iam.service.impl.DatabasePermissionSnapshotResolver;
import group.aitools.nhs.platform.iam.service.impl.DefaultAuthorizationService;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.execution.domain.AgentExecutionEvent;
import group.aitools.nhs.platform.execution.persistence.mapper.AgentExecutionEventMapper;
import group.aitools.nhs.platform.execution.service.ExecutionEventPersistenceService;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import group.aitools.nhs.platform.identity.mapper.MachineIdentityMapper;
import group.aitools.nhs.platform.identity.service.ApiCredentialAuthenticator;
import group.aitools.nhs.platform.identity.service.CredentialSecretGenerator;
import group.aitools.nhs.platform.identity.service.MachineIdentityApplicationService;
import group.aitools.nhs.platform.identity.web.CreateApiApplicationRequest;
import group.aitools.nhs.platform.identity.web.CreateServiceAccountRequest;
import group.aitools.nhs.platform.identity.web.IssueApiCredentialRequest;
import group.aitools.nhs.platform.model.domain.AgentModel;
import group.aitools.nhs.platform.model.mapper.AgentModelMapper;
import group.aitools.nhs.platform.knowledge.mapper.KnowledgeCatalogMapper;
import group.aitools.nhs.platform.knowledge.persistence.row.KnowledgeParseJobRow;
import group.aitools.nhs.platform.knowledge.persistence.row.KnowledgeRetrievalRow;
import group.aitools.nhs.platform.memory.mapper.MemoryCatalogMapper;
import group.aitools.nhs.platform.project.domain.AgentProject;
import group.aitools.nhs.platform.project.domain.AgentProjectMember;
import group.aitools.nhs.platform.project.mapper.AgentProjectMapper;
import group.aitools.nhs.platform.task.domain.AgentTask;
import group.aitools.nhs.platform.task.domain.AgentTaskAccessRule;
import group.aitools.nhs.platform.task.domain.AgentTaskParticipant;
import group.aitools.nhs.platform.task.domain.AgentTaskResource;
import group.aitools.nhs.platform.task.domain.AgentTaskVersion;
import group.aitools.nhs.platform.task.mapper.AgentTaskMapper;
import group.aitools.nhs.platform.task.mapper.AgentTaskVersionMapper;
import group.aitools.nhs.platform.task.mapper.TaskControlMapper;
import group.aitools.nhs.platform.task.service.TaskApplicationService;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.platform.task.service.TaskVersionContentHasher;
import group.aitools.nhs.platform.task.web.ConvertConversationToTaskRequest;
import group.aitools.nhs.platform.task.web.TaskMutationResult;
import group.aitools.nhs.platform.task.web.TaskResourceRequest;
import group.aitools.nhs.system.api.UserService;
import group.aitools.nhs.system.api.domain.UserDTO;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@Tag("postgres")
@Tag("dev")
@EnabledIfEnvironmentVariable(named = "NHS_TEST_JDBC_URL", matches = ".+")
class PostgresPlatformIntegrationTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );
    private static final CurrentPrincipal ADMIN = new CurrentPrincipal(
        1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
    );

    private static DataSource dataSource;
    private static SqlSessionFactory sessionFactory;

    @BeforeAll
    static void initializeMyBatis() {
        String url = System.getenv("NHS_TEST_JDBC_URL");
        String username = environmentOrDefault("NHS_TEST_DB_USER", "agent_server");
        String password = environmentOrDefault("NHS_TEST_DB_PASSWORD", "agent_server");
        dataSource = new UnpooledDataSource("org.postgresql.Driver", url, username, password);

        Environment environment = new Environment("postgres-test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(PermissionRuleQueryMapper.class);
        configuration.addMapper(PermissionAdministrationMapper.class);
        configuration.addMapper(MachineIdentityMapper.class);
        configuration.addMapper(AgentTaskMapper.class);
        configuration.addMapper(AgentTaskVersionMapper.class);
        configuration.addMapper(TaskControlMapper.class);
            configuration.addMapper(AgentExecutionEventMapper.class);
            configuration.addMapper(AgentModelMapper.class);
            configuration.addMapper(KnowledgeCatalogMapper.class);
            configuration.addMapper(MemoryCatalogMapper.class);
            configuration.addMapper(AgentProjectMapper.class);
            sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void cleanTables() throws SQLException {
        executeSql("""
            TRUNCATE TABLE
                agent_project_member,
                agent_project,
                agent_api_credential,
                agent_api_application,
                agent_service_account,
                agent_acceptance_record,
                agent_artifact,
                agent_approval_request,
                agent_execution_event,
                agent_run_step,
                agent_agent_version_tool,
                agent_agent_version_skill,
                agent_agent_version_knowledge,
                agent_definition_version,
                agent_definition,
                agent_tool,
                agent_model,
                agent_task_run,
                task_access_rule,
                iam_permission_copy_record,
                iam_temporary_grant,
                iam_user_permission_override,
                iam_user_permission_binding,
                iam_permission_profile_entry,
                iam_permission_profile,
                agent_task_resource,
                agent_task_participant,
                agent_task_version,
                agent_task,
                agent_conversation,
                agent_knowledge_chunk,
                agent_knowledge_document,
                agent_memory,
                agent_job_queue,
                agent_knowledge_base
            """);
    }

    @Test
    void projectVisibilityRequiresOwnerOrActiveMembership() throws SQLException {
        executeSql("""
            INSERT INTO agent_project
                (id, project_key, name, status, owner_id, workspace_policy_json,
                 notification_policy_json, tags_json, create_by, del_flag)
            VALUES
                (2001, 'P-owner', 'Owner project', 'active', 101, '{}'::jsonb, '{}'::jsonb, '[]'::jsonb, 101, '0'),
                (2002, 'P-member', 'Shared project', 'active', 202, '{}'::jsonb, '{}'::jsonb, '[]'::jsonb, 202, '0'),
                (2003, 'P-removed', 'Removed project', 'active', 303, '{}'::jsonb, '{}'::jsonb, '[]'::jsonb, 303, '0'),
                (2004, 'P-outsider', 'Private project', 'active', 404, '{}'::jsonb, '{}'::jsonb, '[]'::jsonb, 404, '0');

            INSERT INTO agent_project_member
                (id, project_id, user_id, member_role, permission_json, status, created_by)
            VALUES
                (2011, 2002, 101, 'member', '{}'::jsonb, 'active', 202),
                (2012, 2003, 101, 'viewer', '{}'::jsonb, 'removed', 303);
            """);

        try (SqlSession session = sessionFactory.openSession()) {
            List<AgentProject> visible = session.getMapper(AgentProjectMapper.class)
                .selectVisibleProjects(101L, false, null, 100);

            assertEquals(Set.of(2001L, 2002L), idsOfProjects(visible));
        }
    }

    @Test
    void concurrentProjectKeyInsertHasOneWinnerAndActiveMembershipIsUnique() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        AgentProject first = project(2101L, "P-concurrent", 101L);
        AgentProject second = project(2102L, "P-concurrent", 102L);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> left = executor.submit(() -> insertProjectAfter(start, first));
            Future<Integer> right = executor.submit(() -> insertProjectAfter(start, second));
            start.countDown();
            assertEquals(Set.of(0, 1), Set.of(left.get(), right.get()));
        }

        long winnerProjectId;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT id FROM agent_project WHERE project_key = 'P-concurrent' AND del_flag = '0'")) {
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                winnerProjectId = result.getLong(1);
                assertFalse(result.next());
            }
        }

        AgentProjectMember owner = member(2111L, winnerProjectId, 999L, "member");
        AgentProjectMember duplicate = member(2112L, winnerProjectId, 999L, "viewer");
        try (SqlSession session = sessionFactory.openSession(false)) {
            AgentProjectMapper mapper = session.getMapper(AgentProjectMapper.class);
            assertEquals(1, mapper.insertMember(owner));
            assertEquals(0, mapper.insertMember(duplicate));
            session.commit();
        }
    }

    @Test
    void taskControlMappersAppendVersionsAndReplaceResourcesAndAcl() throws Exception {
        AgentTask task = task(2300L, "T-control", null, "{}");
        task.setAcceptanceConfigJson("{\"mode\":\"human\"}");
        task.setBudgetJson("{\"maxTokens\":1000}");
        task.setExternalRefsJson("{}");
        task.setTagsJson("[\"phase-1\"]");
        task.setExtraJson("{\"creationRequestHash\":\"" + "a".repeat(64) + "\"}");
        AgentTaskVersion firstVersion = taskVersion(2301L, 2300L, 1, "First title");
        AgentTaskParticipant owner = taskParticipant(2302L, 2300L, 101L, "owner");
        AgentTaskResource firstResource = taskResource(2303L, 2300L, "agent_version", 9601L, "use");

        try (SqlSession session = sessionFactory.openSession(false)) {
            AgentTaskMapper tasks = session.getMapper(AgentTaskMapper.class);
            AgentTaskVersionMapper versions = session.getMapper(AgentTaskVersionMapper.class);
            TaskControlMapper controls = session.getMapper(TaskControlMapper.class);
            assertEquals(1, tasks.insertIfAbsent(task));
            assertEquals(1, versions.insertSnapshot(firstVersion));
            assertEquals(1, tasks.bindInitialVersion(2300L, 2301L, 101L));
            assertEquals(1, controls.insertParticipant(owner));
            assertEquals(1, controls.insertResource(firstResource));
            session.commit();
        }

        try (SqlSession session = sessionFactory.openSession(false)) {
            AgentTaskMapper tasks = session.getMapper(AgentTaskMapper.class);
            AgentTaskVersionMapper versions = session.getMapper(AgentTaskVersionMapper.class);
            TaskControlMapper controls = session.getMapper(TaskControlMapper.class);
            assertEquals(2300L, controls.lockTask(2300L));
            AgentTask current = tasks.selectPlatformTaskById(2300L);
            assertEquals("{\"maxTokens\": 1000}", current.getBudgetJson());
            assertEquals(List.of("OWNER"), controls.selectRelations(2300L, 101L, "human"));
            assertEquals(1, controls.selectResources(2300L).size());
            assertEquals(2, versions.selectNextVersionNo(2300L));

            AgentTaskVersion secondVersion = taskVersion(2304L, 2300L, 2, "Second title");
            assertEquals(1, versions.insertSnapshot(secondVersion));
            current.setTitle("Second title");
            current.setObjective("Second objective");
            current.setCurrentVersionId(2304L);
            current.setUpdateBy(101L);
            current.setUpdateTime(LocalDateTime.now());
            assertEquals(1, tasks.updateDefinitionAndVersion(current, 2301L));
            assertEquals(1, controls.deleteResources(2300L));
            assertEquals(1, controls.insertResource(
                taskResource(2305L, 2300L, "knowledge_base", 77L, "read")
            ));

            AgentTaskAccessRule allow = taskAccessRule(2306L, 2300L, 202L, "view", "allow");
            assertEquals(1, controls.insertAccessRule(allow));
            assertEquals("allow", controls.selectActiveAccessRule(
                2300L, "user", 202L, null, "view"
            ).getEffect());
            assertEquals(1, controls.revokeAccessRule(2300L, 2306L, LocalDateTime.now()));
            assertEquals(1, controls.insertAccessRule(
                taskAccessRule(2307L, 2300L, 202L, "view", "deny")
            ));
            session.commit();
        }

        try (SqlSession session = sessionFactory.openSession()) {
            AgentTaskVersionMapper versions = session.getMapper(AgentTaskVersionMapper.class);
            TaskControlMapper controls = session.getMapper(TaskControlMapper.class);
            assertEquals(2, versions.selectVersions(2300L, 10).size());
            assertEquals("knowledge_base", controls.selectResources(2300L).getFirst().getResourceType());
            assertEquals("deny", controls.selectAccessRules(2300L, 10).getFirst().getEffect());
        }

        assertThrows(SQLException.class, () -> executeSql("""
            UPDATE agent_task_version SET title = 'tampered' WHERE id = 2301
            """));
    }

    @Test
    void taskApplicationServiceCreatesAndReplaysConversationAggregateWithRealMappers() {
        try (SqlSession session = sessionFactory.openSession(false)) {
            AgentTaskMapper tasks = session.getMapper(AgentTaskMapper.class);
            AgentTaskVersionMapper versions = session.getMapper(AgentTaskVersionMapper.class);
            TaskControlMapper controls = session.getMapper(TaskControlMapper.class);
            AtomicLong sequence = new AtomicLong(2400L);
            PlatformIdGenerator ids = new PlatformIdGenerator() {
                @Override
                public Long nextId() {
                    return sequence.incrementAndGet();
                }

                @Override
                public String nextUuid() {
                    return "postgres-service-test";
                }
            };
            CurrentPrincipalProvider principals = () -> MEMBER;
            AuthorizationEnforcer authorization = mock(AuthorizationEnforcer.class);
            JsonMapper jsonMapper = JsonMapper.builder().build();
            TaskApplicationService service = new TaskApplicationService(
                principals, authorization, ids, tasks, versions, controls,
                session.getMapper(AgentProjectMapper.class), mock(TaskQueryService.class),
                new TaskVersionContentHasher(jsonMapper), jsonMapper
            );
            ConvertConversationToTaskRequest draft = new ConvertConversationToTaskRequest(
                "postgres-conversation-1", "Database-backed task", "Verify the complete aggregate",
                null, null, 9601L, null, "enterprise_shared", "development", "single_agent",
                "L1_short_task", "R1", "human", 1, 0, null,
                Map.of("selectedMessages", List.of(11L, 12L)),
                List.of(new TaskResourceRequest(
                    "tool", 88L, "use", true, "agent", Map.of("permissionSource", "profile")
                )),
                Map.of("mode", "human"), Map.of("request", "build"), Map.of("maxTokens", 1000),
                Map.of("ticket", "AGENT-1"), List.of("phase-1"), null
            );

            String draftHash = service.previewConversationDraftHash(77L, draft);
            TaskMutationResult created = service.createFromConversation(
                77L, draft.withDraftHash(draftHash)
            );
            TaskMutationResult replayed = service.createFromConversation(
                77L, draft.withDraftHash(draftHash)
            );

            Long taskId = created.task().id();
            assertFalse(created.replayed());
            assertTrue(replayed.replayed());
            assertEquals(taskId, replayed.task().id());
            assertEquals(77L, tasks.selectPlatformTaskById(taskId).getSourceConversationId());
            assertEquals(List.of("OWNER"), controls.selectRelations(taskId, MEMBER.id(), "human"));
            assertEquals(List.of("agent_version", "tool"), controls.selectResources(taskId).stream()
                .map(AgentTaskResource::getResourceType).toList());
            assertEquals(1, versions.selectVersions(taskId, 10).size());
            assertEquals(created.taskVersionId(), versions.selectVersions(taskId, 10).getFirst().getId());
            session.commit();
        }
    }

    @Test
    void iamAdministrationPublishesBindsAndCopiesReusableRulesWithRealMappers() throws SQLException {
        executeSql("""
            INSERT INTO agent_knowledge_base
                (id, knowledge_key, name, provider_type, visibility, status, owner_id, del_flag)
            VALUES
                (7001, 'private-kb', 'Private knowledge', 'postgres_pgvector',
                 'private', 'active', 101, '0')
            """);

        try (SqlSession session = sessionFactory.openSession(false)) {
            PermissionAdministrationMapper administration = session.getMapper(
                PermissionAdministrationMapper.class
            );
            AtomicLong sequence = new AtomicLong(3000L);
            PlatformIdGenerator ids = new PlatformIdGenerator() {
                @Override
                public Long nextId() {
                    return sequence.incrementAndGet();
                }

                @Override
                public String nextUuid() {
                    return "iam-postgres-test";
                }
            };
            UserService users = mock(UserService.class);
            when(users.selectById(anyLong())).thenReturn(new UserDTO());
            AuthorizationEnforcer authorization = mock(AuthorizationEnforcer.class);
            JsonMapper jsonMapper = JsonMapper.builder().build();
            PermissionAdministrationService service = new PermissionAdministrationService(
                () -> ADMIN, authorization, ids, administration, users, jsonMapper
            );
            PermissionRuleInput tool = new PermissionRuleInput(
                "tool", 88L, null, "invoke", "allow", Map.of("maxTokens", 1000), "developer tool"
            );
            PermissionRuleInput privateKnowledge = new PermissionRuleInput(
                "knowledge_base", 7001L, null, "read", "allow", Map.of(), "private source knowledge"
            );

            var profile = service.createProfile(new CreatePermissionProfileRequest(
                "developer", "Developer", "Base developer capability", "custom",
                List.of(tool, privateKnowledge)
            ));
            service.updateProfileStatus(profile.id(), "published");
            service.putBinding(101L, new PutPermissionBindingRequest(
                "profile", profile.id(), profile.versionNo(), List.of()
            ));
            var sourceSummary = service.patchOverrides(101L, new PatchPermissionOverridesRequest(List.of(
                new PermissionOverrideMutation("upsert", new PermissionRuleInput(
                    "tool", 89L, null, "invoke", "approval_required", Map.of(), "high-risk tool"
                ), null)
            )));
            service.createTemporaryGrant(101L, new CreateTemporaryGrantRequest(
                new PermissionRuleInput("tool", 90L, null, "invoke", "allow", Map.of(), null),
                "incident response", null, LocalDateTime.now().plusHours(2)
            ));

            var copied = service.copy(202L, new CopyPermissionRequest(
                "copy-101-to-202", 101L, "copy_base", null, null
            ));
            var replayed = service.copy(202L, new CopyPermissionRequest(
                "copy-101-to-202", 101L, "copy_base", null, null
            ));
            service.patchOverrides(202L, new PatchPermissionOverridesRequest(List.of(
                new PermissionOverrideMutation("upsert", new PermissionRuleInput(
                    "tool", 91L, null, "invoke", "allow", Map.of(), "target-only override"
                ), null)
            )));
            service.patchOverrides(101L, new PatchPermissionOverridesRequest(List.of(
                new PermissionOverrideMutation("upsert", new PermissionRuleInput(
                    "tool", 92L, null, "invoke", "allow", Map.of(), "new source permission"
                ), null)
            )));
            var appended = service.copy(202L, new CopyPermissionRequest(
                "append-101-to-202", 101L, "append_missing", null, null
            ));
            var targetSummary = service.summary(202L);
            var savedTemplate = service.copy(303L, new CopyPermissionRequest(
                "template-from-101", 101L, "save_template", "reference-developer", "Reference developer"
            ));

            assertEquals(2, sourceSummary.baseRules().size());
            assertEquals(1, sourceSummary.overrides().size());
            assertEquals(2, copied.addedRuleCount());
            assertEquals(1, copied.excludedRules().size());
            assertEquals("knowledge_base", copied.excludedRules().getFirst().resourceType());
            assertTrue(replayed.replayed());
            assertEquals(copied.copyRecordId(), replayed.copyRecordId());
            assertEquals(1, appended.addedRuleCount());
            assertEquals(2, appended.retainedRuleCount());
            assertEquals(3, targetSummary.baseRules().size());
            assertEquals(1, targetSummary.overrides().size());
            assertEquals(1, targetSummary.overrides().stream()
                .filter(rule -> Long.valueOf(91L).equals(rule.resourceId())).count());
            assertEquals(1, savedTemplate.createdProfileVersion());
            assertEquals("draft", service.profile(savedTemplate.createdProfileId()).status());

            DatabasePermissionSnapshotResolver resolver = new DatabasePermissionSnapshotResolver(
                session.getMapper(PermissionRuleQueryMapper.class), jsonMapper
            );
            CurrentPrincipal target = new CurrentPrincipal(
                202L, "target", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
            );
            assertEquals(1, resolver.resolve(
                target, PermissionContext.active("tool", 88L, "invoke")
            ).rules().stream().filter(rule -> rule.matches(
                PermissionContext.active("tool", 88L, "invoke")
            )).count());
            assertEquals(1, resolver.resolve(
                target, PermissionContext.active("tool", 89L, "invoke")
            ).rules().stream().filter(rule -> rule.matches(
                PermissionContext.active("tool", 89L, "invoke")
            )).count());
            assertTrue(resolver.resolve(
                target, PermissionContext.active("tool", 90L, "invoke")
            ).rules().stream().noneMatch(rule -> rule.matches(
                PermissionContext.active("tool", 90L, "invoke")
            )));
            assertEquals(1, resolver.resolve(
                target, PermissionContext.active("tool", 91L, "invoke")
            ).rules().stream().filter(rule -> rule.matches(
                PermissionContext.active("tool", 91L, "invoke")
            )).count());
            assertEquals(1, resolver.resolve(
                target, PermissionContext.active("tool", 92L, "invoke")
            ).rules().stream().filter(rule -> rule.matches(
                PermissionContext.active("tool", 92L, "invoke")
            )).count());
            assertTrue(resolver.resolve(
                target, PermissionContext.active("knowledge_base", 7001L, "read")
            ).rules().stream().noneMatch(rule -> rule.matches(
                PermissionContext.active("knowledge_base", 7001L, "read")
            )));
            session.commit();
        }
    }

    @Test
    void machineCredentialIsStoredAsHashAndAuthenticatesOnlyServiceAccountScope() throws SQLException {
        try (SqlSession session = sessionFactory.openSession(false)) {
            MachineIdentityMapper identities = session.getMapper(MachineIdentityMapper.class);
            AtomicLong sequence = new AtomicLong(4000L);
            PlatformIdGenerator ids = new PlatformIdGenerator() {
                @Override
                public Long nextId() {
                    return sequence.incrementAndGet();
                }

                @Override
                public String nextUuid() {
                    return "machine-identity-test";
                }
            };
            UserService users = mock(UserService.class);
            when(users.selectById(anyLong())).thenReturn(new UserDTO());
            JsonMapper jsonMapper = JsonMapper.builder().build();
            MachineIdentityApplicationService service = new MachineIdentityApplicationService(
                () -> ADMIN, mock(AuthorizationEnforcer.class), ids, identities, users,
                new CredentialSecretGenerator(), jsonMapper
            );
            var account = service.createServiceAccount(new CreateServiceAccountRequest(
                "automation-worker", "Automation worker", null, 1L,
                LocalDateTime.now().plusDays(30), Map.of("purpose", "integration-test")
            ));
            var application = service.createApiApplication(new CreateApiApplicationRequest(
                "integration-api", "Integration API", "open_api", 1L,
                "https://example.com/callback", List.of("tasks:read", "tasks:run"),
                LocalDateTime.now().plusDays(20)
            ));
            var issued = service.issueCredential(application.id(), new IssueApiCredentialRequest(
                account.id(), List.of("tasks:read"), LocalDateTime.now().plusDays(10)
            ));

            assertTrue(issued.secret().matches("agk_[A-Za-z0-9_-]{12}\\.[A-Za-z0-9_-]{43}"));
            assertEquals(Set.of("tasks:read"), issued.credential().scopes());
            assertEquals(1, service.credentials(application.id(), 10).size());
            assertEquals(null, identities.selectApiCredentials(application.id(), 10).getFirst().getSecretHash());

            try (PreparedStatement statement = session.getConnection().prepareStatement("""
                SELECT secret_hash, secret_ciphertext, key_prefix
                FROM agent_api_credential
                WHERE id = ?
                """)) {
                statement.setLong(1, issued.credential().id());
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(ContentHashing.sha256(issued.secret()), result.getString("secret_hash"));
                    assertEquals(null, result.getString("secret_ciphertext"));
                    assertEquals(issued.credential().keyPrefix(), result.getString("key_prefix"));
                }
            }

            ApiCredentialAuthenticator authenticator = new ApiCredentialAuthenticator(identities, jsonMapper);
            var authenticated = authenticator.authenticate(issued.secret());
            assertEquals(PrincipalType.SERVICE_ACCOUNT, authenticated.principal().type());
            assertEquals(Set.of(PlatformRole.SERVICE_ACCOUNT), authenticated.principal().roles());
            assertEquals(account.id(), authenticated.principal().id());
            assertEquals(Set.of("tasks:read"), authenticated.scopes());

            service.revokeCredential(application.id(), issued.credential().id());
            assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(
                ServiceException.class, () -> authenticator.authenticate(issued.secret())
            ).getCode());
            session.commit();
        }
    }

    private int insertProjectAfter(CountDownLatch start, AgentProject project) throws Exception {
        start.await();
        try (SqlSession session = sessionFactory.openSession(false)) {
            int inserted = session.getMapper(AgentProjectMapper.class).insertProject(project);
            session.commit();
            return inserted;
        }
    }

    private AgentProject project(Long id, String key, Long ownerId) {
        AgentProject project = new AgentProject();
        project.setId(id);
        project.setProjectKey(key);
        project.setName("Concurrent project");
        project.setStatus("active");
        project.setOwnerId(ownerId);
        project.setWorkspacePolicyJson("{}");
        project.setNotificationPolicyJson("{}");
        project.setTagsJson("[]");
        project.setCreateBy(ownerId);
        project.setCreateTime(LocalDateTime.now());
        project.setDelFlag("0");
        project.setExtraJson("{}");
        return project;
    }

    private AgentProjectMember member(Long id, Long projectId, Long userId, String role) {
        AgentProjectMember member = new AgentProjectMember();
        member.setId(id);
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setMemberRole(role);
        member.setPermissionJson("{}");
        member.setStatus("active");
        member.setJoinedAt(LocalDateTime.now());
        member.setCreatedBy(userId);
        member.setCreatedAt(LocalDateTime.now());
        return member;
    }

    private AgentTaskVersion taskVersion(
        Long id,
        Long taskId,
        int versionNo,
        String title
    ) {
        AgentTaskVersion version = new AgentTaskVersion();
        version.setId(id);
        version.setTaskId(taskId);
        version.setVersionNo(versionNo);
        version.setTitle(title);
        version.setObjective("Objective " + versionNo);
        version.setAgentVersionId(9601L);
        version.setContextSnapshotJson("{}");
        version.setResourceSnapshotJson("{\"agentVersionId\":9601,\"resources\":[]}");
        version.setAcceptanceSnapshotJson("{\"mode\":\"human\"}");
        version.setInputSnapshotJson("{}");
        version.setContentHash(String.valueOf(versionNo).repeat(64));
        version.setCreatedBy(101L);
        version.setCreatedAt(LocalDateTime.now());
        return version;
    }

    private AgentTaskParticipant taskParticipant(
        Long id,
        Long taskId,
        Long userId,
        String type
    ) {
        AgentTaskParticipant participant = new AgentTaskParticipant();
        participant.setId(id);
        participant.setTaskId(taskId);
        participant.setUserId(userId);
        participant.setParticipantType(type);
        participant.setSource("system");
        participant.setStatus("active");
        participant.setCreatedAt(LocalDateTime.now());
        return participant;
    }

    private AgentTaskResource taskResource(
        Long id,
        Long taskId,
        String type,
        Long resourceId,
        String permission
    ) {
        AgentTaskResource resource = new AgentTaskResource();
        resource.setId(id);
        resource.setTaskId(taskId);
        resource.setResourceType(type);
        resource.setResourceId(resourceId);
        resource.setPermission(permission);
        resource.setRequired(true);
        resource.setGrantSource("user");
        resource.setGrantSnapshotJson("{}");
        resource.setCreatedBy(101L);
        resource.setCreatedAt(LocalDateTime.now());
        return resource;
    }

    private AgentTaskAccessRule taskAccessRule(
        Long id,
        Long taskId,
        Long userId,
        String action,
        String effect
    ) {
        AgentTaskAccessRule rule = new AgentTaskAccessRule();
        rule.setId(id);
        rule.setTaskId(taskId);
        rule.setSubjectType("user");
        rule.setSubjectId(userId);
        rule.setAction(action);
        rule.setEffect(effect);
        rule.setCreatedBy(101L);
        rule.setCreatedAt(LocalDateTime.now());
        return rule;
    }

    private Set<Long> idsOfProjects(List<AgentProject> projects) {
        Set<Long> ids = new HashSet<>();
        projects.forEach(project -> ids.add(project.getId()));
        return ids;
    }

    @Test
    void realPermissionResolutionHonorsDenyAndIgnoresExpiredOrRevokedGrants() throws Exception {
        executeSql("""
            INSERT INTO iam_permission_profile
                (id, profile_key, name, version_no, status, created_by)
            VALUES (1, 'developer', 'Developer', 1, 'published', 1);

            INSERT INTO iam_permission_profile_entry
                (id, profile_id, resource_type, resource_id, action, effect)
            VALUES (2, 1, 'tool', 88, 'invoke', 'allow');

            INSERT INTO iam_user_permission_binding
                (id, user_id, profile_id, profile_version, binding_type, status, created_by)
            VALUES (3, 101, 1, 1, 'profile', 'active', 1);

            INSERT INTO iam_user_permission_override
                (id, user_id, resource_type, resource_id, action, effect, reason, status, created_by)
            VALUES (4, 101, 'tool', 88, 'invoke', 'deny', 'blocked for adversarial test', 'active', 1);

            INSERT INTO iam_temporary_grant
                (id, user_id, resource_type, resource_id, action, effect, reason, expires_at, created_by, created_at)
            VALUES
                (5, 101, 'tool', 89, 'invoke', 'allow', 'active grant',
                 CURRENT_TIMESTAMP + INTERVAL '1 hour', 1, CURRENT_TIMESTAMP),
                (6, 101, 'tool', 90, 'invoke', 'allow', 'expired grant',
                 CURRENT_TIMESTAMP - INTERVAL '1 hour', 1, CURRENT_TIMESTAMP - INTERVAL '2 hours');

            INSERT INTO iam_temporary_grant
                (id, user_id, resource_type, resource_id, action, effect, reason, expires_at, revoked_at, created_by)
            VALUES
                (7, 101, 'tool', 91, 'invoke', 'allow', 'revoked grant',
                 CURRENT_TIMESTAMP + INTERVAL '1 hour', CURRENT_TIMESTAMP, 1);
            """);

        try (SqlSession session = sessionFactory.openSession(true)) {
            PermissionRuleQueryMapper mapper = session.getMapper(PermissionRuleQueryMapper.class);
            DatabasePermissionSnapshotResolver resolver = new DatabasePermissionSnapshotResolver(
                mapper, JsonMapper.builder().build()
            );
            DefaultAuthorizationService authorizationService = new DefaultAuthorizationService(resolver);

            AuthorizationDecision denied = authorizationService.authorize(
                MEMBER, PermissionContext.active("tool", 88L, "invoke")
            );
            AuthorizationDecision activeGrant = authorizationService.authorize(
                MEMBER, PermissionContext.active("tool", 89L, "invoke")
            );
            AuthorizationDecision expiredGrant = authorizationService.authorize(
                MEMBER, PermissionContext.active("tool", 90L, "invoke")
            );
            AuthorizationDecision revokedGrant = authorizationService.authorize(
                MEMBER, PermissionContext.active("tool", 91L, "invoke")
            );

            assertEquals(PermissionEffect.DENY, denied.effect());
            assertEquals("EXPLICIT_DENY", denied.reasonCode());
            assertTrue(denied.evidence().stream().anyMatch(
                item -> item.source() == PermissionSource.USER_OVERRIDE
            ));
            assertTrue(activeGrant.allowed());
            assertEquals(PermissionEffect.DENY, expiredGrant.effect());
            assertEquals(PermissionEffect.DENY, revokedGrant.effect());
        }
    }

    @Test
    void visibleTaskQueryAppliesExplicitDenyToSharedAndAdministratorBranches() throws Exception {
        executeSql("""
            INSERT INTO agent_task
                (id, task_key, title, objective, visibility, owner_id, create_by)
            VALUES
                (100, 'T-shared', 'Shared', 'Shared task', 'enterprise_shared', 101, 101),
                (101, 'T-restricted-allow', 'Restricted allowed', 'Restricted task', 'restricted', 101, 101),
                (102, 'T-shared-deny', 'Shared denied', 'Denied shared task', 'enterprise_shared', 102, 102),
                (103, 'T-admin-deny', 'Admin denied', 'Denied administrator task', 'restricted', 103, 103);

            INSERT INTO task_access_rule
                (id, task_id, subject_type, subject_id, action, effect, created_by)
            VALUES
                (200, 101, 'user', 101, 'view', 'allow', 101),
                (201, 102, 'user', 101, 'view', 'deny', 1),
                (202, 103, 'user', 1, 'view', 'deny', 1);
            """);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentTaskMapper mapper = session.getMapper(AgentTaskMapper.class);
            List<AgentTask> memberTasks = mapper.selectVisibleTasks(
                101L, "user", true, true, false, false, 100
            );
            List<AgentTask> adminTasks = mapper.selectVisibleTasks(
                1L, "user", true, true, false, true, 100
            );

            Set<Long> memberIds = ids(memberTasks);
            Set<Long> adminIds = ids(adminTasks);
            assertTrue(memberIds.containsAll(Set.of(100L, 101L)));
            assertFalse(memberIds.contains(102L));
            assertFalse(memberIds.contains(103L));
            assertFalse(adminIds.contains(103L));
            assertTrue(adminIds.containsAll(Set.of(100L, 101L, 102L)));
        }
    }

    @Test
    void concurrentConversationConversionInsertCreatesOneJsonbTask() throws Exception {
        AgentTask firstTask = task(300L, "T-race-a", 700L, "{\"winner\":\"a\"}");
        AgentTask secondTask = task(301L, "T-race-b", 700L, "{\"winner\":\"b\"}");
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> insertTaskAfter(start, firstTask));
            Future<Integer> second = executor.submit(() -> insertTaskAfter(start, secondTask));
            start.countDown();

            Set<Integer> insertResults = Set.of(first.get(), second.get());
            assertEquals(Set.of(0, 1), insertResults);
        }

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("""
                SELECT count(*), bool_and(jsonb_typeof(context_snapshot_json) = 'object')
                FROM agent_task
                WHERE source_conversation_id = 700 AND del_flag = '0'
                """)) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
                assertTrue(result.getBoolean(2));
            }
        }
    }

    @Test
    void executionEventsAreIdempotentAndReplayFromDurableCursor() {
        AtomicLong ids = new AtomicLong(9000);
        PlatformIdGenerator idGenerator = new PlatformIdGenerator() {
            @Override
            public Long nextId() {
                return ids.incrementAndGet();
            }

            @Override
            public String nextUuid() {
                return Long.toString(ids.incrementAndGet());
            }
        };

        try (SqlSession session = sessionFactory.openSession(false)) {
            AgentExecutionEventMapper mapper = session.getMapper(AgentExecutionEventMapper.class);
            ExecutionEventPersistenceService service = new ExecutionEventPersistenceService(
                idGenerator, mapper, JsonMapper.builder().build()
            );
            RuntimeEvent first = runtimeEvent("source-1", "first");
            RuntimeEvent second = runtimeEvent("source-2", "second");

            ExecutionEventView firstWrite = service.append(first);
            ExecutionEventView replayedWrite = service.append(first);
            ExecutionEventView secondWrite = service.append(second);
            session.commit();

            assertEquals(firstWrite.cursor(), replayedWrite.cursor());
            assertTrue(secondWrite.cursor() > firstWrite.cursor());
            List<AgentExecutionEvent> replay = mapper.selectConversationEvents(
                700L, firstWrite.cursor(), 100
            );
            assertEquals(1, replay.size());
            assertEquals(secondWrite.cursor(), replay.getFirst().getCursor());
            assertEquals("second", replay.getFirst().getSummary());
            assertEquals(LocalDateTime.of(2026, 8, 14, 4, 0), replay.getFirst().getOccurredAt());
        }
    }

    @Test
    void modelRegistryPersistsJsonbAndSupportsSafeSoftDeleteReuse() throws Exception {
        AgentModel first = model(9500L, "integration-chat", "Integration Chat");
        try (SqlSession session = sessionFactory.openSession(false)) {
            AgentModelMapper mapper = session.getMapper(AgentModelMapper.class);
            assertEquals(1, mapper.insertModel(first));
            AgentModel stored = mapper.selectModelById(first.getId());
            assertEquals("env:INTEGRATION_MODEL_KEY", stored.getCredentialRef());
            assertEquals("{\"temperature\": 0.25}", stored.getReasoningConfigJson());
            assertEquals(1, mapper.selectModels("chat", "openai", "Integration", false, 10).size());
            assertEquals(first.getId(), mapper.lockModel(first.getId()));
            assertEquals(1, mapper.softDelete(first.getId(), 1L, LocalDateTime.now()));
            session.commit();
        }

        AgentModel replacement = model(9501L, "integration-chat", "Replacement Chat");
        try (SqlSession session = sessionFactory.openSession(false)) {
            AgentModelMapper mapper = session.getMapper(AgentModelMapper.class);
            assertEquals(1, mapper.insertModel(replacement));
            session.commit();
        }

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("""
                SELECT count(*),
                       bool_and(credential_ref LIKE 'env:%'),
                       bool_and(reasoning_config_json ? 'temperature')
                FROM agent_model
                WHERE model_key = 'integration-chat'
                """)) {
                assertTrue(result.next());
                assertEquals(2, result.getInt(1));
                assertTrue(result.getBoolean(2));
                assertTrue(result.getBoolean(3));
            }
        }
    }

    @Test
    void knowledgeSchemaRetrievesGroundedChunksAndRecoversExpiredLeases() throws Exception {
        executeSql("""
            INSERT INTO agent_knowledge_base
                (id, knowledge_key, name, provider_type, visibility, status, config_json,
                 owner_id, revision_no, create_by, del_flag, extra_json)
            VALUES
                (9550, 'integration-knowledge', 'Integration Knowledge', 'postgres_pgvector',
                 'enterprise_shared', 'active', '{}'::jsonb, 101, 1, 101, '0', '{}'::jsonb);

            INSERT INTO agent_knowledge_document
                (id, knowledge_base_id, document_key, name, content_hash, parser_type, status,
                 chunk_count, metadata_json, storage_type, storage_ref, mime_type, size_bytes,
                 revision_no, created_by, del_flag)
            VALUES
                (9551, 9550, 'doc-9551', 'expense-policy.txt', repeat('a', 64), 'text', 'ready',
                 2, '{}'::jsonb, 'local', '9551/source.bin', 'text/plain', 100, 1, 101, '0');

            INSERT INTO agent_knowledge_chunk
                (id, knowledge_base_id, document_id, chunk_no, content, content_hash,
                 token_count, embedding_model_id, embedding_dimension, embedding,
                 metadata_json, status)
            VALUES
                (9552, 9550, 9551, 1, 'Expense claims require manager approval.', repeat('b', 64),
                 7, 77, 3, '[1,0,0]'::vector, '{"page":1}'::jsonb, 'active'),
                (9553, 9550, 9551, 2, 'Travel bookings use the approved supplier.', repeat('c', 64),
                 7, 77, 3, '[0,1,0]'::vector, '{"page":2}'::jsonb, 'active');

            INSERT INTO agent_job_queue
                (id, job_type, biz_key, payload_json, status, priority, attempt_no,
                 max_attempts, available_at)
            VALUES
                (9554, 'knowledge_parse', 'knowledge-document:9551:1',
                 '{"knowledgeBaseId":9550,"documentId":9551,"revision":1}'::jsonb,
                 'queued', 10, 0, 3, CURRENT_TIMESTAMP),
                (9555, 'knowledge_parse', 'knowledge-document:9551:2',
                 '{"knowledgeBaseId":9550,"documentId":9551,"revision":2}'::jsonb,
                 'queued', 10, 0, 3, CURRENT_TIMESTAMP);
            """);

        try (SqlSession session = sessionFactory.openSession()) {
            KnowledgeCatalogMapper mapper = session.getMapper(KnowledgeCatalogMapper.class);
            List<KnowledgeRetrievalRow> lexical = mapper.searchLexical(9550L, "Expense", 10);
            List<KnowledgeRetrievalRow> vector = mapper.searchVector(
                9550L, 77L, 3, "[1,0,0]", 10
            );

            assertEquals(9552L, lexical.getFirst().getChunkId());
            assertEquals(9552L, vector.getFirst().getChunkId());
            assertEquals(1.0, vector.getFirst().getScore(), 0.000001);
        }

        try (SqlSession first = sessionFactory.openSession(false);
             SqlSession second = sessionFactory.openSession(false)) {
            KnowledgeParseJobRow left = first.getMapper(KnowledgeCatalogMapper.class)
                .claimParseJob("worker-left");
            KnowledgeParseJobRow right = second.getMapper(KnowledgeCatalogMapper.class)
                .claimParseJob("worker-right");

            assertEquals(Set.of(9554L, 9555L), Set.of(left.getId(), right.getId()));
            assertFalse(Boolean.TRUE.equals(left.getRecovered()));
            assertFalse(Boolean.TRUE.equals(right.getRecovered()));
            first.commit();
            second.commit();
        }

        executeSql("""
            UPDATE agent_job_queue
            SET status = 'success', worker_id = NULL, lease_until = NULL
            WHERE id = 9555;
            UPDATE agent_job_queue
            SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 minute'
            WHERE id = 9554;
            UPDATE agent_knowledge_document SET status = 'processing' WHERE id = 9551;
            """);

        try (SqlSession session = sessionFactory.openSession(false)) {
            KnowledgeCatalogMapper mapper = session.getMapper(KnowledgeCatalogMapper.class);
            KnowledgeParseJobRow recovered = mapper.claimParseJob("worker-recovery");

            assertEquals(9554L, recovered.getId());
            assertTrue(Boolean.TRUE.equals(recovered.getRecovered()));
            assertEquals(0, mapper.markDocumentProcessing(
                9551L, 1L, false, LocalDateTime.now()
            ));
            assertEquals(1, mapper.markDocumentProcessing(
                9551L, 1L, true, LocalDateTime.now()
            ));
            assertEquals(0, mapper.completeDocument(
                9551L, 2L, "text", 2, "{}", LocalDateTime.now()
            ));
            assertEquals(1, mapper.renewParseJob(9554L, "worker-recovery"));
            assertEquals(1, mapper.failParseJob(9554L, "worker-recovery", "retry"));
            session.commit();
        }

        assertThrows(SQLException.class, () -> executeSql("""
            INSERT INTO agent_knowledge_document
                (id, knowledge_base_id, document_key, name, content_hash, status,
                 metadata_json, storage_type, revision_no, del_flag)
            VALUES
                (9556, 9550, 'doc-duplicate', 'duplicate.txt', repeat('a', 64), 'pending',
                 '{}'::jsonb, 'local', 1, '0')
            """));
    }

    @Test
    void governedMemorySearchReviewAndRuntimeSelectionUseV21Rules() throws Exception {
        executeSql("""
            INSERT INTO agent_memory
                (id, memory_key, scope_type, scope_id, memory_type, content, content_hash,
                 source_type, confidence, sensitive_level, review_status, metadata_json,
                 revision_no, reviewed_by, reviewed_at, created_by, del_flag)
            VALUES
                (9570, 'response-style', 'user', 101, 'preference',
                 'Prefer concise engineering answers.', repeat('a', 64), 'manual', 1.0,
                 'internal', 'approved', '{"source":"settings"}'::jsonb, 1, 101,
                 CURRENT_TIMESTAMP, 101, '0'),
                (9571, 'release-rule', 'project', 2001, 'fact',
                 'Production releases require two reviewers.', repeat('b', 64), 'manual', 0.9,
                 'internal', 'pending', '{}'::jsonb, 1, NULL, NULL, 101, '0'),
                (9572, 'private-budget', 'task', 3001, 'fact',
                 'The confidential budget is restricted.', repeat('c', 64), 'manual', 1.0,
                 'sensitive', 'approved', '{}'::jsonb, 1, 101, CURRENT_TIMESTAMP, 101, '0'),
                (9573, 'expired-fact', 'task', 3001, 'fact',
                 'This fact has expired.', repeat('d', 64), 'manual', 1.0,
                 'internal', 'approved', '{}'::jsonb, 1, 101, CURRENT_TIMESTAMP, 101, '0');
            UPDATE agent_memory SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
            WHERE id = 9573;
            """);

        try (SqlSession session = sessionFactory.openSession(false)) {
            MemoryCatalogMapper mapper = session.getMapper(MemoryCatalogMapper.class);

            assertEquals(9570L, mapper.selectScopeMemories(
                "user", 101L, false, "engineering", 10
            ).getFirst().getId());
            assertTrue(mapper.selectApprovedForSnapshot("task", 3001L, 10).isEmpty());
            assertEquals(1, mapper.reviewMemory(
                9571L, 1L, "approved", 101L, "verified", LocalDateTime.now()
            ));
            assertEquals(0, mapper.reviewMemory(
                9571L, 1L, "rejected", 102L, "stale", LocalDateTime.now()
            ));
            assertEquals(9571L, mapper.selectApprovedForSnapshot(
                "project", 2001L, 10
            ).getFirst().getId());
            session.commit();
        }

        assertThrows(SQLException.class, () -> executeSql("""
            INSERT INTO agent_memory
                (id, memory_key, scope_type, scope_id, memory_type, content, content_hash,
                 source_type, sensitive_level, review_status, metadata_json, revision_no,
                 created_by, del_flag)
            VALUES
                (9574, 'response-style', 'user', 101, 'preference', 'duplicate', repeat('e', 64),
                 'manual', 'internal', 'pending', '{}'::jsonb, 1, 101, '0')
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            INSERT INTO agent_memory
                (id, memory_key, scope_type, scope_id, memory_type, content, content_hash,
                 source_type, sensitive_level, review_status, metadata_json, revision_no,
                 reviewed_at, created_by, del_flag)
            VALUES
                (9575, 'invalid-review', 'user', 101, 'fact', 'invalid', repeat('f', 64),
                 'manual', 'internal', 'pending', '{}'::jsonb, 1, CURRENT_TIMESTAMP, 101, '0')
            """));
    }

    @Test
    void databaseRejectsPublishedVersionAndBindingMutation() throws Exception {
        executeSql("""
            INSERT INTO agent_definition
                (id, agent_key, name, agent_type, engine_type, is_system, is_default,
                 status, owner_id, sort_order, engine_config_json, create_by, del_flag, extra_json)
            VALUES
                (9600, 'immutable-agent', 'Immutable Agent', 'assistant', 'agentscope_java',
                 false, true, 'active', 1, 0, '{}'::jsonb, 1, '0', '{}'::jsonb);

            INSERT INTO agent_definition_version
                (id, agent_id, version_no, system_prompt, model_id, runtime_config_json,
                 welcome_config_json, routing_tags_json, status, content_hash, created_by)
            VALUES
                (9601, 9600, 1, 'immutable prompt', NULL, '{}'::jsonb, '{}'::jsonb,
                 '[]'::jsonb, 'draft', repeat('a', 64), 1);

            INSERT INTO agent_tool
                (id, tool_key, name, tool_type, risk_level, status, version_no, create_by, del_flag)
            VALUES (9602, 'immutable-tool', 'Immutable Tool', 'api', 'R1', 'active', 1, 1, '0');

            INSERT INTO agent_agent_version_tool
                (id, agent_version_id, resource_id, permission, config_json)
            VALUES (9603, 9601, 9602, 'use', '{}'::jsonb);

            UPDATE agent_definition_version
            SET status = 'published', published_at = CURRENT_TIMESTAMP
            WHERE id = 9601;
            """);

        assertThrows(SQLException.class, () -> executeSql("""
            UPDATE agent_definition_version SET system_prompt = 'tampered' WHERE id = 9601
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            INSERT INTO agent_agent_version_tool
                (id, agent_version_id, resource_id, permission, config_json)
            VALUES (9604, 9601, 9999, 'use', '{}'::jsonb)
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            INSERT INTO agent_definition_version
                (id, agent_id, version_no, system_prompt, runtime_config_json,
                 welcome_config_json, routing_tags_json, status, content_hash, created_by)
            VALUES
                (9605, 9600, 2, 'second published', '{}'::jsonb, '{}'::jsonb,
                 '[]'::jsonb, 'published', repeat('b', 64), 1)
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            INSERT INTO agent_definition
                (id, agent_key, name, agent_type, engine_type, is_system, is_default,
                 status, owner_id, sort_order, engine_config_json, create_by, del_flag, extra_json)
            VALUES
                (9606, 'second-default', 'Second Default', 'assistant', 'agentscope_java',
                 false, true, 'active', 1, 0, '{}'::jsonb, 1, '0', '{}'::jsonb)
            """));

        executeSql("UPDATE agent_definition_version SET status = 'archived' WHERE id = 9601");
        assertThrows(SQLException.class, () -> executeSql("""
            UPDATE agent_definition_version SET system_prompt = 'archived tamper' WHERE id = 9601
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            DELETE FROM agent_definition_version WHERE id = 9601
            """));
    }

    @Test
    void databaseProtectsFrozenTaskRunsAndRejectsIllegalStateJumps() throws Exception {
        executeSql("""
            INSERT INTO agent_task
                (id, task_key, title, objective, status, owner_id, create_by)
            VALUES (9700, 'T-run-integrity', 'Run integrity', 'Protect execution facts', 'ready', 101, 101);

            INSERT INTO agent_task_version
                (id, task_id, version_no, title, objective, context_snapshot_json,
                 resource_snapshot_json, acceptance_snapshot_json, input_snapshot_json,
                 content_hash, created_by)
            VALUES
                (9701, 9700, 1, 'Run integrity', 'Protect execution facts', '{}'::jsonb,
                 '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, repeat('a', 64), 101);

            INSERT INTO agent_task_run
                (id, task_id, task_version_id, trace_id, status, attempt_no,
                 authorization_snapshot_json, runtime_snapshot_json, budget_snapshot_json,
                 usage_json, created_by)
            VALUES
                (9702, 9700, 9701, repeat('b', 64), 'queued', 1,
                 '{"decision":"allow"}'::jsonb, '{"runId":9702}'::jsonb,
                 '{}'::jsonb, '{}'::jsonb, 101);

            INSERT INTO agent_run_step
                (id, run_id, step_key, step_type, sequence_no, status,
                 agent_version_id, input_json)
            VALUES (9703, 9702, 'agent-1', 'agent', 1, 'pending', 1, '{"text":"work"}'::jsonb);
            """);

        assertThrows(SQLException.class, () -> executeSql("""
            UPDATE agent_task_version SET objective = 'tampered' WHERE id = 9701
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            DELETE FROM agent_task_version WHERE id = 9701
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            UPDATE agent_task_run
            SET runtime_snapshot_json = '{"runId":9999}'::jsonb
            WHERE id = 9702
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            UPDATE agent_task_run
            SET status = 'succeeded', finished_at = CURRENT_TIMESTAMP
            WHERE id = 9702
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            UPDATE agent_run_step
            SET status = 'succeeded', finished_at = CURRENT_TIMESTAMP
            WHERE id = 9703
            """));

        executeSql("""
            UPDATE agent_task_run
            SET status = 'running', worker_id = 'worker-1', started_at = CURRENT_TIMESTAMP
            WHERE id = 9702;
            UPDATE agent_run_step
            SET status = 'running', started_at = CURRENT_TIMESTAMP
            WHERE id = 9703;
            UPDATE agent_run_step
            SET status = 'succeeded', finished_at = CURRENT_TIMESTAMP
            WHERE id = 9703;
            UPDATE agent_task_run
            SET status = 'succeeded', finished_at = CURRENT_TIMESTAMP,
                worker_id = NULL, lease_until = NULL
            WHERE id = 9702;
            """);

        assertThrows(SQLException.class, () -> executeSql("""
            UPDATE agent_task_run SET status = 'running' WHERE id = 9702
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            DELETE FROM agent_task_run WHERE id = 9702
            """));
    }

    @Test
    void databaseProtectsApprovalRecoverySnapshotAndCommittedDecision() throws Exception {
        executeSql("""
            INSERT INTO agent_task
                (id, task_key, title, objective, status, owner_id, create_by)
            VALUES (9800, 'T-approval-integrity', 'Approval integrity',
                    'Protect pending side effects', 'blocked', 101, 101);

            INSERT INTO agent_task_version
                (id, task_id, version_no, title, objective, context_snapshot_json,
                 resource_snapshot_json, acceptance_snapshot_json, input_snapshot_json,
                 content_hash, created_by)
            VALUES
                (9801, 9800, 1, 'Approval integrity', 'Protect pending side effects', '{}'::jsonb,
                 '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, repeat('a', 64), 101);

            INSERT INTO agent_task_run
                (id, task_id, task_version_id, trace_id, status, attempt_no,
                 authorization_snapshot_json, runtime_snapshot_json, budget_snapshot_json,
                 usage_json, created_by)
            VALUES
                (9802, 9800, 9801, repeat('b', 64), 'waiting_approval', 1,
                 '{}'::jsonb, '{"runId":9802}'::jsonb, '{}'::jsonb, '{}'::jsonb, 101);

            INSERT INTO agent_run_step
                (id, run_id, step_key, step_type, sequence_no, status, input_json)
            VALUES (9803, 9802, 'agent-1', 'agent', 1, 'waiting', '{}'::jsonb);

            INSERT INTO agent_approval_request
                (id, task_id, run_id, step_id, risk_level, action_summary, status,
                 requested_by, expires_at, request_event_id, reply_id, pending_actions_json)
            VALUES
                (9804, 9800, 9802, 9803, 'R3', 'send external data', 'pending', 101,
                 CURRENT_TIMESTAMP + INTERVAL '1 hour', 'approval-event-1', 'reply-1',
                 '[{"id":"call-1","name":"send","input":{"value":"redacted"}}]'::jsonb);
            """);

        assertThrows(SQLException.class, () -> executeSql("""
            UPDATE agent_approval_request
            SET pending_actions_json = '[{"id":"forged","name":"delete"}]'::jsonb
            WHERE id = 9804
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            UPDATE agent_approval_request SET reply_id = 'forged-reply' WHERE id = 9804
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            UPDATE agent_approval_request SET status = 'approved', decided_at = CURRENT_TIMESTAMP
            WHERE id = 9804
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            DELETE FROM agent_approval_request WHERE id = 9804
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            INSERT INTO agent_approval_request
                (id, task_id, run_id, step_id, risk_level, action_summary, status,
                 request_event_id, reply_id, pending_actions_json)
            VALUES
                (9805, 9800, 9802, 9803, 'R3', 'duplicate', 'pending',
                 'approval-event-2', 'reply-1', '[]'::jsonb)
            """));

        executeSql("""
            UPDATE agent_approval_request
            SET status = 'approved', reviewer_id = 701, review_comment = 'approved once',
                decision_metadata_json = '{"reviewerId":701,"decision":"approved"}'::jsonb,
                decision_key_hash = repeat('c', 64), decided_at = CURRENT_TIMESTAMP
            WHERE id = 9804
            """);

        assertThrows(SQLException.class, () -> executeSql("""
            UPDATE agent_approval_request SET review_comment = 'rewritten' WHERE id = 9804
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            UPDATE agent_approval_request
            SET status = 'rejected', decided_at = CURRENT_TIMESTAMP
            WHERE id = 9804
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            UPDATE agent_task_run
            SET status = 'succeeded', finished_at = CURRENT_TIMESTAMP
            WHERE id = 9802
            """));

        executeSql("""
            UPDATE agent_task_run
            SET status = 'running', worker_id = 'worker-approved',
                lease_until = CURRENT_TIMESTAMP + INTERVAL '30 minutes'
            WHERE id = 9802
            """);
    }

    @Test
    void concurrentReviewersCanCommitOnlyOneApprovalDecision() throws Exception {
        executeSql("""
            INSERT INTO agent_approval_request
                (id, task_id, run_id, step_id, risk_level, action_summary, status,
                 requested_by, expires_at, request_event_id, reply_id, pending_actions_json)
            VALUES
                (9900, 9901, 9902, 9903, 'R3', 'single winner', 'pending', 101,
                 CURRENT_TIMESTAMP + INTERVAL '1 hour', 'approval-race-event', 'race-reply',
                 '[{"id":"race-call","name":"external_write","input":{}}]'::jsonb)
            """);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> approved = executor.submit(
                () -> decideApprovalAfter(start, 9900L, 701L, "approved", "a".repeat(64))
            );
            Future<Integer> rejected = executor.submit(
                () -> decideApprovalAfter(start, 9900L, 702L, "rejected", "b".repeat(64))
            );
            start.countDown();
            assertEquals(Set.of(0, 1), Set.of(approved.get(), rejected.get()));
        }

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("""
                SELECT status, reviewer_id, decision_key_hash
                FROM agent_approval_request WHERE id = 9900
                """)) {
                assertTrue(result.next());
                assertTrue(Set.of("approved", "rejected").contains(result.getString(1)));
                assertTrue(Set.of(701L, 702L).contains(result.getLong(2)));
                assertEquals(64, result.getString(3).length());
            }
        }
    }

    @Test
    void databaseProtectsImmutableArtifactVersionsAndAcceptanceDecisions() throws Exception {
        executeSql("""
            INSERT INTO agent_artifact
                (id, task_id, run_id, artifact_type, name, version_no, storage_type,
                 storage_ref, content_hash, sensitive_level, visibility, status,
                 metadata_json, created_by)
            VALUES
                (9950, 9951, 9952, 'document', 'report.pdf', 1, 'local',
                 'tasks/9951/report.pdf', repeat('a', 64), 'internal', 'inherit',
                 'available', '{"source":"runtime"}'::jsonb, 101);

            INSERT INTO agent_acceptance_record
                (id, task_id, run_id, artifact_ids_json, acceptance_type, result,
                 rule_result_json, comment, reviewer_id, rework_no,
                 idempotency_key_hash, request_hash)
            VALUES
                (9960, 9951, 9952, '[9950]'::jsonb, 'human', 'passed',
                 '{}'::jsonb, 'accepted', 101, 0, repeat('b', 64), repeat('c', 64));
            """);

        assertThrows(SQLException.class, () -> executeSql("""
            UPDATE agent_artifact SET storage_ref = 'tampered' WHERE id = 9950
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            DELETE FROM agent_artifact WHERE id = 9950
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            UPDATE agent_artifact SET status = 'created' WHERE id = 9950
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            INSERT INTO agent_artifact
                (id, task_id, run_id, artifact_type, name, version_no, storage_type,
                 storage_ref, content_hash, status)
            VALUES
                (9953, 9951, 9952, 'document', 'report.pdf', 1, 'local',
                 'tasks/9951/duplicate.pdf', repeat('d', 64), 'available')
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            UPDATE agent_acceptance_record SET comment = 'rewritten' WHERE id = 9960
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            DELETE FROM agent_acceptance_record WHERE id = 9960
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            INSERT INTO agent_acceptance_record
                (id, task_id, run_id, artifact_ids_json, acceptance_type, result,
                 reviewer_id, idempotency_key_hash, request_hash)
            VALUES
                (9961, 9951, 9952, '[9950]'::jsonb, 'human', 'rework',
                 102, repeat('d', 64), repeat('e', 64))
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            INSERT INTO agent_acceptance_record
                (id, task_id, run_id, artifact_ids_json, acceptance_type, result,
                 reviewer_id, idempotency_key_hash, request_hash)
            VALUES
                (9962, 9951, 9953, '[9950]'::jsonb, 'human', 'pending',
                 NULL, repeat('b', 64), NULL)
            """));
        assertThrows(SQLException.class, () -> executeSql("""
            INSERT INTO agent_acceptance_record
                (id, task_id, run_id, artifact_ids_json, acceptance_type, result,
                 reviewer_id, idempotency_key_hash, request_hash)
            VALUES
                (9963, 9951, 9953, '[]'::jsonb, 'human', 'passed',
                 101, repeat('f', 64), repeat('0', 64))
            """));
    }

    private int insertTaskAfter(CountDownLatch start, AgentTask task) throws Exception {
        start.await();
        try (SqlSession session = sessionFactory.openSession(false)) {
            int inserted = session.getMapper(AgentTaskMapper.class).insertIfAbsent(task);
            session.commit();
            return inserted;
        }
    }

    private int decideApprovalAfter(
        CountDownLatch start,
        Long approvalId,
        Long reviewerId,
        String status,
        String decisionKeyHash
    ) throws Exception {
        start.await();
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("""
            UPDATE agent_approval_request
            SET status = ?, reviewer_id = ?,
                decision_metadata_json = jsonb_build_object('reviewerId', ?, 'decision', ?),
                decision_key_hash = ?, decided_at = CURRENT_TIMESTAMP
            WHERE id = ? AND status = 'pending'
              AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
            """)) {
            statement.setString(1, status);
            statement.setLong(2, reviewerId);
            statement.setLong(3, reviewerId);
            statement.setString(4, status);
            statement.setString(5, decisionKeyHash);
            statement.setLong(6, approvalId);
            return statement.executeUpdate();
        }
    }

    private AgentTask task(Long id, String key, Long conversationId, String contextJson) {
        AgentTask task = new AgentTask();
        task.setId(id);
        task.setTaskKey(key);
        task.setTitle("Race task");
        task.setObjective("Prove idempotent conversion");
        task.setSourceConversationId(conversationId);
        task.setContextSnapshotJson(contextJson);
        task.setVisibility("enterprise_shared");
        task.setCategory("general");
        task.setOrchestrationMode("single_agent");
        task.setLifecycleLevel("L1_short_task");
        task.setRiskLevel("R1");
        task.setStatus("ready");
        task.setImportance(0);
        task.setUrgency(0);
        task.setQueuePriority(0);
        task.setOwnerId(101L);
        task.setOwnerPrincipalType("human");
        task.setAcceptanceMode("human");
        task.setCreateBy(101L);
        task.setCreateTime(LocalDateTime.now());
        task.setDelFlag("0");
        return task;
    }

    private AgentModel model(Long id, String key, String displayName) {
        AgentModel model = new AgentModel();
        model.setId(id);
        model.setModelKey(key);
        model.setDisplayName(displayName);
        model.setProviderType("openai");
        model.setModelName("gpt-integration");
        model.setModelType("chat");
        model.setEndpointUrl("https://api.openai.com/v1");
        model.setCredentialRef("env:INTEGRATION_MODEL_KEY");
        model.setContextSize(32000);
        model.setMaxOutputTokens(4096);
        model.setReasoningConfigJson("{\"temperature\":0.25}");
        model.setCapabilityJson("{\"streaming\":true}");
        model.setStatus("active");
        model.setCreateBy(1L);
        model.setCreateTime(LocalDateTime.now());
        model.setDelFlag("0");
        model.setExtraJson("{}");
        return model;
    }

    private RuntimeEvent runtimeEvent(String sourceEventId, String summary) {
        return new RuntimeEvent(
            sourceEventId,
            new RuntimeExecutionKey("execution-1", "trace-events"),
            700L,
            null,
            null,
            RuntimeEventType.TEXT_DELTA,
            RuntimeEventStatus.SUCCESS,
            Instant.parse("2026-08-14T04:00:00Z"),
            summary,
            Map.of("delta", summary),
            RuntimeSensitiveLevel.PUBLIC
        );
    }

    private Set<Long> ids(List<AgentTask> tasks) {
        Set<Long> ids = new HashSet<>();
        tasks.forEach(task -> ids.add(task.getId()));
        return ids;
    }

    private void executeSql(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
