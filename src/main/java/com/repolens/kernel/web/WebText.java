package com.repolens.kernel.web;

/**
 * 网页文本粗处理：把 HTML 剥成可读纯文本，非 HTML（json/纯文本）原样返回。
 * 不引 Jsoup 等第三方解析器（保持内核 0 外部依赖），用正则做「够用」的粗剥——
 * 去掉 script/style/注释块、标签换空格、解转义常见实体、压缩空白。
 */
final class WebText {

    private WebText() {
    }

    static String process(String body, String contentType) {
        if (body == null) {
            return "";
        }
        boolean html = (contentType != null && contentType.toLowerCase().contains("html"))
                || looksLikeHtml(body);
        if (!html) {
            return body.strip();
        }
        String s = body;
        s = s.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        s = s.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        s = s.replaceAll("(?is)<!--.*?-->", " ");
        s = s.replaceAll("(?is)<head[^>]*>.*?</head>", " ");
        // 块级标签换行，便于阅读
        s = s.replaceAll("(?i)<(/?)(p|div|br|li|tr|h[1-6]|section|article|ul|ol|table)[^>]*>", "\n");
        s = s.replaceAll("(?s)<[^>]+>", " ");
        s = unescape(s);
        // 压缩空白：多空格→1，多空行→1
        s = s.replaceAll("[ \\t\\x0B\\f]+", " ");
        s = s.replaceAll("\\n[ \\t]*\\n\\s*", "\n\n");
        return s.strip();
    }

    private static boolean looksLikeHtml(String body) {
        String head = body.length() > 512 ? body.substring(0, 512) : body;
        String lower = head.toLowerCase();
        return lower.contains("<!doctype html") || lower.contains("<html") || lower.contains("<body");
    }

    private static String unescape(String s) {
        return s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }
}
