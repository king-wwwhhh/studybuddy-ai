package org.example.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * LLM 输出的 JSON 抽取工具：
 * - 自动剥离 markdown ```json ... ``` 围栏
 * - 自动裁掉首尾非 JSON 字符
 * - 支持顶层对象 { ... } 或数组 [ ... ]
 * - 无法解析时返回空集合而非抛错（调用方可显式判定）
 */
public final class JsonExtractor {

    private static final ObjectMapper MAPPER = Mappers.JSON;

    private JsonExtractor() {}

    public static List<Map<String, Object>> parseTopLevelArray(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String cleaned = stripFence(raw);
        // 优先尝试整体解析
        try {
            Object o = MAPPER.readValue(cleaned, Object.class);
            if (o instanceof List<?> l) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> casted = (List<Map<String, Object>>) (List<?>) l;
                return casted;
            }
        } catch (Exception ignore) {
            // fall through: 尝试切 array
        }
        int[] arr = bracketRange(cleaned, '[', ']');
        if (arr == null) return List.of();
        String json = cleaned.substring(arr[0], arr[1] + 1);
        try {
            Object o = MAPPER.readValue(json, Object.class);
            if (o instanceof List<?>) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> casted = (List<Map<String, Object>>) (List<?>) o;
                return casted;
            }
        } catch (Exception ignore) { /* swallow */ }
        return List.of();
    }

    public static Map<String, Object> parseTopLevelObject(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        String cleaned = stripFence(raw);
        try {
            Object o = MAPPER.readValue(cleaned, Object.class);
            if (o instanceof Map<?,?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) o;
                return casted;
            }
        } catch (Exception ignore) { /* try slice */ }
        int[] br = bracketRange(cleaned, '{', '}');
        if (br == null) return Map.of();
        String json = cleaned.substring(br[0], br[1] + 1);
        try {
            Object o = MAPPER.readValue(json, Object.class);
            if (o instanceof Map<?,?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) o;
                return casted;
            }
        } catch (Exception ignore) { /* swallow */ }
        return Map.of();
    }

    /** 去掉 markdown ```json ... ``` 围栏 / 首尾非 JSON 字符 */
    private static String stripFence(String raw) {
        String s = raw.replaceAll("(?s)```[a-zA-Z0-9_-]*\\s*", "");
        s = s.replaceAll("(?s)```", "");
        s = s.replaceAll("^\\s+", "");
        // 找到首个 { 或 [ 之前的全部空白
        int first = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[' || c == '{') { first = i; break; }
        }
        if (first > 0) s = s.substring(first);
        // 找最后一个 } 或 ] 之后全部丢弃
        int last = -1;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == ']' || c == '}') { last = i; break; }
        }
        if (last >= 0 && last < s.length() - 1) s = s.substring(0, last + 1);
        return s.trim();
    }

    /** 在字符串中找与开括号匹配的顶层 end，忽略字符串内的 / 嵌套括号。返回 [start, end]。找不到返 null。 */
    private static int[] bracketRange(String s, char open, char close) {
        int start = s.indexOf(open);
        if (start < 0) return null;
        boolean inStr = false;
        boolean esc = false;
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\' && inStr) { esc = true; continue; }
            if (c == '"') { inStr = !inStr; continue; }
            if (inStr) continue;
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return new int[]{start, i};
            }
        }
        return null;
    }
}
