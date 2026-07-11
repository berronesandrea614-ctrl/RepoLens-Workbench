package com.repolens.service.support;

import org.springframework.stereotype.Component;

@Component
public class RiskClassifier {

    public String classify(String toolName, String targetPath) {
        if (targetPath == null) targetPath = "";
        if ("deleteFile".equals(toolName)) return "D";
        if ("runVerification".equals(toolName)) return "B";
        if (targetPath.contains("pom.xml") || targetPath.contains("application.yml")
                || targetPath.contains(".env") || targetPath.contains("secret")) return "D";
        if (targetPath.endsWith("Test.java") || targetPath.endsWith("Tests.java")) return "B";
        if (targetPath.endsWith(".java") || targetPath.endsWith(".ts")
                || targetPath.endsWith(".tsx")) return "B";
        return "C";
    }
}
