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

### Skill favorites — backend missing (as of v0.8.6)
- **Web client shows:** a Star/favorite button on skills + a favorites filter.
- **Evidence it's vapor:** `packages/data-provider/.../data-service.ts`
  `getSkillFavorites` / `updateSkillFavorites` are client STUBS that
  `return Promise.resolve([])` / echo input, with a comment that the backend route is
  "phase 2" and these resolve empty "so the UI hooks compile and the Star button is a
  no-op." There is **no skill-favorites route** in `api/server/routes/settings.js`; the
  generic `/favorites` route only accepts agent / model+endpoint / spec favorites and
  **rejects bare skill ids**. No endpoint builder for skill favorites exists.
- **Mobile status:** NOT built — deliberately skipped (a Star button would be a no-op
  wired to nothing).
- **Re-check next sync:** look for a real `GET/POST /api/.../skills/favorites` route
  mounted server-side (not a data-service stub) and a skill-typed favorite in the
  favorites schema. If present, build favorite/unfavorite + favorites filter then.
- **Recorded:** 2026-06-02 during the v0.8.6 sync.
