package ai.antigem.domain.model

/** Which coding agent owns a conversation. Fixed when the conversation is created. */
enum class AgentKind {
    AGY,
    CLAUDE,
    OPENCODE,
    CODEX,
    /** ParallaxAI: DeepSeek chats through the ParallaxAI server API, with the conversation kept as context. */
    PARALLAXAI,
    /** Parallax AI RE: full-access agent using a local model (OpenAI-compatible, e.g. llama-server) via OpenCode runtime with phone tools/MCPs. */
    PARALLAX_AI_RE
}
