package com.repolens.llm;

public interface EmbeddingGateway {

    float[] embed(String text);
}
