package org.example.auth;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 用户名规则（注册 / 管理员改用户名时统一校验）。
 *
 * <p><b>格式</b>：4-20 位，字母/数字/下划线/中文，必须含字母或中文（防止纯数字撞邮箱前缀）。</p>
 *
 * <p><b>违规词</b>：内置敏感词 + 系统保留词双重过滤。保留词是为了防止
 * 用户占用 "admin"、"root" 这类容易冒充官方/管理员的名称。</p>
 */
public final class UsernamePolicy {

    /** 长度下限 */
    public static final int MIN_LEN = 4;
    /** 长度上限 */
    public static final int MAX_LEN = 20;

    /** 只允许字母、数字、下划线、中文 */
    private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9_\\u4e00-\\u9fa5]+$");
    /** 必须含至少一个字母或中文（纯数字不允许，避免与手机号/QQ号混淆） */
    private static final Pattern HAS_LETTER = Pattern.compile("[A-Za-z\\u4e00-\\u9fa5]");

    /** 系统保留词：不允许普通用户注册（防止冒充官方/管理员） */
    private static final Set<String> RESERVED = new HashSet<>(Arrays.asList(
            "admin", "administrator", "root", "system", "sys", "superadmin",
            "official", "support", "help", "service", "staff", "moderator",
            "管理员", "官方", "系统", "客服", "超级管理员", "版主", "站长", "后台"
    ));

    /** 敏感词（含脏话/违法/歧视等，实际项目应外置到配置文件或词库） */
    private static final Set<String> BLOCKED = new HashSet<>(Arrays.asList(
            "fuck", "shit", "bitch", "dick", "porn", "sex", "nazi", "hitler",
            "傻逼", "智障", "脑残", "白痴", "废物", "垃圾", "操你", "草泥马",
            "法轮", "赌博", "毒品", "代考", "代写", "枪手", "色情", "裸聊"
    ));

    private UsernamePolicy() {
    }

    /**
     * 校验用户名。
     *
     * @return null 表示合规；否则返回给用户看的具体原因
     */
    public static String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            return "用户名不能为空";
        }
        String name = raw.trim();
        if (name.length() < MIN_LEN || name.length() > MAX_LEN) {
            return "用户名长度需在 " + MIN_LEN + "-" + MAX_LEN + " 位之间";
        }
        if (!ALLOWED.matcher(name).matches()) {
            return "用户名只能包含字母、数字、下划线或中文";
        }
        if (!HAS_LETTER.matcher(name).find()) {
            return "用户名至少包含一个字母或中文";
        }
        String lower = name.toLowerCase();
        if (RESERVED.contains(lower)) {
            return "该用户名是系统保留名，请换一个";
        }
        for (String bad : BLOCKED) {
            if (lower.contains(bad)) {
                return "用户名包含违规内容，请换一个";
            }
        }
        return null;
    }

    public static boolean isValid(String raw) {
        return validate(raw) == null;
    }
}
