export interface EmbedApplicationConfigInput {
  allowedOrigins: string;
  agentVersionIds: string[];
  displayName: string;
  primaryColor: string;
  watermark: boolean;
  maxSessionMinutes: number | null;
}

export interface EmbedApplicationConfigPayload extends Record<string, unknown> {
  allowedOrigins: string[];
  agentVersionIds: string[];
  displayName?: string;
  primaryColor: string;
  watermark: boolean;
  maxSessionMinutes: number;
}

const colorPattern = /^#[0-9a-fA-F]{6}$/;

export function emptyEmbedApplicationConfig(): EmbedApplicationConfigInput {
  return {
    allowedOrigins: '',
    agentVersionIds: [],
    displayName: '',
    primaryColor: '#18a058',
    watermark: true,
    maxSessionMinutes: 60
  };
}

export function readEmbedApplicationConfig(value: Record<string, unknown> | null | undefined): EmbedApplicationConfigInput {
  const fallback = emptyEmbedApplicationConfig();
  if (!value) return fallback;
  return {
    allowedOrigins: Array.isArray(value.allowedOrigins)
      ? value.allowedOrigins.filter((item): item is string => typeof item === 'string').join('\n')
      : '',
    agentVersionIds: Array.isArray(value.agentVersionIds)
      ? value.agentVersionIds.map(String).filter(Boolean)
      : [],
    displayName: typeof value.displayName === 'string' ? value.displayName : '',
    primaryColor: typeof value.primaryColor === 'string' ? value.primaryColor : fallback.primaryColor,
    watermark: typeof value.watermark === 'boolean' ? value.watermark : fallback.watermark,
    maxSessionMinutes: typeof value.maxSessionMinutes === 'number'
      ? value.maxSessionMinutes
      : fallback.maxSessionMinutes
  };
}

export function buildEmbedApplicationConfig(input: EmbedApplicationConfigInput): EmbedApplicationConfigPayload {
  const allowedOrigins = [...new Set(
    input.allowedOrigins
      .split(/\r?\n/)
      .map(item => item.trim())
      .filter(Boolean)
      .map(normalizeOrigin)
  )];
  if (!allowedOrigins.length) throw new Error('Embed 应用至少需要一个允许的宿主 Origin');
  if (allowedOrigins.length > 50) throw new Error('Embed 应用最多允许 50 个宿主 Origin');

  const agentVersionIds = [...new Set(input.agentVersionIds.map(String).filter(Boolean))];
  if (!agentVersionIds.length) throw new Error('Embed 应用至少需要选择一个 Agent 发布版本');
  if (agentVersionIds.length > 100) throw new Error('Embed 应用最多允许 100 个 Agent 发布版本');

  const primaryColor = input.primaryColor.trim();
  if (!colorPattern.test(primaryColor)) throw new Error('品牌色必须是六位十六进制颜色');
  const maxSessionMinutes = input.maxSessionMinutes;
  if (!Number.isInteger(maxSessionMinutes) || (maxSessionMinutes ?? 0) < 5 || (maxSessionMinutes ?? 0) > 1440) {
    throw new Error('会话有效期上限必须在 5 到 1440 分钟之间');
  }
  const displayName = input.displayName.trim();
  if (displayName.length > 128) throw new Error('显示名称不能超过 128 个字符');

  return {
    allowedOrigins,
    agentVersionIds,
    ...(displayName ? { displayName } : {}),
    primaryColor: primaryColor.toLowerCase(),
    watermark: input.watermark,
    maxSessionMinutes: maxSessionMinutes as number
  };
}

function normalizeOrigin(value: string) {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new Error(`宿主 Origin 格式无效：${value}`);
  }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password
    || url.search || url.hash || (url.pathname !== '/' && url.pathname !== '')) {
    throw new Error(`宿主 Origin 必须是无路径的 HTTP(S) 来源：${value}`);
  }
  return url.origin.toLowerCase();
}
