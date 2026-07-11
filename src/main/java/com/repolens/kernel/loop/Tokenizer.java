package com.repolens.kernel.loop;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.repolens.llm.model.LlmMessage;
import com.repolens.llm.model.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * token 估算器，用于主循环的 token 预算与 compaction 触发判断（M6 升级）。
 *
 * <p><b>M6：接入真 BPE。</b> 默认走 {@code jtokkit} 的 {@code o200k_base}（GPT-4o/o200k 家族的真实
 * 分词表，编码数据内嵌在 jtokkit jar 里、无网络无外部资源），{@link #estimate(String)} 返回的是
 * <b>真实 token 数</b>而非字符近似。国产/其它 OpenAI-Compatible 模型的分词表未必与 o200k 完全一致，
 * 但同属 BPE 子词量级，误差远小于「字符数近似」——用于 compaction 触发（单调、量级正确）完全够。
 *
 * <p><b>降级路径（诚实标注）。</b> 万一运行环境加载 jtokkit 失败（缺库/类加载异常），自动回落到
 * <b>校准过的加权近似</b>{@link #approximate(String)}：正则切成 词 / 数字 / 标点 / CJK单字，
 * 分别加权（词≈按长度折算子词、CJK≈1.1 token/字、标点≈1 token），比纯字符数贴近得多。
 * 通过 {@link #isExact()} 可查询当前用的是真 BPE 还是近似，绝不谎报精确。
 *
 * <p>每条消息另加固定结构开销（role/分隔符），贴近真实 chat 计费的 per-message overhead。
 */
@Component("kernelTokenizer")
public class Tokenizer {

    private static final Logger log = LoggerFactory.getLogger(Tokenizer.class);

    /** 每条消息的固定结构开销（role/分隔符等），经验值，贴近 OpenAI chat 计费的 per-message overhead。 */
    private static final int PER_MESSAGE_OVERHEAD = 4;

    /** 真 BPE 编码器；加载失败则为 null，走近似分支。 */
    private final Encoding bpe;

    public Tokenizer() {
        Encoding loaded = null;
        try {
            EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
            loaded = registry.getEncoding(EncodingType.O200K_BASE);
            // 冒烟：确保编码表真能用（内嵌资源真的被读到）
            loaded.countTokens("ok");
            log.info("[tokenizer] 使用真 BPE 分词器：jtokkit o200k_base");
        } catch (Throwable t) {
            log.warn("[tokenizer] jtokkit 加载失败，降级为校准加权近似（非精确）：{}", t.toString());
            loaded = null;
        }
        this.bpe = loaded;
    }

    /** 当前是否为真 BPE（true=jtokkit 精确；false=校准近似）。诚实边界，可被上层日志/断言查询。 */
    public boolean isExact() {
        return bpe != null;
    }

    /** 估算一段纯文本的 token 数。真 BPE 可用则精确，否则走校准近似。 */
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        if (bpe != null) {
            try {
                return bpe.countTokens(text);
            } catch (Throwable t) {
                // 单次编码异常（极端字符），退化为近似，不影响循环
                return approximate(text);
            }
        }
        return approximate(text);
    }

    /** 估算一条对话消息（含 content 与 tool_calls 的参数体量）。 */
    public int estimate(LlmMessage msg) {
        if (msg == null) {
            return 0;
        }
        int total = PER_MESSAGE_OVERHEAD + estimate(msg.getContent());
        if (msg.getToolCalls() != null) {
            for (ToolCall tc : msg.getToolCalls()) {
                total += estimate(tc.getName());
                if (tc.getArguments() != null) {
                    total += estimate(tc.getArguments().toString());
                }
            }
        }
        return total;
    }

    /** 估算整段消息历史的 token 数。 */
    public int estimate(List<LlmMessage> messages) {
        if (messages == null) {
            return 0;
        }
        int total = 0;
        for (LlmMessage m : messages) {
            total += estimate(m);
        }
        return total;
    }

    /**
     * 校准加权近似（jtokkit 不可用时的兜底）。按 BPE 的经验规律切类加权：
     * <ul>
     *   <li>CJK/全角：≈ 1.1 token/字（中文常见 1~2 token/字，取略高于 1）；</li>
     *   <li>连续 ASCII 词：按 {@code ceil(len/4)} 折算子词（cl100k/o200k 英文经验约 4 char/token）；</li>
     *   <li>数字串：≈ ceil(len/3)（数字更碎）；</li>
     *   <li>标点/符号：每个 ≈ 1 token。</li>
     * </ul>
     * 比纯字符数强很多，且单调、量级正确，够 compaction 触发用。
     */
    int approximate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0.0;
        int n = text.length();
        int i = 0;
        while (i < n) {
            int cp = text.codePointAt(i);
            int cc = Character.charCount(cp);
            if (isCjkOrWide(cp)) {
                tokens += 1.1;
                i += cc;
            } else if (isAsciiLetter(cp)) {
                int start = i;
                i += cc;
                while (i < n && isAsciiLetter(text.codePointAt(i))) {
                    i += Character.charCount(text.codePointAt(i));
                }
                int len = i - start;
                tokens += Math.ceil(len / 4.0);
            } else if (cp >= '0' && cp <= '9') {
                int start = i;
                i += cc;
                while (i < n) {
                    int c2 = text.codePointAt(i);
                    if (c2 < '0' || c2 > '9') break;
                    i += Character.charCount(c2);
                }
                int len = i - start;
                tokens += Math.ceil(len / 3.0);
            } else if (Character.isWhitespace(cp)) {
                // 空白多被并入相邻子词，忽略不计
                i += cc;
            } else {
                // 标点/符号：单独成 token
                tokens += 1.0;
                i += cc;
            }
        }
        return (int) Math.ceil(tokens);
    }

    private static boolean isAsciiLetter(int cp) {
        return (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z');
    }

    /** CJK 统一表意文字 / 假名 / 谚文 / CJK 标点与全角形式。 */
    private static boolean isCjkOrWide(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)   // CJK 统一表意
                || (cp >= 0x3400 && cp <= 0x4DBF)   // 扩展 A
                || (cp >= 0x3040 && cp <= 0x30FF)   // 平/片假名
                || (cp >= 0xAC00 && cp <= 0xD7A3)   // 谚文音节
                || (cp >= 0x3000 && cp <= 0x303F)   // CJK 符号与标点
                || (cp >= 0xFF00 && cp <= 0xFFEF);  // 全角形式
    }
}
