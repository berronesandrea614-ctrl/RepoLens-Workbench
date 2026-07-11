package com.repolens.domain.dto.chat;

import lombok.Data;

@Data
public class MentionDTO {
    /** "file" | "symbol" | "selection" */
    private String type;
    /** file: repo relative path; symbol: className#methodName or symbol name; selection: empty */
    private String value;
    /** selection's selected text; null for file/symbol */
    private String extra;
}
