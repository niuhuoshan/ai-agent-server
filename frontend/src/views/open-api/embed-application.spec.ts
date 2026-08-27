import { describe, expect, it } from 'vitest';
import { buildEmbedApplicationConfig, emptyEmbedApplicationConfig, readEmbedApplicationConfig } from './embed-application';

describe('embed application configuration', () => {
  it('normalizes origins and emits a secret-free policy document', () => {
    const result = buildEmbedApplicationConfig({
      ...emptyEmbedApplicationConfig(),
      allowedOrigins: 'HTTPS://Portal.Example.com:443/\nhttp://localhost:5173\nhttps://portal.example.com',
      agentVersionIds: ['20', '10', '20'],
      displayName: ' 财务助手 ',
      primaryColor: '#18A058',
      maxSessionMinutes: 30
    });

    expect(result).toEqual({
      allowedOrigins: ['https://portal.example.com', 'http://localhost:5173'],
      agentVersionIds: ['20', '10'],
      displayName: '财务助手',
      primaryColor: '#18a058',
      watermark: true,
      maxSessionMinutes: 30
    });
    expect(JSON.stringify(result)).not.toMatch(/token|secret|password/i);
  });

  it.each([
    { allowedOrigins: '', agentVersionIds: ['10'] },
    { allowedOrigins: '*', agentVersionIds: ['10'] },
    { allowedOrigins: 'https://example.com/embed', agentVersionIds: ['10'] },
    { allowedOrigins: 'https://example.com', agentVersionIds: [] }
  ])('rejects an unsafe or incomplete browser policy', value => {
    expect(() => buildEmbedApplicationConfig({
      ...emptyEmbedApplicationConfig(),
      ...value
    })).toThrow();
  });

  it('hydrates an existing server policy for editing without trusting unknown fields', () => {
    expect(readEmbedApplicationConfig({
      allowedOrigins: ['https://portal.example.com'],
      agentVersionIds: [10, '20'],
      primaryColor: '#123456',
      watermark: false,
      maxSessionMinutes: 15,
      ignored: 'value'
    })).toEqual({
      allowedOrigins: 'https://portal.example.com',
      agentVersionIds: ['10', '20'],
      displayName: '',
      primaryColor: '#123456',
      watermark: false,
      maxSessionMinutes: 15
    });
  });
});
