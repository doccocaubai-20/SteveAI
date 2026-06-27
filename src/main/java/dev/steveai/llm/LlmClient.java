package dev.steveai.llm;

import dev.steveai.agent.SteveAgent;

import java.util.concurrent.CompletableFuture;

public interface LlmClient {
    CompletableFuture<String> plan(SteveAgent agent, String ownerName, String playerRequest);
}
