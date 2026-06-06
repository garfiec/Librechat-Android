package com.garfiec.librechat.shared.navigation

import androidx.savedstate.serialization.SavedStateConfiguration
import com.garfiec.librechat.feature.agents.navigation.agentsSerializersModule
import com.garfiec.librechat.feature.auth.navigation.authSerializersModule
import com.garfiec.librechat.feature.chat.navigation.chatSerializersModule
import com.garfiec.librechat.feature.conversations.navigation.conversationsSerializersModule
import com.garfiec.librechat.feature.files.navigation.filesSerializersModule
import com.garfiec.librechat.feature.settings.navigation.settingsSerializersModule
import com.garfiec.librechat.feature.skills.navigation.skillsSerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus

/**
 * Combined [SerializersModule] for all navigation route types.
 * Registers polymorphic serializers for each feature module's sealed route hierarchy.
 * Used by Nav 3's SavedStateConfiguration for type-safe state saving.
 */
val navigationSerializersModule: SerializersModule =
    authSerializersModule +
        chatSerializersModule +
        conversationsSerializersModule +
        agentsSerializersModule +
        filesSerializersModule +
        settingsSerializersModule +
        skillsSerializersModule

/**
 * Nav 3 [SavedStateConfiguration] with polymorphic serialization for all route types.
 * Required for KMP (iOS) since Nav 3 cannot use reflection-based serialization on non-JVM platforms.
 */
val navigationSavedStateConfig = SavedStateConfiguration {
    serializersModule = navigationSerializersModule
}
