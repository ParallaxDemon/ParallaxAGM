package ai.antigem.domain.model

/** Which coding agent owns a conversation. Fixed when the conversation is created. */
enum class AgentKind {
    AGY,
    CLAUDE,
    OPENCODE,
    CODEX,
    /** ParallaxAI: DeepSeek chats through the ParallaxAI server API, with the conversation kept as context. */
    PARALLAXAI
}
