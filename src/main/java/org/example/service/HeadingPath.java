package org.example.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 标题层级 → 章节路径（如「第一章 > 1.2 向量检索」）。
 *
 * <p>为什么需要它：原来每个分片只带**当前那一级标题**（`title`），同级标题会互相覆盖，
 * 二级/三级标题看不出所属章节。检索命中后只能说"来自某段"，说不出"来自哪一章的哪一节"；
 * 切片跨章节时也无法回溯上下文。</p>
 *
 * <p>纯逻辑、无 Spring 依赖，可直接单测。</p>
 */
public final class HeadingPath {

    /** 路径分隔符 */
    public static final String SEPARATOR = " > ";

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    private final List<Integer> levels = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();

    /**
     * 压入一个标题：同级或更深的旧标题先弹出，形成正确层级。
     * 例：已压 `# 章`、`## 节`，再压 `## 新节` → 「章 > 新节」；压 `### 小节` → 「章 > 新节 > 小节」。
     */
    public void push(int level, String title) {
        if (title == null || title.isBlank()) {
            return;
        }
        while (!levels.isEmpty() && levels.get(levels.size() - 1) >= level) {
            levels.remove(levels.size() - 1);
            titles.remove(titles.size() - 1);
        }
        levels.add(level);
        titles.add(title.trim());
    }

    /** 当前路径；没有标题时为空串 */
    public String current() {
        return String.join(SEPARATOR, titles);
    }

    public boolean isEmpty() {
        return titles.isEmpty();
    }

    /** 取 `offset` 之前最后一个标题的路径 —— 表格块要挂到它所属的章节下 */
    public static String at(String content, int offset) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        HeadingPath path = new HeadingPath();
        Matcher matcher = HEADING.matcher(content);
        while (matcher.find()) {
            if (matcher.start() >= offset) {
                break;
            }
            path.push(matcher.group(1).length(), matcher.group(2));
        }
        return path.current();
    }

    /** 父路径 + 叶子（表名等）；父为空时直接返回叶子 */
    public static String append(String parent, String leaf) {
        String p = parent == null ? "" : parent.trim();
        String l = leaf == null ? "" : leaf.trim();
        if (p.isEmpty()) {
            return l;
        }
        if (l.isEmpty()) {
            return p;
        }
        return p + SEPARATOR + l;
    }
}
