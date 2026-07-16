# feature:conversations

## Screen
`ConversationListScreen` -- the drawer content showing all conversations. Sealed interface: `ConversationsRoute : NavKey` with routes `Conversations` and `ArchivedConversations` (both `@Serializable` data objects).

## Pagination
- Cursor-based via `ConversationRepository.loadNextPage(cursor, tags)`
- `nextCursor: String?` in UI state; null = no more pages
- `loadMore()` called when LazyColumn nears the end
- Pull-to-refresh via `refresh()` which resets to first page

## Date Grouping
- `DateGroupHeader` renders section labels: Today, Yesterday, Previous 7 Days, Previous 30 Days, month-year
- Conversations are flattened into a sealed `ConversationListItem` (DateHeader | ConvoItem)

## Search
- `ConversationSearchBar` with 500ms debounce (via coroutine delay)
- `onSearchQueryChanged()` cancels previous search job, waits 500ms, calls `SearchRepository.search()`
- When search is active, normal pagination is disabled (`hasMore = false`)
- Clear search restores normal conversation list

## Tags
- `TagFilterBar` displays `FilterChip` per tag (multi-select toggle)
- `TagPicker` for assigning tags to a conversation
- Tags observed via `TagRepository.observeTags()` (Room-backed Flow); `SAVED_TAG` and `count == 0` tags are filtered out in the ViewModel
- `toggleTag()` / `clearTagFilter()` reload conversations with new filter

## CRUD Actions (via `ConversationActions` component)
| Action | API | Notes |
|--------|-----|-------|
| Rename | `POST /api/convos/update` | Inline rename, max 100 chars |
| Archive | `POST /api/convos/archive` | `{ conversationId, isArchived: true }` |
| Delete | `DELETE /api/convos` | Confirmation dialog required |
| Duplicate | `POST /api/convos/duplicate` | Navigates to new conversation |
| Fork | `POST /api/convos/fork` | From message context menu in chat, not here |
| Share | `POST /api/share` | Only if `sharedLinksEnabled` in server config |

## Export / Import
- `ConversationExporter` supports `ExportFormat.JSON` and `ExportFormat.MARKDOWN`
- Export is client-side: fetches messages, serializes locally, emits `ExportReady` event
- `ExportFormatPicker` component for format selection
- `ConversationImporter` parses JSON (LibreChat/ChatGPT format) via `POST /api/convos/import`
- File I/O uses SAF (Storage Access Framework)

## Events
`ConversationListEvent` sealed interface for one-shot UI events: `ShareLinkCopied`, `NavigateToConversation`, `NavigateToNewChat` (emitted when the currently-open conversation is deleted, so the host moves off the now-gone thread), `ShowError`, `ExportReady`, `ImportSuccess`
