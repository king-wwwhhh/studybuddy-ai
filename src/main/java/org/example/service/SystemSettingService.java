package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统级开关（管理员可调）。
 *
 * <p>用单表 key-value 存放，避免为每个开关加一列。
 * 目前只有"是否允许注册"，后续加开关只需登记一个 key。</p>
 */
@Service
public class SystemSettingService {

    private static final Logger logger = LoggerFactory.getLogger(SystemSettingService.class);

    /** 是否允许新用户注册（true/false） */
    public static final String KEY_REGISTRATION_OPEN = "registration.open";

    private final JdbcTemplate jdbc;

    public SystemSettingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String get(String key, String defaultValue) {
        try {
            String v = jdbc.queryForObject("SELECT setting_value FROM system_settings WHERE setting_key=?",
                    String.class, key);
            return v == null ? defaultValue : v;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)));
    }

    public void set(String key, String value) {
        String now = LocalDateTime.now().toString();
        try {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM system_settings WHERE setting_key=?",
                    Integer.class, key);
            if (n != null && n > 0) {
                jdbc.update("UPDATE system_settings SET setting_value=?, updated_at=? WHERE setting_key=?",
                        value, now, key);
            } else {
                jdbc.update("INSERT INTO system_settings(setting_key, setting_value, updated_at) VALUES(?,?,?)",
                        key, value, now);
            }
            logger.info("系统开关变更: {} = {}", key, value);
        } catch (Exception e) {
            logger.warn("写入系统开关失败 {}={}: {}", key, value, e.getMessage());
        }
    }

    /** 注册是否开放 */
    public boolean isRegistrationOpen() {
        return getBoolean(KEY_REGISTRATION_OPEN, true);
    }

    /** 所有开关（供管理后台展示） */
    public Map<String, String> all() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            jdbc.query("SELECT setting_key, setting_value FROM system_settings ORDER BY setting_key",
                    rs -> {
                        out.put(rs.getString("setting_key"), rs.getString("setting_value"));
                    });
        } catch (Exception e) {
            logger.debug("读取系统开关失败: {}", e.getMessage());
        }
        return out;
    }
}
