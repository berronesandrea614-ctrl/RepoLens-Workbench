package com.repolens.service.support.context;

import com.repolens.llm.model.LlmMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextManagerTest {

    Tokenizer tokenizer = new Tokenizer();
    LargeOutputStore store = new LargeOutputStore();
    com.repolens.mapper.SessionContextNoteMapper sessionContextNoteMapper =
            org.mockito.Mockito.mock(com.repolens.mapper.SessionContextNoteMapper.class);
    ContextManager manager = new ContextManager(tokenizer, store, sessionContextNoteMapper);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(manager, "triggerRatio", 0.92);
        ReflectionTestUtils.setField(manager, "targetRatio", 0.70);
        ReflectionTestUtils.setField(manager, "defaultWindowTokens", 65536);
    }

    @Test
    void noCompression_whenBelowThreshold() {
        List<LlmMessage> msgs = new ArrayList<>();
        msgs.add(LlmMessage.builder().role("system").content("sys").build());
        msgs.add(LlmMessage.builder().role("user").content("user msg").build());
        boolean compacted = manager.compact(msgs, 65536);
        assertThat(compacted).isFalse();
    }

    @Test
    void l1Compression_removesOldToolResults() {
        List<LlmMessage> msgs = new ArrayList<>();
        msgs.add(LlmMessage.builder().role("system").content("sys").build());
        msgs.add(LlmMessage.builder().role("user").content("user").build());
        for (int i = 0; i < 20; i++) {
            msgs.add(LlmMessage.builder()
                    .role("tool")
                    .toolCallId("id" + i)
                    .content("a".repeat(3000))
                    .build());
        }
        int smallWindow = 1000;
        boolean compacted = manager.compact(msgs, smallWindow);
        assertThat(compacted).isTrue();
        assertThat(msgs.get(0).getContent()).isEqualTo("sys");
    }

    @Test
    void largeOutput_getsStoredToDisk() {
        String big = "x".repeat(60_000);
        var result = store.maybeStore(big);
        assertThat(result.stored()).isTrue();
        assertThat(result.ref()).isNotNull();
        assertThat(result.content()).contains("context-blob:");
    }
}
