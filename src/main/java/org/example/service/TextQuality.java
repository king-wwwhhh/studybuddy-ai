package org.example.service;

/**
 * 正文可用性判定（纯逻辑）。
 *
 * <p>为什么单独抽出来：抽取出来的"非空文本"不等于"可用正文"。
 * 典型反例是一页封面 PDF —— 只有标题和网址共几十个字符，
 * 它会被当作正常内容切 1 片入库，检索命中后模型看不到任何实质内容。</p>
 */
public final class TextQuality {

    /** 有效字符下限：低于此值视为没有可用正文 */
    public static final int MIN_USEFUL_CHARS = 80;

    private TextQuality() {
    }

    /** 有效字符数：去掉所有空白后计数（避免大段空行凑数） */
    public static int usefulChars(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    /** 是否具备可用正文 */
    public static boolean usable(String text) {
        return usefulChars(text) >= MIN_USEFUL_CHARS;
    }

    /** 给用户看的说明文案 */
    public static String rejectionMessage(String text) {
        return String.format(
                "抽取到的正文过少（仅 %d 个有效字符，少于 %d），未写入知识库。"
                        + "该文件可能只有封面/图片，或正文无法复制。"
                        + "建议：换成可复制文本的版本，或先用 OCR 转换后再上传。",
                usefulChars(text), MIN_USEFUL_CHARS);
    }
}
