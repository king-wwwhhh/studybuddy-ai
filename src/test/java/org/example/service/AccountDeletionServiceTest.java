package org.example.service;

import org.example.auth.AuthContext;
import org.example.support.SqliteTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AccountDeletionServiceTest {
    @TempDir Path data;
    @AfterEach void clear() { AuthContext.clear(); }

    private void seed(SqliteTestDatabase db) {
        db.user(1, "ADMIN");
        db.user(2, "USER");
        AuthContext.set(new AuthContext.Principal(1, "admin@test", "ADMIN"));
        for (int id : new int[]{1, 2}) {
            db.jdbc.update("INSERT INTO chat_session(id,user_id,created_at,updated_at) VALUES(?,?,?,?)",
                    "s" + id, id, "2026-11-01", "2026-11-01");
            db.jdbc.update("INSERT INTO chat_message(session_id,role,content,created_at) VALUES(?,'user','private',?)",
                    "s" + id, "2026-11-01");
        }
    }

    private UploadStorageService uploads() throws Exception {
        var uploads = new UploadStorageService();
        SqliteTestDatabase.inject(uploads, "dataDir", data.toString());
        return uploads;
    }

    private void file(SqliteTestDatabase db) throws Exception {
        Files.createDirectories(data.resolve("uploads/2"));
        Files.writeString(data.resolve("uploads/2/file.txt"), "private");
        db.jdbc.update("INSERT INTO knowledge_files(user_id,file_name,repo_path,stored_path,created_at,updated_at) "
                + "VALUES(2,'file.txt','u2/file.txt','uploads/2/file.txt','t','t')");
    }

    @Test void deletesMessagesBySessionAndCleansFilesWithoutTouchingOtherUser() throws Exception {
        try (var db = new SqliteTestDatabase()) {
            seed(db);
            file(db);
            var deleted = new java.util.ArrayList<Long>();
            var service = new AccountDeletionService(db.jdbc, uploads(), null) {
                @Override protected void deleteVectors(long userId) { deleted.add(userId); }
            };
            service.delete(2);
            assertEquals(java.util.List.of(2L), deleted);
            assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=2", Integer.class));
            assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE session_id='s2'", Integer.class));
            assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE session_id='s1'", Integer.class));
            assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_files WHERE user_id=2", Integer.class));
            assertFalse(Files.exists(data.resolve("uploads/2/file.txt")));
        }
    }

    @Test void externalFailurePreservesDisabledAccountAndCleanupReferencesForRetry() throws Exception {
        try (var db = new SqliteTestDatabase()) {
            seed(db);
            file(db);
            var service = new AccountDeletionService(db.jdbc, uploads(), null);
            assertThrows(IllegalStateException.class, () -> service.delete(2));
            assertEquals("DISABLED", db.jdbc.queryForObject("SELECT status FROM users WHERE id=2", String.class));
            assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_files WHERE user_id=2", Integer.class));
            assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE session_id='s2'", Integer.class));
            assertTrue(Files.exists(data.resolve("uploads/2/file.txt")));
        }
    }

    @Test void sqlFailureRollsBackAllDatabaseDeletes() throws Exception {
        try (var db = new SqliteTestDatabase()) {
            seed(db);
            db.jdbc.execute("CREATE TRIGGER fail_delete BEFORE DELETE ON users BEGIN SELECT RAISE(ABORT,'test failure'); END");
            var service = new AccountDeletionService(db.jdbc, uploads(), null);
            assertThrows(RuntimeException.class, () -> service.delete(2));
            assertEquals("DISABLED", db.jdbc.queryForObject("SELECT status FROM users WHERE id=2", String.class));
            assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM chat_session WHERE id='s2'", Integer.class));
            assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE session_id='s2'", Integer.class));
        }
    }

    @Test void rejectsCurrentAccountAndUnsafeFilePaths() throws Exception {
        try (var db = new SqliteTestDatabase()) {
            seed(db);
            var uploads = uploads();
            var service = new AccountDeletionService(db.jdbc, uploads, null);
            assertThrows(org.example.auth.AuthService.AuthException.class, () -> service.delete(1));
            assertThrows(java.io.IOException.class, () -> uploads.deleteRequired("../outside.txt"));
            assertThrows(java.io.IOException.class, () -> uploads.deleteRequired("uploads"));
        }
    }
}
