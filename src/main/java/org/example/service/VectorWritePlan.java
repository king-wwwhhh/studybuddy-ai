package org.example.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 向量写入计划：主键生成、旧版本清理表达式、写入完整性判定。
 *
 * <p>背景：原实现是「先删旧向量 → 再嵌入 → 再写入」。中间任一步失败（限流、网络、模型报错），
 * 旧向量已经没了、新向量又没写进去，该文件会从知识库里静默消失，而调用方只看到一个 FAILED。</p>
 *
 * <p>改成「先写新版本 → 校验条数 → 再删旧版本」就需要解决两件事，本类负责：</p>
 * <ol>
 *   <li><b>新旧数据不能撞主键</b>：主键里带内容版本（sha 前 8 位），内容变了就是新主键，
 *       新旧可以共存；同内容重复摄取仍得到同一批主键（幂等）。</li>
 *   <li><b>旧数据要能精确删掉</b>：按确定性主键算出上一版本的主键集合（同时覆盖历史无版本方案），
 *       用 `id in [...]` 定点删除，不依赖 JSON 条件过滤。</li>
 * </ol>
 *
 * <p>纯逻辑、无 Spring / Milvus 依赖，可直接单测。</p>
 */
public final class VectorWritePlan {

    /** 主键里的版本分隔符 */
    static final String VERSION_SEPARATOR = "#";

    private VectorWritePlan() {
    }

    /** 内容版本：sha 前 8 位（够区分内容，又不撑长主键） */
    public static String versionOf(String sha) {
        if (sha == null || sha.isBlank()) {
            return "0";
        }
        return sha.length() <= 8 ? sha : sha.substring(0, 8);
    }

    /**
     * 分片主键：`UUID(source#版本_分片号)`。
     * 同内容重复摄取 → 同一批主键（幂等覆盖同一批行）；内容变化 → 一批新主键，旧行由 {@link #previousIds} 精确清理。
     */
    public static String chunkId(String source, String sha, int chunkIndex) {
        String base = source + VERSION_SEPARATOR + versionOf(sha) + "_" + chunkIndex;
        return UUID.nameUUIDFromBytes(base.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** 历史主键方案（改造前不含版本号），只在清理旧数据时使用 */
    public static String legacyChunkId(String source, int chunkIndex) {
        return UUID.nameUUIDFromBytes((source + "_" + chunkIndex).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * 上一版本留下、需要清理的主键：同时覆盖「历史方案」与「版本化方案」，
     * 避免两套方案产出的行被漏删（否则同一文件会在检索结果里出现两份）。
     */
    public static List<String> previousIds(String source, String prevSha, int prevChunkCount) {
        Set<String> ids = new LinkedHashSet<>();
        if (source == null || source.isEmpty() || prevChunkCount <= 0) {
            return new ArrayList<>(ids);
        }
        for (int i = 0; i < prevChunkCount; i++) {
            ids.add(legacyChunkId(source, i));
            if (prevSha != null && !prevSha.isBlank()) {
                ids.add(chunkId(source, prevSha, i));
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * 按批切分待删除主键。
     *
     * <p>为什么要分批：`id in [...]` 是字符串表达式，主键是 36 字符 UUID，且每个旧分片会贡献
     * 2 个主键（历史方案 + 版本化方案）。一个 5000 分片的文件就是 1 万个 UUID ≈ 400 KB 表达式，
     * 会撞上 Milvus 表达式/RPC 的长度上限。默认按 {@link #DEFAULT_DELETE_BATCH} 分批。</p>
     */
    public static List<List<String>> batchIds(List<String> ids, int batchSize) {
        List<List<String>> batches = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            return batches;
        }
        int size = Math.max(1, batchSize);
        for (int i = 0; i < ids.size(); i += size) {
            batches.add(new ArrayList<>(ids.subList(i, Math.min(ids.size(), i + size))));
        }
        return batches;
    }

    /** 单次删除表达式里最多放多少个主键 */
    public static final int DEFAULT_DELETE_BATCH = 200;

    /** Milvus 删除表达式 `id in [...]`；空集合返回 null（调用方直接跳过删除） */
    public static String deleteExprByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("id in [");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(ids.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    /**
     * 写入是否完整。
     *
     * <p>以 Milvus 返回的总条数为准；部分服务端不回传条数（全为 0）时退化为「每批 RPC 都成功即视为成功」
     * —— 调用方只在 RPC 成功时把该批条数记进来。宁可保守失败（保留旧向量），也不要在数据不全时删旧。</p>
     */
    public static boolean writeComplete(List<Long> insertCounts, int expectedChunks) {
        if (expectedChunks <= 0 || insertCounts == null || insertCounts.isEmpty()) {
            return false;
        }
        long sum = 0;
        for (Long c : insertCounts) {
            if (c == null) {
                return false;
            }
            sum += c;
        }
        if (sum == expectedChunks) {
            return true;
        }
        return sum == 0; // 服务端未回传条数：以「每批 RPC 成功」为准
    }
}
