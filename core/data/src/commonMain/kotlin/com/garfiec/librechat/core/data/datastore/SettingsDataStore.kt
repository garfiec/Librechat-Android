package com.garfiec.librechat.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.currentAccountId
import com.garfiec.librechat.core.common.identity.flatMapAccountOrEmpty
import com.garfiec.librechat.core.data.prefetch.PrefetchDepth
import com.garfiec.librechat.core.data.prefetch.PrefetchRunOutcome
import com.garfiec.librechat.core.data.prefetch.ScheduledRunRecord
import com.garfiec.librechat.core.model.ModelRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

class SettingsDataStore(
    private val dataStore: DataStore<Preferences>,
    private val activeAccountProvider: ActiveAccountProvider,
    appScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) {
    /**
     * Selected app language as a BCP-47/ISO code (e.g. "es", "zh"), or [DEFAULT_LANGUAGE] (the
     * "follow the device locale" sentinel) when the user hasn't chosen one. Drives the runtime
     * locale applied at the app root.
     */
    val selectedLanguage: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_LANGUAGE] ?: DEFAULT_LANGUAGE
    }

    /**
     * Initial language seeded for the first compose frame, warmed up asynchronously off the Main
     * thread (Koin instantiates this singleton on Main at startup, so the read must not block).
     * Stays [DEFAULT_LANGUAGE] until [isReady] flips; the root composable gates on [isReady] so the
     * persisted locale is applied before the first frame draws (no flash of the wrong language).
     */
    @Volatile
    var initialSelectedLanguage: String = DEFAULT_LANGUAGE
        private set

    private val _isReady = MutableStateFlow(false)

    /** Flips true once the async warm-up has resolved [initialSelectedLanguage]. */
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        appScope.launch(ioDispatcher) {
            try {
                val prefs = dataStore.data.first()
                initialSelectedLanguage = prefs[KEY_SELECTED_LANGUAGE] ?: DEFAULT_LANGUAGE
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the default; the live flow still drives the UI value.
            } finally {
                _isReady.value = true
            }
        }
    }

    val latexRenderer: Flow<LatexRenderer> = dataStore.data.map { prefs ->
        LatexRenderer.fromString(prefs[KEY_LATEX_RENDERER])
    }

    val chatFontSize: Flow<ChatFontSize> = dataStore.data.map { prefs ->
        ChatFontSize.fromString(prefs[KEY_CHAT_FONT_SIZE])
    }

    val starredModelsDisplay: Flow<StarredModelsDisplay> = dataStore.data.map { prefs ->
        StarredModelsDisplay.fromString(prefs[KEY_STARRED_MODELS_DISPLAY])
    }

    val chatHeaderContent: Flow<ChatHeaderContent> = dataStore.data.map { prefs ->
        ChatHeaderContent.fromString(prefs[KEY_CHAT_HEADER_CONTENT])
    }

    val chatHeaderAlignment: Flow<ChatHeaderAlignment> = dataStore.data.map { prefs ->
        ChatHeaderAlignment.fromString(prefs[KEY_CHAT_HEADER_ALIGNMENT])
    }

    val autoScrollEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AUTO_SCROLL_ENABLED] ?: true
    }

    // Background prefetch. Deliberately global, not account-scoped — these are preferences about
    // the device's bandwidth and battery. All default off: this spends the user's data on requests
    // they did not make, which has to be asked for.

    val prefetchEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_PREFETCH_ENABLED] ?: false
    }

    /** Nested under [prefetchEnabled]; far heavier than the text it accompanies. */
    val prefetchAttachmentsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_PREFETCH_ATTACHMENTS] ?: false
    }

    /** Overrides the unmetered-only default, for users who are mostly on cellular. */
    val prefetchOnMeteredEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_PREFETCH_ON_METERED] ?: false
    }

    /** Clamped on read, not trusted: this value is the request count for every pass. */
    val prefetchDepth: Flow<Int> = dataStore.data.map { prefs ->
        (prefs[KEY_PREFETCH_DEPTH] ?: PrefetchDepth.DEFAULT).coerceIn(PrefetchDepth.RANGE)
    }

    val showThinkingBlocks: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SHOW_THINKING_BLOCKS] ?: true
    }

    /** Where the v0.8.7 context-usage gauge is surfaced. Default [ContextBarPlacement.OPTIONS_SHEET]. */
    val contextBarPlacement: Flow<ContextBarPlacement> = dataStore.data.map { prefs ->
        ContextBarPlacement.fromString(prefs[KEY_CONTEXT_BAR_PLACEMENT])
    }

    /** Whether the options-sheet context gauge's inline breakdown is expanded. */
    val contextGaugeExpanded: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_CONTEXT_GAUGE_EXPANDED] ?: false
    }

    /**
     * What the send control does mid-run (v0.8.8 steering). Default [DuringRunAction.QUEUE] —
     * steering needs a server that has the route, queueing works everywhere.
     */
    val duringRunAction: Flow<DuringRunAction> = dataStore.data.map { prefs ->
        DuringRunAction.fromString(prefs[KEY_DURING_RUN_ACTION])
    }

    /**
     * Whether composer attachments are routed to the provider / to text extraction automatically,
     * or with a prompt. Default [UploadRoutingMode.AUTO].
     */
    val uploadRoutingMode: Flow<UploadRoutingMode> = dataStore.data.map { prefs ->
        UploadRoutingMode.fromString(prefs[KEY_UPLOAD_ROUTING_MODE])
    }

    val autoReadEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AUTO_READ_ENABLED] ?: false
    }

    val showImageDescriptions: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SHOW_IMAGE_DESCRIPTIONS] ?: false
    }

    val selectedVoiceId: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_VOICE_ID]
    }

    // Account-scoped: the last endpoint/model is per-account (server A's pick must not seed server B).
    // Emits nothing while the account is Warming (NOT null) so a one-shot/wait-on-unresolved consumer
    // — the model seeder — blocks for the real value instead of mistaking Warming for "none saved" and
    // seeding a lower tier; emits null only once the account resolves (logged out or genuinely absent).
    val lastUsedEndpoint: Flow<String?> = scopedString(::endpointKey)

    val lastUsedModel: Flow<String?> = scopedString(::modelKey)

    /**
     * The account's most-used endpoint/model pairs, ranked by send count (recency breaks ties),
     * capped to [limit]. Backs the home-screen app shortcuts / quick actions. Emits nothing while
     * the account is Warming (so a one-shot consumer waits for the real value) and an empty list
     * once resolved-but-logged-out, so a shortcut publisher clears its entries on sign-out.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun topUsedModels(limit: Int): Flow<List<ModelRef>> =
        activeAccountProvider.state.flatMapLatest { state ->
            when (state) {
                AccountState.Warming -> emptyFlow()
                is AccountState.Resolved ->
                    state.id?.let { id ->
                        dataStore.data.map { prefs -> rankUsage(prefs[usageKey(id.value)], limit) }
                    } ?: flowOf(emptyList())
            }
        }

    val dismissKeyboardOnSend: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_DISMISS_KEYBOARD_ON_SEND] ?: true
    }

    val ttsSource: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_SOURCE] ?: "device"
    }

    val ttsSpeechRate: Flow<Float> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_SPEECH_RATE] ?: 1.0f
    }

    val ttsPitch: Flow<Float> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_PITCH] ?: 1.0f
    }

    val ttsVoiceName: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_VOICE_NAME] ?: ""
    }

    val tabletSidebarOpen: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_TABLET_SIDEBAR_OPEN] ?: true
    }

    val tabletSidebarGestureEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_TABLET_SIDEBAR_GESTURE_ENABLED] ?: true
    }

    val autoSendAfterStt: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AUTO_SEND_AFTER_STT] ?: false
    }

    val sttEngine: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_STT_ENGINE] ?: ""
    }

    val sttLanguage: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_STT_LANGUAGE] ?: ""
    }

    /**
     * Mobile-only preference: when the Browser STT engine is selected, prefer the platform's
     * on-device recognizer (Android `createOnDeviceSpeechRecognizer` / iOS
     * `requiresOnDeviceRecognition`) over the cloud recognizer. Defaults ON. Irrelevant for the
     * External engine (server transcription is inherently remote). Has no web counterpart.
     */
    val sttOnDevice: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_STT_ON_DEVICE] ?: true
    }

    /**
     * Mobile-only preference for the Built-in STT engine: when ON, dictation stops as soon as the
     * recognizer detects you've finished speaking (end-of-speech), enabling a hands-free flow where
     * [autoSendAfterStt] then sends the message. When OFF (default) dictation runs continuously until
     * the user taps stop. Irrelevant for the External engine (single-shot record→upload). No web
     * counterpart.
     */
    val sttEndOfSpeech: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_STT_END_OF_SPEECH] ?: false
    }

    val ttsEngine: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_ENGINE] ?: ""
    }

    val ttsVoice: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_VOICE] ?: ""
    }

    val ttsCaching: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_CACHING] ?: true
    }

    val chatLayoutStyle: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_CHAT_LAYOUT_STYLE] ?: "thread"
    }

    // Unlike the typed enum prefs above (StarredModelsDisplay, ContextBarPlacement, …), these are
    // exposed as raw String and mapped in the caller: their enums (FileSortField/FileViewMode in
    // :feature:files, DrawerTab in :shared) are UI-domain types that can't be imported down into
    // :core:data. Keep the enum<->string mapping in the ViewModel — don't "harmonize" by moving them.

    /** Files screen list/grid toggle, stored as the FileViewMode storage string; null until first set. */
    val filesViewMode: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_FILES_VIEW_MODE]
    }

    /** Files screen sort field, stored as the FileSortField storage string; null until first set. */
    val filesSortField: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_FILES_SORT_FIELD]
    }

    /** Files screen sort order, stored as the FileSortOrder storage string; null until first set. */
    val filesSortOrder: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_FILES_SORT_ORDER]
    }

    /** Drawer "Library" toggle, stored as the DrawerTab storage string; null until first set. */
    val drawerLibraryTab: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_DRAWER_LIBRARY_TAB]
    }

    val showAvatars: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SHOW_AVATARS] ?: true
    }

    val showBubbles: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SHOW_BUBBLES] ?: false
    }

    val inlineArtifactPrefs: Flow<InlineArtifactPrefs> = dataStore.data.map { prefs ->
        InlineArtifactPrefs(
            mermaid = prefs[KEY_INLINE_ARTIFACT_MERMAID] ?: false,
            svg = prefs[KEY_INLINE_ARTIFACT_SVG] ?: false,
            html = prefs[KEY_INLINE_ARTIFACT_HTML] ?: false,
            react = prefs[KEY_INLINE_ARTIFACT_REACT] ?: false,
            markdown = prefs[KEY_INLINE_ARTIFACT_MARKDOWN] ?: false,
        )
    }

    val artifactDisplayPrefs: Flow<ArtifactDisplayPrefs> = dataStore.data.map { prefs ->
        ArtifactDisplayPrefs(
            mode = ArtifactDisplayMode.fromString(prefs[KEY_ARTIFACT_DISPLAY_MODE]),
        )
    }

    val selectedMcpServers: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_MCP_SERVERS]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    val enabledTools: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_ENABLED_TOOLS]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    /**
     * The backend version for which the user dismissed the version mismatch warning.
     * When the backend updates to a new version, the dialog will appear again.
     */
    val dismissedVersionWarning: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_DISMISSED_VERSION_WARNING]
    }

    suspend fun setSelectedLanguage(languageCode: String) {
        dataStore.edit { prefs ->
            prefs[KEY_SELECTED_LANGUAGE] = languageCode
        }
    }

    suspend fun setLatexRenderer(renderer: LatexRenderer) {
        dataStore.edit { prefs ->
            prefs[KEY_LATEX_RENDERER] = renderer.toStorageString()
        }
    }

    suspend fun setChatFontSize(size: ChatFontSize) {
        dataStore.edit { prefs ->
            prefs[KEY_CHAT_FONT_SIZE] = size.toStorageString()
        }
    }

    suspend fun setStarredModelsDisplay(display: StarredModelsDisplay) {
        dataStore.edit { prefs ->
            prefs[KEY_STARRED_MODELS_DISPLAY] = display.toStorageString()
        }
    }

    suspend fun setChatHeaderContent(content: ChatHeaderContent) {
        dataStore.edit { prefs ->
            prefs[KEY_CHAT_HEADER_CONTENT] = content.toStorageString()
        }
    }

    suspend fun setChatHeaderAlignment(alignment: ChatHeaderAlignment) {
        dataStore.edit { prefs ->
            prefs[KEY_CHAT_HEADER_ALIGNMENT] = alignment.toStorageString()
        }
    }

    suspend fun setAutoScrollEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_AUTO_SCROLL_ENABLED] = enabled
        }
    }

    suspend fun setPrefetchEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_PREFETCH_ENABLED] = enabled
        }
    }

    suspend fun setPrefetchAttachmentsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_PREFETCH_ATTACHMENTS] = enabled
        }
    }

    suspend fun setPrefetchDepth(depth: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_PREFETCH_DEPTH] = depth.coerceIn(PrefetchDepth.RANGE)
        }
    }

    suspend fun setPrefetchOnMeteredEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_PREFETCH_ON_METERED] = enabled
        }
    }

    suspend fun setShowThinkingBlocks(show: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SHOW_THINKING_BLOCKS] = show
        }
    }

    suspend fun setContextBarPlacement(placement: ContextBarPlacement) {
        dataStore.edit { prefs ->
            prefs[KEY_CONTEXT_BAR_PLACEMENT] = placement.toStorageString()
        }
    }

    suspend fun setDuringRunAction(action: DuringRunAction) {
        dataStore.edit { prefs ->
            prefs[KEY_DURING_RUN_ACTION] = action.toStorageString()
        }
    }

    suspend fun setUploadRoutingMode(mode: UploadRoutingMode) {
        dataStore.edit { prefs ->
            prefs[KEY_UPLOAD_ROUTING_MODE] = mode.toStorageString()
        }
    }

    suspend fun setContextGaugeExpanded(expanded: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_CONTEXT_GAUGE_EXPANDED] = expanded
        }
    }

    suspend fun setAutoReadEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_AUTO_READ_ENABLED] = enabled
        }
    }

    suspend fun setShowImageDescriptions(show: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SHOW_IMAGE_DESCRIPTIONS] = show
        }
    }

    suspend fun setDismissKeyboardOnSend(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_DISMISS_KEYBOARD_ON_SEND] = enabled
        }
    }

    suspend fun setSelectedVoiceId(voiceId: String) {
        dataStore.edit { prefs ->
            prefs[KEY_SELECTED_VOICE_ID] = voiceId
        }
    }

    suspend fun setLastUsedModel(endpoint: String, model: String) {
        val accountId = activeAccountProvider.currentAccountId()?.value ?: return
        dataStore.edit { prefs ->
            prefs[endpointKey(accountId)] = endpoint
            prefs[modelKey(accountId)] = model
            // Reset migration: drop the pre-keying bare entries (they'd mis-attribute A's model to B).
            prefs.remove(stringPreferencesKey(LAST_USED_ENDPOINT))
            prefs.remove(stringPreferencesKey(LAST_USED_MODEL))
        }
    }

    // Suspends (emits nothing) while Warming; once Resolved emits the account's scoped value, or null
    // when logged out. flatMapLatest re-subscribes on every identity transition so a switch re-emits.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun scopedString(keyFor: (String) -> Preferences.Key<String>): Flow<String?> =
        activeAccountProvider.state.flatMapLatest { state ->
            when (state) {
                AccountState.Warming -> emptyFlow()
                is AccountState.Resolved ->
                    state.id?.let { id -> dataStore.data.map { it[keyFor(id.value)] } } ?: flowOf(null)
            }
        }

    private fun endpointKey(accountId: String) = accountScopedKey(accountId, LAST_USED_ENDPOINT)

    private fun modelKey(accountId: String) = accountScopedKey(accountId, LAST_USED_MODEL)

    private fun usageKey(accountId: String) = accountScopedKey(accountId, MODEL_USAGE)

    private fun listRefreshKey(accountId: String) =
        accountScopedKey(accountId, PREFETCH_LIST_REFRESHED_AT)

    /**
     * When the prefetcher last paged the whole conversation list for [accountId], or null if it
     * never has. Account-scoped, so the prefix purge sweeps it on logout with everything else.
     */
    suspend fun prefetchListRefreshedAt(accountId: String): Long? =
        dataStore.data.first()[listRefreshKey(accountId)]?.toLongOrNull()

    suspend fun recordPrefetchListRefreshed(accountId: String, atMillis: Long) {
        dataStore.edit { prefs -> prefs[listRefreshKey(accountId)] = atMillis.toString() }
    }

    /** Forgets the recorded refresh, so the next pass pages the list rather than reusing it. */
    suspend fun clearPrefetchListRefreshed(accountId: String) {
        dataStore.edit { prefs -> prefs.remove(listRefreshKey(accountId)) }
    }

    private fun scheduledRunKey(accountId: String) =
        accountScopedKey(accountId, PREFETCH_LAST_SCHEDULED_RUN)

    /**
     * The last scheduled prefetch for the active account, and how it ended.
     *
     * Account-scoped, because what a run did is account-specific and every other figure on the
     * readout is resolved against the active account — a device-level record would show one
     * account's overnight warm on another account's screen. Emits null rather than nothing while
     * identity is warming: this feeds a `combine` that would otherwise hold the entire readout blank
     * until an account resolved.
     *
     * Stored as one string so adding a field cannot silently change the shape of an existing
     * install's value: an entry that does not parse reads as "no run recorded", which is honest.
     */
    val lastScheduledRun: Flow<ScheduledRunRecord?> =
        activeAccountProvider.flatMapAccountOrEmpty<ScheduledRunRecord?>(null) { id ->
            dataStore.data.map { prefs -> prefs[scheduledRunKey(id.value)]?.let(::decodeScheduledRun) }
        }

    /** No-ops without an account: a run that never resolved one has nothing to attribute the record to. */
    suspend fun recordScheduledRun(accountId: String?, record: ScheduledRunRecord) {
        if (accountId == null) return
        dataStore.edit { prefs ->
            prefs[scheduledRunKey(accountId)] = "${record.atMillis}|${record.outcome.name}"
        }
    }

    private fun decodeScheduledRun(raw: String): ScheduledRunRecord? {
        val (millis, outcome) = raw.split('|', limit = 2).takeIf { it.size == 2 } ?: return null
        return ScheduledRunRecord(
            atMillis = millis.toLongOrNull() ?: return null,
            // An outcome this build does not know about (a downgrade) is not worth failing over,
            // but it must not be guessed at either.
            outcome = PrefetchRunOutcome.entries.firstOrNull { it.name == outcome } ?: return null,
        )
    }

    /**
     * Records one "used" tick for [endpoint]/[model] — called once per message sent, the true
     * usage signal (a passively auto-restored model that's never chatted with must not climb the
     * ranking). Read-modify-write of the account-scoped counts map; a monotonically increasing
     * [UsageEntry.seq] provides the recency tie-break and bounds map growth via [MAX_USAGE_ENTRIES].
     */
    suspend fun incrementModelUsage(endpoint: String, model: String) {
        if (endpoint.isBlank() || model.isBlank()) return
        // Await account resolution instead of snapshotting it: the first send right after a fresh
        // login can fire before the active account has re-homed (the bearer is already staged, so the
        // chat POST succeeds), and a snapshot read would hit null and drop that usage silently. Only
        // reached from the send path, which implies a logged-in account, so this resolves promptly.
        val accountId = activeAccountProvider.awaitResolvedAccount().value
        val key = usageKey(accountId)
        // The mutation is a durable write; keep it off the caller's cancellation so a ViewModel
        // teardown mid-edit (e.g. the new-chat → Chat(id) handoff) can't drop the recorded tick.
        withContext(NonCancellable) {
            dataStore.edit { prefs ->
                val current = decodeUsage(prefs[key])
                val nextSeq = (current.values.maxOfOrNull { it.seq } ?: 0L) + 1
                val composite = usageCompositeKey(endpoint, model)
                val incremented = current[composite]?.let { it.copy(count = it.count + 1, seq = nextSeq) }
                    ?: UsageEntry(count = 1, seq = nextSeq)
                val merged = current + (composite to incremented)
                // Keep the map bounded: retain the strongest entries by the same (count, recency) order
                // the ranking uses, so a long tail of one-off models can't grow the pref without limit.
                val trimmed = if (merged.size > MAX_USAGE_ENTRIES) {
                    merged.entries
                        .sortedWith(compareByDescending<Map.Entry<String, UsageEntry>> { it.value.count }
                            .thenByDescending { it.value.seq })
                        .take(MAX_USAGE_ENTRIES)
                        .associate { it.toPair() }
                } else {
                    merged
                }
                prefs[key] = usageJson.encodeToString(trimmed)
            }
        }
    }

    private fun usageCompositeKey(endpoint: String, model: String) =
        "$endpoint$USAGE_KEY_SEPARATOR$model"

    /** Tolerant decode — a malformed/absent blob yields an empty map, never a crash. */
    private fun decodeUsage(raw: String?): Map<String, UsageEntry> =
        raw?.let { runCatching { usageJson.decodeFromString<Map<String, UsageEntry>>(it) }.getOrNull() }
            ?: emptyMap()

    /** Decode + rank by (count desc, recency desc), take [limit], split composite keys to [ModelRef]. */
    private fun rankUsage(raw: String?, limit: Int): List<ModelRef> =
        decodeUsage(raw).entries
            .sortedWith(
                compareByDescending<Map.Entry<String, UsageEntry>> { it.value.count }
                    .thenByDescending { it.value.seq },
            )
            .take(limit)
            .mapNotNull { (composite, _) ->
                val sep = composite.indexOf(USAGE_KEY_SEPARATOR)
                if (sep <= 0 || sep >= composite.lastIndex) {
                    null
                } else {
                    ModelRef(composite.substring(0, sep), composite.substring(sep + 1))
                }
            }

    suspend fun setTtsSource(source: String) {
        dataStore.edit { prefs ->
            prefs[KEY_TTS_SOURCE] = source
        }
    }

    suspend fun setTtsSpeechRate(rate: Float) {
        dataStore.edit { prefs ->
            prefs[KEY_TTS_SPEECH_RATE] = rate
        }
    }

    suspend fun setTtsPitch(pitch: Float) {
        dataStore.edit { prefs ->
            prefs[KEY_TTS_PITCH] = pitch
        }
    }

    suspend fun setTtsVoiceName(voiceName: String) {
        dataStore.edit { prefs ->
            prefs[KEY_TTS_VOICE_NAME] = voiceName
        }
    }

    suspend fun setTabletSidebarOpen(open: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_TABLET_SIDEBAR_OPEN] = open
        }
    }

    suspend fun setTabletSidebarGestureEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_TABLET_SIDEBAR_GESTURE_ENABLED] = enabled
        }
    }

    suspend fun setAutoSendAfterStt(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_AUTO_SEND_AFTER_STT] = enabled
        }
    }

    suspend fun setSttEngine(engine: String) {
        dataStore.edit { prefs ->
            prefs[KEY_STT_ENGINE] = engine
        }
    }

    suspend fun setSttLanguage(language: String) {
        dataStore.edit { prefs ->
            prefs[KEY_STT_LANGUAGE] = language
        }
    }

    suspend fun setSttOnDevice(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_STT_ON_DEVICE] = enabled
        }
    }

    suspend fun setSttEndOfSpeech(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_STT_END_OF_SPEECH] = enabled
        }
    }

    suspend fun setTtsEngine(engine: String) {
        dataStore.edit { prefs ->
            prefs[KEY_TTS_ENGINE] = engine
        }
    }

    suspend fun setTtsVoice(voice: String) {
        dataStore.edit { prefs ->
            prefs[KEY_TTS_VOICE] = voice
        }
    }

    suspend fun setTtsCaching(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_TTS_CACHING] = enabled
        }
    }

    suspend fun setChatLayoutStyle(style: String) {
        dataStore.edit { prefs ->
            prefs[KEY_CHAT_LAYOUT_STYLE] = style
        }
    }

    suspend fun setFilesViewMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[KEY_FILES_VIEW_MODE] = mode
        }
    }

    suspend fun setFilesSort(field: String, order: String) {
        dataStore.edit { prefs ->
            prefs[KEY_FILES_SORT_FIELD] = field
            prefs[KEY_FILES_SORT_ORDER] = order
        }
    }

    suspend fun setDrawerLibraryTab(tab: String) {
        dataStore.edit { prefs ->
            prefs[KEY_DRAWER_LIBRARY_TAB] = tab
        }
    }

    suspend fun setShowAvatars(show: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SHOW_AVATARS] = show
        }
    }

    suspend fun setShowBubbles(show: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SHOW_BUBBLES] = show
        }
    }

    suspend fun setInlineArtifactMermaid(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_INLINE_ARTIFACT_MERMAID] = enabled }
    }

    suspend fun setInlineArtifactSvg(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_INLINE_ARTIFACT_SVG] = enabled }
    }

    suspend fun setInlineArtifactHtml(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_INLINE_ARTIFACT_HTML] = enabled }
    }

    suspend fun setInlineArtifactReact(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_INLINE_ARTIFACT_REACT] = enabled }
    }

    suspend fun setInlineArtifactMarkdown(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_INLINE_ARTIFACT_MARKDOWN] = enabled }
    }

    suspend fun setArtifactDisplayMode(mode: ArtifactDisplayMode) {
        dataStore.edit { prefs -> prefs[KEY_ARTIFACT_DISPLAY_MODE] = mode.toStorageString() }
    }

    /**
     * Saves the backend version for which the user chose "Don't warn again".
     * If the backend later updates to a different version, the warning will reappear.
     */
    suspend fun setDismissedVersionWarning(version: String) {
        dataStore.edit { prefs ->
            prefs[KEY_DISMISSED_VERSION_WARNING] = version
        }
    }

    suspend fun setSelectedMcpServers(servers: Set<String>) {
        dataStore.edit { prefs ->
            if (servers.isEmpty()) {
                prefs.remove(KEY_SELECTED_MCP_SERVERS)
            } else {
                prefs[KEY_SELECTED_MCP_SERVERS] = servers.joinToString(",")
            }
        }
    }

    suspend fun setEnabledTools(tools: Set<String>) {
        dataStore.edit { prefs ->
            if (tools.isEmpty()) {
                prefs.remove(KEY_ENABLED_TOOLS)
            } else {
                prefs[KEY_ENABLED_TOOLS] = tools.joinToString(",")
            }
        }
    }

    companion object {
        /**
         * Sentinel meaning "follow the device locale" — the default until the user picks a
         * specific language. Maps to a null locale override at the app root, so formatting and
         * resource resolution keep following the system (current behavior preserved).
         */
        const val DEFAULT_LANGUAGE = "system"

        private val KEY_SELECTED_LANGUAGE = stringPreferencesKey("selected_language")
        private val KEY_LATEX_RENDERER = stringPreferencesKey("latex_renderer")
        private val KEY_CHAT_FONT_SIZE = stringPreferencesKey("chat_font_size")
        private val KEY_STARRED_MODELS_DISPLAY = stringPreferencesKey("starred_models_display")
        private val KEY_CHAT_HEADER_CONTENT = stringPreferencesKey("chat_header_content")
        private val KEY_CHAT_HEADER_ALIGNMENT = stringPreferencesKey("chat_header_alignment")
        private val KEY_AUTO_SCROLL_ENABLED = booleanPreferencesKey("auto_scroll_enabled")
        private val KEY_PREFETCH_ENABLED = booleanPreferencesKey("prefetch_enabled")
        private val KEY_PREFETCH_ATTACHMENTS = booleanPreferencesKey("prefetch_attachments")
        private val KEY_PREFETCH_ON_METERED = booleanPreferencesKey("prefetch_on_metered")
        private val KEY_PREFETCH_DEPTH = intPreferencesKey("prefetch_depth")
        private val KEY_SHOW_THINKING_BLOCKS = booleanPreferencesKey("show_thinking_blocks")
        private val KEY_CONTEXT_BAR_PLACEMENT = stringPreferencesKey("context_bar_placement")
        private val KEY_DURING_RUN_ACTION = stringPreferencesKey("during_run_action")
        private val KEY_UPLOAD_ROUTING_MODE = stringPreferencesKey("upload_routing_mode")
        private val KEY_CONTEXT_GAUGE_EXPANDED = booleanPreferencesKey("context_gauge_expanded")
        private val KEY_AUTO_READ_ENABLED = booleanPreferencesKey("auto_read_enabled")
        private val KEY_SHOW_IMAGE_DESCRIPTIONS = booleanPreferencesKey("show_image_descriptions")
        private val KEY_SELECTED_VOICE_ID = stringPreferencesKey("selected_voice_id")
        private const val LAST_USED_ENDPOINT = "last_used_endpoint"
        private const val LAST_USED_MODEL = "last_used_model"
        private const val MODEL_USAGE = "model_usage"
        private const val PREFETCH_LIST_REFRESHED_AT = "prefetch_list_refreshed_at"
        private const val PREFETCH_LAST_SCHEDULED_RUN = "prefetch_last_scheduled_run"
        private const val MAX_USAGE_ENTRIES = 50
        private const val USAGE_KEY_SEPARATOR = '\u0000'
        private val KEY_DISMISS_KEYBOARD_ON_SEND = booleanPreferencesKey("dismiss_keyboard_on_send")
        private val KEY_TTS_SOURCE = stringPreferencesKey("tts_source")
        private val KEY_TTS_SPEECH_RATE = floatPreferencesKey("tts_speech_rate")
        private val KEY_TTS_PITCH = floatPreferencesKey("tts_pitch")
        private val KEY_TTS_VOICE_NAME = stringPreferencesKey("tts_voice_name")
        private val KEY_TABLET_SIDEBAR_OPEN = booleanPreferencesKey("tablet_sidebar_open")
        private val KEY_TABLET_SIDEBAR_GESTURE_ENABLED = booleanPreferencesKey("tablet_sidebar_gesture_enabled")
        private val KEY_AUTO_SEND_AFTER_STT = booleanPreferencesKey("auto_send_after_stt")
        private val KEY_STT_ENGINE = stringPreferencesKey("stt_engine")
        private val KEY_STT_LANGUAGE = stringPreferencesKey("stt_language")
        private val KEY_STT_ON_DEVICE = booleanPreferencesKey("stt_on_device")
        private val KEY_STT_END_OF_SPEECH = booleanPreferencesKey("stt_end_of_speech")
        private val KEY_TTS_ENGINE = stringPreferencesKey("tts_engine")
        private val KEY_TTS_VOICE = stringPreferencesKey("tts_voice")
        private val KEY_TTS_CACHING = booleanPreferencesKey("tts_caching")
        private val KEY_CHAT_LAYOUT_STYLE = stringPreferencesKey("chat_layout_style")
        private val KEY_FILES_VIEW_MODE = stringPreferencesKey("files_view_mode")
        private val KEY_FILES_SORT_FIELD = stringPreferencesKey("files_sort_field")
        private val KEY_FILES_SORT_ORDER = stringPreferencesKey("files_sort_order")
        private val KEY_DRAWER_LIBRARY_TAB = stringPreferencesKey("drawer_library_tab")
        private val KEY_SHOW_AVATARS = booleanPreferencesKey("show_avatars")
        private val KEY_SHOW_BUBBLES = booleanPreferencesKey("show_bubbles")
        private val KEY_INLINE_ARTIFACT_MERMAID = booleanPreferencesKey("inline_artifact_mermaid")
        private val KEY_INLINE_ARTIFACT_SVG = booleanPreferencesKey("inline_artifact_svg")
        private val KEY_INLINE_ARTIFACT_HTML = booleanPreferencesKey("inline_artifact_html")
        private val KEY_INLINE_ARTIFACT_REACT = booleanPreferencesKey("inline_artifact_react")
        private val KEY_INLINE_ARTIFACT_MARKDOWN = booleanPreferencesKey("inline_artifact_markdown")
        private val KEY_ARTIFACT_DISPLAY_MODE = stringPreferencesKey("artifact_display_mode")
        private val KEY_DISMISSED_VERSION_WARNING = stringPreferencesKey("dismissed_version_warning")
        private val KEY_SELECTED_MCP_SERVERS = stringPreferencesKey("selected_mcp_servers")
        private val KEY_ENABLED_TOOLS = stringPreferencesKey("enabled_tools")
    }
}

/** Per-model usage record: total send [count], plus a monotonic [seq] for the recency tie-break. */
@Serializable
private data class UsageEntry(val count: Int, val seq: Long)

private val usageJson = Json { ignoreUnknownKeys = true }
