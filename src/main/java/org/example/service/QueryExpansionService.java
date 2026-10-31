package org.example.service;

import org.example.auth.OwnerSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 查询扩展：把「用户那句短问题」补成「能匹配到具体资料的检索词」。
 *
 * <p><b>为什么需要</b>：用户自然提问是「求职意向是什么」这种通用句式，
 * 而知识库里存的是一份具体简历。这句短查询的向量与文档距离很远
 * （实测 score 1.13 > 阈值 1.0 → 召回 0 条），
 * 但只要问题里带上文件名（「王浩杰-xxx.pdf 里面写了什么」），score 立刻降到 0.60 命中。
 * 也就是说：**检索失败不是知识库的问题，是查询词太泛**。</p>
 *
 * <p><b>做法</b>：检索前把三种信息拼进查询词——</p>
 * <ol>
 *   <li>原始问题（保留语义）</li>
 *   <li>该用户知识库的**文件名列表**（把"简历/计划"这类泛称锚定到具体文档）</li>
 *   <li>最近一轮用户提问（补全「这份文件」「他」这类指代）</li>
 * </ol>
 *
 * <p>成本为零（不调模型，只做字符串拼接），对用户完全无感。</p>
 */
@Service
public class QueryExpansionService {

    private static final Logger logger = LoggerFactory.getLogger(QueryExpansionService.class);

    private final JdbcTemplate jdbc;

    @Value("${rag.expansion.enabled:true}")
    private boolean enabled;

    /** 最多拼接多少个文件名，避免查询词过长反而稀释语义 */
    @Value("${rag.expansion.max-files:6}")
    private int maxFiles;

    public QueryExpansionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 扩展查询词。
     *
     * @param question 用户原始问题
     * @param previous 上一轮用户提问（可为 null）
     * @return 扩展后的查询词；未启用或无补充信息时原样返回
     */
    public String expand(String question, String previous) {
        if (!enabled || question == null || question.isBlank()) {
            return question;
        }
        StringBuilder sb = new StringBuilder(question.trim());

        List<String> fileNames;
        try {
            fileNames = jdbc.queryForList(
                    "SELECT file_name FROM knowledge_files WHERE " + OwnerSupport.visibleScope("user_id")
                            + " ORDER BY updated_at DESC LIMIT " + Math.max(1, maxFiles),
                    String.class);
        } catch (Exception e) {
            logger.debug("查询扩展：取文件名失败，按原查询检索: {}", e.getMessage());
            return question;
        }

        if (fileNames != null && !fileNames.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (String n : fileNames) {
                if (n == null || n.isBlank()) continue;
                // 去掉扩展名和多余字符，只保留可读的主体
                String base = n.replaceAll("(?i)\\.(pdf|docx?|pptx?|xlsx?|txt|md|markdown)$", "")
                        .replaceAll("[_\\-]+", " ").trim();
                if (!base.isEmpty()) names.append(base).append(' ');
            }
            if (names.length() > 0) {
                sb.append(' ').append(names.toString().trim());
            }
        }

        if (previous != null && !previous.isBlank() && !previous.equals(question)) {
            String p = previous.trim();
            if (p.length() > 80) p = p.substring(0, 80);
            sb.append(' ').append(p);
        }

        String expanded = sb.toString().trim();
        if (!expanded.equals(question.trim())) {
            logger.info("查询扩展: 「{}」->「{}」", question, expanded);
        }
        return expanded;
    }
}
