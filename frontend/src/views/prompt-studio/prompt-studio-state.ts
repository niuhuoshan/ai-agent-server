export function parsePromptVariables(value: string): Record<string, unknown> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(value || '{}');
  } catch {
    throw new Error('变量必须是有效的 JSON 对象');
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('变量必须是 JSON 对象');
  }
  return parsed as Record<string, unknown>;
}

export function defaultPromptVariables(names: string[]) {
  return JSON.stringify(Object.fromEntries(names.map(name => [name, ''])), null, 2);
}

/** Returns declared placeholders that have no value in the test document. */
export function missingPromptVariables(names: string[], value: string): string[] {
  let parsed: Record<string, unknown>;
  try {
    parsed = parsePromptVariables(value);
  } catch {
    return [...names];
  }
  return names.filter(name => !Object.prototype.hasOwnProperty.call(parsed, name) || parsed[name] == null);
}

/** Keeps the test JSON in sync when the editor discovers a new placeholder. */
export function mergePromptVariables(names: string[], value: string): string {
  let parsed: Record<string, unknown> = {};
  try {
    parsed = parsePromptVariables(value);
  } catch {
    parsed = {};
  }
  for (const name of names) {
    if (!Object.prototype.hasOwnProperty.call(parsed, name)) parsed[name] = '';
  }
  return JSON.stringify(parsed, null, 2);
}

export function promptRequestError(error: unknown, fallback: string) {
  const response = (error as {
    response?: {
      status?: number;
      data?: { code?: number | string; message?: string; msg?: string };
    };
  } | null)?.response;
  const backendCode = Number(response?.data?.code);
  if (response?.status === 501 || backendCode === 501) {
    return '提示词测试/优化当前不可用：请先在模型中心配置并启用对话模型';
  }
  return response?.data?.message || response?.data?.msg || fallback;
}
