package com.garfiec.librechat.shared

import com.garfiec.librechat.core.network.api.AgentToolsApi
import com.garfiec.librechat.core.network.api.PermissionsApi
import com.garfiec.librechat.core.network.api.SkillsApi
import com.garfiec.librechat.core.network.sse.SseClient
import org.koin.core.annotation.KoinInternalApi
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Builds the **real** iOS Koin graph (iOS actuals resolve here — unlike the JVM
 * `KoinGraphVerificationTest`, which resolves the same shared list against Android
 * actuals) and asserts the definitions the iOS graph must carry are registered.
 *
 * What this guards: the exact bug this refactor fixed — iOS having dropped
 * `networkModule` and hand-listing a drifted subset of API services (it was missing
 * [AgentToolsApi]/[PermissionsApi]/[SkillsApi], whose repos in the shared `dataModule`
 * would `NoDefinitionFound` at runtime on device). Their presence proves iOS still
 * `includes(networkModule)`; [SseClient] additionally proves the moved iOS
 * `SseHttpTransport` binding is in the graph; [LibreChatSDK] is the sole iOS-only
 * binding (Swift resolves it at launch) that no shared module and no JVM test covers.
 *
 * What it deliberately does NOT do: `createEagerInstances = false` + a
 * registration-presence check (not `get`) means no provider lambda runs, so there is
 * zero DataStore/keychain I/O and no `createdAtStart` `AccountRegistry` construction —
 * making the K/N unit binary deterministic and I/O-free. It therefore does not catch
 * *intra-actual* per-binding drift (a platform actual binding a different set than its
 * counterpart); that residual gap is called out in the plan and stays device-tested.
 */
@OptIn(KoinInternalApi::class)
class IosKoinGraphTest {

    @Test
    fun iosGraphBindsNetworkSurfaceAndSdk() {
        // == the production iOS graph: IosKoinHelper starts modules(iosSharedModule).
        val koin = koinApplication(createEagerInstances = false) {
            modules(iosSharedModule)
        }.koin

        listOf(
            LibreChatSDK::class,
            AgentToolsApi::class,
            PermissionsApi::class,
            SkillsApi::class,
            SseClient::class,
        ).forEach { c ->
            // hasType (not primaryType ==) so a future `singleOf(::Impl) bind Interface`
            // still matches on its secondary type.
            assertTrue(
                koin.instanceRegistry.instances.values.any { it.beanDefinition.hasType(c) },
                "iOS Koin graph is missing a binding for ${c.simpleName}",
            )
        }
    }
}
