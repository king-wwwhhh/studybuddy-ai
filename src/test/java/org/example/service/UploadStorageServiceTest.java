package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 原文件落盘：文件名净化与路径穿越防护。 */
class UploadStorageServiceTest {

    @Test
    @DisplayName("保留中文，去掉路径部分")
    void keepsChineseStripsPath() {
        assertEquals("王浩杰-Agent开发最新.pdf", UploadStorageService.safeFileName("王浩杰-Agent开发最新.pdf"));
        assertEquals("a.pdf", UploadStorageService.safeFileName("../../etc/a.pdf"));
        assertEquals("a.pdf", UploadStorageService.safeFileName("C:\\Users\\x\\a.pdf"));
    }

    @Test
    @DisplayName("Windows 非法字符被替换")
    void sanitizesIllegalChars() {
        assertEquals("a_b_c_.pdf", UploadStorageService.safeFileName("a:b|c?.pdf").replace("__", "_"));
        assertFalse(UploadStorageService.safeFileName("a<b>c.pdf").contains("<"));
        assertFalse(UploadStorageService.safeFileName("a<b>c.pdf").contains(">"));
    }

    @Test
    @DisplayName("空名与点目录名归为 unknown")
    void handlesEmpty() {
        assertEquals("unknown", UploadStorageService.safeFileName(null));
        assertEquals("unknown", UploadStorageService.safeFileName(""));
        assertEquals("unknown", UploadStorageService.safeFileName(".."));
        assertEquals("unknown", UploadStorageService.safeFileName("."));
    }

    @Test
    @DisplayName("超长文件名截断")
    void truncatesLong() {
        String longName = "x".repeat(300) + ".pdf";
        assertTrue(UploadStorageService.safeFileName(longName).length() <= 120);
    }
}
