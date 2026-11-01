package org.example.controller;

import org.example.auth.AuthContext;
import org.example.auth.AuthService;
import org.example.auth.UserRepository;
import org.example.dto.ApiResponse;
import org.example.service.MemoryService;
import org.example.support.SqliteTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AccountAndSessionRegressionTest {
    @AfterEach void clear() { AuthContext.clear(); }

    @Test void cachedSessionCannotBeReusedByAnotherUser() throws Exception {
        try (var db = new SqliteTestDatabase()) {
            var memory = new MemoryService(db.jdbc, null);
            var controller = new ChatController();
            SqliteTestDatabase.inject(controller, "memoryService", memory);
            var get = ChatController.class.getDeclaredMethod("getOrCreateSession", String.class);
            get.setAccessible(true);
            AuthContext.set(new AuthContext.Principal(1, "a@test", "USER"));
            Object first = get.invoke(controller, "private-session");
            memory.saveMessage("private-session", "user", "private content");
            assertSame(first, get.invoke(controller, "private-session"));
            AuthContext.set(new AuthContext.Principal(2, "b@test", "USER"));
            var error = assertThrows(InvocationTargetException.class, () -> get.invoke(controller, "private-session"));
            assertInstanceOf(AuthService.AuthException.class, error.getCause());
            assertEquals(403, ((AuthService.AuthException) error.getCause()).status);
        }
    }

    @Test void rejectedDemotionDoesNotPartiallyChangeUsernameAndLastAdminCannotBeDisabled() throws Exception {
        try (var db = new SqliteTestDatabase()) {
            db.user(1, "ADMIN");
            var controller = new AdminController(null, new UserRepository(db.jdbc));
            SqliteTestDatabase.inject(controller, "jdbc", db.jdbc);
            var response = controller.updateUser(1, Map.of("username", "changedname", "role", "USER"));
            assertFalse(((ApiResponse<?>) response.getBody()).isSuccess());
            assertEquals("user1", db.jdbc.queryForObject("SELECT username FROM users WHERE id=1", String.class));
            assertEquals(409, controller.updateStatus(1, "DISABLED").getStatusCode().value());
            assertEquals("ACTIVE", db.jdbc.queryForObject("SELECT status FROM users WHERE id=1", String.class));
        }
    }

    @Test void validEditSavesUsernameAndRoleTogether() throws Exception {
        try (var db = new SqliteTestDatabase()) {
            db.user(1, "ADMIN");
            db.user(2, "USER");
            var controller = new AdminController(null, new UserRepository(db.jdbc));
            SqliteTestDatabase.inject(controller, "jdbc", db.jdbc);
            var response = controller.updateUser(2, Map.of("username", "changedname", "role", "ADMIN"));
            assertTrue(((ApiResponse<?>) response.getBody()).isSuccess());
            assertEquals("changedname", db.jdbc.queryForObject("SELECT username FROM users WHERE id=2", String.class));
            assertEquals("ADMIN", db.jdbc.queryForObject("SELECT role FROM users WHERE id=2", String.class));
        }
    }
}
