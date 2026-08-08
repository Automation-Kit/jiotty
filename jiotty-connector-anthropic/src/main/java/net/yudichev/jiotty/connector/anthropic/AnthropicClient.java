package net.yudichev.jiotty.connector.anthropic;

import java.util.concurrent.CompletableFuture;

/// Minimal client for Anthropic's Messages API. One request, one reply — no streaming, no tools, no conversation state held here: a multi-turn caller replays
/// the whole exchange in [MessagesRequest#messages()] each time.
public interface AnthropicClient {
    /// Sends `request` and completes with the model's reply.
    ///
    /// A *successful* future still says nothing about whether the reply is usable — check [MessagesResponse#isCompleteTurn()].
    CompletableFuture<MessagesResponse> sendMessage(MessagesRequest request);
}
