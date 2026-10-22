package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HeadingPath 单测：标题层级压栈、偏移点路径、路径拼接。 */
class HeadingPathTest {

    @Test
    @DisplayName("push：同级替换、更深继承、回溯更浅层级")
    void pushHierarchy() {
        HeadingPath p = new HeadingPath();
        p.push(1, "第一章");
        assertEquals("第一章", p.current());

        p.push(2, "1.1 概念");
        assertEquals("第一章 > 1.1 概念", p.current());

        p.push(3, "1.1.1 定义");
        assertEquals("第一章 > 1.1 概念 > 1.1.1 定义", p.current());

        p.push(2, "1.2 检索"); // 同级 → 顶掉 1.1 及其子级
        assertEquals("第一章 > 1.2 检索", p.current());

        p.push(1, "第二章"); // 更浅 → 只剩第二章
        assertEquals("第二章", p.current());
    }

    @Test
    @DisplayName("push：空白标题被忽略；初始为空串")
    void pushIgnoresBlank() {
        HeadingPath p = new HeadingPath();
        assertTrue(p.isEmpty());
        assertEquals("", p.current());
        p.push(1, "  ");
        p.push(1, null);
        assertTrue(p.isEmpty());
        p.push(1, "  有内容  ");
        assertEquals("有内容", p.current(), "标题两侧空白应被裁掉");
    }

    @Test
    @DisplayName("at：取 offset 之前最后一个标题的路径（表格块用）")
    void pathAtOffset() {
        String content = String.join("\n",
                "# 第一章",
                "正文 A",
                "## 1.1 小节",
                "正文 B",
                "# 第二章",
                "正文 C");
        assertEquals("", HeadingPath.at(content, 0), "第一个标题之前没有路径");
        assertEquals("第一章", HeadingPath.at(content, content.indexOf("正文 A") + 1));
        assertEquals("第一章 > 1.1 小节", HeadingPath.at(content, content.indexOf("正文 B") + 1));
        assertEquals("第二章", HeadingPath.at(content, content.indexOf("正文 C") + 1));
    }

    @Test
    @DisplayName("at：空内容/无标题返回空串")
    void pathAtOffsetEdge() {
        assertEquals("", HeadingPath.at(null, 5));
        assertEquals("", HeadingPath.at("", 0));
        assertEquals("", HeadingPath.at("没有标题的正文", 3));
    }

    @Test
    @DisplayName("append：父路径与叶子拼接，任一侧为空时退化")
    void appendPath() {
        assertEquals("第一章 > 【表：成绩单】", HeadingPath.append("第一章", "【表：成绩单】"));
        assertEquals("【表：成绩单】", HeadingPath.append("", "【表：成绩单】"));
        assertEquals("【表：成绩单】", HeadingPath.append(null, "【表：成绩单】"));
        assertEquals("第一章", HeadingPath.append("第一章", null));
        assertEquals("第一章", HeadingPath.append("  第一章  ", "  "));
    }
}
