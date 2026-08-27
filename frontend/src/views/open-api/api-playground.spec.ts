import { describe, expect, it } from 'vitest';
import { apiDocsUrl, filterNhsV1Spec } from './api-playground';

describe('API Playground specification boundary', () => {
  it('keeps only Nhs v1 paths and injects the authenticated server', () => {
    const result = filterNhsV1Spec({
      openapi: '3.1.0',
      paths: {
        '/api/v1/chat/completions': { post: { summary: 'chat' } },
        '/platform/agent-debug/runs': { get: { summary: 'internal' } }
      }
    }, '/proxy-default');

    expect(Object.keys(result.paths || {})).toEqual(['/api/v1/chat/completions']);
    expect(result.paths?.['/api/v1/chat/completions']?.post).toMatchObject({
      security: [{ SessionBearer: [] }]
    });
    expect(result.components?.securitySchemes?.SessionBearer).toMatchObject({
      type: 'http', scheme: 'bearer'
    });
    expect(result.servers).toEqual([{ url: '/proxy-default', description: '当前牛火山企业智能体平台' }]);
  });

  it('normalizes the API document URL without changing the configured base path', () => {
    expect(apiDocsUrl('/api/')).toBe('/api/v3/api-docs');
    expect(apiDocsUrl('http://127.0.0.1:8080')).toBe('http://127.0.0.1:8080/v3/api-docs');
  });
});
