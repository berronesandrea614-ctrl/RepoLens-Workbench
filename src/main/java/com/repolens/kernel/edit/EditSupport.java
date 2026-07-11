package com.repolens.kernel.edit;

import com.repolens.kernel.shadow.FileChangeRecorder;

import java.util.Map;

/**
 * 编辑工具族共用的小工具：参数解析与 hash。集中一处避免各 handler 重复。
 */
final class EditSupport {

    private EditSupport() {
    }

    /** 内容 sha256（复用 M1 FileChangeRecorder 的实现，全内核口径一致）。 */
    static String sha256(String content) {
        return FileChangeRecorder.sha256Hex(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 取字符串参数，缺失或非串返回 null。 */
    static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 取布尔参数，缺失按默认值。 */
    static boolean bool(Map<String, Object> args, String key, boolean dflt) {
        Object v = args == null ? null : args.get(key);
        if (v == null) {
            return dflt;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    /** 取整数参数，缺失或非法按默认值。 */
    static int intArg(Map<String, Object> args, String key, int dflt) {
        Object v = args == null ? null : args.get(key);
        if (v == null) {
            return dflt;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
