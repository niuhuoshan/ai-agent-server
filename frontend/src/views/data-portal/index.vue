<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NAlert, NButton, NEmpty, NInput, NSpin, NTag } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  clearNhsDatasetQuestionClick,
  fetchNhsDatasetMenu,
  fetchPortalDataPortalHome,
  recordNhsDatasetQuestionClick,
  recommendNhsDatasetTableQuestions,
  refreshNhsDatasetGroupQuestions,
  type NhsDatasetGroup,
  type NhsDatasetMenu,
  type NhsDatasetQuestion,
  type PortalDataPortalActivity,
  type PortalDataPortalHome,
  type PortalDataPortalReport
} from '@/service/api';
import {
  buildDataPortalCatalog,
  datasetGroupKey,
  datasetGroupTables,
  datasetIdFromGroup,
  filterDataPortalCatalog,
  filterDataPortalReports,
  portalQuestionRoute,
  questionClickCount,
  resolveDataPortalSection,
  type DataPortalCatalogDataset,
  type DataPortalCatalogTable,
  type DataPortalReportFilter,
  type DataPortalSection
} from './data-portal';

const route = useRoute();
const router = useRouter();

const sections: Array<{ value: DataPortalSection; label: string; icon: string }> = [
  { value: 'overview', label: '数据首页', icon: 'lucide:layout-dashboard' },
  { value: 'reports', label: '我的报表', icon: 'lucide:file-chart-column' },
  { value: 'scenarios', label: '推荐场景', icon: 'lucide:sparkles' },
  { value: 'catalog', label: '数据目录', icon: 'lucide:database' }
];

const activeSection = ref<DataPortalSection>(resolveDataPortalSection(route.query?.section));
const home = ref<PortalDataPortalHome | null>(null);
const menu = ref<NhsDatasetMenu | null>(null);
const loading = ref(false);
const loadError = ref('');
const catalogKeyword = ref('');
const reportKeyword = ref('');
const reportFilter = ref<DataPortalReportFilter>('all');
const refreshedQuestions = ref<Record<string, NhsDatasetQuestion[]>>({});
const refreshingGroups = ref<Record<string, boolean>>({});
const groupErrors = ref<Record<string, string>>({});
const tableRecommendations = ref<Record<string, NhsDatasetQuestion[]>>({});
const recommendingTables = ref<Record<string, boolean>>({});
const tableErrors = ref<Record<string, string>>({});

const groups = computed(() => menu.value?.groups || []);
const catalog = computed(() => buildDataPortalCatalog(menu.value));
const visibleCatalog = computed(() => filterDataPortalCatalog(catalog.value, catalogKeyword.value));
const reports = computed(() => home.value?.report_summary.items || []);
const visibleReports = computed(() => filterDataPortalReports(reports.value, reportFilter.value, reportKeyword.value));
const reportCounts = computed(() => ({
  all: reports.value.length,
  subscribed: reports.value.filter(item => Boolean(item.subscription_status)).length,
  shared: reports.value.filter(item => !item.is_owner).length,
  recent: reports.value.filter(item => Boolean(item.last_run_at)).length,
  failed: reports.value.filter(item => Boolean(item.last_error)).length
}));
const tableCount = computed(() => catalog.value.reduce((total, item) => total + item.tables.length, 0));
const columnCount = computed(() => catalog.value.reduce((total, item) => total + item.columnCount, 0));
const generatedAt = computed(() => menu.value?.generated_at || home.value?.generated_at || null);

const reportFilters: Array<{ value: DataPortalReportFilter; label: string }> = [
  { value: 'all', label: '全部' },
  { value: 'subscribed', label: '已订阅' },
  { value: 'shared', label: '企业共享' },
  { value: 'recent', label: '最近运行' },
  { value: 'failed', label: '有失败' }
];

function requestError(error: unknown, fallback: string) {
  if (!error || typeof error !== 'object') return fallback;
  const value = error as { response?: { data?: { msg?: string; message?: string } }; message?: string };
  return value.response?.data?.msg || value.response?.data?.message || value.message || fallback;
}

function reportTitle(report: PortalDataPortalReport) {
  return report.title || '未命名报表';
}

function formatTime(value?: string | null) {
  if (!value) return '暂无记录';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false });
}

function groupQuestions(group: NhsDatasetGroup) {
  return (refreshedQuestions.value[datasetGroupKey(group)] || group.questions || []).slice(0, 5);
}

function groupFollowups(group: NhsDatasetGroup) {
  return (group.followups || []).slice(0, 3);
}

function reportStatus(report: PortalDataPortalReport) {
  if (report.last_error) return '最近运行失败';
  if (report.subscription_status) return '已订阅';
  if (report.is_owner) return '我创建的';
  return '企业共享';
}

function reportStatusType(report: PortalDataPortalReport) {
  if (report.last_error) return 'error';
  if (report.subscription_status) return 'success';
  return 'default';
}

async function load(forceRefresh = false) {
  loading.value = true;
  loadError.value = '';
  const [homeResult, menuResult] = await Promise.allSettled([
    fetchPortalDataPortalHome(),
    fetchNhsDatasetMenu(forceRefresh)
  ]);

  if (homeResult.status === 'fulfilled' && !homeResult.value.error && homeResult.value.data) {
    home.value = homeResult.value.data;
  } else if (!home.value) {
    loadError.value = requestError(
      homeResult.status === 'rejected' ? homeResult.reason : homeResult.value.error,
      '数据首页暂时无法获取'
    );
  }

  if (menuResult.status === 'fulfilled' && !menuResult.value.error && menuResult.value.data) {
    menu.value = menuResult.value.data;
  } else if (!menu.value) {
    loadError.value ||= requestError(
      menuResult.status === 'rejected' ? menuResult.reason : menuResult.value.error,
      '推荐场景和数据目录暂时无法获取'
    );
  }
  loading.value = false;
}

function setSection(section: DataPortalSection) {
  activeSection.value = section;
  void router.replace({
    query: {
      ...route.query,
      section: section === 'overview' ? undefined : section
    }
  });
}

function setReportFilter(filter: DataPortalReportFilter) {
  reportFilter.value = filter;
  void router.replace({ query: { ...route.query, filter: filter === 'all' ? undefined : filter } });
}

function groupForCatalogDataset(dataset: DataPortalCatalogDataset) {
  return (
    groups.value.find(group => group.id === dataset.groupId) ||
    groups.value.find(group => {
      return (group.related_data || []).some(
        item => String(item.dataset_id ?? item.dataset ?? item.display_name ?? '') === String(dataset.id ?? dataset.key)
      );
    })
  );
}

function incrementQuestionClick(group: NhsDatasetGroup, question: NhsDatasetQuestion) {
  question.click_count = questionClickCount(question) + 1;
  const existing = group.total_click_count;
  if (typeof existing === 'number') group.total_click_count = existing + 1;
}

async function openQuestion(group: NhsDatasetGroup | undefined, question: NhsDatasetQuestion) {
  const datasetId = group ? datasetIdFromGroup(group) : undefined;
  try {
    const result = await recordNhsDatasetQuestionClick({
      query: question.query,
      label: question.label,
      group_id: group?.id,
      dataset_menu_hash: menu.value?.dataset_menu_hash
    });
    if (!result.error && group) incrementQuestionClick(group, question);
    if (result.error) window.$message?.warning?.(requestError(result.error, '问题已打开，但点击记录失败'));
  } catch (error) {
    window.$message?.warning?.(requestError(error, '问题已打开，但点击记录失败'));
  }
  await router.push(portalQuestionRoute(question, datasetId));
}

async function clearQuestionClick(question: NhsDatasetQuestion) {
  try {
    const result = await clearNhsDatasetQuestionClick(question.query);
    if (!result.error) {
      question.click_count = 0;
      question.last_clicked_at = undefined;
    } else {
      window.$message?.error?.(requestError(result.error, '清除点击记录失败'));
    }
  } catch (error) {
    window.$message?.error?.(requestError(error, '清除点击记录失败'));
  }
}

async function refreshGroup(group: NhsDatasetGroup) {
  const key = datasetGroupKey(group);
  const tables = datasetGroupTables(group);
  if (!tables.length) {
    groupErrors.value = { ...groupErrors.value, [key]: '当前场景没有可用的数据表，暂时无法换一批' };
    return;
  }
  refreshingGroups.value = { ...refreshingGroups.value, [key]: true };
  groupErrors.value = { ...groupErrors.value, [key]: '' };
  try {
    const result = await refreshNhsDatasetGroupQuestions({
      group_title: group.title,
      tables,
      dataset_menu_hash: menu.value?.dataset_menu_hash,
      group_id: group.id,
      exclude_questions: groupQuestions(group).map(question => ({ label: question.label, query: question.query })),
      purpose: 'questions'
    });
    if (result.error) {
      groupErrors.value = { ...groupErrors.value, [key]: requestError(result.error, '换一批失败，请稍后重试') };
    } else if (!result.data?.questions?.length) {
      groupErrors.value = { ...groupErrors.value, [key]: result.data?.refresh_disabled_reason || '暂无更多不同问题' };
    } else {
      refreshedQuestions.value = { ...refreshedQuestions.value, [key]: result.data.questions };
    }
  } catch (error) {
    groupErrors.value = { ...groupErrors.value, [key]: requestError(error, '换一批失败，请稍后重试') };
  } finally {
    refreshingGroups.value = { ...refreshingGroups.value, [key]: false };
  }
}

function tableKey(dataset: DataPortalCatalogDataset, table: DataPortalCatalogTable) {
  return `${dataset.key}::${table.name}`;
}

function tableQuestion(
  dataset: DataPortalCatalogDataset,
  table: DataPortalCatalogTable,
  purpose: 'structure' | 'detail'
) {
  const physical = table.physicalName ? `（物理表：${table.physicalName}）` : '';
  return {
    label: purpose === 'structure' ? '结构说明' : '查询明细',
    query:
      purpose === 'structure'
        ? `说明${dataset.name}数据集下数据表“${table.name}”${physical}的字段结构和分析口径`
        : `查询数据表“${table.name}”${physical}最近10条明细数据`
  } satisfies NhsDatasetQuestion;
}

async function recommendTable(dataset: DataPortalCatalogDataset, table: DataPortalCatalogTable) {
  const key = tableKey(dataset, table);
  const group = groupForCatalogDataset(dataset);
  recommendingTables.value = { ...recommendingTables.value, [key]: true };
  tableErrors.value = { ...tableErrors.value, [key]: '' };
  try {
    const result = await recommendNhsDatasetTableQuestions({
      table: table.name,
      physical_table_name: table.physicalName || undefined,
      dataset_name: dataset.name,
      columns: table.columns.map(column => ({
        name: column.name,
        term: column.term,
        type: column.type,
        description: column.description
      }))
    });
    if (result.error) {
      tableErrors.value = { ...tableErrors.value, [key]: requestError(result.error, '推荐提问生成失败') };
    } else if (!result.data?.questions?.length) {
      tableErrors.value = { ...tableErrors.value, [key]: result.data?.refresh_disabled_reason || '暂无推荐提问' };
    } else {
      tableRecommendations.value = { ...tableRecommendations.value, [key]: result.data.questions };
      if (group && dataset.id) {
        // Keep the route context available for questions returned by the table endpoint.
        group.related_data = (group.related_data || []).map(item => ({
          ...item,
          dataset_id: item.dataset_id ?? dataset.id
        }));
      }
    }
  } catch (error) {
    tableErrors.value = { ...tableErrors.value, [key]: requestError(error, '推荐提问生成失败') };
  } finally {
    recommendingTables.value = { ...recommendingTables.value, [key]: false };
  }
}

async function openTableQuestion(
  dataset: DataPortalCatalogDataset,
  table: DataPortalCatalogTable,
  question: NhsDatasetQuestion
) {
  await openQuestion(groupForCatalogDataset(dataset), question);
}

async function openActivity(activity: PortalDataPortalActivity) {
  if (activity.conversation_id !== undefined) {
    await router.push({
      path: '/chatbi',
      query: { conversation_id: String(activity.conversation_id), source: 'data_portal' }
    });
    return;
  }
  if (activity.report_id !== undefined) {
    await router.push({ path: '/saved-reports', query: { report_id: String(activity.report_id) } });
  }
}

async function openReport(report: PortalDataPortalReport) {
  await router.push({ path: '/saved-reports', query: { report_id: String(report.id) } });
}

watch(
  () => route.query.section,
  value => {
    activeSection.value = resolveDataPortalSection(value);
  }
);

watch(
  () => route.query.filter,
  value => {
    const accepted = ['all', 'subscribed', 'shared', 'recent', 'failed'] as const;
    reportFilter.value =
      typeof value === 'string' && accepted.includes(value as (typeof accepted)[number])
        ? (value as DataPortalReportFilter)
        : 'all';
  }
);

onMounted(() => void load());
</script>

<template>
  <div class="data-portal-page">
    <header class="portal-header">
      <div class="portal-title-wrap">
        <div class="portal-mark"><SvgIcon icon="lucide:chart-no-axes-combined" /></div>
        <div>
          <div class="portal-kicker">PRIVATE DATA WORKSPACE</div>
          <h1>我的数据门户</h1>
          <p>在授权数据范围内，发现报表、场景和可查询字段。</p>
        </div>
      </div>
      <div class="portal-header-actions">
        <NTag v-if="menu?.has_datasets" size="small" type="success" :bordered="false">
          {{ menu.dataset_count }} 个授权数据集
        </NTag>
        <NTag v-if="menu?.is_fallback" size="small" type="warning" :bordered="false">基础推荐</NTag>
        <NButton data-testid="portal-refresh" quaternary :loading="loading" title="刷新数据门户" @click="load(true)">
          <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
          刷新
        </NButton>
      </div>
    </header>

    <nav class="portal-tabs app-tab-list app-tab-list--line" role="tablist" aria-label="数据门户视图">
      <button
        v-for="section in sections"
        :key="section.value"
        :data-testid="`portal-tab-${section.value}`"
        type="button"
        role="tab"
        :aria-selected="activeSection === section.value"
        class="app-tab"
        :class="{ active: activeSection === section.value }"
        @click="setSection(section.value)"
      >
        <SvgIcon :icon="section.icon" />
        <span>{{ section.label }}</span>
      </button>
    </nav>

    <NAlert v-if="loadError" class="portal-alert" type="warning" :show-icon="true">
      {{ loadError }}，已保留最近一次成功内容。
    </NAlert>

    <NSpin :show="loading && !home && !menu">
      <template v-if="activeSection === 'overview'">
        <section class="metric-grid" aria-label="数据门户概览">
          <article class="metric-item">
            <span>已订阅报表</span>
            <strong>{{ home?.report_summary.subscribed || 0 }}</strong>
            <small>当前账号的自动更新报表</small>
          </article>
          <article class="metric-item">
            <span>企业共享</span>
            <strong>{{ home?.report_summary.shared || 0 }}</strong>
            <small>团队成员共享给你的报表</small>
          </article>
          <article class="metric-item">
            <span>授权数据表</span>
            <strong>{{ tableCount }}</strong>
            <small>可用于自然语言分析</small>
          </article>
          <article class="metric-item">
            <span>近期分析</span>
            <strong>{{ home?.recent_analysis.length || 0 }}</strong>
            <small>个人会话和报表运行</small>
          </article>
        </section>

        <section class="overview-columns">
          <article class="surface-panel">
            <div class="panel-heading">
              <div>
                <h2>需要关注</h2>
                <p>只展示当前账号可见的运行状态。</p>
              </div>
              <SvgIcon icon="lucide:triangle-alert" />
            </div>
            <div v-if="home?.attention" class="attention-list">
              <div>
                <span>今日失败运行</span>
                <strong :class="{ danger: home.attention.failed_runs_today > 0 }">
                  {{ home.attention.failed_runs_today }}
                </strong>
              </div>
              <div>
                <span>活跃订阅</span>
                <strong>{{ home.attention.active_subscriptions }}</strong>
              </div>
              <div>
                <span>今日完成订阅</span>
                <strong>{{ home.attention.completed_subscriptions_today }}</strong>
              </div>
            </div>
            <NEmpty v-else description="暂无关注事项" />
          </article>
          <article class="surface-panel">
            <div class="panel-heading">
              <div>
                <h2>最近分析</h2>
                <p>继续处理最近打开的会话或报表。</p>
              </div>
              <SvgIcon icon="lucide:clock-3" />
            </div>
            <div v-if="home?.recent_analysis.length" class="activity-list">
              <button
                v-for="activity in home.recent_analysis.slice(0, 5)"
                :key="`${activity.type}-${activity.id}`"
                type="button"
                @click="openActivity(activity)"
              >
                <span class="activity-status" :class="activity.status === 'failed' ? 'failed' : 'ok'">
                  <SvgIcon :icon="activity.status === 'failed' ? 'lucide:circle-x' : 'lucide:circle-check'" />
                </span>
                <span class="activity-copy">
                  <strong>{{ activity.title }}</strong>
                  <small>{{ activity.subtitle || activity.type }} · {{ formatTime(activity.occurred_at) }}</small>
                </span>
                <SvgIcon icon="lucide:arrow-up-right" />
              </button>
            </div>
            <NEmpty v-else description="暂无最近分析" />
          </article>
        </section>

        <section class="surface-panel preview-panel">
          <div class="panel-heading">
            <div>
              <h2>推荐场景</h2>
              <p>从授权数据开始，直接进入 ChatBI。</p>
            </div>
            <button type="button" class="text-action" @click="setSection('scenarios')">
              查看全部
              <SvgIcon icon="lucide:arrow-right" />
            </button>
          </div>
          <div v-if="groups.length" class="preview-grid">
            <div v-for="group in groups.slice(0, 2)" :key="datasetGroupKey(group)" class="preview-item">
              <strong>{{ group.title }}</strong>
              <span>{{ group.summary }}</span>
              <button
                v-for="question in groupQuestions(group).slice(0, 2)"
                :key="question.query"
                data-testid="portal-question"
                type="button"
                @click="openQuestion(group, question)"
              >
                {{ question.label }}
                <SvgIcon icon="lucide:arrow-up-right" />
              </button>
            </div>
          </div>
          <NEmpty v-else description="暂无推荐场景" />
        </section>

        <section class="surface-panel preview-panel">
          <div class="panel-heading">
            <div>
              <h2>我的报表</h2>
              <p>查看最近运行的企业共享报表。</p>
            </div>
            <button type="button" class="text-action" @click="setSection('reports')">
              查看全部
              <SvgIcon icon="lucide:arrow-right" />
            </button>
          </div>
          <div v-if="reports.length" class="report-preview-list">
            <button
              v-for="report in reports.slice(0, 4)"
              :key="String(report.id)"
              type="button"
              @click="openReport(report)"
            >
              <span>
                <strong>{{ reportTitle(report) }}</strong>
                <small>{{ reportStatus(report) }} · {{ formatTime(report.last_run_at) }}</small>
              </span>
              <SvgIcon icon="lucide:arrow-up-right" />
            </button>
          </div>
          <NEmpty v-else description="暂无报表" />
        </section>
      </template>

      <section v-else-if="activeSection === 'reports'" class="section-stack">
        <div class="section-heading">
          <div>
            <div class="portal-kicker">REPORTS</div>
            <h2>我的报表</h2>
            <p>报表权限与运行结果沿用当前账号的数据范围。</p>
          </div>
          <NTag size="small" :bordered="false">{{ visibleReports.length }} / {{ reports.length }}</NTag>
        </div>
        <div class="toolbar surface-panel">
          <div class="filter-tabs app-tab-list app-tab-list--segment app-tab-list--compact" role="tablist" aria-label="报表筛选">
            <button
              v-for="filter in reportFilters"
              :key="filter.value"
              type="button"
              class="app-tab"
              :class="{ active: reportFilter === filter.value }"
              @click="setReportFilter(filter.value)"
            >
              {{ filter.label }}
              <span>{{ reportCounts[filter.value] }}</span>
            </button>
          </div>
          <NInput
            v-model:value="reportKeyword"
            data-testid="report-search"
            clearable
            placeholder="搜索报表名称、标签或负责人"
          />
        </div>
        <div v-if="visibleReports.length" class="report-list">
          <article v-for="report in visibleReports" :key="String(report.id)" class="report-row">
            <div class="report-icon"><SvgIcon icon="lucide:file-chart-column" /></div>
            <div class="report-copy">
              <div class="report-name">
                <h3>{{ reportTitle(report) }}</h3>
                <NTag size="small" :type="reportStatusType(report)" :bordered="false">{{ reportStatus(report) }}</NTag>
              </div>
              <p>{{ report.description || '暂无报表说明' }}</p>
              <div class="report-meta">
                <span v-if="report.owner_name">负责人 {{ report.owner_name }}</span>
                <span>最近运行 {{ formatTime(report.last_run_at) }}</span>
                <span v-if="report.subscription_next_run_at">
                  下次运行 {{ formatTime(report.subscription_next_run_at) }}
                </span>
              </div>
              <div v-if="report.tags?.length" class="tag-line">
                <NTag v-for="tag in report.tags" :key="tag" size="small" :bordered="false">{{ tag }}</NTag>
              </div>
              <p v-if="report.last_error" class="error-note">{{ report.last_error }}</p>
            </div>
            <NButton quaternary title="打开报表" @click="openReport(report)">
              <template #icon><SvgIcon icon="lucide:arrow-up-right" /></template>
            </NButton>
          </article>
        </div>
        <NEmpty v-else description="没有符合条件的报表" />
      </section>

      <section v-else-if="activeSection === 'scenarios'" class="section-stack">
        <div class="section-heading">
          <div>
            <div class="portal-kicker">SCENARIOS</div>
            <h2>推荐场景</h2>
            <p>场景由当前授权数据集和字段生成，点击问题会记录偏好并带入 ChatBI。</p>
          </div>
          <div class="heading-status">
            <NTag v-if="menu?.from_cache" size="small" :bordered="false">来自缓存</NTag>
            <NTag v-if="menu?.llm_generation_failed" size="small" type="warning" :bordered="false">
              模型不可用，使用基础推荐
            </NTag>
          </div>
        </div>
        <div v-if="groups.length" class="scenario-grid">
          <article v-for="group in groups" :key="datasetGroupKey(group)" class="scenario-row">
            <div class="scenario-heading">
              <div>
                <div class="scenario-title">
                  <h3>{{ group.title }}</h3>
                  <NTag v-for="tag in (group.tags || []).slice(0, 4)" :key="tag" size="small" :bordered="false">
                    {{ tag }}
                  </NTag>
                </div>
                <p>{{ group.summary }}</p>
              </div>
              <NButton
                data-testid="group-refresh"
                quaternary
                :loading="Boolean(refreshingGroups[datasetGroupKey(group)])"
                title="换一批问题"
                @click="refreshGroup(group)"
              >
                <template #icon><SvgIcon icon="lucide:rotate-cw" /></template>
                换一批
              </NButton>
            </div>
            <div class="question-grid">
              <div v-for="question in groupQuestions(group)" :key="question.query" class="question-row">
                <button
                  data-testid="portal-question"
                  type="button"
                  class="question-main"
                  @click="openQuestion(group, question)"
                >
                  <span>{{ question.label }}</span>
                  <small>{{ question.query }}</small>
                </button>
                <span
                  v-if="questionClickCount(question)"
                  class="click-count"
                  :title="`已点击 ${questionClickCount(question)} 次`"
                >
                  <SvgIcon icon="lucide:mouse-pointer-click" />
                  {{ questionClickCount(question) }}
                </span>
                <button
                  v-if="questionClickCount(question)"
                  type="button"
                  class="icon-action"
                  title="清除点击记录"
                  @click="clearQuestionClick(question)"
                >
                  <SvgIcon icon="lucide:eraser" />
                </button>
              </div>
            </div>
            <div v-if="groupFollowups(group).length" class="followup-line">
              <span>继续追问</span>
              <button
                v-for="question in groupFollowups(group)"
                :key="question.query"
                data-testid="portal-followup"
                type="button"
                @click="openQuestion(group, question)"
              >
                {{ question.label }}
              </button>
            </div>
            <p v-if="groupErrors[datasetGroupKey(group)]" class="error-note">
              {{ groupErrors[datasetGroupKey(group)] }}
            </p>
          </article>
        </div>
        <NEmpty v-else description="当前账号暂无可查询的数据集" />
      </section>

      <section v-else class="section-stack">
        <div class="section-heading">
          <div>
            <div class="portal-kicker">CATALOG</div>
            <h2>数据目录</h2>
            <p>展开数据集、数据表和字段，查看可用的业务口径与推荐提问。</p>
          </div>
          <NTag size="small" :bordered="false">{{ visibleCatalog.length }} 个数据集</NTag>
        </div>
        <div class="catalog-toolbar surface-panel">
          <div class="catalog-counts">
            <span>
              <strong>{{ catalog.length }}</strong>
              数据集
            </span>
            <span>
              <strong>{{ tableCount }}</strong>
              数据表
            </span>
            <span>
              <strong>{{ columnCount }}</strong>
              字段
            </span>
          </div>
          <NInput
            v-model:value="catalogKeyword"
            data-testid="catalog-search"
            clearable
            placeholder="搜索数据集、表、字段或业务说明"
          />
        </div>
        <div v-if="visibleCatalog.length" class="catalog-list">
          <details
            v-for="dataset in visibleCatalog"
            :key="dataset.key"
            class="catalog-dataset"
            :open="Boolean(catalogKeyword)"
          >
            <summary>
              <span class="summary-leading">
                <SvgIcon icon="lucide:database" />
                <span>
                  <strong>{{ dataset.name }}</strong>
                  <small>{{ dataset.tables.length }} 张表 · {{ dataset.columnCount }} 个字段</small>
                </span>
              </span>
              <SvgIcon icon="lucide:chevron-down" />
            </summary>
            <div class="table-list">
              <details
                v-for="table in dataset.tables"
                :key="table.key"
                class="catalog-table"
                :open="Boolean(catalogKeyword)"
              >
                <summary>
                  <span class="summary-leading">
                    <SvgIcon icon="lucide:table-2" />
                    <span>
                      <strong>{{ table.name }}</strong>
                      <small v-if="table.physicalName">
                        {{ table.physicalName }} · {{ table.columns.length }} 个字段
                      </small>
                      <small v-if="table.description">{{ table.description }}</small>
                    </span>
                  </span>
                  <SvgIcon icon="lucide:chevron-down" />
                </summary>
                <div class="table-details">
                  <div v-if="table.columns.length" class="column-grid">
                    <div v-for="column in table.columns" :key="column.name" class="column-item">
                      <span>
                        <strong>{{ column.term || column.name }}</strong>
                        <small>{{ column.name }}</small>
                      </span>
                      <NTag size="small" :bordered="false">{{ column.type || '字段' }}</NTag>
                      <p>{{ column.description || '暂无业务说明' }}</p>
                    </div>
                  </div>
                  <NEmpty v-else description="暂无字段元数据" />
                  <div class="table-actions">
                    <NButton
                      size="small"
                      secondary
                      @click="openQuestion(groupForCatalogDataset(dataset), tableQuestion(dataset, table, 'structure'))"
                    >
                      <template #icon><SvgIcon icon="lucide:braces" /></template>
                      结构说明
                    </NButton>
                    <NButton
                      size="small"
                      secondary
                      @click="openQuestion(groupForCatalogDataset(dataset), tableQuestion(dataset, table, 'detail'))"
                    >
                      <template #icon><SvgIcon icon="lucide:list" /></template>
                      查询明细
                    </NButton>
                    <NButton
                      data-testid="table-recommend"
                      size="small"
                      type="primary"
                      secondary
                      :loading="Boolean(recommendingTables[tableKey(dataset, table)])"
                      @click="recommendTable(dataset, table)"
                    >
                      <template #icon><SvgIcon icon="lucide:sparkles" /></template>
                      推荐提问
                    </NButton>
                  </div>
                  <p v-if="tableErrors[tableKey(dataset, table)]" class="error-note">
                    {{ tableErrors[tableKey(dataset, table)] }}
                  </p>
                  <div v-if="tableRecommendations[tableKey(dataset, table)]?.length" class="recommendation-list">
                    <span class="recommendation-label">推荐提问</span>
                    <button
                      v-for="question in tableRecommendations[tableKey(dataset, table)]"
                      :key="question.query"
                      data-testid="table-question"
                      type="button"
                      @click="openTableQuestion(dataset, table, question)"
                    >
                      {{ question.label }}
                      <small>{{ question.query }}</small>
                      <SvgIcon icon="lucide:arrow-up-right" />
                    </button>
                  </div>
                </div>
              </details>
            </div>
          </details>
        </div>
        <NEmpty v-else description="没有找到匹配的数据集、数据表或字段" />
      </section>
    </NSpin>

    <footer class="portal-footer">
      <span>数据范围由管理员授权决定</span>
      <span v-if="generatedAt">目录更新于 {{ formatTime(generatedAt) }}</span>
      <button type="button" class="text-action" @click="router.push('/chatbi')">
        进入 ChatBI
        <SvgIcon icon="lucide:arrow-right" />
      </button>
    </footer>
  </div>
</template>

<style scoped>
.data-portal-page {
  min-height: 100%;
  padding: 24px;
  color: var(--n-text-color);
  background: var(--n-color);
}
.portal-header,
.portal-title-wrap,
.portal-header-actions,
.portal-tabs,
.section-heading,
.panel-heading,
.scenario-heading,
.report-name,
.report-meta,
.tag-line,
.catalog-toolbar,
.catalog-counts,
.summary-leading,
.table-actions,
.portal-footer {
  display: flex;
  align-items: center;
}
.portal-header {
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 18px;
}
.portal-title-wrap {
  gap: 12px;
  min-width: 0;
}
.portal-mark {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  color: #fff;
  background: var(--n-primary-color);
  border-radius: 8px;
}
.portal-title-wrap h1,
.section-heading h2,
.panel-heading h2 {
  margin: 2px 0 0;
  font-size: 20px;
  font-weight: 650;
}
.portal-title-wrap p,
.section-heading p,
.panel-heading p {
  margin: 4px 0 0;
  color: var(--n-text-color-3);
  font-size: 12px;
}
.portal-kicker {
  color: var(--n-primary-color);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0;
}
.portal-header-actions {
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}
.portal-tabs {
  gap: 24px;
  margin-bottom: 18px;
  overflow-x: auto;
  border-bottom: 1px solid var(--n-border-color);
}
.portal-tabs button,
.filter-tabs button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: 0;
  color: var(--n-text-color-2);
  background: transparent;
  border-radius: 6px;
  cursor: pointer;
  transition:
    background 0.16s ease,
    color 0.16s ease;
}
.portal-tabs button {
  min-height: 46px;
  padding: 12px 4px 13px;
  border-bottom: 2px solid transparent;
  border-radius: 0;
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
}
.portal-tabs button:hover,
.portal-tabs button.active,
.filter-tabs button:hover,
.filter-tabs button.active {
  color: var(--n-primary-color);
}
.portal-tabs button.active {
  border-bottom-color: var(--n-primary-color);
  font-weight: 650;
}
.portal-alert {
  margin-bottom: 16px;
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}
.metric-item,
.surface-panel,
.scenario-row,
.report-row,
.catalog-dataset {
  border: 1px solid var(--n-border-color);
  background: var(--n-color-modal);
  border-radius: 8px;
}
.metric-item {
  min-height: 112px;
  padding: 15px;
}
.metric-item span,
.metric-item small {
  display: block;
  color: var(--n-text-color-3);
  font-size: 12px;
}
.metric-item strong {
  display: block;
  margin: 8px 0 4px;
  color: var(--n-text-color);
  font-size: 26px;
  line-height: 1;
}
.overview-columns {
  display: grid;
  grid-template-columns: minmax(0, 0.85fr) minmax(0, 1.15fr);
  gap: 14px;
  margin-bottom: 14px;
}
.surface-panel {
  padding: 16px;
}
.panel-heading {
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}
.panel-heading > svg {
  color: var(--n-text-color-3);
}
.attention-list {
  display: grid;
  gap: 9px;
}
.attention-list div {
  display: flex;
  justify-content: space-between;
  padding: 10px 0;
  border-bottom: 1px solid var(--n-divider-color);
  font-size: 13px;
}
.attention-list div:last-child {
  border-bottom: 0;
}
.attention-list strong {
  color: var(--n-primary-color);
}
.attention-list strong.danger {
  color: var(--n-error-color);
}
.activity-list,
.report-preview-list {
  display: grid;
  gap: 2px;
}
.activity-list button,
.report-preview-list button {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 4px;
  color: inherit;
  text-align: left;
  background: transparent;
  border: 0;
  border-bottom: 1px solid var(--n-divider-color);
  cursor: pointer;
}
.activity-list button:last-child,
.report-preview-list button:last-child {
  border-bottom: 0;
}
.activity-list button:hover,
.report-preview-list button:hover {
  color: var(--n-primary-color);
}
.activity-list button > svg:last-child,
.report-preview-list button > svg:last-child {
  margin-left: auto;
  color: var(--n-text-color-3);
}
.activity-status {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border-radius: 50%;
}
.activity-status.ok {
  color: var(--n-success-color);
  background: var(--n-success-color-suppl);
}
.activity-status.failed {
  color: var(--n-error-color);
  background: var(--n-error-color-suppl);
}
.activity-copy,
.report-preview-list button > span {
  display: grid;
  min-width: 0;
  gap: 3px;
}
.activity-copy strong,
.report-preview-list strong {
  overflow: hidden;
  color: var(--n-text-color);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.activity-copy small,
.report-preview-list small {
  overflow: hidden;
  color: var(--n-text-color-3);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.preview-panel {
  margin-bottom: 14px;
}
.preview-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.preview-item {
  display: grid;
  gap: 7px;
  padding: 13px;
  background: var(--n-action-color);
  border-radius: 6px;
}
.preview-item > strong {
  font-size: 14px;
}
.preview-item > span {
  color: var(--n-text-color-2);
  font-size: 12px;
  line-height: 1.5;
}
.preview-item button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 0;
  color: var(--n-primary-color);
  text-align: left;
  background: transparent;
  border: 0;
  border-top: 1px solid var(--n-divider-color);
  cursor: pointer;
  font-size: 12px;
}
.text-action {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0;
  color: var(--n-primary-color);
  font-size: 12px;
  background: transparent;
  border: 0;
  cursor: pointer;
  white-space: nowrap;
}
.section-stack {
  display: grid;
  gap: 14px;
}
.section-heading {
  justify-content: space-between;
  gap: 16px;
}
.heading-status {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
}
.toolbar,
.catalog-toolbar {
  justify-content: space-between;
  gap: 14px;
}
.toolbar :deep(.n-input),
.catalog-toolbar :deep(.n-input) {
  width: min(360px, 100%);
}
.filter-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 3px;
}
.filter-tabs button {
  padding: 6px 9px;
  font-size: 12px;
}
.filter-tabs button:hover,
.filter-tabs button.active {
  background: var(--n-action-color);
  border-radius: 6px;
}
.filter-tabs button.active {
  font-weight: 650;
}
.filter-tabs button span {
  color: var(--n-text-color-3);
  font-size: 10px;
}
.report-list,
.scenario-grid,
.catalog-list {
  display: grid;
  gap: 10px;
}
.report-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px;
}
.report-icon {
  display: grid;
  flex: 0 0 auto;
  width: 32px;
  height: 32px;
  place-items: center;
  color: var(--n-primary-color);
  background: var(--n-action-color);
  border-radius: 6px;
}
.report-copy {
  min-width: 0;
  flex: 1;
}
.report-name {
  flex-wrap: wrap;
  gap: 8px;
}
.report-name h3,
.scenario-title h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}
.report-copy > p,
.scenario-heading p {
  margin: 7px 0 0;
  color: var(--n-text-color-2);
  font-size: 12px;
  line-height: 1.5;
}
.report-meta {
  flex-wrap: wrap;
  gap: 8px 16px;
  margin-top: 9px;
  color: var(--n-text-color-3);
  font-size: 11px;
}
.tag-line {
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 8px;
}
.error-note {
  margin: 8px 0 0;
  color: var(--n-error-color);
  font-size: 12px;
}
.scenario-row {
  padding: 16px;
}
.scenario-heading {
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}
.scenario-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 7px;
}
.question-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-top: 14px;
}
.question-row {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 5px;
  padding: 9px 10px;
  background: var(--n-action-color);
  border-radius: 6px;
}
.question-main {
  display: grid;
  min-width: 0;
  flex: 1;
  gap: 3px;
  padding: 0;
  color: var(--n-text-color);
  text-align: left;
  background: transparent;
  border: 0;
  cursor: pointer;
}
.question-main:hover {
  color: var(--n-primary-color);
}
.question-main span {
  font-size: 13px;
  font-weight: 600;
}
.question-main small {
  overflow: hidden;
  color: var(--n-text-color-3);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.click-count {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: var(--n-text-color-3);
  font-size: 10px;
}
.icon-action {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  color: var(--n-text-color-3);
  background: transparent;
  border: 0;
  cursor: pointer;
}
.followup-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--n-divider-color);
}
.followup-line > span {
  margin-right: 3px;
  color: var(--n-text-color-3);
  font-size: 11px;
}
.followup-line button {
  padding: 4px 7px;
  color: var(--n-primary-color);
  background: transparent;
  border: 1px solid var(--n-border-color);
  border-radius: 4px;
  cursor: pointer;
  font-size: 11px;
}
.catalog-counts {
  flex-wrap: wrap;
  gap: 18px;
  color: var(--n-text-color-2);
  font-size: 12px;
}
.catalog-counts strong {
  color: var(--n-text-color);
  font-size: 18px;
}
.catalog-dataset {
  overflow: hidden;
}
.catalog-dataset > summary,
.catalog-table > summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 14px;
  cursor: pointer;
  list-style: none;
}
.catalog-dataset > summary::-webkit-details-marker,
.catalog-table > summary::-webkit-details-marker {
  display: none;
}
.catalog-dataset > summary > svg:last-child,
.catalog-table > summary > svg:last-child {
  color: var(--n-text-color-3);
  transition: transform 0.16s ease;
}
.catalog-dataset[open] > summary > svg:last-child,
.catalog-table[open] > summary > svg:last-child {
  transform: rotate(180deg);
}
.summary-leading {
  min-width: 0;
  gap: 9px;
}
.summary-leading > svg {
  flex: 0 0 auto;
  color: var(--n-primary-color);
}
.summary-leading > span {
  display: grid;
  min-width: 0;
  gap: 3px;
}
.summary-leading strong {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.summary-leading small {
  overflow: hidden;
  color: var(--n-text-color-3);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.table-list {
  display: grid;
  gap: 8px;
  padding: 0 10px 10px;
}
.catalog-table {
  border: 1px solid var(--n-border-color);
  border-radius: 6px;
}
.table-details {
  padding: 0 12px 12px;
  border-top: 1px solid var(--n-divider-color);
}
.column-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  padding-top: 12px;
}
.column-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 3px 8px;
  padding: 9px;
  background: var(--n-action-color);
  border-radius: 5px;
}
.column-item span {
  display: grid;
  min-width: 0;
  gap: 2px;
}
.column-item strong {
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.column-item small {
  overflow: hidden;
  color: var(--n-text-color-3);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.column-item p {
  grid-column: 1 / -1;
  margin: 2px 0 0;
  color: var(--n-text-color-3);
  font-size: 11px;
  line-height: 1.4;
}
.table-actions {
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 12px;
}
.recommendation-list {
  display: grid;
  gap: 5px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--n-divider-color);
}
.recommendation-label {
  color: var(--n-text-color-3);
  font-size: 11px;
}
.recommendation-list button {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 8px;
  padding: 8px;
  color: var(--n-text-color);
  text-align: left;
  background: var(--n-action-color);
  border: 0;
  border-radius: 5px;
  cursor: pointer;
}
.recommendation-list button:hover {
  color: var(--n-primary-color);
}
.recommendation-list button small {
  overflow: hidden;
  color: var(--n-text-color-3);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.portal-footer {
  justify-content: space-between;
  gap: 12px;
  margin-top: 18px;
  padding: 12px 2px 0;
  color: var(--n-text-color-3);
  border-top: 1px solid var(--n-divider-color);
  font-size: 11px;
}
@media (max-width: 900px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .overview-columns {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 640px) {
  .data-portal-page {
    padding: 14px;
  }
  .portal-header {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
  }
  .portal-header-actions {
    width: 100%;
    justify-content: flex-start;
  }
  .portal-tabs button {
    min-width: max-content;
    padding-inline: 4px;
  }
  .metric-grid,
  .preview-grid,
  .question-grid,
  .column-grid {
    grid-template-columns: 1fr;
  }
  .toolbar,
  .catalog-toolbar {
    align-items: stretch;
    flex-direction: column;
  }
  .toolbar :deep(.n-input),
  .catalog-toolbar :deep(.n-input) {
    width: 100%;
  }
  .section-heading {
    align-items: flex-start;
  }
  .portal-footer {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
