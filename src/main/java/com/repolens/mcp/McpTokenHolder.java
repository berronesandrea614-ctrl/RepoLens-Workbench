package com.repolens.mcp;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Holds the MCP server token generated once at startup.
 * The token is a cryptographically random 32-byte hex string (64 chars).
 * Used for authenticating requests to the /mcp endpoint.
 */
@Component
public class McpTokenHolder {

    private final String token;

    public McpTokenHolder() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        this.token = sb.toString();
    }

    public String getToken() {
        return token;
    }

    /**
     * Constant-time comparison to prevent timing attacks.
     */
    public boolean matches(String candidate) {
        if (candidate == null || candidate.length() != token.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < token.length(); i++) {
            diff |= token.charAt(i) ^ candidate.charAt(i);
        }
        return diff == 0;
    }
}
