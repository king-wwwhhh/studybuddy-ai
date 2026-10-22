package org.example.service;

import org.example.config.DocumentChunkConfig;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档分片服务
 * 负责将长文档切分为多个有语义完整性的小片段
 */
@Service
public class DocumentChunkService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentChunkService.class);

    /**
     * 分片字节硬上限：Milvus content 字段为 VARCHAR(max_length=8192)，按 UTF-8 字节计数。
     * 中文 1 字 ≈ 3 字节，6000 字符 ≈ 18000 字节会超限导致插入失败，故按字节预算切分并预留余量。
     */
    private static final int HARD_MAX_BYTES = 8000;

    @Autowired
    DocumentChunkConfig chunkConfig; // 包可见：单测直接注入配置，不需要起 Spring 上下文

    /**
     * 智能分片文档。
     *
     * <p>两条通道：</p>
     * <ul>
     *   <li><b>表格块</b>（`【表：<表名>】` + Markdown 表头 + 数据行）：按「整行」装箱，且每片都注入表名与表头
     *       —— 否则续片只剩裸数字，看不出属于哪张表哪一列；</li>
     *   <li><b>普通文本</b>：原有逻辑（标题 → 段落 → 字节硬切）。</li>
     * </ul>
     *
     * @param content 文档内容
     * @param filePath 文件路径（用于日志）
     * @return 文档分片列表
     */
    public List<DocumentChunk> chunkDocument(String content, String filePath) {
        List<DocumentChunk> chunks = new ArrayList<>();

        if (content == null || content.trim().isEmpty()) {
            logger.warn("文档内容为空: {}", filePath);
            return chunks;
        }

        // 1. 先定位表格块：按出现顺序切开，保证「文本 → 表格 → 文本」的相对顺序不丢
        List<TableChunker.Block> tableBlocks = TableChunker.findBlocks(content);
        int cursor = 0;
        for (TableChunker.Block block : tableBlocks) {
            if (block.start > cursor) {
                chunks.addAll(chunkPlainText(content.substring(cursor, block.start)));
            }
            List<String> tableChunks = TableChunker.split(block, tableChunkMaxChars());
            for (String tableText : tableChunks) {
                DocumentChunk chunk = new DocumentChunk(tableText, block.start, block.end, 0);
                chunk.setTitle(block.marker); // 表名进 title → metadata，便于溯源
                chunk.setHeadingPath(HeadingPath.append(HeadingPath.at(content, block.start), block.marker));
                chunks.add(chunk);
            }
            cursor = Math.max(cursor, block.end);
        }
        if (cursor < content.length()) {
            chunks.addAll(chunkPlainText(content.substring(cursor)));
        }

        // 2. 重新编号保证 chunkIndex 唯一（FileIngestService 用 source+chunkIndex 生成 Milvus 主键）
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setChunkIndex(i);
        }

        logger.info("文档分片完成: {} -> {} 个分片（表格块 {} 个）", filePath, chunks.size(), tableBlocks.size());
        return chunks;
    }

    /** 表格分片字符预算：给标记行 + 表头留出余量，避免一片只剩表头没数据 */
    private int tableChunkMaxChars() {
        return Math.max(120, chunkConfig.getMaxSize() - 100);
    }

    /** 普通文本通道：标题 → 段落 → 字节硬切（与改造前一致） */
    private List<DocumentChunk> chunkPlainText(String text) {
        List<DocumentChunk> chunks = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }
        List<Section> sections = splitByHeadings(text);
        int globalChunkIndex = 0;
        for (Section section : sections) {
            List<DocumentChunk> sectionChunks = chunkSection(section, globalChunkIndex);
            chunks.addAll(sectionChunks);
            globalChunkIndex += sectionChunks.size();
        }
        return ensureMaxChunkSize(chunks);
    }

    /**
     * 将超长分片（超过 HARD_MAX_CHARS）按句末/空格切成小块，保持 chunkIndex 由调用方后续重排。
     */
    private List<DocumentChunk> ensureMaxChunkSize(List<DocumentChunk> chunks) {
        List<DocumentChunk> out = new ArrayList<>();
        for (DocumentChunk chunk : chunks) {
            String content = chunk.getContent();
            if (content == null || content.isEmpty()) continue;
            // 按 UTF-8 字节数判断是否超限（中文 1 字 ≈ 3 字节，字符数远小于字节数）
            if (utf8Bytes(content) <= HARD_MAX_BYTES) {
                out.add(chunk);
                continue;
            }
            int idx = 0;
            while (idx < content.length()) {
                // 字节安全窗口：最多到 HARD_MAX_BYTES 字节，且切在合法字符边界
                int end = byteSafeEnd(content, idx);
                String window = content.substring(idx, end);
                int winLen = end - idx;
                // 尽量在句末断开，找不到则取窗口内最后一个空格；都没有就硬切
                int cut = end;
                int lastSentenceEnd = -1;
                for (char marker : new char[]{'。', '？', '！', '.', '?', '!'}) {
                    int p = window.lastIndexOf(marker);
                    if (p > lastSentenceEnd) lastSentenceEnd = p;
                }
                if (lastSentenceEnd >= winLen / 2) {
                    cut = idx + lastSentenceEnd + 1;
                } else {
                    int sp = window.lastIndexOf(' ');
                    if (sp >= winLen / 2) cut = idx + sp;
                }
                if (cut <= idx) cut = end; // 防死循环：无论如何前进
                String pieceContent = content.substring(idx, cut).trim();
                if (!pieceContent.isEmpty()) {
                    DocumentChunk piece = new DocumentChunk(
                            pieceContent,
                            chunk.getStartIndex() + idx,
                            chunk.getStartIndex() + cut,
                            chunk.getChunkIndex());
                    piece.setTitle(chunk.getTitle());
                    piece.setHeadingPath(chunk.getHeadingPath());
                    out.add(piece);
                }
                idx = cut;
            }
        }
        return out;
    }

    /** 计算字符串的 UTF-8 字节数 */
    private static int utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 从 start 起找最大 end，使 content[start, end) 的 UTF-8 字节数 ≤ HARD_MAX_BYTES（切在合法字符边界） */
    private static int byteSafeEnd(String content, int start) {
        int bytes = 0;
        int i = start;
        int n = content.length();
        while (i < n) {
            char c = content.charAt(i);
            int cb;
            if (c < 0x80) cb = 1;
            else if (c < 0x800) cb = 2;
            else if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(content.charAt(i + 1))) cb = 4;
            else cb = 3;
            if (bytes + cb > HARD_MAX_BYTES) break;
            bytes += cb;
            i += (cb == 4) ? 2 : 1;
        }
        return i;
    }

    /**
     * 按照 Markdown 标题分割文档。
     *
     * <p>标题行本身**不进入分片正文**：标题信息已由 `title` / `headingPath` 承载，
     * 再让 `## 1.1 概念` 这类文本重复出现在每个分片里，只会白烧 embedding token 并稀释正文语义。</p>
     */
    private List<Section> splitByHeadings(String content) {
        List<Section> sections = new ArrayList<>();
        
        // 匹配 Markdown 标题：# 标题, ## 标题, ### 标题等
        Pattern headingPattern = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
        Matcher matcher = headingPattern.matcher(content);

        int lastEnd = 0;
        String currentTitle = null;
        HeadingPath path = new HeadingPath();

        while (matcher.find()) {
            // 保存上一个章节
            if (lastEnd < matcher.start()) {
                String sectionContent = content.substring(lastEnd, matcher.start()).trim();
                if (!sectionContent.isEmpty()) {
                    sections.add(new Section(currentTitle, path.current(), sectionContent, lastEnd));
                }
            }

            // 更新当前标题与章节路径（同名父级会被更深标题继承，同级标题互相替换）
            path.push(matcher.group(1).length(), matcher.group(2));
            currentTitle = matcher.group(2).trim();
            // 正文从标题行之后开始 → 标题文本不再重复进分片
            lastEnd = afterHeadingLine(content, matcher.end());
        }

        // 添加最后一个章节
        if (lastEnd < content.length()) {
            String sectionContent = content.substring(lastEnd).trim();
            if (!sectionContent.isEmpty()) {
                sections.add(new Section(currentTitle, path.current(), sectionContent, lastEnd));
            }
        }

        // 如果没有找到任何标题，将整个文档作为一个章节
        if (sections.isEmpty()) {
            sections.add(new Section(null, "", content, 0));
        }

        return sections;
    }

    /** 标题行结束位置（跳过标题后的换行）：正文从标题行之后开始 */
    private static int afterHeadingLine(String content, int headingEnd) {
        int i = headingEnd;
        while (i < content.length() && (content.charAt(i) == '\n' || content.charAt(i) == '\r')) {
            i++;
        }
        return i;
    }

    /**
     * 对单个章节进行分片
     */
    private List<DocumentChunk> chunkSection(Section section, int startChunkIndex) {
        List<DocumentChunk> chunks = new ArrayList<>();
        String content = section.content;
        String title = section.title;

        // 如果章节内容小于最大尺寸，直接作为一个分片
        if (content.length() <= chunkConfig.getMaxSize()) {
            DocumentChunk chunk = new DocumentChunk(
                content, 
                section.startIndex, 
                section.startIndex + content.length(), 
                startChunkIndex
            );
            chunk.setTitle(title);
            chunk.setHeadingPath(section.headingPath);
            chunks.add(chunk);
            return chunks;
        }

        // 章节内容较长，需要进一步分片
        // 优先在段落边界分割
        List<String> paragraphs = splitByParagraphs(content);
        
        StringBuilder currentChunk = new StringBuilder();
        int currentStartIndex = section.startIndex;
        int chunkIndex = startChunkIndex;

        for (String paragraph : paragraphs) {
            // 如果当前分片加上新段落超过最大尺寸
            if (currentChunk.length() > 0 && 
                currentChunk.length() + paragraph.length() > chunkConfig.getMaxSize()) {
                
                // 保存当前分片
                String chunkContent = currentChunk.toString().trim();
                DocumentChunk chunk = new DocumentChunk(
                    chunkContent,
                    currentStartIndex,
                    currentStartIndex + chunkContent.length(),
                    chunkIndex++
                );
                chunk.setTitle(title);
            chunk.setHeadingPath(section.headingPath);
                chunks.add(chunk);

                // 开始新分片，包含重叠部分
                String overlap = getOverlapText(chunkContent);
                currentChunk = new StringBuilder(overlap);
                currentStartIndex = currentStartIndex + chunkContent.length() - overlap.length();
            }

            currentChunk.append(paragraph).append("\n\n");
        }

        // 保存最后一个分片
        if (currentChunk.length() > 0) {
            String chunkContent = currentChunk.toString().trim();
            DocumentChunk chunk = new DocumentChunk(
                chunkContent,
                currentStartIndex,
                currentStartIndex + chunkContent.length(),
                chunkIndex
            );
            chunk.setTitle(title);
            chunk.setHeadingPath(section.headingPath);
            chunks.add(chunk);
        }

        return chunks;
    }

    /**
     * 按段落分割文本
     */
    private List<String> splitByParagraphs(String content) {
        List<String> paragraphs = new ArrayList<>();
        
        // 按双换行符分割段落
        String[] parts = content.split("\n\n+");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }

        return paragraphs;
    }

    /**
     * 获取重叠文本
     * 从文本末尾提取指定长度的内容作为下一个分片的开头
     */
    private String getOverlapText(String text) {
        int overlapSize = Math.min(chunkConfig.getOverlap(), text.length());
        if (overlapSize <= 0) {
            return "";
        }

        // 从末尾提取重叠内容
        String overlap = text.substring(text.length() - overlapSize);
        
        // 尝试在句子边界截断（查找最后一个句号、问号、感叹号）
        int lastSentenceEnd = Math.max(
            overlap.lastIndexOf('。'),
            Math.max(overlap.lastIndexOf('？'), overlap.lastIndexOf('！'))
        );
        
        if (lastSentenceEnd > overlapSize / 2) {
            return overlap.substring(lastSentenceEnd + 1).trim();
        }

        return overlap.trim();
    }

    /**
     * 章节数据类
     */
    private static class Section {
        String title;
        /** 章节路径（如「第一章 > 1.2 向量检索」），空串表示无标题 */
        String headingPath;
        String content;
        int startIndex;

        Section(String title, String headingPath, String content, int startIndex) {
            this.title = title;
            this.headingPath = headingPath == null ? "" : headingPath;
            this.content = content;
            this.startIndex = startIndex;
        }
    }
}
