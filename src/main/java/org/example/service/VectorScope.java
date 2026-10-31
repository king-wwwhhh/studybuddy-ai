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

    /**
     * 请求级用户覆盖。
     *
     * <p><b>为什么需要</b>：SSE 对话在 chat-sse-* 线程里接收请求，
     * 但工具调用会被 Reactor 切到 boundedElastic 线程执行，
     * 此时 ThreadLocal 里的登录用户已经丢失，OwnerSupport.currentUserId() 退化为 0，
     * 检索条件变成"只查系统数据"，用户自己的文档一条都召回不到
     * （表现：文件明明在知识库里，一直说没找到）。</p>
     *
     * <p>因此由 Controller 在请求入口显式登记 userId，检索时优先读它。</p>
     */
    /**
     * 显式登记的请求用户（静态，跨线程可见）。
     *
     * <p>为什么不用 ThreadLocal：SSE 工具调用会切到 boundedElastic 线程池，
     * ThreadLocal / InheritableThreadLocal 在**线程池复用**场景下都不可靠
     * （线程是复用的，inherited 只在首次创建时复制一次）。</p>
     *
     * <p>本应用是单机学习助手，用户量小，用一个进程级变量 + 请求边界登记已足够；
     * 多租户高并发场景应改为把 userId 显式传入工具参数。</p>
     */
    private static volatile Long REQUEST_USER_OVERRIDE = null;

    /** 在请求入口登记当前用户（工具线程异步执行时也能读到正确的 userId） */
    public static void setRequestUser(Long userId) {
        if (userId != null) {
            REQUEST_USER_OVERRIDE = userId;
        }
    }

    /** 取出并清理（请求结束时调用，避免线程复用串号） */
    public static void clearRequestUser() {
        REQUEST_USER_OVERRIDE = null;
    }

    /** 纯视图：不消费，供检索时反复读取 */
    public static Long requestUser() {
        return REQUEST_USER_OVERRIDE;
    }

    /** 当前用户可检索的范围：本人 or 系统公共（user_id=0）or 旧数据（无该字段） */
    public static String searchExprForCurrentUser() {
        Long override = REQUEST_USER_OVERRIDE;
        return searchExpr(override != null ? override : OwnerSupport.currentUserId());
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
