import type { McpImportEntryView } from '@/service/api';

export function buildSafeMcpImportDocument(entry: McpImportEntryView, credentialName: string) {
  if (!entry.importable || !entry.endpointUrl) {
    throw new Error('MCP 服务不可导入');
  }
  const server: Record<string, unknown> = {
    url: entry.endpointUrl,
    type: entry.transport
  };
  if (entry.authType !== 'none') {
    if (!/^[A-Z][A-Z0-9_]{0,127}$/.test(credentialName)) {
      throw new Error('MCP 凭据环境变量无效');
    }
    const placeholder = '${' + credentialName + '}';
    if (entry.authType === 'bearer') {
      server.headers = { Authorization: 'Bearer ' + placeholder };
    } else {
      if (!entry.authHeader) throw new Error('MCP 鉴权请求头无效');
      server.headers = { [entry.authHeader]: placeholder };
    }
  }
  return { mcpServers: { [entry.sourceKey]: server } };
}
