package com.garfiec.librechat.feature.skills.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.garfiec.librechat.feature.skills.screen.SkillDetailScreen
import com.garfiec.librechat.feature.skills.screen.SkillEditorScreen
import com.garfiec.librechat.feature.skills.screen.SkillsListScreen
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Serializable sealed interface SkillsRoute : NavKey

@Serializable data object SkillsList : SkillsRoute

@Serializable data class SkillDetail(val skillId: String) : SkillsRoute

/** [skillId] null = create a new skill; non-null = edit an existing one. */
@Serializable data class SkillEditor(val skillId: String? = null) : SkillsRoute

fun EntryProviderScope<NavKey>.skillsEntries(
    onNavigate: (NavKey) -> Unit,
    onBack: () -> Unit,
) {
    entry<SkillsList> {
        SkillsListScreen(
            onSkillClick = { id -> onNavigate(SkillDetail(skillId = id)) },
            onCreateSkill = { onNavigate(SkillEditor(skillId = null)) },
            onBack = onBack,
        )
    }
    entry<SkillDetail> { key ->
        SkillDetailScreen(
            skillId = key.skillId,
            onBack = onBack,
            onEdit = { id -> onNavigate(SkillEditor(skillId = id)) },
            onDelete = onBack,
        )
    }
    entry<SkillEditor> { key ->
        SkillEditorScreen(
            skillId = key.skillId,
            onBack = onBack,
            // After save, go back to wherever we came from (list or detail).
            onSave = { onBack() },
        )
    }
}

val skillsSerializersModule = SerializersModule {
    polymorphic(NavKey::class) {
        subclass(SkillsList::class, SkillsList.serializer())
        subclass(SkillDetail::class, SkillDetail.serializer())
        subclass(SkillEditor::class, SkillEditor.serializer())
    }
}
