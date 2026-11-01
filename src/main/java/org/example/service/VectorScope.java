package org.example.service;

import org.example.auth.OwnerSupport;

/**
 * 向量检索/删除的**用户可见范围表达式**（Milvus boolean expr）。
 *
 * <p>为什么必须做：Milvus 的 chunk metadata 原本只有 `_source / file_sha / chunkIndex`，
 * 没有归属信息 —— 任何用户上传的文档，任何人都能检索到。这是多用户化里最危险的一条（跨用户数据泄露）。</p>
 *
 * <p>决策 D2「宽容过滤」：旧向量（多用户改造之前写入的）metadata 里没有 `user_id` 字段。
 * 如果表达式只写 `user_id == 当前用户`，这些旧数据会**全部检索不到**；
 * 所以额外放行"字段不存在"的情况（`not exists user_id`），把它们当作系统公共数据。</p>
 *
 * <p>注意：Milvus 的 JSON 过滤里判断"键是否存在"用 `exists`，不存在用 `not exists`。</p>
 */
public final class VectorScope {

    /** metadata 中承载归属的键名 */
    public static final String USER_FIELD = "user_id";

    private VectorScope() {
    }

    /** 当前用户可检索的范围：本人 or 系统公共（user_id=0）or 旧数据（无该字段） */
    public static String searchExprForCurrentUser() {
        return searchExpr(OwnerSupport.currentUserId());
    }

    /**
     * 指定用户的可见范围。
     *
     * @param userId 用户 id；{@link OwnerSupport#SYSTEM_USER_ID} 表示系统本人
     */
    public static String searchExpr(long userId) {
        return "metadata[\"" + USER_FIELD + "\"] == " + userId
                + " or metadata[\"" + USER_FIELD + "\"] == " + OwnerSupport.SYSTEM_USER_ID
                + " or not exists metadata[\"" + USER_FIELD + "\"]";
    }

    /**
     * 写入/删除时用的**严格归属**条件（不含旧数据放行）：
     * 写自己的数据、删自己的数据时必须精确匹配，避免误删他人。
     */
    public static String strictExpr(long userId) {
        return "metadata[\"" + USER_FIELD + "\"] == " + userId;
    }

    /**
     * 与应用侧 SQL 过滤保持一致的"token 是否属于该用户"判定（供测试与纯逻辑路径复用）。
     */
    public static boolean belongsTo(long rowUserId, long userId) {
        return rowUserId == userId || rowUserId == OwnerSupport.SYSTEM_USER_ID;
    }
}
