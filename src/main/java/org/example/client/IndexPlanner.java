package org.example.client;

/**
 * 向量索引选型与参数规划。
 *
 * <p>纯逻辑：无 Spring、无 Milvus 依赖，可直接单测。原来 `IndexType.IVF_FLAT` + `nlist=128`
 * 是写死在代码里的死值 —— 数据量只有几千 chunk 时 128 个桶聚类不稳、剪枝收益近 0；
 * 数据量涨到百万级时同一个 nlist/nprobe 又会让召回明显下降。这里把「按规模选型」规则收敛到一处。</p>
 *
 * <p>阈值依据：≤5 万条时暴力 FLAT 单查询仍是几十毫秒量级且 100% 召回；
 * 5 万~500 万是 HNSW 的甜点区（读多写少）；再往上内存与构建成本压不住，转 IVF 系。 </p>
 */
public final class IndexPlanner {

    public static final String TYPE_AUTO = "AUTO";
    public static final String FLAT = "FLAT";
    public static final String HNSW = "HNSW";
    public static final String IVF_FLAT = "IVF_FLAT";

    /** ≤ 此规模用 FLAT：精确、零调参、无边界效应 */
    public static final long FLAT_MAX_ROWS = 50_000L;

    /** ≤ 此规模用 HNSW：读多写少、召回敏感 */
    public static final long HNSW_MAX_ROWS = 5_000_000L;

    /** Faiss 经验：N < 39·nlist 时 k-means 聚类不稳 */
    static final long ROWS_PER_NLIST = 39L;

    private IndexPlanner() {
    }

    /**
     * 解析最终索引类型。
     *
     * @param configured 配置值：auto / FLAT / HNSW / IVF_FLAT（大小写不敏感，未知值按 auto 处理）
     * @param rows       collection 当前行数
     */
    public static String resolveType(String configured, long rows) {
        String c = configured == null ? "" : configured.trim().toUpperCase();
        if (c.isEmpty() || TYPE_AUTO.equals(c)) {
            return autoType(rows);
        }
        if (FLAT.equals(c) || HNSW.equals(c) || IVF_FLAT.equals(c)) {
            return c;
        }
        return autoType(rows); // 拼错配置不该让应用起不来，退回自动
    }

    /** 按规模自动选型 */
    public static String autoType(long rows) {
        if (rows <= FLAT_MAX_ROWS) {
            return FLAT;
        }
        if (rows <= HNSW_MAX_ROWS) {
            return HNSW;
        }
        return IVF_FLAT;
    }

    /**
     * IVF 桶数：以 4·√N 起步，夹在 [64, 65536]，并保证 N ≥ 39·nlist。
     * 小数据下宁可退化（桶更少），也不要聚类不稳导致召回抖动。
     */
    public static int nlistFor(long rows) {
        if (rows <= 0) {
            return 64;
        }
        long bySqrt = Math.round(4 * Math.sqrt(rows));
        long n = Math.max(64L, Math.min(65536L, bySqrt));
        long maxByRows = rows / ROWS_PER_NLIST;
        if (maxByRows > 0 && n > maxByRows) {
            n = Math.max(64L, maxByRows);
        }
        return (int) n;
    }

    /**
     * 建索引参数（Milvus extra params JSON）。
     *
     * @param nlist 配置值；≤0 表示按行数自动计算
     */
    public static String buildExtraParams(String type, long rows, int nlist, int m, int efConstruction) {
        if (HNSW.equals(type)) {
            return "{\"M\":" + Math.max(4, m) + ",\"efConstruction\":" + Math.max(8, efConstruction) + "}";
        }
        if (IVF_FLAT.equals(type)) {
            return "{\"nlist\":" + (nlist > 0 ? nlist : nlistFor(rows)) + "}";
        }
        return "{}"; // FLAT 无参数
    }

    /**
     * 检索参数（Milvus search params JSON）。
     *
     * <p>HNSW 的 `ef` 必须 ≥ topK，否则候选堆宽度不够、TopK 会被静默截断。</p>
     */
    public static String buildSearchParams(String type, int nprobe, int ef, int topK) {
        if (HNSW.equals(type)) {
            return "{\"ef\":" + Math.max(Math.max(ef, 64), topK) + "}";
        }
        if (IVF_FLAT.equals(type)) {
            return "{\"nprobe\":" + Math.max(1, nprobe) + "}";
        }
        return "{}"; // FLAT 无需参数
    }
}
