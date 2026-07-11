package com.repolens.kernel.tools;

import com.repolens.kernel.route.LlmCallKind;
import com.repolens.kernel.route.ModelRouter;
import com.repolens.kernel.spi.KernelTool;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.kernel.web.KernelWebClient;
import com.repolens.llm.LlmClient;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import com.repolens.llm.model.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code WebFetch} 工具：抓取一个 URL 的内容并按 {@code prompt} 抽取要点（对齐 Claude Code 的 WebFetch 语义）。
 *
 * <ul>
 *   <li>给了 {@code prompt} 且有可用小模型 → 抓取正文后用 CHORE 档小模型在页面内容上跑该 prompt，
 *       返回「针对性答案」而非整页原文（省上下文、更可用）；抽取失败降级为返回清洗后的正文。</li>
 *   <li>没给 {@code prompt} → 返回清洗+截断后的页面正文。</li>
 * </ul>
 *
 * <p>出网走 {@link KernelWebClient} 两层门控——默认关闭（0 出网红线），关闭时回一句「联网默认关闭」的说明，
 * agent 据此不再空转。readOnly=true。
 */
@Component
public class WebFetchTool implements KernelTool {

    private static final int DEFAULT_MAX_CHARS = 12000;
    /** 喂给抽取小模型的页面正文上限（防超小模型上下文）。 */
    private static final int EXTRACT_CONTENT_CAP = 16000;

    private final KernelWebClient webClient;

    @Autowired(required = false)
    private LlmClient llmClient;
    @Autowired(required = false)
    private ModelRouter modelRouter;

    public WebFetchTool(KernelWebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public String name() {
        return "WebFetch";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("WebFetch")
                .description("抓取一个 http/https URL 的内容。给了 prompt 就返回针对该 prompt 从页面抽取的答案，"
                        + "否则返回清洗后的网页正文。用于读已知链接的文档/网页。需联网（默认可用）。")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "url", Map.of("type", "string", "description", "目标 URL（http 或 https）"),
                                "prompt", Map.of("type", "string",
                                        "description", "（可选）你想从这个页面得到什么——给了就返回针对性抽取答案"),
                                "maxChars", Map.of("type", "integer",
                                        "description", "（可选）返回正文字符上限，默认 12000")),
                        "required", List.of("url")))
                .build();
    }

    @Override
    public String execute(ToolContext ctx, Map<String, Object> args) {
        String url = str(args.get("url"));
        if (url == null || url.isBlank()) {
            return "WebFetch 缺少参数 url。";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "WebFetch 只支持 http/https URL，实际=" + url;
        }
        int maxChars = intArg(args.get("maxChars"), DEFAULT_MAX_CHARS);
        String prompt = str(args.get("prompt"));

        KernelWebClient.FetchResult res;
        try {
            res = webClient.fetch(url, Math.max(maxChars, prompt != null ? EXTRACT_CONTENT_CAP : maxChars));
        } catch (KernelWebClient.WebAccessException e) {
            return e.getMessage();
        } catch (Exception e) {
            return "WebFetch 出网被拒或异常：" + e.getMessage();
        }
        if (res.status() == -1) {
            return "WebFetch 网络错误：" + res.content();
        }
        if (res.status() >= 400) {
            return "WebFetch 返回 HTTP " + res.status() + "（可能是反爬/需鉴权/已失效）。URL=" + url;
        }
        String content = res.content();
        if (content == null || content.isBlank()) {
            return "WebFetch 抓到空内容（HTTP " + res.status() + "）。URL=" + url;
        }

        // 有 prompt + 可用小模型 → 抽取
        if (prompt != null && !prompt.isBlank() && llmClient != null) {
            String extracted = extract(ctx, url, content, prompt);
            if (extracted != null && !extracted.isBlank()) {
                return "【WebFetch 抽取 · " + url + "】\n" + extracted;
            }
            // 抽取失败降级为正文
        }
        String head = content.length() > maxChars ? content.substring(0, maxChars) : content;
        String tail = res.truncated() || content.length() > maxChars ? "\n…[内容已截断]" : "";
        return "【WebFetch · " + url + "】\n" + head + tail;
    }

    private String extract(ToolContext ctx, String url, String content, String prompt) {
        try {
            String capped = content.length() > EXTRACT_CONTENT_CAP ? content.substring(0, EXTRACT_CONTENT_CAP) : content;
            String model = pickModel(ctx);
            String sys = "你是网页信息抽取器。只依据给定网页内容作答，忠实、简洁；"
                    + "内容里没有的就明说「页面未提及」，不要编造。";
            String user = "网页 URL：" + url + "\n\n网页内容：\n" + capped
                    + "\n\n请针对以下需求从上面内容抽取并作答：\n" + prompt;
            LlmRequest req = LlmRequest.builder()
                    .modelName(model)
                    .systemPrompt(sys)
                    .userPrompt(user)
                    .temperature(0.0)
                    .build();
            LlmResponse resp = llmClient.generate(req);
            if (resp != null && Boolean.TRUE.equals(resp.getSuccess()) && resp.getContent() != null) {
                return resp.getContent().strip();
            }
            // success 可能为 null（部分 client 不填）——只要有 content 就用
            if (resp != null && resp.getContent() != null && !resp.getContent().isBlank()) {
                return resp.getContent().strip();
            }
        } catch (Exception ignore) {
            // 降级：返回 null，由调用方回落正文
        }
        return null;
    }

    private String pickModel(ToolContext ctx) {
        String main = ctx == null ? null : ctx.modelName();
        Path repoDir = ctx == null ? null : ctx.repoDir();
        if (modelRouter != null) {
            try {
                return modelRouter.modelFor(LlmCallKind.CHORE, main, repoDir);
            } catch (Exception ignore) {
                // 回退主模型
            }
        }
        return main;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int intArg(Object o, int def) {
        if (o == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return def;
        }
    }
}
