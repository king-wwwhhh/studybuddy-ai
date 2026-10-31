package org.example.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 用户名规则：长度 / 字符集 / 保留词 / 违规词。 */
class UsernamePolicyTest {

    @Test
    @DisplayName("合法用户名通过")
    void acceptsValid() {
        assertTrue(UsernamePolicy.isValid("zhangsan"));
        assertTrue(UsernamePolicy.isValid("小明同学"));
        assertTrue(UsernamePolicy.isValid("user_2024"));
        assertTrue(UsernamePolicy.isValid("TestUser"));
        assertTrue(UsernamePolicy.isValid("abc123"));
    }

    @Test
    @DisplayName("长度必须 4-20")
    void rejectsBadLength() {
        assertNotNull(UsernamePolicy.validate("abc"), "3 位应拒绝");
        assertNotNull(UsernamePolicy.validate("a".repeat(21)), "21 位应拒绝");
        assertNull(UsernamePolicy.validate("abcd"), "4 位应通过");
        assertNull(UsernamePolicy.validate("a".repeat(20)), "20 位应通过");
    }

    @Test
    @DisplayName("只允许字母数字下划线中文")
    void rejectsBadChars() {
        assertNotNull(UsernamePolicy.validate("user@name"));
        assertNotNull(UsernamePolicy.validate("name with space"));
        assertNotNull(UsernamePolicy.validate("user-name"));
        assertNotNull(UsernamePolicy.validate("用户名!!"));
    }

    @Test
    @DisplayName("纯数字拒绝（避免与手机号/QQ号混淆）")
    void rejectsAllDigits() {
        assertNotNull(UsernamePolicy.validate("12345678"));
    }

    @Test
    @DisplayName("系统保留名拒绝（防冒充管理员）")
    void rejectsReserved() {
        assertNotNull(UsernamePolicy.validate("admin"));
        assertNotNull(UsernamePolicy.validate("ADMIN"), "大小写不敏感");
        assertNotNull(UsernamePolicy.validate("root"));
        assertNotNull(UsernamePolicy.validate("official"));
        assertNotNull(UsernamePolicy.validate("客服"));
    }

    @Test
    @DisplayName("违规词拒绝")
    void rejectsBlockedWords() {
        assertNotNull(UsernamePolicy.validate("fuckyou"));
        assertNotNull(UsernamePolicy.validate("傻逼123"));
        assertNotNull(UsernamePolicy.validate("pornuser"));
    }

    @Test
    @DisplayName("空值与 null 拒绝")
    void rejectsEmpty() {
        assertNotNull(UsernamePolicy.validate(null));
        assertNotNull(UsernamePolicy.validate(""));
        assertNotNull(UsernamePolicy.validate("   "));
    }
}
