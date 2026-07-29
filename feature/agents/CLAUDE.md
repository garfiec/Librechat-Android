# feature:agents

## Screens
- **AgentMarketplaceScreen** -- grid of agent cards with search and category filters
- **AgentDetailScreen** -- full agent info with "Start Chat" action

## Navigation
- Sealed interface: `AgentsRoute : NavKey` with typed route classes
- Routes: `AgentMarketplace` (grid), `AgentDetail(agentId: String)` (detail), `AgentEditorCreate`, `AgentEditorEdit(agentId: String)` (all `@Serializable`)
- Feature entries registered via `EntryProviderScope<NavKey>.agentsEntries()`
- `onStartChat(agentId)` callback navigates to chat with the selected agent

## Marketplace ViewModel
- `AgentMarketplaceViewModel` uses server-side pagination via `AgentRepository.getAgentsPaginated()`
- Page size: 10 agents per page
- Categories fetched from `getAgentCategories()` API (server-driven, not client-derived)
- `onCategorySelected()` toggles category filter and resets to page 1
- `onSearchQueryChanged()` debounces 500ms, then resets to page 1 with server-side search
- `loadMore()` appends next page; no-ops if already loading or no more pages
- Pull-to-refresh resets to page 1
- **Gotcha**: Search and category changes reset pagination — always load from page 1 when filters change

## Agent Card Layout
- Card: 136dp height, endpoint avatar + name (2-line clamp) + description (2-line clamp) + "By {author}"
- Category badge at top-right corner
- Tap opens agent detail

## Agent Detail
- `AgentDetailViewModel` loads single agent by ID
- Shows: large avatar, name, description, capabilities/tools list
- Actions: Pin/Unpin, Copy link, "Start Chat" button
- "Start Chat" flow: sets `endpoint: "agents"` + `agent_id`, creates conversation template, navigates to new chat

## Data Layer
- `AgentRepository` in `:core:data` wraps `AgentsApi` from `:core:network`
- API: `GET /api/agents` (list), `GET /api/agents/:id` (detail), `GET /api/agents/categories`
- Categories are server-driven, not hardcoded; special values: "promoted" (Top Picks), "all"

## Infinite Scroll
- `LazyVerticalGrid` with `rememberLazyGridState()` + `derivedStateOf` for scroll detection
- Triggers `loadMore()` when last visible item is within 3 of the end
- Shows `CircularProgressIndicator` in a full-span grid item while loading more

## Spec Notes (not yet implemented)
- Category tabs with slide animation (`AnimatedContent` with `slideInHorizontally`)
- Permission check: `PermissionTypes.MARKETPLACE` with `Permissions.USE`

### Agent Editor — Advanced Sections
- `AgentEditorScreen` now includes 6 collapsible sections below the basic fields:
  - `AgentActionsPanel` — OpenAPI action CRUD (domain, type, auth)
  - `AgentCodeInterpreterSection` / `AgentFileSearchSection` — simple capability toggles
  - `AgentSharingSection` — visibility (Private/Team/Public) + collaborative toggle
  - `AgentHandoffConfig` — select agents for handoff, displayed as InputChips
- `AgentEditorViewModel` depends on both `AgentRepository` and `McpRepository`
- **Gotcha**: MCP tools load requires a separate `McpRepository.getTools()` call; they're not bundled with agent data
- **Gotcha**: `isPublic`/`isCollaborative` map to the sharing section, not individual toggles in the agent model

### Unified tools picker (v0.8.8)
- `ToolsMarketplaceDialog` is the ONE picker for what an agent can do — built-in capabilities,
  plugin tools, MCP servers and skills in one searchable list with kind filter chips. It replaced
  `ToolSelectDialog` and `AgentMcpToolsSelector` (both deleted), which each browsed a slice of the
  same catalog.
- Everywhere else in the editor shows only what is ALREADY on the agent, never the catalog:
  plugin tools, MCP tools and actions are rows in "Tools & Actions"; skills are chips in
  `AgentSkillsSection`. Adding is the picker's job. An MCP tool whose server has since gone away
  still gets a row, by raw name, so it can be removed instead of sitting invisibly on the agent.
- `AgentSkillsSection` keeps its own "Add skills" dialog rather than deferring to the picker —
  upstream does the same (`ToolsSection` renders a skills `SelectedSection` next to a dedicated
  `SkillsDialog`), because the skills allowlist has a master switch the catalog has no notion of:
  enabled with an empty allowlist means "all skills", not "none".
- `AgentCapabilitiesSection` (Artifacts, EndAfterTools, HideSeq, RecursionLimit) is NOT part of the
  catalog — those are run-behaviour knobs, not things an agent can be given.
- The catalog is DERIVED, not stored: `AgentEditorUiState.marketplaceCatalog()` builds it from the
  reference lists the editor already loads, so it cannot go stale against them.
- **Gotcha**: a row's toggle routes back through the existing per-kind entry point
  (`onCodeInterpreterToggled`, `onToolToggled`, …), never straight into state. Code interpreter
  hangs an auth check off its toggle and the picker must not be a second path that skips it.
- **Gotcha**: `MarketplaceItem.id` is what the AGENT stores; `favoriteId` is what the FAVORITES
  route pins. They differ for MCP — the agent stores a tool name, the pin is the server.
- Favorites come from `ToolFavoritesRepository` (v0.8.8 `/favorites/tools`). The star column is
  hidden entirely when `areToolFavoritesSupported` is false; see VERSION_GATES.md for the gate.
- Built-in rows carry a `MarketplaceBuiltinLabel` rather than a name string — they are the only
  rows this app names itself, so their text is resolved from compose resources at render time.
  `filterMarketplace` therefore takes a `builtinLabels` map so search can see those labels.
- Version history is fetched lazily (`AgentLoaderDelegate.loadVersions`) because v0.8.8 stopped
  inlining `versions[]`. The active-revision comparison runs against `versionBasis`, captured at
  load — reading live form state would compare against whatever the user has since typed.
- Not ported from upstream's rework: OrchestrationHub and StatefulSessions.
