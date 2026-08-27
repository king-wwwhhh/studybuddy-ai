package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * GitHub 知识库真源服务
 *
 * 走本机 SSH（~/.ssh/id_ed25519 已注册 GitHub），无需 PAT。
 * 通过 git CLI 实现：克隆私有仓库到本地缓存、推送新文件、拉取同步、列文件、算 sha。
 * 仓库即「真源」，本地 ./github_cache 为镜像，向量化始终基于缓存重建。
 */
@Service
public class GithubService {

    private static final Logger logger = LoggerFactory.getLogger(GithubService.class);

    @Value("${github.repo-url:}")
    private String repoUrl;

    @Value("${github.cache-path:./github_cache}")
    private String cachePath;

    @Value("${github.git-executable:git}")
    private String gitExecutable;

    @Value("${github.push-enabled:true}")
    private boolean pushEnabled;

    /** 仓库克隆/拉取一次（幂等） */
    public void ensureCloned() {
        Path cache = Paths.get(cachePath).normalize();
        Path gitDir = cache.resolve(".git");
        try {
            if (Files.exists(gitDir)) {
                logger.info("GitHub 缓存已存在，执行 git pull: {}", cache);
                runGit(cache, "pull");
            } else {
                Files.createDirectories(cache.getParent());
                logger.info("克隆 GitHub 仓库 {} -> {}", repoUrl, cache);
                // clone 目标目录不能已存在
                if (Files.exists(cache)) {
                    deleteRecursively(cache);
                }
                runGit(cache.getParent(), "clone", repoUrl, cache.toString());
            }
        } catch (Exception e) {
            logger.error("GitHub 克隆/拉取失败（知识库真源不可用，仅影响上传/同步）: {}", e.getMessage());
            throw new RuntimeException("GitHub 仓库同步失败: " + e.getMessage(), e);
        }
    }

    /** 将文件写入缓存并推送到 GitHub（真源）。best-effort：失败抛出，由调用方决定是否阻塞主线。 */
    public void pushFile(byte[] content, String relativePath, String commitMsg) {
        if (!pushEnabled) {
            logger.info("push-enabled=false，跳过 GitHub 推送: {}", relativePath);
            return;
        }
        Path cache = Paths.get(cachePath).normalize();
        try {
            ensureCloned();
        } catch (Exception e) {
            logger.warn("克隆/拉取失败，跳过 GitHub 真源（数据仍入本地 Milvus/DB）: {}", e.getMessage());
            return;
        }
        try {
            Path target = safeResolve(cache, relativePath);
            Files.createDirectories(target.getParent());
            Files.write(target, content);

            // 用 'add -A .' 而不是 'add <file>'：工作区可能还有未暂存删除/重命名，commit 会因此失败
            // 这里把所有变化一次性 stage 起来（包含新文件 + 删除 + 修改），保证 commit 一定能成
            runGit(cache, "add", "-A", ".");

            // commit 可能仍然 "nothing to commit"（内容完全相同），单独忽略
            try {
                runGit(cache, "-c", "user.name=study-agent", "-c", "user.email=agent@local",
                        "commit", "-m", commitMsg);
            } catch (RuntimeException ce) {
                String m = ce.getMessage() == null ? "" : ce.getMessage();
                if (m.contains("nothing to commit") || m.contains("no changes added to commit")) {
                    logger.info("内容无变化，跳过提交: {}", relativePath);
                    return;
                }
                throw ce;
            }

            // push 也是 best-effort：本机若没有 SSH 凭据 / 网络失败，不应阻塞上传
            try {
                runGit(cache, "push", "-u", "origin", "HEAD");
                logger.info("已推送至 GitHub 真源: {}", relativePath);
            } catch (RuntimeException pe) {
                logger.warn("本地缓存已 commit，但 git push 失败（数据安全，可在 console/CLI 手动重推）: {}", pe.getMessage());
            }
        } catch (IOException e) {
            // 仅写文件失败是真正错误——抛出给调用方
            throw new RuntimeException("写入/推送文件失败: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // 其它 git 异常都降级（best-effort）
            logger.warn("GitHub 真源跳过（其余链路继续）: {}", e.getMessage());
        }
    }

    /** 拉取最新（同步前调用） */
    public void pull() {
        Path cache = Paths.get(cachePath).normalize();
        if (Files.exists(cache.resolve(".git"))) {
            runGit(cache, "pull");
        } else {
            ensureCloned();
        }
    }

    /** 列出缓存中所有知识文件（相对路径，排除 .git） */
    public List<String> listFiles() {
        Path cache = Paths.get(cachePath).normalize();
        List<String> result = new ArrayList<>();
        if (!Files.exists(cache)) {
            return result;
        }
        try (Stream<Path> walk = Files.walk(cache)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !isInsideGit(p, cache))
                    .forEach(p -> result.add(cache.relativize(p).toString().replace(File.separatorChar, '/')));
        } catch (IOException e) {
            logger.warn("遍历缓存文件失败: {}", e.getMessage());
        }
        return result;
    }

    /** 读取缓存中的文件字节 */
    public byte[] readFile(String relativePath) {
        Path cache = Paths.get(cachePath).normalize();
        Path target = safeResolve(cache, relativePath);
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new RuntimeException("读取缓存文件失败: " + relativePath, e);
        }
    }

    /** 计算内容 SHA-256（用于去重/变更检测） */
    public String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 不可用", e);
        }
    }

    // ============================ 内部工具 ============================

    private void runGit(Path workingDir, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(gitExecutable);
        for (String a : args) {
            cmd.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);
        // 首次连接自动接受 GitHub host key（开发机个人仓库场景）
        pb.environment().put("GIT_SSH_COMMAND", "ssh -o StrictHostKeyChecking=accept-new");
        try {
            Process p = pb.start();
            String out;
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = p.getInputStream().read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
                out = baos.toString(StandardCharsets.UTF_8);
            }
            int code = p.waitFor();
            if (code != 0) {
                throw new RuntimeException("git " + String.join(" ", args) + " 失败(code=" + code + "): " + out);
            }
            logger.debug("git {} -> {}", String.join(" ", args), out.trim());
        } catch (IOException e) {
            throw new RuntimeException("执行 git 命令异常: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("git 命令被中断", e);
        }
    }

    private boolean isInsideGit(Path p, Path cache) {
        Path rel = cache.relativize(p);
        return rel.startsWith(".git") || rel.toString().replace(File.separatorChar, '/').startsWith(".git/");
    }

    /** 安全解析相对路径，禁止目录穿越 */
    private Path safeResolve(Path base, String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("非法的文件路径（禁止目录穿越）: " + relativePath);
        }
        Path resolved = base.resolve(normalized).normalize();
        if (!resolved.startsWith(base.normalize())) {
            throw new IllegalArgumentException("非法的文件路径: " + relativePath);
        }
        return resolved;
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        } else {
            Files.deleteIfExists(path);
        }
    }
}
