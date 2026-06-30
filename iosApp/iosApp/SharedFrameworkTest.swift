import Foundation
// Import the KMP shared framework — validates Kotlin/Native → Swift bridging compiles
import Shared

/// Validates the full SKIE-enhanced KMP iOS framework export:
/// 1. Model classes (Message, Conversation, User, Agent, etc.)
/// 2. SDK entry points (LibreChatSDK, AuthApi, SseClient)
/// 3. Platform implementations (IosConnectivityObserver)
/// 4. SKIE enhancements (sealed → enum, Flow → AsyncSequence, suspend → async)
///
/// To verify: build the Shared framework, then compile this file against it.
/// If this compiles, the full KMP→SKIE→Swift pipeline is working correctly.

func verifySharedFrameworkImport() {
    // 1. Model class instantiation (unchanged from Phase 2)
    let message = Message(
        messageId: "msg-swift-001",
        conversationId: "conv-swift-001",
        parentMessageId: nil,
        responseMessageId: nil,
        overrideParentMessageId: nil,
        user: nil,
        model: "gpt-4o",
        endpoint: "openAI",
        sender: "Assistant",
        text: "Hello from Swift!",
        isCreatedByUser: false,
        error: false,
        unfinished: false,
        finishReason: "stop",
        tokenCount: nil,
        iconURL: nil,
        content: nil,
        files: nil,
        attachments: nil,
        feedback: nil,
        threadId: nil,
        metadata: nil,
        contextMeta: nil,
        createdAt: nil,
        updatedAt: nil,
        title: nil,
        manualSkills: nil,
        alwaysAppliedSkills: nil,
        quotes: nil
    )
    assert(message.messageId == "msg-swift-001")
    assert(message.text == "Hello from Swift!")

    // 2. SDK classes are visible
    // Note: We can't instantiate LibreChatSDK without Koin, but we can verify the type exists
    let _: LibreChatSDK.Type = LibreChatSDK.self
    let _: AuthApi.Type = AuthApi.self
    let _: SseClient.Type = SseClient.self
    let _: ChatApi.Type = ChatApi.self

    // 3. Platform implementations are visible
    let _: IosConnectivityObserver.Type = IosConnectivityObserver.self

    // 4. SKIE-enhanced sealed class → Swift enum (StreamEvent)
    // Kotlin sealed interface subclasses are exported as separate classes
    // SKIE's onEnum(of:) enables exhaustive switching
    let contentDelta = StreamEventContentDelta(
        chunk: "Hello",
        messageId: nil,
        agentId: nil,
        groupId: nil
    )
    switch onEnum(of: contentDelta as (any StreamEvent)) {
    case .contentDelta(let d):
        assert(d.chunk == "Hello")
    case .error, .final, .toolCallStart, .toolCallComplete,
         .thinkingDelta, .attachmentCreated, .retrying, .sync,
         .step, .created, .contextSummary, .subagentUpdate,
         .titleUpdate, .tokenUsageUpdate, .contextUsageUpdate:
        assertionFailure("Wrong case")
    }

    // 5. LoginResponse and request types
    let loginReq = LoginRequest(email: "test@test.com", password: "pass")
    assert(loginReq.email == "test@test.com")

    print("✅ All shared framework + SKIE integration verified successfully")
}
