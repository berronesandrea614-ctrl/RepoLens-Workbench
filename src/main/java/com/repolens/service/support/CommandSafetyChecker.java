package com.repolens.service.support;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class CommandSafetyChecker {

    private static final List<Pattern> BLOCKED = List.of(
            Pattern.compile("\\brm\\s+-rf\\b"),
            Pattern.compile(":\\(\\)\\s*\\{"),
            Pattern.compile("\\bsudo\\b"),
            Pattern.compile("curl.+\\|.*(bash|sh)"),
            Pattern.compile("wget.+\\|.*(bash|sh)"),
            Pattern.compile("\\bgit\\s+push\\b"),
            Pattern.compile("\\bdd\\s+"),
            Pattern.compile("\\bmkfs\\b"),
            Pattern.compile(">\\s*/dev/(sda|nvme)"),
            Pattern.compile("\\breboot\\b"),
            Pattern.compile("\\bshutdown\\b")
    );

    public String check(String command) {
        if (command == null || command.isBlank()) {
            return "empty command";
        }
        for (Pattern p : BLOCKED) {
            if (p.matcher(command).find()) {
                return "blocked by safety checker: matches " + p.pattern();
            }
        }
        return null;
    }
}
