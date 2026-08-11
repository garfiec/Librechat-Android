# Upstream Paths to Watch

These are the directories and files in the `upstream/` submodule that matter for mobile parity.
Use these paths when generating focused diffs between the current baseline commit (UPSTREAM_VERSION commit=) and the target ref (tag, rc tag, or untagged commit for partial syncs).

## Server: Routes, Controllers, Middleware, Services

| Path | What It Contains | Why It Matters |
|------|------------------|----------------|
| `api/server/routes/` | Express route definitions (REST endpoints) | Defines the API contract the mobile app calls |
| `api/server/routes/agents/` | Agent chat, actions, tools, v1, OpenAI routes | Agents API surface — mobile's `AgentsApi.kt` + `ChatApi.kt` |
| `api/server/routes/files/` | File upload, avatar, images, speech subroutes | Mobile's `FilesApi.kt` / `FilesExtApi.kt` |
| `api/server/routes/admin/` | Admin-only routes (currently `auth.js`) | Admin surface — likely deferrable but flag any changes |
| `api/server/controllers/` | Request handlers and business logic | Reveals exact request/response shapes and validation |
| `api/server/middleware/` | Auth, rate limiting, abort, request validation | Changes here can alter headers, error shapes, and auth flow the mobile client relies on |
| `api/server/services/` | AuthService, MCP, Endpoints, Files, Runs, Tools, Artifacts, etc. | Business logic that shapes responses and implements features |
| `api/models/` | Mongoose models (Agent, Conversation, Message, File, etc.) | DB schema drives API response shapes — especially fields, defaults, enums |

## Data Provider Package (canonical types + API client)

| Path | What It Contains | Why It Matters |
|------|------------------|----------------|
| `packages/data-provider/src/api-endpoints.ts` | **Canonical list of endpoint URL builders** | Route renames and new endpoints land here first |
| `packages/data-provider/src/config.ts` | `VERSION` constant, config types | Source of truth for backend version we track |
| `packages/data-provider/src/data-service.ts` | HTTP client functions | Actual request shapes the web client sends |
| `packages/data-provider/src/parsers.ts` | Request/response parsers | Normalizes shapes before/after the wire |
| `packages/data-provider/src/permissions.ts` | Permission schemas | Role gating for endpoints |
| `packages/data-provider/src/types/` | TypeScript types (queries, mutations, agents, files, runs, mcpServers, web) | React Query hook types — canonical request/response shapes |
| `packages/data-provider/src/react-query/` | React Query service + hook exports | Links endpoints to hooks |
| `packages/data-provider/src/schemas.ts` | Zod validation schemas | Request validation contracts |
| `packages/data-provider/src/` (other files) | actions, artifacts, azure, bedrock, feedback, file-config, generate, keys, mcp, messages, models, roles | Feature-specific type sources |

## Data Schemas Package (DB-side)

| Path | What It Contains | Why It Matters |
|------|------------------|----------------|
| `packages/data-schemas/src/schema/` | Database schema definitions | DB schema changes → API response shape changes |
| `packages/data-schemas/src/models/` | Typed model wrappers | Typed facade over DB schemas |
| `packages/data-schemas/src/types/` | TypeScript type exports | Consumed by controllers |

## API Package (newer, recent versions)

| Path | What It Contains | Why It Matters |
|------|------------------|----------------|
| `packages/api/src/` | acl, agents, apiKeys, app, auth, cache, cdn, cluster, crypto, db, ... | New monorepo workspace introduced in recent versions — scan for any newly-exposed public surface |

## Web Client (reference for feature parity)

| Path | What It Contains | Why It Matters |
|------|------------------|----------------|
| `client/src/components/` | React UI components by feature area | Reference for mobile UI feature parity |
| `client/src/hooks/` | Custom React hooks (data fetching, state) | Shows how the web app consumes APIs — informs ViewModel design |
| `client/src/data-provider/` | Query/mutation modules by domain (Agents, Auth, Endpoints, Files, MCP, Memories, Messages, SSE, Tools, ...) | **Often the most accurate mapping of feature → endpoint** — prefer over guessing from `hooks/` |
| `client/src/store/` | Recoil/Jotai state atoms | Global state patterns — informs mobile state management |
| `client/src/Providers/` | React Context providers | Feature flags, config-driven UI — informs mobile feature gating |

## Server Config Schema

| Path | What It Contains | Why It Matters |
|------|------------------|----------------|
| `librechat.example.yaml` | Reference server config | New keys gate new `/api/config` response fields |
| `.env.example` | Required/optional env vars | Changes hint at new feature availability |

## Release Notes (authoritative)

GitHub Releases often explicitly flag breaking changes and migrations that diffs hide.
For every stable or rc tag between current baseline and target (inclusive; untagged partial targets have no release notes — scan merged PRs and commit subjects instead):
```bash
gh api repos/danny-avila/LibreChat/releases/tags/{tag} --jq .body
```
Falls back to `WebFetch` on `https://github.com/danny-avila/LibreChat/releases/tag/{tag}`.

Upstream has no repo-root `CHANGELOG.md` — release notes on GitHub are the canonical changelog.

## Diff Command Template

Overview:
```bash
cd upstream && git diff {base_commit}..{target_commit} --stat -- \
  api/server/routes/ \
  api/server/controllers/ \
  api/server/middleware/ \
  api/server/services/ \
  api/models/ \
  packages/data-provider/src/ \
  packages/data-schemas/src/ \
  packages/api/src/ \
  client/src/components/ \
  client/src/hooks/ \
  client/src/data-provider/ \
  client/src/store/ \
  client/src/Providers/ \
  librechat.example.yaml \
  .env.example
```

For detailed diffs, run each path separately to keep output manageable:
```bash
cd upstream && git diff {base_commit}..{target_commit} -- packages/data-provider/src/api-endpoints.ts
```

## Hand-mirrored constants (checked separately, not by reading these diffs)

Some paths below contain values the client copies verbatim because the server never serves them:
`documentSupportedProviders` (`schemas.ts`), `documentParserMimeTypes` / `mimeTypeAliases` /
`fullMimeTypesList` / `excelMimeTypes` / `bedrockDocumentFormats` (`file-config.ts`),
`FEEDBACK_TAGS` (`feedback.ts`), `ArtifactModes` (`artifacts.ts`), plus the whole of
`parameterSettings.ts` and `packages/api/src/artifacts/update.ts`.

`excelMimeTypes` is tracked separately because upstream spreads it into `fullMimeTypesList` while
the Kotlin transcription inlines it — so it moves without `fullMimeTypesList` appearing to change.

Do **not** rely on spotting these in the diff sweep. They read as inert constant edits — no route
changed, no response shape changed — so they get categorized as low-impact and deferred, while the
Kotlin copy quietly goes stale. `scripts/check-mirrors.py` diffs each watched region between the two
revisions and names the Kotlin file to reconcile; the registry with the reasoning for each entry is
`scripts/mirrors.json`. Adding a hardcoded mirror means adding a registry entry in the same PR — an
unregistered mirror is one nobody will notice going stale.

## Commit-message scan (complements the diff)

```bash
cd upstream && git log --oneline {base_commit}..{target_commit}
```
Breaking changes are frequently summarized in subject lines (e.g., `BREAKING CHANGE:`, `refactor!:`).
