package com.repolens.service.impl.support;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

public final class CodeChunkSegmenter {

    private CodeChunkSegmenter() {
    }

    public static List<ChunkSlice> splitByMaxChars(List<String> lines,
                                                   int startLineInclusive,
                                                   int endLineInclusive,
                                                   int maxChars) {
        return splitByMaxChars(lines, startLineInclusive, endLineInclusive, maxChars, 0);
    }

    /**
     * 按字符上限切片，并在相邻切片之间保留 overlapChars 大小的滑动窗口重叠，
     * 以免语义被硬切断裂（对 embedding 的短输入窗口尤其重要）。行号元数据随之保留。
     */
    public static List<ChunkSlice> splitByMaxChars(List<String> lines,
                                                   int startLineInclusive,
                                                   int endLineInclusive,
                                                   int maxChars,
                                                   int overlapChars) {
        List<ChunkSlice> slices = new ArrayList<>();
        if (lines == null || lines.isEmpty() || maxChars <= 0) {
            return slices;
        }

        int normalizedStart = Math.max(1, startLineInclusive);
        int normalizedEnd = Math.min(lines.size(), Math.max(normalizedStart, endLineInclusive));
        if (normalizedStart > normalizedEnd) {
            return slices;
        }
        // 重叠不能超过单个切片能容纳的范围，否则窗口无法向前推进。
        int normalizedOverlap = Math.max(0, Math.min(overlapChars, maxChars - 1));

        int cursor = normalizedStart;
        while (cursor <= normalizedEnd) {
            int sliceStart = cursor;
            StringBuilder builder = new StringBuilder();
            int lineNumber = cursor;
            while (lineNumber <= normalizedEnd) {
                String withNewline = lines.get(lineNumber - 1) + "\n";
                if (builder.length() > 0 && builder.length() + withNewline.length() > maxChars) {
                    break;
                }
                builder.append(withNewline);
                lineNumber++;
            }

            int sliceEnd = lineNumber - 1;
            if (sliceEnd < sliceStart) {
                // 单行本身就超过 maxChars：强制作为一个切片，保证向前推进。
                builder.setLength(0);
                builder.append(lines.get(sliceStart - 1)).append("\n");
                sliceEnd = sliceStart;
            }

            slices.add(new ChunkSlice(sliceStart, sliceEnd, builder.toString()));
            if (sliceEnd >= normalizedEnd) {
                break;
            }

            int nextCursor = sliceEnd + 1;
            if (normalizedOverlap > 0) {
                int overlapAccum = 0;
                int back = sliceEnd;
                while (back > sliceStart && overlapAccum < normalizedOverlap) {
                    overlapAccum += lines.get(back - 1).length() + 1;
                    back--;
                }
                nextCursor = back + 1;
            }
            if (nextCursor <= sliceStart) {
                nextCursor = sliceStart + 1;
            }
            cursor = nextCursor;
        }
        return slices;
    }

    @Getter
    @RequiredArgsConstructor
    public static class ChunkSlice {
        private final int startLine;
        private final int endLine;
        private final String content;
    }
}
