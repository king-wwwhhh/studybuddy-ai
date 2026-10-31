package org.example.service;

import org.example.auth.AuthContext;
import org.example.entity.PlanTask;
import org.example.entity.StudyPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 计划模块多用户隔离集成测试（R3）：共享内存 SQLite 真跑 SQL。
 *
 * <p>这是"两个用户互相看不到对方计划"的核心证据，覆盖：列表/详情/任务/看板/章节/薄弱点/
 * 删除与任务状态更新的越权拦截。</p>
 */
class PlanServiceIsolationTest {

    private JdbcTemplate jdbc;
    private PlanService service;
    private Connection keepAlive;

    @BeforeEach
    void setUp() throws SQLException {
        String db = "plan_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:file:" + db + "?mode=memory&cache=shared");
        keepAlive = ds.getConnection();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE study_plan(id INTEGER PRIMARY KEY AUTOINCREMENT,user_id INTEGER DEFAULT 0,"
                + "title TEXT,subject TEXT,exam_date TEXT,total_days INTEGER,status TEXT,priority INTEGER,"
                + "hours_per_day REAL,notes TEXT,goal_text TEXT,last_replan_at TEXT,auto_replan_count INTEGER DEFAULT 0,"
                + "created_at TEXT,updated_at TEXT)");
        jdbc.execute("CREATE TABLE plan_task(id INTEGER PRIMARY KEY AUTOINCREMENT,plan_id INTEGER,user_id INTEGER DEFAULT 0,"
                + "phase TEXT,week INTEGER,day INTEGER,title TEXT,description TEXT,expected_hours REAL,"
                + "status TEXT,scheduled_date TEXT,chapter TEXT,actual_date TEXT,completion_note TEXT)");
        jdbc.execute("CREATE TABLE daily_checkin(id INTEGER PRIMARY KEY AUTOINCREMENT,plan_id INTEGER,user_id INTEGER,"
                + "date TEXT,done_task_ids TEXT,completion_text TEXT,mood TEXT,hours_studied REAL,created_at TEXT)");
        jdbc.execute("CREATE TABLE weak_area(id INTEGER PRIMARY KEY AUTOINCREMENT,plan_id INTEGER,user_id INTEGER,"
                + "subject TEXT,chapter TEXT,reason TEXT,severity INTEGER,suggested_action TEXT,created_at TEXT,updated_at TEXT)");
        jdbc.execute("CREATE TABLE plan_chapter(id INTEGER PRIMARY KEY AUTOINCREMENT,plan_id INTEGER,user_id INTEGER,"
                + "name TEXT,created_at TEXT,UNIQUE(plan_id,name))");
        service = new PlanService(jdbc, null);
    }

    @AfterEach
    void tearDown() throws SQLException {
        AuthContext.clear();
        if (keepAlive != null && !keepAlive.isClosed()) keepAlive.close();
    }

    private static void login(long userId) {
        AuthContext.set(new AuthContext.Principal(userId, "u" + userId + "@qq.com", "USER"));
    }

    private StudyPlan createPlanFor(String title) {
        return service.createPlan(title, "数学", LocalDate.now().plusDays(30), 30,
                List.of(Map.of("title", title + " 任务1", "day", 1),
                        Map.of("title", title + " 任务2", "day", 2)),
                3, 2.0, null, "通过考试");
    }

    @Test
    @DisplayName("A 建的计划，B 在列表里看不到，详情返回 null")
    void planNotVisibleToOthers() {
        login(1);
        StudyPlan a = createPlanFor("A 的计划");
        assertNotNull(a.getId());

        login(2);
        assertTrue(service.listPlans().isEmpty(), "B 不应看到 A 的计划");
        assertNull(service.getPlan(a.getId()), "B 按 id 直查也应拿不到");
        assertFalse(service.ownsPlan(a.getId()));

        login(1);
        assertEquals(1, service.listPlans().size());
        assertTrue(service.ownsPlan(a.getId()));
    }

    @Test
    @DisplayName("B 拿不到 A 的任务列表、看板、章节、薄弱点")
    void subResourcesNotVisible() {
        login(1);
        StudyPlan a = createPlanFor("A 的计划");
        service.addChapter(a.getId(), "第一章");
        service.upsertWeakArea(a.getId(), "第一章", "薄弱", 4, "多练");

        login(2);
        assertTrue(service.getTasks(a.getId()).isEmpty(), "任务列表应空");
        assertTrue(service.listChapters(a.getId()).isEmpty(), "章节应空");
        assertTrue(service.getWeakAreas(a.getId()).isEmpty(), "薄弱点应空");
        Map<String, Object> dash = service.getDashboard(a.getId());
        assertEquals("plan not found", dash.get("error"), "看板应拒绝");
    }

    @Test
    @DisplayName("B 删除 A 的计划无效，A 的数据仍然完好")
    void deleteRejected() {
        login(1);
        StudyPlan a = createPlanFor("A 的计划");

        login(2);
        assertEquals(0, service.deletePlan(a.getId()), "越权删除应返回 0");

        login(1);
        assertNotNull(service.getPlan(a.getId()), "A 的计划必须还在");
        assertEquals(2, service.getTasks(a.getId()).size());
    }

    @Test
    @DisplayName("B 修改 A 的任务状态被拒（返回 null，状态不变）")
    void updateTaskRejected() {
        login(1);
        StudyPlan a = createPlanFor("A 的计划");
        List<PlanTask> tasks = service.getTasks(a.getId());
        Long taskId = tasks.get(0).getId();

        login(2);
        assertNull(service.updateTaskStatus(taskId, "DONE"), "越权更新应返回 null");

        login(1);
        PlanTask after = service.getTasks(a.getId()).get(0);
        assertEquals("TODO", after.getStatus(), "任务状态不应被他人改动");
    }

    @Test
    @DisplayName("A 自己可以正常更新任务状态（隔离不能误伤正常路径）")
    void ownerCanStillUpdate() {
        login(1);
        StudyPlan a = createPlanFor("A 的计划");
        Long taskId = service.getTasks(a.getId()).get(0).getId();

        PlanTask updated = service.updateTaskStatus(taskId, "DONE", "完成了");
        assertNotNull(updated);
        assertEquals("DONE", updated.getStatus());
    }

    @Test
    @DisplayName("系统数据（user_id=0，改造前遗留）对所有登录用户可见")
    void legacySystemDataVisible() {
        jdbc.update("INSERT INTO study_plan(user_id,title,status,created_at,updated_at) VALUES(0,'旧计划','ACTIVE',?,?)",
                LocalDateTime.now().toString(), LocalDateTime.now().toString());

        login(7);
        assertEquals(1, service.listPlans().size(), "旧数据应作为公共数据可见");
        assertTrue(service.listPlans().get(0).getTitle().contains("旧计划"));
    }
}
