package net.magicterra.agent.api;

import java.util.Map;

/**
 * Pre-dispatch params gate for {@link AgentApi#route}. Implementations throw
 * {@link IllegalArgumentException} on invalid params. Wired by the bootstrap
 * (AgentDriverCommon) from the MCP ToolCatalog — injected as a functional
 * interface so the api layer stays transport/schema agnostic (Hard Rule #1,
 * same seam style as requireSchemasFor).
 */
@FunctionalInterface
public interface ParamsValidator {
    void validate(String method, Map<String, Object> params);
}
