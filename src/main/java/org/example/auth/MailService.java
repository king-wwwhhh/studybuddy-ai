package org.example.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 验证码邮件发送（QQ 邮箱 SMTP）。
 *
 * <p>两种运行模式：</p>
 * <ul>
 *   <li><b>真实发送</b>：配置了 {@code spring.mail.username} 与密码（QQ 邮箱"授权码"）时走 SMTP；</li>
 *   <li><b>开发模式</b>：未配置时把验证码打到日志，接口照常返回成功 —— 本地联调不需要真实邮箱。</li>
 * </ul>
 * 这样即使邮件通道不可用，注册/找回密码流程也不会被阻塞（fail-open 只针对开发环境，生产必须配置）。
 */
@Service
public class MailService {

    private static final Logger logger = LoggerFactory.getLogger(MailService.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${spring.mail.username:}")
    private String from;

    @Value("${auth.mail.dev-log-fallback:true}")
    private boolean devLogFallback;

    public MailService(ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.mailSenderProvider = mailSenderProvider;
    }

    /** 是否已配置真实发件通道 */
    public boolean isConfigured() {
        return from != null && !from.isBlank() && mailSenderProvider.getIfAvailable() != null;
    }

    /**
     * 发送验证码。
     *
     * @return true 表示已投递（或开发模式下已打印）；false 表示真实发送失败且不允许降级
     */
    public boolean sendCode(String to, String code, String purpose) {
        String subject = "【学习助手】" + purposeCn(purpose) + "验证码";
        String body = "你的" + purposeCn(purpose) + "验证码是：" + code
                + "\n有效期 " + (VerificationCodePolicy.TTL_SECONDS / 60) + " 分钟，请勿转发给他人。\n"
                + "如果这不是你本人的操作，请忽略本邮件。";

        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null || from == null || from.isBlank()) {
            if (devLogFallback) {
                logger.warn("[开发模式] 未配置 SMTP，验证码输出到日志：to={} purpose={} code={}", to, purpose, code);
                return true;
            }
            logger.error("未配置 SMTP 且不允许降级，验证码无法发送：to={}", to);
            return false;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            sender.send(message);
            logger.info("验证码邮件已发送: to={} purpose={}", to, purpose);
            return true;
        } catch (Exception e) {
            logger.error("验证码邮件发送失败: to={} err={}", to, e.getMessage());
            if (devLogFallback) {
                logger.warn("[降级] 邮件发送失败，验证码输出到日志：to={} code={}", to, code);
                return true;
            }
            return false;
        }
    }

    private static String purposeCn(String purpose) {
        if ("REGISTER".equals(purpose)) return "注册";
        if ("RESET".equals(purpose)) return "找回密码";
        if ("LOGIN".equals(purpose)) return "登录";
        return "账号";
    }
}
