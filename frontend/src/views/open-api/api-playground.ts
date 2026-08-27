export interface OpenApiDocument {
  openapi?: string;
  info?: Record<string, unknown>;
  servers?: Array<Record<string, unknown>>;
  paths?: Record<string, Record<string, unknown>>;
  components?: {
    securitySchemes?: Record<string, Record<string, unknown>>;
    [key: string]: unknown;
  };
  [key: string]: unknown;
}

const HTTP_METHODS = new Set(['get', 'put', 'post', 'delete', 'options', 'head', 'patch', 'trace']);

/** Keeps the interactive console restricted to the public Nhs compatibility surface. */
export function filterNhsV1Spec(source: OpenApiDocument, serverUrl: string) {
  const paths = Object.fromEntries(
    Object.entries(source.paths || {})
      .filter(([path]) => path.startsWith('/api/v1'))
      .map(([path, item]) => {
        const operations = Object.fromEntries(
          Object.entries(item || {}).map(([method, operation]) => {
            if (!HTTP_METHODS.has(method) || !operation || typeof operation !== 'object') {
              return [method, operation];
            }
            return [method, { ...(operation as Record<string, unknown>), security: [{ SessionBearer: [] }] }];
          })
        );
        return [path, operations];
      })
  );
  const components = source.components || {};
  const securitySchemes = components.securitySchemes || {};
  return {
    ...source,
    servers: [{ url: serverUrl, description: '当前牛火山企业智能体平台' }],
    paths,
    components: {
      ...components,
      securitySchemes: {
        ...securitySchemes,
        SessionBearer: {
          type: 'http',
          scheme: 'bearer',
          bearerFormat: 'NHS access token'
        }
      }
    },
    security: [{ SessionBearer: [] }]
  } satisfies OpenApiDocument;
}

export function apiDocsUrl(baseUrl: string) {
  return `${baseUrl.replace(/\/$/, '')}/v3/api-docs`;
}
