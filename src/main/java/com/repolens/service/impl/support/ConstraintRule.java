package com.repolens.service.impl.support;

/**
 * Parsed constraint rule from AGENTS.md / CLAUDE.md (Feature B P2).
 * Immutable Java 17 record used for rule caching and violation detection.
 *
 * <p>Severity: BLOCK → shown red in UI; WARN → advisory only.
 * SEMANTIC rules are always WARN + checkable=false (never produce violations).
 */
public record ConstraintRule(
        /** Rule type constant — see TYPE_* fields below. */
        String type,
        /** Glob / path pattern extracted from the rule sentence; null for typeless rules. */
        String pattern,
        /** Original rule sentence from the rules file. */
        String rawText,
        /** If false, this rule is advisory only and will never produce a ConstraintViolation. */
        boolean checkable,
        /** BLOCK or WARN. */
        String severity
) {

    // ── Rule type constants ────────────────────────────────────────────────────
    /** Do not modify a directory (e.g. "don't touch src/legacy"). */
    public static final String PATH_FORBIDDEN     = "PATH_FORBIDDEN";
    /** Do not touch files of a certain type (e.g. "don't modify *.lock"). */
    public static final String FILETYPE_FORBIDDEN = "FILETYPE_FORBIDDEN";
    /** Do not add new dependencies. */
    public static final String NO_NEW_DEP         = "NO_NEW_DEP";
    /** Must run tests / verification before completing. */
    public static final String MUST_VERIFY        = "MUST_VERIFY";
    /** Stay within declared scope. */
    public static final String KEEP_SCOPE         = "KEEP_SCOPE";
    /** Style / advisory — never produces violations, always WARN. */
    public static final String SEMANTIC           = "SEMANTIC";

    // ── Severity constants ─────────────────────────────────────────────────────
    public static final String SEVERITY_BLOCK = "BLOCK";
    public static final String SEVERITY_WARN  = "WARN";
}
