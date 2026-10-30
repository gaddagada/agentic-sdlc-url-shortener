package com.agenticsdlc.orchestrator.core.testsupport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageResult;

/**
 * A configurable fake agent that records its calls and contexts. Its id is {@code deterministic} so that it can
 * stand in for the deterministic agents (primary and fallback).
 */
public final class FakeAgent implements StageAgent {

    /** Behaviour of a call. */
    @FunctionalInterface
    public interface Behavior {
        StageResult execute(StageContext context, int call) throws Exception;
    }

    private final AgentRole role;
    private final Behavior behavior;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<StageContext> contexts = new CopyOnWriteArrayList<>();

    public FakeAgent(AgentRole role, Behavior behavior) {
        this.role = role;
        this.behavior = behavior;
    }

    public static FakeAgent succeeding(AgentRole role) {
        return new FakeAgent(role, (ctx, call) -> StageResult.success(Payloads.forRole(role), "fake " + role));
    }

    @Override
    public AgentRole role() {
        return role;
    }

    @Override
    public String id() {
        return AgentRegistry.DETERMINISTIC;
    }

    @Override
    public StageResult execute(StageContext context) throws Exception {
        int call = calls.incrementAndGet();
        contexts.add(context);
        return behavior.execute(context, call);
    }

    public int calls() {
        return calls.get();
    }

    public List<StageContext> contexts() {
        return List.copyOf(contexts);
    }
}
