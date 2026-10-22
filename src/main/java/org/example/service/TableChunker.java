package org.example.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格块识别与「行对齐」分片。
 *
 * <p>背景：抽取阶段会把表格输出成 Markdown（首行是 `【表：<表名>】` 标记行，随后是
 * Markdown 表头两行 + 数据行）。而默认分片是按字符/字节窗口切的，会把一行甚至一个单元格
 * 拦腰截断，且续片只剩裸数字、看不出属于哪张表哪一列。</p>
 *
 * <p>本类只做两件事：</p>
 * <ol>
 *   <li>{@link #findBlocks(String)} 把内容里的表格块定位出来（保留顺序与偏移）；</li>
 *   <li>{@link #split(Block, int)} 按「整行」装箱：每片都带标记行 + 表头，绝不切断任何一行；
 *       单行超预算时该行独占一片。</li>
 * </ol>
 *
 * <p>纯逻辑、无 Spring 依赖，可直接单测。</p>
 */
public final class TableChunker {

    /** 表格块起始标记前缀，须与 TextExtractor 输出保持一致 */
    public static final String MARKER_PREFIX = "【表：";

    private TableChunker() {
    }

    /** 一个表格块：在原文中的位置 + 表名标记 + 表头两行 + 数据行 */
    public static final class Block {

        public final int start;
        /** 结束下标（不含） */
        public final int end;
        public final String marker;
        /** Markdown 表头（首行 + `| --- |` 分隔行）；只有一行时仅含该行 */
        public final List<String> header;
        public final List<String> rows;

        Block(int start, int end, String marker, List<String> header, List<String> rows) {
            this.start = start;
            this.end = end;
            this.marker = marker;
            this.header = header;
            this.rows = rows;
        }

        public int rowCount() {
            return rows.size();
        }

        /** 预览用，例如 `【表：Sheet1】 12 行` */
        public String describe() {
            return marker + " " + rows.size() + " 行";
        }
    }

    /** 找出内容中所有表格块（按出现顺序） */
    public static List<Block> findBlocks(String content) {
        List<Block> blocks = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return blocks;
        }
        String[] lines = content.split("\n", -1);
        int[] lineStarts = new int[lines.length];
        int cursor = 0;
        for (int i = 0; i < lines.length; i++) {
            lineStarts[i] = cursor;
            cursor += lines[i].length() + 1; // +1 为 '\n'
        }

        int i = 0;
        while (i < lines.length) {
            if (!lines[i].trim().startsWith(MARKER_PREFIX)) {
                i++;
                continue;
            }
            int j = i + 1;
            List<String> tableLines = new ArrayList<>();
            while (j < lines.length && isTableRow(lines[j])) {
                tableLines.add(lines[j].trim());
                j++;
            }
            if (tableLines.isEmpty()) {
                i = j == i + 1 ? i + 1 : j;
                continue;
            }
            int start = lineStarts[i];
            int end = lineStarts[j - 1] + lines[j - 1].length();
            List<String> header = tableLines.size() >= 2
                    ? new ArrayList<>(tableLines.subList(0, 2))
                    : new ArrayList<>(tableLines.subList(0, 1));
            List<String> rows = tableLines.size() > header.size()
                    ? new ArrayList<>(tableLines.subList(header.size(), tableLines.size()))
                    : new ArrayList<>();
            blocks.add(new Block(start, end, lines[i].trim(), header, rows));
            i = j;
        }
        return blocks;
    }

    static boolean isTableRow(String line) {
        return line != null && line.trim().startsWith("|");
    }

    /**
     * 行对齐分片：每片都带「标记行 + 表头」，数据行按整行装箱。
     *
     * @param maxChars 单片字符预算（含标记与表头）；过小会被抬到 80
     */
    public static List<String> split(Block block, int maxChars) {
        List<String> out = new ArrayList<>();
        if (block == null) {
            return out;
        }
        int budget = Math.max(80, maxChars);
        String prefix = buildPrefix(block);
        if (block.rows.isEmpty()) {
            out.add(prefix);
            return out;
        }

        StringBuilder current = new StringBuilder(prefix);
        boolean hasRow = false;
        for (String row : block.rows) {
            if (hasRow && current.length() + 1 + row.length() > budget) {
                out.add(current.toString());
                current = new StringBuilder(prefix);
                hasRow = false;
            }
            // 前缀（标记行 + 表头）不以换行结尾，追加行时必须显式补 '\n'
            current.append('\n').append(row);
            hasRow = true;
            // 单行本身就超预算：独占一片，绝不把一行切成两半
            if (current.length() > budget) {
                out.add(current.toString());
                current = new StringBuilder(prefix);
                hasRow = false;
            }
        }
        if (hasRow) {
            out.add(current.toString());
        }
        return out;
    }

    private static String buildPrefix(Block block) {
        StringBuilder sb = new StringBuilder(block.marker);
        for (String h : block.header) {
            sb.append('\n').append(h);
        }
        return sb.toString();
    }
}
