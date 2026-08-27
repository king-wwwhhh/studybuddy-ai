-- 期末速成助手 · SQLite 表结构（每次启动按 IF NOT EXISTS 重建，幂等）
-- 列变更不再写 ALTER TABLE ADD COLUMN，由 SchemaMigrator 在启动期按列名幂等追加。

CREATE TABLE IF NOT EXISTS study_plan (
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
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    plan_id    INTEGER NOT NULL,
    name       TEXT    NOT NULL,
    created_at TEXT    NOT NULL,
    UNIQUE(plan_id, name),
    FOREIGN KEY (plan_id) REFERENCES study_plan (id)
);

CREATE TABLE IF NOT EXISTS chat_session (
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
    id             INTEGER PRIMARY KEY CHECK (id = 1),
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
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    file_name   TEXT    NOT NULL,
    repo        TEXT,
    repo_path   TEXT    NOT NULL UNIQUE,
    file_sha    TEXT,
    chunks      INTEGER,
    size_bytes  INTEGER,
    status      TEXT    NOT NULL DEFAULT 'READY',
    created_at  TEXT    NOT NULL,
    updated_at  TEXT    NOT NULL
);
