# Agent Browser Worker

This is the isolated Playwright process used by the Java browser-control domain.
It is intentionally separate from the application JVM and keeps browser sessions
in memory; the platform persists ownership, leases, and audit events.

Start it locally:

```bash
docker compose up -d --build
```

The Java application defaults to `http://127.0.0.1:8787`. Override
`NHS_PLATFORM_BROWSER_WORKER_URL` when the application runs in another
container/network namespace. Private and loopback targets are blocked by the
worker by default; set `ALLOW_PRIVATE_TARGETS=true` only in a deliberately
isolated development environment.

This service is the isolated browser runtime for `nhs`. It runs
Playwright in a separate Node container; the Java process only calls its HTTP
API and never executes untrusted browser code in the JVM.

```bash
docker compose up -d --build
curl http://127.0.0.1:8787/health
```

The offline Compose profile exposes the browser parameter matrix through
`NHS_BROWSER_USER_AGENT`, `NHS_BROWSER_LOCALE`,
`NHS_BROWSER_TIMEZONE_ID`, `NHS_BROWSER_STEALTH`,
`NHS_BROWSER_SNAPSHOT_CACHE_TTL_MS`, and
`NHS_BROWSER_SNAPSHOT_CACHE_LIMIT`. The worker applies these values when a
session context is created; `/health` reports only the non-sensitive locale,
timezone, stealth flag, generation, and active session identifiers.

Set the platform property when the worker is elsewhere:

```yaml
agent:
  platform:
    browser:
      worker-url: http://127.0.0.1:8787
      request-timeout-ms: 30000
```

The worker keeps sessions in memory. The platform database remains the source
of ownership, status, event audit, and lease records; restarting the worker
invalidates its in-memory sessions and the platform should close/reopen them.

`fill` accepts an empty string so a controlled input can be cleared.
