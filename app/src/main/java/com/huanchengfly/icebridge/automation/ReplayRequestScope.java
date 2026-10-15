package com.huanchengfly.icebridge.automation;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Shares a consumed replay token between hooks belonging to one framework launch only. */
public final class ReplayRequestScope implements AutoCloseable {
    private static final ThreadLocal<ReplayRequestScope> CURRENT = new ThreadLocal<>();
    private final ReplayRequestScope parent;
    private final Map<String, Boolean> verifiedTokens = new HashMap<>();
    private boolean closed;

    private ReplayRequestScope(ReplayRequestScope parent) {
        this.parent = parent;
    }

    /** A nested framework request starts with an independent verification cache. */
    public static ReplayRequestScope open() {
        ReplayRequestScope scope = new ReplayRequestScope(CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    /** Outside a request scope, retain the underlying verifier's ordinary one-use semantics. */
    public static boolean accept(String token, Predicate<String> consume) {
        if (token == null || token.isEmpty()) return false;
        ReplayRequestScope scope = CURRENT.get();
        if (scope == null) return consume.test(token);
        Boolean cached = scope.verifiedTokens.get(token);
        if (cached != null) return cached;
        boolean accepted = consume.test(token);
        scope.verifiedTokens.put(token, accepted);
        return accepted;
    }

    /** Discard this launch's verification and restore only the enclosing launch's scope. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        verifiedTokens.clear();
        if (CURRENT.get() != this) return;
        ReplayRequestScope enclosing = parent;
        while (enclosing != null && enclosing.closed) enclosing = enclosing.parent;
        if (enclosing == null) CURRENT.remove();
        else CURRENT.set(enclosing);
    }
}
