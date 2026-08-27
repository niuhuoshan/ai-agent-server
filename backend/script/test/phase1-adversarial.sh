#!/bin/sh

set -eu

BACKEND_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)

: "${NHS_TEST_JDBC_URL:?NHS_TEST_JDBC_URL is required}"
: "${NHS_TEST_DB_USER:?NHS_TEST_DB_USER is required}"
: "${NHS_TEST_DB_PASSWORD:?NHS_TEST_DB_PASSWORD is required}"
: "${NHS_SANDBOX_TEST_IMAGE:?NHS_SANDBOX_TEST_IMAGE must be a pinned image reference}"

case "$NHS_SANDBOX_TEST_IMAGE" in
  *@sha256:*) ;;
  *) printf '%s\n' 'NHS_SANDBOX_TEST_IMAGE must use an immutable @sha256 digest' >&2; exit 2 ;;
esac

TESTS='ApiDecryptConfigurationTest,
DefaultAuthorizationServiceTest,
DefaultTaskVisibilityServiceTest,
DatabasePermissionSnapshotResolverTest,
PermissionAdministrationServiceTest,
ApiCredentialAuthenticatorTest,
MachineIngressPostgresIntegrationTest,
PlatformRuntimeToolProviderTest,
ToolArgumentValidatorTest,
ConnectorEndpointPolicyTest,
ApiToolExecutorTest,
ReadOnlySqlValidatorTest,
PostgresDataControlIntegrationTest,
ExecutionEventSseServiceTest,
ExecutionEventQueryServiceTest,
ApprovalApplicationServiceTest,
ApprovalRequestRecorderTest,
SandboxRequestAuthenticatorTest,
SandboxPostgresIntegrationTest,
ContainerCommandBuilderTest,
ContainerExecutorIntegrationTest,
AgentApplicationServiceTest,
AgentVersionContentHasherTest,
DatabaseAgentRunRequestResolverTest,
ConversationApplicationServiceTest,
KnowledgeRetrievalServiceTest,
KnowledgeParseJobWorkerTest,
LocalKnowledgeFileStorageTest,
MemoryScopeAuthorizationServiceTest,
WorkflowGraphValidatorTest,
WorkflowPostgresIntegrationTest,
AcceptanceApplicationServiceTest,
HarnessAgentInvocationExternalResumeTest,
PostgresAgentScopeStateStoreIntegrationTest,
NhsMigrationPostgresIntegrationTest'
TESTS=$(printf '%s' "$TESTS" | tr -d '[:space:]')

cd "$BACKEND_DIR"
exec ./mvnw \
  -Dmaven.test.skip=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest="$TESTS" \
  test
