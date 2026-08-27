package org.example.service;

import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.sl.extractor.SlideShowExtractor;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 文本抽取器。
 *
 * 支持：txt/md/json/csv/tex（直接读字符串）、html/htm（去标签）、
 * doc/docx/ppt/pptx/xls/xlsx（Apache POI，失败时 OOXML 格式回退到手写解压）、
 * pdf（尽力抽取：文本算子 + FlateDecode + UTF-16BE BOM；乱码/扫描版置空待上层报错）。
 * 未知类型降级按纯文本读取。
 */
@Service
public class TextExtractor {

    private static final Logger logger = LoggerFactory.getLogger(TextExtractor.class);

    // HTML 标签去除
    private static final Pattern SCRIPT_STYLE = Pattern.compile(
            "(?is)<(script|style)\\b[^>]*>.*?</\\1>");
    private static final Pattern HTML_TAG = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern HTML_ENTITY = Pattern.compile("(?i)&(amp|lt|gt|quot|apos|nbsp|#(\\d+)|#x([0-9a-fA-F]+));");

    /** 抽取入口 */
    public ExtractResult extract(byte[] data, String filename) {
        String ext = extension(filename).toLowerCase();
        String text;
        boolean partial = false;
        try {
            switch (ext) {
                case "txt", "md", "markdown", "json", "csv", "tex", "log", "text":
                    text = new String(data, StandardCharsets.UTF_8);
                    break;
                case "html", "htm":
                    text = stripHtml(new String(data, StandardCharsets.UTF_8));
                    break;
                case "docx":
                    text = extractWithPoi(data, "docx");
                    if (text == null || text.isBlank()) {
                        // 兜底：手写 OOXML 抽取
                        text = extractOoxml(data, List.of("word/document.xml"));
                    }
                    break;
                case "pptx":
                    text = extractWithPoi(data, "pptx");
                    if (text == null || text.isBlank()) {
                        text = extractOoxml(data, List.of(),
                                List.of(startsWith("ppt/slides/slide"), startsWith("ppt/notesSlides/")));
                    }
                    break;
                case "xlsx":
                    text = extractWithPoi(data, "xlsx");
                    if (text == null || text.isBlank()) {
                        text = extractOoxml(data, List.of("xl/sharedStrings.xml"),
                                List.of(startsWith("xl/worksheets/sheet")));
                    }
                    break;
                case "doc", "ppt", "xls": // 旧版二进制格式只能靠 POI
                    text = extractWithPoi(data, ext);
                    break;
                case "pdf":
                    text = extractPdf(data);
                    partial = text.length() < 200; // PDF 抽取可能不完整
                    break;
                default:
                    logger.warn("不支持的扩展名，尝试按纯文本读取: {}", ext);
                    text = tryReadUtf8(data);
            }
        } catch (Throwable t) {
            // 必须 catch Throwable 而非 Exception：正则解析极端输入时可能递归爆栈（StackOverflowError 是 Error），
            // 只 catch Exception 会让它逃逸成 500。统一降级为空文本，保证上传不崩。
            logger.warn("文本抽取失败（降级为空）: {} -> {}", filename, t.getMessage());
            text = "";
            partial = true;
        }
        if (text == null) {
            text = "";
        }
        text = normalize(text);
        return new ExtractResult(text, partial);
    }

    // ===================== OOXML（docx/pptx/xlsx）=====================

    private String extractOoxml(byte[] data, List<String> exactParts) {
        return extractOoxml(data, exactParts, List.of());
    }

    private String extractOoxml(byte[] data, List<String> exactParts, List<PrefixMatcher> prefixMatchers) {
        StringBuilder sb = new StringBuilder();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (matches(name, exactParts, prefixMatchers)) {
                    byte[] xml = readAll(zis);
                    sb.append(stripXmlTags(new String(xml, StandardCharsets.UTF_8))).append("\n");
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("OOXML 解析失败: " + e.getMessage(), e);
        }
        return sb.toString();
    }

    private interface PrefixMatcher {
        boolean test(String name);
    }

    private PrefixMatcher startsWith(String prefix) {
        return name -> name.startsWith(prefix);
    }

    private boolean matches(String name, List<String> exact, List<PrefixMatcher> prefixes) {
        if (exact.contains(name)) {
            return true;
        }
        for (PrefixMatcher p : prefixes) {
            if (p.test(name)) {
                return true;
            }
        }
        return false;
    }

    private static String stripXmlTags(String xml) {
        if (xml == null) return "";
        // 去 XML 注释与处理指令
        String s = xml.replaceAll("(?s)<!--.*?-->", " ")
                     .replaceAll("(?s)<\\?.*?\\?>", " ");
        // 标签替换为空格（保留文本）
        s = s.replaceAll("(?s)<[^>]+>", " ");
        // 常见 OOXML 空白占位
        s = s.replaceAll("&nbsp;", " ")
             .replaceAll("&amp;", "&")
             .replaceAll("&lt;", "<")
             .replaceAll("&gt;", ">")
             .replaceAll("&quot;", "\"");
        return s;
    }

    // ===================== POI（doc/docx/ppt/pptx/xls/xlsx）=====================

    /**
     * 用 Apache POI 抽取 Office 文本（含旧版二进制格式）。
     * 任何失败返回 null，由调用方决定走手写 OOXML 兜底还是置空。
     */
    private String extractWithPoi(byte[] data, String ext) {
        try (InputStream is = new ByteArrayInputStream(data)) {
            switch (ext) {
                case "doc": {
                    try (HWPFDocument doc = new HWPFDocument(is)) {
                        return new WordExtractor(doc).getText();
                    }
                }
                case "docx": {
                    try (XWPFDocument doc = new XWPFDocument(is)) {
                        return docxText(doc);
                    }
                }
                case "ppt": {
                    try (SlideShow<?, ?> ss = new HSLFSlideShow(is)) {
                        return new SlideShowExtractor(ss).getText();
                    }
                }
                case "pptx": {
                    try (SlideShow<?, ?> ss = new XMLSlideShow(is)) {
                        return new SlideShowExtractor(ss).getText();
                    }
                }
                case "xls", "xlsx": {
                    try (Workbook wb = WorkbookFactory.create(is)) {
                        return excelText(wb);
                    }
                }
                default:
                    return null;
            }
        } catch (Exception e) {
            logger.warn("POI 抽取失败（{}，交给兜底/置空）: {}", ext, e.getMessage());
            return null;
        }
    }

    private String docxText(XWPFDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (XWPFParagraph p : doc.getParagraphs()) {
            String t = p.getText();
            if (t != null && !t.isBlank()) sb.append(t).append('\n');
        }
        for (XWPFTable table : doc.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    String t = cell.getText();
                    if (t != null && !t.isBlank()) sb.append(t).append('\t');
                }
                sb.append('\n');
            }
        }
        for (XWPFHeader h : doc.getHeaderList()) {
            for (XWPFParagraph p : h.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) sb.append(t).append('\n');
            }
        }
        for (XWPFFooter f : doc.getFooterList()) {
            for (XWPFParagraph p : f.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) sb.append(t).append('\n');
            }
        }
        return sb.toString();
    }

    private String excelText(Workbook wb) {
        DataFormatter df = new DataFormatter();
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < wb.getNumberOfSheets(); s++) {
            Sheet sheet = wb.getSheetAt(s);
            if (sheet == null) continue;
            sb.append('【').append(sheet.getSheetName()).append("】\n");
            for (Row row : sheet) {
                for (Cell cell : row) {
                    String v = df.formatCellValue(cell);
                    if (v != null && !v.isBlank()) sb.append(v).append('\t');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    // ===================== HTML =====================

    private static String stripHtml(String html) {
        if (html == null) return "";
        String s = SCRIPT_STYLE.matcher(html).replaceAll(" ");
        s = HTML_TAG.matcher(s).replaceAll(" ");
        s = HTML_ENTITY.matcher(s).replaceAll(m -> decodeEntity(m));
        return s;
    }

    private static String decodeEntity(java.util.regex.MatchResult m) {
        String name = m.group(1);
        switch (name) {
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "quot": case "apos": return "\"";
            case "nbsp": return " ";
        }
        if (m.group(2) != null) {
            return String.valueOf((char) Integer.parseInt(m.group(2)));
        }
        if (m.group(3) != null) {
            return String.valueOf((char) Integer.parseInt(m.group(3), 16));
        }
        return " ";
    }

    // ===================== PDF（尽力）=====================

    private String extractPdf(byte[] data) {
        // 1) 优先 Apache PDFBox：带 ToUnicode/CID 解码，真实中文 PDF 能抽出干净正文
        String pdfbox = extractPdfWithPdfBox(data);
        // 2) 回退启发式正则扫描（PDFBox 不可用时）
        String heuristic = extractPdfHeuristic(data);

        // 择优：PDFBox 有可用文本且非乱码则用 PDFBox，否则用启发式
        String text;
        if (pdfbox != null && !pdfbox.isBlank() && !looksGarbled(pdfbox)) {
            text = pdfbox;
        } else {
            text = heuristic;
        }

        // 3) 过滤重复短行（页眉/页脚/页码/水印每页重复的噪声），减少白烧的 embedding token
        text = stripRepeatedBoilerplate(text);

        if (looksGarbled(text)) {
            // CID 字体 / 编码不可映射时抽出来的是乱码（拉丁字符 + C1 控制符），会污染知识库，置空待上层报错
            logger.warn("PDF 文本疑似乱码，置空待上层报错（文件可能是 CID 字体/扫描版）: len={}", text.length());
            return "";
        }
        return text;
    }

    /** PDFBox 抽取正文；失败/异常返回 null，由上层回退启发式扫描 */
    private String extractPdfWithPdfBox(byte[] data) {
        try (PDDocument doc = PDDocument.load(data)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        } catch (Exception e) {
            logger.warn("PDFBox 抽取失败，回退启发式: {}", e.getMessage());
            return null;
        }
    }

    /** 启发式正则扫描：直接扫文本算子（Tj/TJ）+ 解压 FlateDecode 流。无 ToUnicode 解码，作为 PDFBox 的回退 */
    private String extractPdfHeuristic(byte[] data) {
        StringBuilder sb = new StringBuilder();
        String raw = new String(data, StandardCharsets.ISO_8859_1);
        sb.append(scanPdfTextOperators(raw));
        Matcher streamM = Pattern.compile("(?s)stream\\r?\\n(.*?)\\r?\\nendstream").matcher(raw);
        while (streamM.find()) {
            int dictStart = Math.max(0, streamM.start() - 200);
            String dict = raw.substring(dictStart, streamM.start());
            if (dict.contains("/FlateDecode")) {
                byte[] comp = streamM.group(1).getBytes(StandardCharsets.ISO_8859_1);
                byte[] dec = inflate(comp);
                if (dec != null) {
                    String s = new String(dec, StandardCharsets.ISO_8859_1);
                    sb.append(scanPdfTextOperators(s));
                }
            }
        }
        return sb.toString();
    }

    /**
     * 过滤抽取文本里的重复短行（每页重复的页眉/页脚/页码/水印等样板噪声）。
     * 判定：同一行精确文本出现 ≥3 次且长度 ≤40，视为样板剔除。
     */
    private static String stripRepeatedBoilerplate(String text) {
        if (text == null || text.isEmpty()) return text;
        String[] lines = text.split("\n");
        Map<String, Integer> freq = new HashMap<>();
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty()) freq.merge(t, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) { sb.append('\n'); continue; }
            if (freq.getOrDefault(t, 0) >= 3 && t.length() <= 40) {
                continue; // 样板噪声行，剔除
            }
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 扫描 PDF 文本算子（Tj / TJ）。手写单遍扫描替代正则：
     * 原 `\(((?:[^()\\]|\\.)*)\)` 这类"量词包裹分支"的正则在超长文本上会递归爆栈
     * （StackOverflowError，Error 会逃逸成 500）。手写扫描是线性 O(n)，文本再长也不爆栈。
     */
    private String scanPdfTextOperators(String s) {
        StringBuilder out = new StringBuilder();
        scanTjOperator(s, out);      // (text) Tj
        scanTjArrayOperator(s, out); // [(t1) (t2) ...] TJ
        return out.toString();
    }

    private void scanTjOperator(String s, StringBuilder out) {
        int n = s.length(), i = 0;
        while (i < n) {
            int open = s.indexOf('(', i);
            if (open < 0) break;
            PdfLiteral r = readPdfLiteral(s, open);
            if (r == null) { i = open + 1; continue; }
            int k = skipWs(s, r.end);
            if (k + 2 <= n && s.startsWith("Tj", k)) {
                out.append(unescapePdf(r.text)).append(' ');
                i = k + 2;
            } else {
                i = open + 1;
            }
        }
    }

    private void scanTjArrayOperator(String s, StringBuilder out) {
        int n = s.length(), i = 0;
        while (i < n) {
            int open = s.indexOf('[', i);
            if (open < 0) break;
            // 找配对的 ]（跳过括号内的字符串字面量）
            int close = -1, j = open + 1;
            while (j < n) {
                char c = s.charAt(j);
                if (c == '(') {
                    PdfLiteral r = readPdfLiteral(s, j);
                    if (r == null) { j++; continue; }
                    j = r.end;
                } else if (c == ']') { close = j; break; }
                else j++;
            }
            if (close < 0) break;
            // 数组体内抽取每个 (text) 并拼接
            String body = s.substring(open + 1, close);
            int p = 0;
            while (p < body.length()) {
                int lp = body.indexOf('(', p);
                if (lp < 0) break;
                PdfLiteral r = readPdfLiteral(body, lp);
                if (r == null) break;
                out.append(unescapePdf(r.text)).append(' ');
                p = r.end;
            }
            int k = skipWs(s, close + 1);
            if (k + 2 <= n && s.startsWith("TJ", k)) {
                i = k + 2;
            } else {
                i = open + 1;
            }
        }
    }

    /** 一次字符串字面量解析结果：结束下标 + 原始内容 */
    private static class PdfLiteral {
        final int end;
        final String text;
        PdfLiteral(int end, String text) { this.end = end; this.text = text; }
    }

    /** 从 open 处的 '(' 读字符串字面量（处理 \x 转义），返回 [结束下标, 原文]；未闭合返回 null */
    private PdfLiteral readPdfLiteral(String s, int open) {
        int n = s.length();
        StringBuilder lit = new StringBuilder();
        int j = open + 1;
        while (j < n) {
            char c = s.charAt(j);
            if (c == '\\' && j + 1 < n) {
                lit.append(c).append(s.charAt(j + 1));
                j += 2;
            } else if (c == ')') {
                return new PdfLiteral(j + 1, lit.toString());
            } else {
                lit.append(c);
                j++;
            }
        }
        return null;
    }

    private int skipWs(String s, int i) {
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') i++;
            else break;
        }
        return i;
    }

    private String unescapePdf(String s) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': bytes.write('\n'); break;
                    case 'r': bytes.write('\r'); break;
                    case 't': bytes.write('\t'); break;
                    case 'b': bytes.write('\b'); break;
                    case 'f': bytes.write('\f'); break;
                    case '\\': bytes.write('\\'); break;
                    case '(': bytes.write('('); break;
                    case ')': bytes.write(')'); break;
                    default:
                        if (n >= '0' && n <= '7') { // 八进制转义
                            int val = n - '0';
                            int j = i + 1;
                            while (j < s.length() && j < i + 3 && s.charAt(j) >= '0' && s.charAt(j) <= '7') {
                                val = val * 8 + (s.charAt(j) - '0');
                                j++;
                            }
                            bytes.write(val);
                            i = j - 1;
                        } else {
                            bytes.write(n);
                        }
                }
            } else {
                bytes.write(c); // 源按 ISO-8859-1 解码，字符值即原始字节 0-255
            }
        }
        byte[] b = bytes.toByteArray();
        // UTF-16BE BOM（FE FF）：WPS/Word 导出的中文 PDF 常见，直接按 UTF-16BE 解码
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            int len = b.length - 2;
            if ((len & 1) == 1) len--; // 奇数长度去掉末尾半字节
            return new String(b, 2, len, StandardCharsets.UTF_16BE);
        }
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    /** 检测 PDF 尽力抽取出的文本是否为乱码（CID 字体 / UTF-16 错位等）。返回 true 表示不可读。 */
    private boolean looksGarbled(String text) {
        if (text == null || text.isEmpty()) return false;
        int total = text.length();
        int c1 = 0, replacement = 0, cjk = 0;
        for (int i = 0; i < total; i++) {
            char c = text.charAt(i);
            if (c >= 0x80 && c <= 0x9F) {
                c1++; // C1 控制符：正常西文/中文文本不会出现，乱码的显著标志
            } else if (c == '�') {
                replacement++;
            }
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                cjk++;
            }
        }
        if (cjk > 0) return false; // 含中文，不是乱码
        return (double) (c1 + replacement) > 0.05 * total;
    }

    private byte[] inflate(byte[] data) {
        try {
            Inflater inflater = new Inflater();
            inflater.setInput(data);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) {
                    if (inflater.finished() || inflater.needsDictionary()) break;
                    if (inflater.needsInput()) break;
                }
                baos.write(buf, 0, n);
            }
            inflater.end();
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // ===================== 工具 =====================

    private static String tryReadUtf8(byte[] data) {
        try {
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String normalize(String text) {
        if (text == null) return "";
        // 合并多余空白
        return text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static byte[] readAll(ZipInputStream zis) throws java.io.IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = zis.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private static String extension(String filename) {
        if (filename == null) return "";
        int idx = filename.lastIndexOf('.');
        return idx < 0 ? "" : filename.substring(idx + 1);
    }

    /** 抽取结果 */
    public static class ExtractResult {
        public final String text;
        public final boolean partial; // PDF 等可能不完整
        public ExtractResult(String text, boolean partial) {
            this.text = text;
            this.partial = partial;
        }
    }
}
