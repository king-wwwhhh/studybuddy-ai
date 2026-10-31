package org.example.service;

import org.example.auth.OwnerSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 上传原文件的落盘存储。
 *
 * <p><b>为什么需要</b>：原先 `file.getBytes()` 只在内存里用一次就丢，
 * 导致三个后果：①改了解析/OCR 参数无法重跑；②出问题无法复现（原文件没了）；
 * ③用户无法取回自己上传的资料。</p>
 *
 * <p><b>目录布局</b>（按用户分目录，天然隔离）：</p>
 * <pre>
 * {data-dir}/uploads/{userId}/{yyyyMM}/{sha8}_{安全文件名}
 * </pre>
 * <ul>
 *   <li>按 userId 分目录：即使代码出错也不会跨用户覆盖；</li>
 *   <li>文件名前缀取内容 sha 前 8 位：同内容不重复占空间，也便于排查；</li>
 *   <li>安全文件名：只保留字母数字与非 ASCII（中文原样保留），去掉路径分隔符，
 *       防止 ../../ 穿越和 Windows 非法字符。</li>
 * </ul>
 */
@Service
public class UploadStorageService {

    private static final Logger logger = LoggerFactory.getLogger(UploadStorageService.class);

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    @Value("${app.data-dir:C:/Users/joker/study_agent_data}")
    private String dataDir;

    @Value("${app.upload.enabled:true}")
    private boolean enabled;

    /** 保存上传文件，返回相对路径（入库用）；失败返回 null 不影响主流程 */
    public String save(byte[] data, String originalName, String sha) {
        if (!enabled) {
            return null;
        }
        try {
            long userId = OwnerSupport.currentUserId();
            Path dir = Paths.get(dataDir, "uploads", String.valueOf(userId),
                    LocalDate.now().format(MONTH));
            Files.createDirectories(dir);

            String prefix = (sha != null && sha.length() >= 8) ? sha.substring(0, 8) : "nouid";
            Path target = dir.resolve(prefix + "_" + safeFileName(originalName));

            // 同内容已存在则直接复用（避免重复占用）
            if (Files.exists(target) && Files.size(target) == data.length) {
                logger.debug("原文件已存在，跳过写入: {}", target);
                return relativize(target);
            }
            Path tmp = Files.createTempFile(dir, "up_", ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            logger.info("原文件已落盘: {} ({} bytes)", target, data.length);
            return relativize(target);
        } catch (Exception e) {
            // 落盘失败不能阻断上传：解析与向量化才是主链路
            logger.warn("原文件落盘失败（不影响入库）: {} -> {}", originalName, e.getMessage());
            return null;
        }
    }

    /** 删除某个原文件（相对路径）；不存在或失败静默 */
    public void delete(String relativePath) {
        if (!enabled || relativePath == null || relativePath.isBlank()) {
            return;
        }
        try {
            Path p = Paths.get(dataDir).resolve(relativePath).normalize();
            // 防穿越：必须仍在 uploads 目录内
            Path uploadsRoot = Paths.get(dataDir, "uploads").normalize().toAbsolutePath();
            if (!p.toAbsolutePath().startsWith(uploadsRoot)) {
                logger.warn("拒绝删除 uploads 目录外的路径: {}", relativePath);
                return;
            }
            boolean ok = Files.deleteIfExists(p);
            if (ok) {
                logger.info("已删除原文件: {}", p);
            }
        } catch (Exception e) {
            logger.warn("删除原文件失败: {} -> {}", relativePath, e.getMessage());
        }
    }

    /** 读取原文件（供下载/重解析）；不存在返回 null */
    public byte[] read(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        try {
            Path p = Paths.get(dataDir).resolve(relativePath).normalize();
            Path uploadsRoot = Paths.get(dataDir, "uploads").normalize().toAbsolutePath();
            if (!p.toAbsolutePath().startsWith(uploadsRoot) || !Files.exists(p)) {
                return null;
            }
            return Files.readAllBytes(p);
        } catch (IOException e) {
            logger.warn("读取原文件失败: {} -> {}", relativePath, e.getMessage());
            return null;
        }
    }

    private String relativize(Path target) {
        return Paths.get(dataDir).relativize(target).toString().replace('\\', '/');
    }

    /** 安全文件名：去路径分隔符与 Windows 非法字符；保留中文 */
    static String safeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "unknown";
        }
        String base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[<>:\"|?*\\x00-\\x1F]", "_");
        if (base.equals(".") || base.equals("..")) {
            return "unknown";
        }
        return base.length() > 120 ? base.substring(base.length() - 120) : base;
    }
}
