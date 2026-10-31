-- 期末速成助手 · SQLite 表结构（每次启动按 IF NOT EXISTS 重建，幂等）
-- 列变更不再写 ALTER TABLE ADD COLUMN，由 SchemaMigrator 在启动期按列名幂等追加。

CREATE TABLE IF NOT EXISTS study_plan (
    user_id        INTEGER DEFAULT 0,
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    title         TEXT    NOT NULL,
    subject       TEXT,
    exam_date     TEXT,
    total_days    INTEGER,
    status        TEXT    NOT NULL DEFAULT 'PLANNING',
    priority      INTEGER DEFAULT 3,
    hours_per_day REAL    DEFAULT 2.0,
    notes         TEXT,
    goal_text     TEXT,
    last_replan_at    TEXT,             -- 最近一次重排时间（手动/自动都刷新），自动重排冷却锚点
    auto_replan_count INTEGER DEFAULT 0, -- 自动重排累计次数（观察执行状态自动触发）
    created_at    TEXT    NOT NULL,
    updated_at    TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS plan_task (
    user_id        INTEGER DEFAULT 0,
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    plan_id         INTEGER NOT NULL,
    phase           TEXT,
    week            INTEGER,
    day             INTEGER,
    title           TEXT    NOT NULL,
    description     TEXT,
    expected_hours  REAL,
    status          TEXT    NOT NULL DEFAULT 'TODO',
    scheduled_date  TEXT,
    actual_date     TEXT,
    completion_note TEXT,
    chapter         TEXT,
    FOREIGN KEY (plan_id) REFERENCES study_plan (id)
);

CREATE TABLE IF NOT EXISTS daily_checkin (
    user_id        INTEGER DEFAULT 0,
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    plan_id          INTEGER NOT NULL,
    date             TEXT    NOT NULL,
    done_task_ids    TEXT,
    completion_text  TEXT,
    mood             TEXT,
    hours_studied    REAL,
    created_at       TEXT    NOT NULL,
    FOREIGN KEY (plan_id) REFERENCES study_plan (id)
);

CREATE TABLE IF NOT EXISTS weak_area (
    user_id        INTEGER DEFAULT 0,
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    plan_id          INTEGER NOT NULL,
    subject          TEXT,
    chapter          TEXT,
    reason           TEXT,
    severity         INTEGER DEFAULT 3,
    suggested_action TEXT,
    created_at       TEXT    NOT NULL,
    updated_at       TEXT    NOT NULL,
    FOREIGN KEY (plan_id) REFERENCES study_plan (id)
);

-- 手动维护的章节清单：与 plan_task.chapter 一起作为出题「章节」选项来源
CREATE TABLE IF NOT EXISTS plan_chapter (
    user_id        INTEGER DEFAULT 0,
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    plan_id    INTEGER NOT NULL,
    name       TEXT    NOT NULL,
    created_at TEXT    NOT NULL,
    UNIQUE(plan_id, name),
    FOREIGN KEY (plan_id) REFERENCES study_plan (id)
);

CREATE TABLE IF NOT EXISTS chat_session (
    user_id        INTEGER DEFAULT 0,
    id         TEXT PRIMARY KEY,
    agent_name TEXT,
    title      TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS chat_message (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT    NOT NULL,
    role       TEXT    NOT NULL,
    content    TEXT    NOT NULL,
    created_at TEXT    NOT NULL,
    FOREIGN KEY (session_id) REFERENCES chat_session (id)
);

CREATE TABLE IF NOT EXISTS learner_profile (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id        INTEGER DEFAULT 0,
    name           TEXT,
    subjects       TEXT,
    exam_date      TEXT,
    learning_style TEXT,
    goals          TEXT,
    weak_subjects  TEXT,
    created_at     TEXT NOT NULL,
    updated_at     TEXT NOT NULL
);

-- 知识库已摄取文件记录（GitHub 真源镜像的元数据）：列表/去重/同步重建
CREATE TABLE IF NOT EXISTS knowledge_files (
    user_id        INTEGER DEFAULT 0,
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    file_name   TEXT    NOT NULL,
    repo        TEXT,
    repo_path   TEXT    NOT NULL UNIQUE,
    file_sha    TEXT,
    chunks      INTEGER,
    size_bytes  INTEGER,
    status      TEXT    NOT NULL DEFAULT 'READY',
    created_at  TEXT    NOT NULL,
    updated_at  TEXT    NOT NULL,
    stored_path TEXT
);

-- ============ 认证与权限 ============
-- 角色：USER / ADMIN；status：ACTIVE / DISABLED
CREATE TABLE IF NOT EXISTS users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    email         TEXT    NOT NULL UNIQUE,
    -- 登录名：唯一，可与邮箱二选一登录
    username      TEXT    UNIQUE,
    password_hash TEXT,
    nickname      TEXT,
    role          TEXT    NOT NULL DEFAULT 'USER',
    status        TEXT    NOT NULL DEFAULT 'ACTIVE',
    created_at    TEXT    NOT NULL,
    updated_at    TEXT    NOT NULL,
    last_login_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
-- 画像改为每人一行（R2）：通过 user_id 唯一索引保证一个用户只有一份画像
CREATE UNIQUE INDEX IF NOT EXISTS uq_learner_profile_user ON learner_profile(user_id);

-- 系统级开关（key-value）
CREATE TABLE IF NOT EXISTS system_settings (
    setting_key   TEXT PRIMARY KEY,
    setting_value TEXT,
    updated_at    TEXT
);
