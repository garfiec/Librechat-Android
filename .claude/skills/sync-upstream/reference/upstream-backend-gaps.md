# Upstream features with NO real backend (client-only stubs / "vapor")

Some features appear in the upstream **web client** (components, hooks, data-service
functions) but have **no working backend** yet — the client calls are stubs that
`return Promise.resolve(...)`, or the route simply isn't mounted. Building the mobile
counterpart would wire UI to a nonexistent endpoint.

**This file is the designated record of those features, so they are not re-scoped and
re-discovered every sync.**

## How to use this file

- **Phase B (gap analysis):** read this file. For any gap that matches an entry below,
  mark it **backend-blocked — do not build** instead of re-investigating from scratch.
- **Each sync, RE-CHECK** every open entry: has the upstream backend route shipped since
  the recorded version? If yes, remove the entry and scope the mobile feature normally.
- **Phase C (proposal):** list still-open entries under a short "Upstream-incomplete
  (backend not shipped)" note so the user sees they're knowingly skipped, not missed.
- **When you discover a NEW vapor feature mid-sync, ADD it here** (with evidence) before
  moving on.

## Entry format

```
### <feature> — backend missing (as of <version>)
- **Web client shows:** <what the UI/stub looks like>
- **Evidence it's vapor:** <file:line of the client stub / the unmounted route>
- **Mobile status:** not built (would wire to a nonexistent endpoint)
- **Re-check:** <what route/shape to look for upstream to know it shipped>
- **Recorded:** <date> during <tag> sync
```

---

## Closed entries

Kept rather than deleted so a later sync does not re-discover the same feature and re-file it as
vapor. A closed entry needs both halves: the upstream route shipped AND mobile builds against it.

### Skill favorites — backend missing (as of v0.8.6) — **CLOSED 2026-07-25**
- **Was:** the web client had a Star button and favorites filter on skills, but
  `packages/data-provider/.../data-service.ts` `getSkillFavorites` / `updateSkillFavorites` were
  stubs returning `Promise.resolve([])` / echoing input, no skill-favorites route was mounted in
  `api/server/routes/settings.js`, and the generic `/favorites` route rejected bare skill ids.
- **What shipped:** #13952 (`edd614bbf`, 2026-07-05) added a real `ToolFavorite` collection and
  `GET /api/user/settings/favorites/tools` + `PUT`/`DELETE .../:itemType/:itemId`, with
  `itemType ∈ {builtin, tool, mcp, skill}`. Verified present at the pinned target `6c97a7f4`
  (`api/server/routes/settings.js`, `packages/api/src/favorites/handlers.ts`,
  `packages/data-schemas/src/types/favorite.ts`). Skill favorites are not a separate surface — they
  flow through this route as `itemType: 'skill'`, which is also why upstream dropped the reserved
  `TUserFavorite.skillId`.
- **Mobile:** built in the 0.8.8-line partial sync — `ToolFavorite` / `ToolFavoritesRepository`,
  `FavoritesApi.getToolFavorites` / `addToolFavorite` / `removeToolFavorite`, and per-item stars in
  the agent editor's unified tool picker. The old whole-list `/favorites` surface is unchanged and
  still owns pinned agents / models / specs.
- **Recorded:** 2026-06-02 (v0.8.6 sync). **Closed:** 2026-07-25 (0.8.8-line dev sync).
