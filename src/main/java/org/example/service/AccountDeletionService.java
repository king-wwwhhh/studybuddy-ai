package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.dml.DeleteParam;
import org.example.auth.AuthService;
import org.example.auth.OwnerSupport;
import org.example.client.MilvusClientFactory;
import org.example.constant.MilvusConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** External cleanup is idempotent; keep DB references on failure so deletion can be retried. */
@Service
public class AccountDeletionService {
    private final JdbcTemplate jdbc;
    private final UploadStorageService uploads;
    private final MilvusClientFactory factory;
    private final TransactionTemplate transaction;
    @Autowired(required = false)
    private MilvusServiceClient milvus;

    public AccountDeletionService(JdbcTemplate jdbc, UploadStorageService uploads, MilvusClientFactory factory) {
        this.jdbc = jdbc;
        this.uploads = uploads;
        this.factory = factory;
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(
                java.util.Objects.requireNonNull(jdbc.getDataSource())));
    }

    public void delete(long userId) {
        if (userId <= 0 || userId == OwnerSupport.currentUserId()) {
            throw new AuthService.AuthException(400, "不能删除当前登录账号或系统账号");
        }
        var roles = jdbc.queryForList("SELECT role FROM users WHERE id=?", String.class, userId);
        if (roles.isEmpty()) throw new AuthService.AuthException(404, "用户不存在");
        Integer otherAdmins = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role='ADMIN' AND status='ACTIVE' AND id<>?", Integer.class, userId);
        if ("ADMIN".equals(roles.get(0)) && otherAdmins != null && otherAdmins == 0) {
            throw new AuthService.AuthException(409, "这是最后一个管理员，不能删除");
        }

        // Revoke future requests first. Failure leaves a disabled account and all cleanup references.
        jdbc.update("UPDATE users SET status='DISABLED', updated_at=? WHERE id=?",
                java.time.LocalDateTime.now().toString(), userId);
        Integer files = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_files WHERE user_id=?", Integer.class, userId);
        if (milvus != null || (files != null && files > 0)) deleteVectors(userId);
        for (String path : jdbc.queryForList("SELECT stored_path FROM knowledge_files WHERE user_id=?", String.class, userId)) {
            try {
                uploads.deleteRequired(path);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("原文件清理失败，账号已停用，请稍后重试删除", e);
            }
        }
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM chat_message WHERE session_id IN (SELECT id FROM chat_session WHERE user_id=?)", userId);
            for (String table : new String[]{"knowledge_files", "chat_session", "learner_profile", "plan_task",
                    "daily_checkin", "weak_area", "plan_chapter", "study_plan"}) {
                jdbc.update("DELETE FROM " + table + " WHERE user_id=?", userId);
            }
            jdbc.update("DELETE FROM users WHERE id=?", userId);
        });
    }

    protected void deleteVectors(long userId) {
        if (milvus == null) throw new IllegalStateException("向量服务不可用，账号已停用，请恢复服务后重试删除");
        var result = milvus.delete(DeleteParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withDatabaseName(factory.getResolvedDatabase())
                .withExpr(VectorScope.strictExpr(userId)).build());
        if (result.getStatus() != 0) {
            throw new IllegalStateException("向量清理失败，账号已停用，请稍后重试删除");
        }
    }
}
