package com.repolens.llm.impl;

import com.repolens.llm.EmbeddingGateway;
import org.springframework.stereotype.Component;

@Component
public class MockEmbeddingGateway implements EmbeddingGateway {

    private static final int DIMENSION = 16;

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSION];
        int seed = text == null ? 0 : text.hashCode();
        for (int i = 0; i < DIMENSION; i++) {
            vector[i] = (seed + i) % 1000 / 1000.0f;
        }
        return vector;
    }
}
