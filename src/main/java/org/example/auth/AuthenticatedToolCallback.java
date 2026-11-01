package org.example.auth;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Each agent receives callbacks bound to its request, never a process-wide user override. */
public final class AuthenticatedToolCallback implements ToolCallback {
    private final ToolCallback delegate;
    private final AuthContext.Principal principal;

    public AuthenticatedToolCallback(ToolCallback delegate, AuthContext.Principal principal) {
        this.delegate = delegate;
        this.principal = principal;
    }

    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
    @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
    @Override public String call(String input) {
        return AuthContext.callAs(principal, () -> delegate.call(input));
    }
    @Override public String call(String input, ToolContext context) {
        return AuthContext.callAs(principal, () -> delegate.call(input, context));
    }
}
