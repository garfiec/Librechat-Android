package com.garfiec.librechat.feature.files.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.garfiec.librechat.core.model.FileObject
import com.garfiec.librechat.feature.files.screen.FilesScreen
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Serializable sealed interface FilesRoute : NavKey

@Serializable data object Files : FilesRoute

/**
 * Attachment-picker variant of the files screen. Reached from the chat composer's "From server"
 * attach action so the user can attach an already-uploaded file by reference (no re-upload).
 *
 * [targetConversationId] is the conversation that launched the picker (`null` for the new-chat
 * landing, which has no id yet). It is carried back with the selection so the result routes only
 * to its launcher rather than to whichever chat screen happens to collect first.
 */
@Serializable data class FilesPicker(val targetConversationId: String? = null) : FilesRoute

fun EntryProviderScope<NavKey>.filesEntries(
    onBack: (() -> Unit)? = null,
) {
    entry<Files> {
        FilesScreen(onBack = onBack)
    }
}

/**
 * Registers the [FilesPicker] route. [onConfirm] receives the launching conversation id (from the
 * [FilesPicker] key) and the chosen server files; the host wires it to stage the selection and pop
 * back to the chat composer.
 */
fun EntryProviderScope<NavKey>.filePickerEntries(
    onConfirm: (targetConversationId: String?, files: List<FileObject>) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    entry<FilesPicker> { key ->
        FilesScreen(
            pickerMode = true,
            onConfirmSelection = { files -> onConfirm(key.targetConversationId, files) },
            onBack = onBack,
        )
    }
}

val filesSerializersModule = SerializersModule {
    polymorphic(NavKey::class) {
        subclass(Files::class, Files.serializer())
        subclass(FilesPicker::class, FilesPicker.serializer())
    }
}
