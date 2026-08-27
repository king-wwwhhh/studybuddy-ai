// 期末速成助手 前端应用（手写 SPA）
// 设计要点：
//  1) 完全自包含，不依赖任何外网 CDN（内置轻量 Markdown 解析）
//  2) 演示模式：未连后端时自动启用，所有功能本地可交互，数据存 localStorage
//  3) 后端可达时（同源部署）自动切换为真实 API，无需改代码

/* 多 Agent 展示配置：名称 / 图标 / 主题色 / 一句话说明。
   让「谁是主管路由、谁在回答」在界面上一眼可见。 */
const AGENT_INFO = {
    auto:    { name: '自动路由',  icon: '🤖', color: '#9aa6c0', desc: '由主管 Agent 根据意图自动选择最合适的助手' },
    chat:    { name: '智能学伴',  icon: '💬', color: '#7c5cff', desc: '通用问答 / 日常闲聊，必要时检索知识库' },
    study:   { name: '答疑导师',  icon: '📚', color: '#19c3ff', desc: '知识点讲解 / RAG 检索答疑' },
    quiz:    { name: '出题考官',  icon: '📝', color: '#ff5ca8', desc: '测验刷题 / 交卷批改 / 标薄弱点' },
    planner: { name: '规划师',    icon: '📅', color: '#34d399', desc: '制定复习计划 / 按天拆解任务' },
    coach:   { name: '督导教练',  icon: '🔥', color: '#fbbf24', desc: '打卡监督 / 进度反馈 / 自动重排' }
};

class StudyBuddyApp {
    constructor() {
        this.apiBaseUrl = (location.origin.includes(':') ? location.origin : 'http://localhost:9900') + '/api';
        this.selectedAgent = 'auto';
        this.selectedModel = '';
        this.BUILTIN_MODELS = [
            { id: '', name: '默认模型', builtin: true },
            { id: 'qwen3-max', name: 'Qwen3-Max（最强）', builtin: true },
            { id: 'qwen3-30b-a3b-thinking-2507', name: 'Qwen3-30B 思维版', builtin: true },
            { id: 'qwen3-plus', name: 'Qwen3-Plus', builtin: true },
            { id: 'qwen3-turbo', name: 'Qwen3-Turbo（最快）', builtin: true }
        ];
        this.customModels = this.loadCustomModels();
        this.sessionId = this.generateSessionId();
        this.isBusy = false;
        this.currentChatHistory = [];
        this.chatHistories = this.loadChatHistories();
        // 演示模式默认开启，后续探测到后端会切换为 live
        this.demo = true;
        this._pendingUploadName = '';
        this.store = this.loadStore();

        this.bindGlobal();
        this.switchView('chat');
        this.renderHistorySidebar();
        this.init();
        this.bindFx(); // 粒子 / 液态悬停的最终绑定（具体实现在文件底部 IIFE 中按需挂）
    }
    /** 占位：粒子与液态效果的具体绑定在文件底部 IIFE 中通过 MutationObserver 自动跑，这里仅作为钩子存在。 */
    bindFx() { /* no-op；保留以防有子类需要扩展；副作用由底部 IIFE 自动绑定 */ }

    async init() {
        // 先以演示数据立即渲染，保证离线/独立预览秒开
        this.renderDemo();
        // 立即填充模型下拉（demo 列表）；后端可达时会被真实列表覆盖
        this.loadModels();
        // 后台探测后端，成功则切真实数据
        this.probeBackend();
    }

    /* ---------- 本地存储 / 演示数据仓库 ---------- */
    loadStore() {
        const get = (k, d) => { try { const v = JSON.parse(localStorage.getItem(k)); return v == null ? d : v; } catch (e) { return d; } };
        return {
            profile: get('sb_profile', { name: '同学', subjects: '高数, 线代, 英语', examDate: '', learningStyle: '费曼式', goals: '两周内刷完近五年真题' }),
            kb: get('sb_kb', [{ fileName: '高数上_期末笔记.md', chunkCount: 18, fileSize: 24000, uploadedAt: '2026-06-08' }]),
            plans: get('sb_plans', [{
                id: 'plan_demo1', title: '高数（上）期末冲刺', subject: '高等数学', examDate: '2026-06-20', streak: 3,
                tasks: [
                    { id: 't1', title: '第一章 极限与连续 · 概念梳理', status: 'DONE', estimatedTime: '2h', dueDate: '2026-06-10' },
                    { id: 't2', title: '第二章 导数与微分 · 刷题', status: 'DOING', estimatedTime: '3h', dueDate: '2026-06-12' },
                    { id: 't3', title: '第三章 中值定理 · 证明题专项', status: 'TODO', estimatedTime: '3h', dueDate: '2026-06-14' },
                    { id: 't4', title: '第四章 不定积分 · 技巧归纳', status: 'TODO', estimatedTime: '2h', dueDate: '2026-06-16' },
                    { id: 't5', title: '近五年真题套卷模考', status: 'TODO', estimatedTime: '4h', dueDate: '2026-06-18' }
                ]
            }]),
            sessions: get('sb_sessions', [
                { id: 's1', title: '费曼讲解：微积分基本定理', agentName: '答疑导师' },
                { id: 's2', title: '制定高数复习计划', agentName: '规划师' }
            ]),
            checkins: get('sb_checkins', []),
            quizResults: get('sb_quiz_results', [])
        };
    }
    persist() { try { localStorage.setItem('sb_profile', JSON.stringify(this.store.profile)); localStorage.setItem('sb_kb', JSON.stringify(this.store.kb)); localStorage.setItem('sb_plans', JSON.stringify(this.store.plans)); localStorage.setItem('sb_sessions', JSON.stringify(this.store.sessions)); localStorage.setItem('sb_checkins', JSON.stringify(this.store.checkins)); localStorage.setItem('sb_quiz_results', JSON.stringify(this.store.quizResults)); } catch (e) {} }

    async probeBackend() {
        const ctrl = new AbortController();
        const t = setTimeout(() => ctrl.abort(), 2200);
        try {
            const res = await fetch(this.apiBaseUrl + '/profile', { signal: ctrl.signal, cache: 'no-store' });
            clearTimeout(t);
            if (res.ok) {
                this.demo = false;
                const badge = document.getElementById('demoBadge'); if (badge) badge.hidden = true;
                // 拉取后端真实模型列表并刷新下拉
                this.loadModels();
                // 拉取后端真实 Agent 列表，渲染动态切换器
                this.loadAgents();
                // 切真实数据
                this.loadProfile(); this.loadSessions(); this.loadKnowledge(); this.loadPlans();
                this.toast('已连接后端', 'success');
            } else { this.markDemo(); }
        } catch (e) { clearTimeout(t); this.markDemo(); }
    }
    markDemo() { this.demo = true; const badge = document.getElementById('demoBadge'); if (badge) badge.hidden = false; }

    /* ---------- 模型切换（切换入口在对话框输入区） ---------- */
    async loadModels() {
        // live 模式下拉取后端真实模型列表，覆盖内置预设
        if (!this.demo) {
            try {
                const data = await this.api('/models', {});
                const remote = (data && data.models) || [];
                if (remote.length) {
                    this.BUILTIN_MODELS = [{ id: '', name: '默认模型 · ' + (data.current || ''), builtin: true }]
                        .concat(remote.map(m => ({ id: m.id, name: m.name || m.id, builtin: true })));
                }
            } catch (e) { /* 拉取失败则保留内置预设 */ }
        }
        this.renderModelMenu();
        this.updateModelPill();
    }
    getModel(id) {
        const all = this.BUILTIN_MODELS.concat(this.customModels);
        return all.find(m => m.id === id) || this.BUILTIN_MODELS[0];
    }

    /* ---------- Agent 切换器（动态渲染后端 /api/agents） ---------- */
    async loadAgents() {
        if (this.demo) return;
        try {
            const data = await this.api('/agents', {});
            const agents = Array.isArray(data) ? data : ((data && data.agents) || []);
            if (!agents.length) return;
            const switchEl = document.getElementById('agentSwitch');
            if (!switchEl) return;
            const labels = { chat: '学伴', study: '答疑', quiz: '出题', planner: '规划', coach: '督导' };
            // 保留「自动」在前，其余按后端返回动态渲染（带图标 + 说明 tooltip）
            const html = `<button class="chip active" data-agent="auto" title="由主管 Agent 根据意图自动选择最合适的助手">🤖 自动</button>` +
                agents.map(a => {
                    const info = this.agentInfo(a.type) || {};
                    const icon = info.icon ? info.icon + ' ' : '';
                    const desc = a.description || info.desc || '';
                    return `<button class="chip" data-agent="${this.escapeHtml(a.type)}" title="${this.escapeHtml(desc)}">${this.escapeHtml(icon + (labels[a.type] || a.name))}</button>`;
                }).join('');
            switchEl.innerHTML = html;
            // 重新绑定点击（原监听在 bindGlobal 中绑到容器，仍然有效）
        } catch (e) { /* 拉取失败则保留静态 chips */ }
    }
    updateModelPill() {
        const text = document.getElementById('modelPillText');
        const dot = document.getElementById('modelDot');
        const chip = document.getElementById('modelPillChip');
        if (!text) return;
        const m = this.getModel(this.selectedModel);
        text.textContent = m ? m.name : '默认模型';
        if (dot) dot.className = 'model-dot' + (m && m.external ? ' external' : '');
        if (chip) {
            if (m && m.external) {
                chip.textContent = '外部·仅对话';
                chip.className = 'src-chip src-ext';
            } else {
                chip.textContent = '内置';
                chip.className = 'src-chip src-builtin';
            }
            chip.title = m && m.external
                ? '外部 API：仅支持普通对话，不执行知识库检索/计划/打卡等工具'
                : '内置 DashScope 模型：多 Agent + 知识库 + 工具';
        }
    }
    /** 生成模型卡片列表 HTML（供「切换模型」「模型设置」两个弹窗共用） */
    _modelCardsHtml() {
        const all = this.BUILTIN_MODELS.concat(this.customModels);
        return all.map(m => {
            const isExt = !!m.external;
            const sub = isExt ? (m.baseUrl + ' · ' + m.model) : (m.id ? m.id : '后端默认路由');
            const src = isExt ? '外部 · 仅对话' : '内置 · 多 Agent';
            const active = m.id === this.selectedModel ? ' active' : '';
            const useLabel = m.id === this.selectedModel ? '使用中' : '切换';
            return `<div class="model-card${active}" data-mid="${this.escapeHtml(m.id)}">
                <div class="mc-info">
                    <div class="mc-name">${this.escapeHtml(m.name)} <span class="mi-source${isExt ? ' ext' : ''}">${src}</span></div>
                    <div class="mc-sub">${this.escapeHtml(sub)}</div>
                </div>
                <div class="mc-actions">
                    <button class="mini ${m.id === this.selectedModel ? 'primary' : ''}" data-use="${this.escapeHtml(m.id)}">${useLabel}</button>
                    ${isExt ? `<button class="mini danger" data-rm="${this.escapeHtml(m.id)}">删除</button>` : ''}
                </div>
            </div>`;
        }).join('');
    }
    /** 绑定模型卡片上的「切换 / 删除」点击 */
    _bindModelCards(root) {
        if (!root) return;
        root.querySelectorAll('[data-use]').forEach(b => b.addEventListener('click', () => this.selectModel(b.dataset.use)));
        root.querySelectorAll('[data-rm]').forEach(b => b.addEventListener('click', () => this.deleteModel(b.dataset.rm)));
    }
    /** 切换模型弹窗（输入区胶囊点击弹出） */
    renderModelMenu() {
        const list = document.getElementById('modelSwitchList');
        if (!list) return;
        list.innerHTML = this._modelCardsHtml();
        this._bindModelCards(list);
    }
    openModelSwitchModal() {
        this.renderModelMenu();
        const m = document.getElementById('modelSwitchModal');
        if (m) m.hidden = false;
    }
    closeModelSwitchModal() { const m = document.getElementById('modelSwitchModal'); if (m) m.hidden = true; }
    selectModel(id) {
        this.selectedModel = id;
        this.updateModelPill(); this.renderModelMenu(); this.renderModelList();
        this.closeModelSwitchModal();
        const m = this.getModel(id);
        if (m && m.external) {
            this.toast('已切换到外部 API：' + m.name + '（仅支持对话，不检索知识库）', 'info', 5000);
        } else {
            this.toast('已切换模型：' + (m ? m.name : '默认'), 'info');
        }
    }
    /* 外部模型（DeepSeek / OpenAI 兼容）配置 */
    loadCustomModels() { try { const v = JSON.parse(localStorage.getItem('sb_models')); return Array.isArray(v) ? v : []; } catch (e) { return []; } }
    saveCustomModels() { try { localStorage.setItem('sb_models', JSON.stringify(this.customModels)); } catch (e) {} }
    presetBase(p) { return { deepseek: 'https://api.deepseek.com/v1', openai: 'https://api.openai.com/v1', moonshot: 'https://api.moonshot.cn/v1', custom: '' }[p] || ''; }
    presetName(p) { return { deepseek: 'DeepSeek', openai: 'OpenAI', moonshot: 'Kimi', custom: '自定义' }[p] || '外部'; }
    openModelModal() { this.renderModelList(); const m = document.getElementById('modelModal'); if (m) m.hidden = false; }
    closeModelModal() { const m = document.getElementById('modelModal'); if (m) m.hidden = true; }
    renderModelList() {
        const list = document.getElementById('modelList');
        if (!list) return;
        list.innerHTML = this._modelCardsHtml();
        this._bindModelCards(list);
    }
    deleteModel(id) {
        this.customModels = this.customModels.filter(m => m.id !== id);
        this.saveCustomModels();
        if (this.selectedModel === id) this.selectedModel = '';
        this.updateModelPill(); this.renderModelMenu(); this.renderModelList();
        this.toast('已删除模型', 'info');
    }
    async addExternalModel() {
        const name = document.getElementById('extName').value.trim();
        const preset = document.getElementById('extPreset').value;
        let baseUrl = document.getElementById('extBase').value.trim();
        const modelId = document.getElementById('extModel').value.trim();
        const apiKey = document.getElementById('extKey').value.trim();
        if (!name || !modelId || !apiKey) { this.toast('请填写名称、模型 ID 和 API Key', 'warning'); return; }
        if (!baseUrl) baseUrl = this.presetBase(preset);
        const m = { id: 'ext_' + Date.now(), name, provider: this.presetName(preset), baseUrl, model: modelId, apiKey, external: true };
        this.customModels.push(m); this.saveCustomModels();
        ['extName', 'extBase', 'extModel', 'extKey'].forEach(id => document.getElementById(id).value = '');
        this.renderModelList(); this.renderModelMenu();
        this.selectModel(m.id);
        this.closeModelModal();
        this.toast('已添加：' + name + '（仅支持对话，不检索知识库）', 'success', 5000);
    }

    renderDemo() {
        this.loadProfile(); this.loadSessions(); this.loadKnowledge(); this.loadPlans();
    }

    /* ---------- 工具 ---------- */
    sleep(ms) { return new Promise(r => setTimeout(r, ms)); }
    escapeHtml(t) { const d = document.createElement('div'); d.textContent = t; return d.innerHTML; }
    generateSessionId() { return 'session_' + Math.random().toString(36).substr(2, 9) + '_' + Date.now(); }
    toast(msg, type = 'info', duration = 3200) {
        const n = document.createElement('div');
        n.className = 'toast ' + type; n.textContent = msg;
        document.body.appendChild(n);
        setTimeout(() => { n.style.animation = 'slideOut .3s ease'; setTimeout(() => n.remove(), 300); }, duration);
    }
    showOverlay(show, text = '处理中…', sub = '') {
        const o = document.getElementById('loadingOverlay'); if (!o) return;
        o.style.display = show ? 'flex' : 'none';
        document.getElementById('loadingText').textContent = text;
        document.getElementById('loadingSub').textContent = sub;
        document.body.style.overflow = show ? 'hidden' : '';
    }

    /* ---------- 离线 Markdown 解析（替代 CDN marked） ---------- */
    md(src) {
        if (!src) return '';
        const esc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
        const inline = s => s
            .replace(/`([^`]+)`/g, (m, c) => `<code>${c}</code>`)
            .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
            .replace(/\*([^*]+)\*/g, '<em>$1</em>')
            .replace(/\[([^\]]+)\]\(([^)]+)\)/g, (m, t, u) => `<a href="${u}" target="_blank" rel="noopener">${t}</a>`);
        const lines = esc(src).split('\n');
        let html = '', i = 0, inCode = false, codeBuf = [];
        while (i < lines.length) {
            let line = lines[i];
            if (line.startsWith('```')) {
                if (!inCode) { inCode = true; codeBuf = []; i++; continue; }
                inCode = false; html += `<pre><code>${codeBuf.join('\n')}</code></pre>`; i++; continue;
            }
            if (inCode) { codeBuf.push(line); i++; continue; }
            if (/^\s*$/.test(line)) { i++; continue; }
            if (/^###\s+/.test(line)) { html += `<h3>${inline(line.replace(/^###\s+/, ''))}</h3>`; i++; continue; }
            if (/^##\s+/.test(line)) { html += `<h2>${inline(line.replace(/^##\s+/, ''))}</h2>`; i++; continue; }
            if (/^#\s+/.test(line)) { html += `<h1>${inline(line.replace(/^#\s+/, ''))}</h1>`; i++; continue; }
            if (/^>\s+/.test(line)) { html += `<blockquote>${inline(line.replace(/^>\s+/, ''))}</blockquote>`; i++; continue; }
            if (/^---\s*$/.test(line)) { html += '<hr>'; i++; continue; }
            if (/^\s*[-*]\s+/.test(line)) {
                let items = [];
                while (i < lines.length && /^\s*[-*]\s+/.test(lines[i])) { items.push(`<li>${inline(lines[i].replace(/^\s*[-*]\s+/, ''))}</li>`); i++; }
                html += `<ul>${items.join('')}</ul>`; continue;
            }
            if (/^\s*\d+\.\s+/.test(line)) {
                let items = [];
                while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i])) { items.push(`<li>${inline(lines[i].replace(/^\s*\d+\.\s+/, ''))}</li>`); i++; }
                html += `<ol>${items.join('')}</ol>`; continue;
            }
            html += `<p>${inline(line)}</p>`; i++;
        }
        if (inCode) html += `<pre><code>${codeBuf.join('\n')}</code></pre>`;
        return html;
    }
    renderMarkdown(c) { return this.md(c); }
    highlight() {}

    /* ---------- API 路由（demo / live） ---------- */
    async api(path, opts = {}) {
        if (this.demo) return this.demoApi(path, opts);
        const res = await fetch(this.apiBaseUrl + path, opts);
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const ct = res.headers.get('content-type') || '';
        if (ct.includes('application/json')) {
            const data = await res.json();
            // 仅在 data.data 有值时解包（ApiResponse 形态）；否则返回完整对象
            if (data && data.data !== undefined && (data.code === 200 || data.message === 'success' || data.success === true)) return data.data;
            if (data && data.errorMessage) throw new Error(data.errorMessage);
            return data.data !== undefined ? data.data : data;
        }
        return res.text();
    }
    async apiJson(path, body) { return this.api(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }); }

    async demoApi(path, opts) {
        const method = (opts.method || 'GET').toUpperCase();
        let body = null;
        if (opts.body) { try { body = JSON.parse(opts.body); } catch (e) { body = null; } }

        if (/^\/chat$/.test(path) && method === 'POST') {
            await this.sleep(700 + Math.random() * 500);
            return { answer: this.demoReply(body.Question || '', body.Agent || 'auto'), activeAgent: body.Agent || 'auto' };
        }
        if (path === '/knowledge/list') return { files: this.store.kb };
        if (path === '/knowledge/upload' && method === 'POST') {
            const name = this._pendingUploadName || '资料';
            this.store.kb.unshift({ fileName: name, chunkCount: Math.floor(Math.random() * 20) + 5, fileSize: Math.floor((Math.random() * 500 + 50) * 1024), uploadedAt: new Date().toISOString().slice(0, 10) });
            this.persist();
            return { message: name + ' 已入库（演示）' };
        }
        if (path === '/knowledge/sync' && method === 'POST') return { message: '已同步（演示）' };
        if (/^\/knowledge\/.+$/.test(path) && method === 'DELETE') return true;

        if (path === '/plans' && method === 'GET') return this.store.plans;
        if (path === '/plans' && method === 'POST') { const p = this.makePlan(body); this.store.plans.unshift(p); this.persist(); return p; }
        if (path === '/plans/generate' && method === 'POST') { const p = this.aiMakePlan(body.goal); this.store.plans.unshift(p); this.persist(); return { message: '已生成计划', plan: p }; }
        let m;
        if ((m = path.match(/^\/plans\/(.+?)\/tasks\/(.+)$/)) && method === 'PUT') {
            const plan = this.store.plans.find(p => p.id === m[1]);
            if (plan) { const t = plan.tasks.find(t => t.id === m[2]); if (t) t.status = body.status; this.persist(); }
            return { ok: true };
        }
        if ((m = path.match(/^\/plans\/(.+?)\/replan$/)) && method === 'POST') {
            const plan = this.store.plans.find(p => p.id === m[1]); if (plan) this.replanTasks(plan); this.persist(); return { ok: true };
        }
        if ((m = path.match(/^\/plans\/(.+?)\/dashboard$/))) {
            const plan = this.store.plans.find(p => p.id === m[1]); return this.computeDashboard(plan);
        }
        if ((m = path.match(/^\/plans\/(.+?)\/weakness$/))) {
            const plan = this.store.plans.find(p => p.id === m[1]); return { weakAreas: this.computeWeak(plan) };
        }
        if ((m = path.match(/^\/plans\/(.+?)\/checkin$/)) && method === 'POST') {
            const plan = this.store.plans.find(p => p.id === m[1]);
            this.store.checkins.push({ planId: m[1], ...body, at: new Date().toISOString() });
            if (plan) plan.streak = (plan.streak || 0) + 1; this.persist();
            return { ok: true };
        }
        if ((m = path.match(/^\/plans\/(.+)$/)) && method === 'DELETE') {
            this.store.plans = this.store.plans.filter(p => p.id !== m[1]); this.persist(); return { ok: true };
        }
        if (path === '/quiz/generate' && method === 'POST') return { questions: this.genQuiz(body.topic, body.count, body.chapter) };
        if (path === '/quiz/grade' && method === 'POST') {
            const answers = body.answers || [];
            const score = answers.filter(a => a.correct).length;
            this.store.quizResults.push({ chapter: body.chapter, at: new Date().toISOString(), score, total: answers.length });
            this.persist();
            return { weakTopics: answers.some(a => !a.correct) ? [body.chapter] : [] };
        }
        if (path === '/profile' && method === 'GET') return this.store.profile;
        if (path === '/profile' && method === 'PUT') { this.store.profile = Object.assign({}, this.store.profile, body); this.persist(); return this.store.profile; }
        if (path === '/memory/sessions') return { sessions: this.store.sessions };
        if (path === '/models' && method === 'GET') return { current: 'qwen3-max', models: [
            { id: 'qwen3-max', name: 'Qwen3-Max（最强）', description: '通义千问最强旗舰' },
            { id: 'qwen3-30b-a3b-thinking-2507', name: 'Qwen3-30B 思维版', description: '轻量思维模型' },
            { id: 'qwen3-plus', name: 'Qwen3-Plus', description: '均衡性价比' },
            { id: 'qwen3-turbo', name: 'Qwen3-Turbo（最快）', description: '极速响应' }
        ] };
        return null;
    }

    /* ---------- 演示数据构造 ---------- */
    makePlan(b) {
        return { id: 'plan_' + Date.now(), title: b.title || '复习计划', subject: b.subject || '', examDate: b.examDate || '', streak: 0, tasks: [] };
    }
    aiMakePlan(goal) {
        const g = (goal || '期末复习').slice(0, 20);
        const days = ['第一天', '第二天', '第三天', '第四天', '第五天', '第六天', '考前一天'];
        const focus = ['概念梳理与公式推导', '典型例题精讲', '易错点专项突破', '综合刷题与计时', '真题套卷模考', '查漏补缺与复盘', '状态调整与速记'];
        const tasks = days.map((d, i) => ({ id: 't' + (i + 1), title: `${d} · ${focus[i % focus.length]}`, status: i === 0 ? 'DOING' : 'TODO', estimatedTime: (2 + (i % 3)) + 'h', dueDate: '' }));
        return { id: 'plan_' + Date.now(), title: g + ' · 复习计划', subject: '', examDate: '', streak: 0, tasks };
    }
    replanTasks(plan) {
        if (!plan.tasks.length) return;
        const order = ['TODO', 'DOING', 'DONE'];
        plan.tasks.forEach((t, i) => { t.status = order[i % 3]; });
    }
    computeDashboard(plan) {
        if (!plan) return { completionRate: 0, doneTasks: 0, totalTasks: 0, streak: 0, statusDistribution: [] };
        const total = plan.tasks.length || 1, done = plan.tasks.filter(t => t.status === 'DONE').length;
        const dist = {};
        plan.tasks.forEach(t => { dist[t.status] = (dist[t.status] || 0) + 1; });
        return {
            completionRate: Math.round(done / total * 100), doneTasks: done, totalTasks: plan.tasks.length,
            streak: plan.streak || 0,
            statusDistribution: Object.keys(dist).map(s => ({ status: s, count: dist[s] }))
        };
    }
    computeWeak(plan) {
        if (!plan) return [];
        const todo = plan.tasks.filter(t => t.status !== 'DONE');
        if (!todo.length) return [{ topic: '已掌握较好', weight: 20 }];
        return todo.slice(0, 5).map((t, i) => ({ topic: t.title.replace(/·.*/, '').trim() || t.title, weight: 55 + (i * 9) % 40 }));
    }
    genQuiz(topic, count, chapter) {
        count = Math.max(1, Math.min(20, Number(count) || 5));
        const key = (topic || '').toLowerCase();
        if (key.includes('拉格朗日') || key.includes('中值定理')) return this.bank(quizLagrange, count);
        if (key.includes('微积分') || key.includes('基本定理')) return this.bank(quizFund, count);
        if (key.includes('线代') || key.includes('线性代数') || key.includes('矩阵')) return this.bank(quizLinear, count);
        // 通用兜底：基于主题生成概念辨析题
        const out = [];
        for (let i = 0; i < count; i++) {
            out.push({
                question: `关于「${topic}」，下列说法第 ${i + 1} 条最可能正确的是？`,
                type: '单选题',
                options: ['A. 核心结论可直接套用，无需验证前提条件', 'B. 应先明确适用条件，再结合例题理解', 'C. 只需背诵定义即可应付考试', 'D. 与前后章节无关联，可孤立学习'],
                answer: 'B. 应先明确适用条件，再结合例题理解'
            });
        }
        return out;
    }
    bank(arr, count) { const a = arr.slice(); const out = []; for (let i = 0; i < count; i++) out.push(a[i % a.length]); return out; }

    demoReply(msg, agent) {
        const q = (msg || '').trim();
        if (/计划|复习|安排|规划/.test(q)) {
            return `好的，我帮你梳理一份 **${this.store.profile.subjects || '期末'}** 的冲刺安排：\n\n## 七天冲刺节奏\n1. **第 1-2 天**：过一遍核心概念与公式推导，建立知识网\n2. **第 3-4 天**：按题型专项刷题，重点攻「证明题」与「计算题」\n3. **第 5-6 天**：近五年真题套卷计时模考，查漏补缺\n4. **考前 1 天**：只看错题本与速记卡，调整作息\n\n> 提示：去「复习计划」点 **✨ AI 帮我规划**，我可以按你的目标拆得更细。\n\n需要我针对某一章先出题吗？`;
        }
        if (/讲|解释|是什么|原理|理解|费曼/.test(q)) {
            return `用费曼学习法来讲：\n\n### 一句话本质\n把一个概念**用自己的话**讲给完全不懂的人听，卡住的地方就是你的薄弱点。\n\n### 三步法\n- **第一步**：写下定义，不许用术语解释术语\n- **第二步**：举一个生活例子（类比）\n- **第三步**：找出"为什么这样"的因果链\n\n\`\`\`text\n例：导数 = 瞬时变化率\n就像车速表上某一刻的读数，不是"走了多远"，而是"此刻有多快"\n\`\`\`\n\n试试把刚才的问题按这三步写下来，发给我我帮你挑刺 💡`;
        }
        if (/题|测验|选择|考|练/.test(q)) {
            return `没问题！我已按你的主题准备好练习。\n\n请切换到左侧 **📝 测验刷题**，填写主题与数量后点「生成题目」，交卷会即时批改并标出薄弱点。\n\n> 例如主题填「拉格朗日中值定理」、数量 5，即可生成一套选择题。`;
        }
        return `收到～我是你的期末速成助手 🎓\n\n你可以让我：\n- **答疑**：把不懂的概念发给我（支持费曼式讲解）\n- **出题**：说"出 3 道关于 XX 的题"\n- **规划**：说"帮我制定一周复习计划"\n- **督导**：把资料传进知识库，我会基于你的资料回答\n\n当前回答由 **${this.agentName(agent)}** 处理。继续问吧！`;
    }
    agentName(a) { const i = AGENT_INFO[a]; return i ? i.name : '自动路由'; }
    agentInfo(a) { return (a && AGENT_INFO[a]) || null; }

    /* ---------- 导航 ---------- */
    bindGlobal() {
        document.getElementById('navList').addEventListener('click', e => { const b = e.target.closest('.nav-item'); if (b) this.switchView(b.dataset.view); });
        document.getElementById('sendButton').addEventListener('click', () => this.sendMessage());
        const input = document.getElementById('messageInput');
        input.addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); this.sendMessage(); } });
        input.addEventListener('input', () => { input.style.height = 'auto'; input.style.height = Math.min(input.scrollHeight, 160) + 'px'; });
        document.getElementById('newChatBtn').addEventListener('click', () => this.newChat());
        document.getElementById('agentSwitch').addEventListener('click', e => {
            const c = e.target.closest('.chip'); if (!c) return;
            this.selectedAgent = c.dataset.agent;
            document.querySelectorAll('#agentSwitch .chip').forEach(x => x.classList.toggle('active', x === c));
            this.setActiveAgent(this.selectedAgent);
            const info = this.agentInfo(this.selectedAgent);
            this.toast('已切换：' + (info ? info.icon + ' ' + info.name : '🤖 自动路由') +
                (this.selectedAgent === 'auto' ? '（由主管实时决策）' : ''), 'info');
        });
        document.getElementById('uploadChatBtn').addEventListener('click', () => document.getElementById('fileInput').click());
        document.getElementById('fileInput').addEventListener('change', e => { if (e.target.files[0]) this.uploadKnowledge(e.target.files[0]); e.target.value = ''; });
        document.querySelectorAll('.suggest').forEach(b => b.addEventListener('click', () => { document.getElementById('messageInput').value = b.dataset.q; this.sendMessage(); }));

        document.getElementById('kbFileInput').addEventListener('change', e => { [...e.target.files].forEach(f => this.uploadKnowledge(f)); e.target.value = ''; });
        document.getElementById('syncBtn').addEventListener('click', () => this.syncKnowledge());
        const dz = document.getElementById('dropZone');
        ['dragover', 'dragenter'].forEach(ev => dz.addEventListener(ev, e => { e.preventDefault(); dz.classList.add('over'); }));
        ['dragleave', 'drop'].forEach(ev => dz.addEventListener(ev, e => { e.preventDefault(); dz.classList.remove('over'); }));
        dz.addEventListener('drop', e => { [...e.dataTransfer.files].forEach(f => this.uploadKnowledge(f)); });

        document.getElementById('newPlanBtn').addEventListener('click', () => this.createPlan());
        document.getElementById('aiPlanBtn').addEventListener('click', () => this.aiPlan());
        document.getElementById('genQuizBtn').addEventListener('click', () => this.generateQuiz());
        document.getElementById('saveProfileBtn').addEventListener('click', () => this.saveProfile());

        // 管理章节弹窗（测验页）
        document.getElementById('manageChapterBtn').addEventListener('click', () => this.openChapterModal());
        document.getElementById('chapterModalClose').addEventListener('click', () => this.closeChapterModal());
        document.getElementById('chapterModal').addEventListener('click', e => { if (e.target.id === 'chapterModal') this.closeChapterModal(); });
        document.getElementById('addChapterBtn').addEventListener('click', () => this.addChapter());
        document.getElementById('chapterInput').addEventListener('keydown', e => { if (e.key === 'Enter') this.addChapter(); });

        // 创建计划（手动）
        document.getElementById('createPlanModalClose').addEventListener('click', () => this.closeCreatePlanModal());
        document.getElementById('cpCancel').addEventListener('click', () => this.closeCreatePlanModal());
        document.getElementById('cpSubmit').addEventListener('click', () => this.submitCreatePlan());
        document.getElementById('createPlanModal').addEventListener('click', e => { if (e.target.id === 'createPlanModal') this.closeCreatePlanModal(); });
        document.getElementById('checkinModalClose').addEventListener('click', () => this.closeCheckinModal());
        document.getElementById('ciCancel').addEventListener('click', () => this.closeCheckinModal());
        document.getElementById('ciSubmit').addEventListener('click', () => this.submitCheckin());
        document.getElementById('checkinModal').addEventListener('click', e => { if (e.target.id === 'checkinModal') this.closeCheckinModal(); });

        // AI 规划（详细表单）
        document.getElementById('aiPlanModalClose').addEventListener('click', () => this.closeAiPlanModal());
        document.getElementById('apCancel').addEventListener('click', () => this.closeAiPlanModal());
        document.getElementById('apSubmit').addEventListener('click', () => this.submitAiPlan());
        document.getElementById('aiPlanModal').addEventListener('click', e => { if (e.target.id === 'aiPlanModal') this.closeAiPlanModal(); });

        // 模型切换入口（对话框输入区）：点击胶囊 → 弹出「切换模型」弹窗
        const mp = document.getElementById('modelPill');
        if (mp) mp.addEventListener('click', e => { e.stopPropagation(); this.openModelSwitchModal(); });
        // 「切换模型」弹窗
        document.getElementById('modelSwitchModalClose').addEventListener('click', () => this.closeModelSwitchModal());
        document.getElementById('modelSwitchModal').addEventListener('click', e => { if (e.target.id === 'modelSwitchModal') this.closeModelSwitchModal(); });
        document.getElementById('modelSwitchSettingsBtn').addEventListener('click', () => { this.closeModelSwitchModal(); this.openModelModal(); });
        // 「模型设置」弹窗
        document.getElementById('modelSettingsBtn').addEventListener('click', e => { e.stopPropagation(); this.openModelModal(); });
        document.getElementById('modelModalClose').addEventListener('click', () => this.closeModelModal());
        document.getElementById('modelModal').addEventListener('click', e => { if (e.target.id === 'modelModal') this.closeModelModal(); });
        document.getElementById('extAddBtn').addEventListener('click', () => this.addExternalModel());
    }
    switchView(view) {
        document.querySelectorAll('.nav-item').forEach(n => n.classList.toggle('active', n.dataset.view === view));
        document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
        const el = document.getElementById('view-' + view); if (el) el.classList.add('active');
    }

    /* ---------- 对话 ---------- */
    async sendMessage() {
        const input = document.getElementById('messageInput');
        const msg = input.value.trim();
        if (!msg || this.isBusy) return;
        input.value = ''; input.style.height = 'auto';
        this.welcomeHide();
        this.addMsg('user', msg);
        this.isBusy = true; this.toggleInput(true);
        // 自动路由时提示「主管在选 Agent」，让多 Agent 决策过程可见
        const cur = this.agentInfo(this.selectedAgent);
        const loadingText = this.selectedAgent === 'auto'
            ? '🧭 主管路由中 · 挑选最合适的助手…'
            : (cur ? cur.icon + ' ' + cur.name + ' 思考中…' : '💭 思考中…');
        const loading = this.addLoading(loadingText);
        const model = this.getModel(this.selectedModel);

        try {
            // =============== 演示模式：逐字流式（不真正发请求） ===============
            if (this.demo) {
                const answer = this.demoReply(msg, this.selectedAgent);
                const msgNode = this.addMsg('assistant', '', { raw: true });
                msgNode.appendChild(this._buildMeta(((model && model.name) || '默认 · 演示') + ' 流式中…', this.selectedAgent === 'auto' ? null : this.selectedAgent));
                await this._streamSimulate(msgNode, answer, 18);
                const meta = msgNode.querySelector('.msg-meta');
                if (meta) meta.remove();
                msgNode._lastAgent = this.selectedAgent;
                msgNode.appendChild(this._buildMeta(((model && model.name) || '默认 · 演示') + ' · 流式', this.selectedAgent));
                this.applyAgentAvatar(msgNode, this.selectedAgent);
                this.setActiveAgent(this.selectedAgent);
                loading.remove();
                this.pushHistory('assistant', answer);
                return;
            }

            // =============== 流式对话：内置 / 外部模型统一走 POST /chat_stream ===============
            // 这样无论内置还是外部，前端都会按 SSE 边来边显示，对应"流式输出"。
            const payload = {
                Id: this.sessionId,
                Question: msg,
                Agent: this.selectedAgent === 'auto' ? null : this.selectedAgent
            };
            let labelSuffix = ' · 流式中…';
            if (model && model.external) {
                if (!model.apiKey) throw new Error('该外部模型未配置 API Key，请点击 ⚙ 去设置');
                payload.ExternalBaseUrl = model.baseUrl;
                payload.ExternalApiKey = model.apiKey;
                payload.ExternalModelName = model.model;
                labelSuffix = ' · 多 Agent · 流式中…';
            } else {
                if (this.selectedModel) payload.Model = this.selectedModel;
            }
            const label = (model && model.name) || '默认模型';

            const msgNode = this.addMsg('assistant', '', { raw: true });
            msgNode.appendChild(this._buildMeta(label + labelSuffix, this.selectedAgent === 'auto' ? null : this.selectedAgent));

            const fullText = await this._streamChatBody(msgNode, payload, { timeoutMs: 120000 });
            loading.remove();
            // 流式成功后更新 meta：清晰展示「哪个 Agent + 哪个模型」答的
            const meta = msgNode.querySelector('.msg-meta');
            if (meta) meta.remove();
            const realAgent = msgNode._lastAgent || this.pushHistoryAgent || (this.selectedAgent !== 'auto' ? this.selectedAgent : null);
            msgNode.appendChild(this._buildMeta(label, realAgent));
            this.applyAgentAvatar(msgNode, realAgent);
            if (realAgent) this.setActiveAgent(realAgent);
            this.pushHistory('assistant', fullText || '（无回复）');
        } catch (e) {
            loading.remove();
            this.addMsg('assistant', '⚠️ 出错了：' + e.message + '\n\n' +
                (this.getModel(this.selectedModel) && this.getModel(this.selectedModel).external
                    ? '外部模型（DeepSeek/OpenAI）：检查 API Key / Base URL / 模型 ID，或确认 9900 已启动以便走代理。'
                    : '内置模型：确认 9900 已启动，或切换到演示模式查看效果。'));
        } finally {
            this.isBusy = false; this.toggleInput(false);
            this.saveCurrentChat();
        }
    }

    /**
     * 通用 SSE 流式消费：POST /chat_stream → 逐 chunk 写入 msgNode。
     * 返回最终累积内容；首 chunk 到达时通知调用方加载动画可取消（由调用方处理）。
     */
    async _streamChatBody(msgNode, payload, opts = {}) {
        const timeoutMs = opts.timeoutMs || 120000;
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort('timeout'), timeoutMs);
        let fullText = '';
        try {
            const resp = await fetch(this.apiBaseUrl + '/chat_stream', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
                body: JSON.stringify(payload),
                signal: controller.signal
            });
            if (!resp.ok || !resp.body) {
                const txt = await resp.text().catch(() => '');
                throw new Error('HTTP ' + resp.status + ' ' + (txt || '').slice(0, 200));
            }
            const reader = resp.body.getReader();
            const decoder = new TextDecoder('utf-8');
            let buf = '';
            while (true) {
                const { value, done } = await reader.read();
                if (done) break;
                buf += decoder.decode(value, { stream: true });
                let sep;
                while ((sep = buf.indexOf('\n\n')) >= 0) {
                    const event = buf.slice(0, sep);
                    buf = buf.slice(sep + 2);
                    const parsed = this._parseSseEvent(event);
                    if (!parsed) continue;
                    if (parsed.type === 'content' && parsed.data) {
                        fullText += parsed.data;
                        this._streamWrite(msgNode, fullText);
                    } else if (parsed.type === 'error') {
                        throw new Error(parsed.data || '流式错误');
                    } else if (parsed.type === 'done') {
                        if (parsed.agent) msgNode._lastAgent = parsed.agent;
                    }
                }
            }
            return fullText;
        } finally {
            clearTimeout(timer);
        }
    }

    /** 模拟逐字流式输出（demo 模式用） */
    async _streamSimulate(msgNode, fullText, ms = 18) {
        // 中文/英文都按字符切，最长不超过 5 字符一帧更流畅
        const chunkSize = fullText.length > 800 ? 6 : 3;
        for (let i = 0; i < fullText.length; i += chunkSize) {
            this._streamWrite(msgNode, fullText.slice(0, i + chunkSize));
            await this.sleep(ms);
        }
    }
    // pushHistoryAgent 全局占位（流式 done 事件写入，供 sendMessage 读取）
    pushHistoryAgent = null;
    appendModelBadge(msgNode, label) {
        if (!msgNode || !label) return;
        const meta = document.createElement('div');
        meta.className = 'msg-meta';
        meta.innerHTML = `<span class="mm-label">模型：</span><span class="mm-name">${this.escapeHtml(label)}</span>`;
        msgNode.appendChild(meta);
    }
    /** 解析单个 SSE 事件块。event 形如 'event: message\\ndata: {...}' */
    _parseSseEvent(event) {
        const lines = event.split(/\r?\n/);
        let dataStr = null;
        for (const line of lines) {
            if (line.startsWith('data:')) {
                dataStr = (dataStr ? dataStr : '') + line.substring(5).trim();
            }
        }
        if (!dataStr) return null;
        try {
            const obj = JSON.parse(dataStr);
            return { type: obj.type || 'content', data: obj.data, agent: obj.agent };
        } catch {
            return null;
        }
    }
    /** 把累积内容写到消息节点上（首次创建文本节点，后续更新） */
    _streamWrite(msgNode, fullText) {
        let body = msgNode.querySelector('.msg-body');
        if (!body) {
            body = document.createElement('div');
            body.className = 'msg-body markdown';
            msgNode.appendChild(body);
        }
        // 用 markdown 渲染（如有），否则纯文本
        if (this.renderMarkdown) {
            body.innerHTML = this.renderMarkdown(fullText);
        } else {
            body.textContent = fullText;
        }
        // 滚动到底
        const sc = document.getElementById('chatMessages');
        if (sc) sc.scrollTop = sc.scrollHeight;
    }
    _buildModelBadge(label) {
        const meta = document.createElement('div');
        meta.className = 'msg-meta';
        meta.innerHTML = `<span class="mm-label">模型：</span><span class="mm-name">${this.escapeHtml(label)}</span>`;
        return meta;
    }
    /** 构建消息 meta：Agent 徽标（带主题色）+ 模型名。agentType 为空则只显示模型。 */
    _buildMeta(modelLabel, agentType) {
        const meta = document.createElement('div');
        meta.className = 'msg-meta';
        const info = this.agentInfo(agentType);
        if (info) {
            const ag = document.createElement('span');
            ag.className = 'mm-agent';
            ag.textContent = info.icon + ' ' + info.name;
            ag.style.color = info.color;
            ag.style.background = info.color + '22';
            ag.style.borderColor = info.color + '66';
            meta.appendChild(ag);
        }
        const lbl = document.createElement('span');
        lbl.className = 'mm-label'; lbl.textContent = '模型：';
        const name = document.createElement('span');
        name.className = 'mm-name'; name.textContent = modelLabel;
        meta.appendChild(lbl); meta.appendChild(name);
        return meta;
    }
    /** 把助理消息的头像换成对应 Agent 的图标 + 主题色（用户头像不受影响） */
    applyAgentAvatar(msgNode, agentType) {
        const info = this.agentInfo(agentType);
        if (!msgNode || !info) return;
        const av = msgNode.querySelector('.msg-avatar');
        if (av && !av.classList.contains('user-av')) {
            av.textContent = info.icon;
            av.style.background = 'linear-gradient(135deg, ' + info.color + ', ' + info.color + 'aa)';
            av.style.boxShadow = '0 2px 10px ' + info.color + '66';
        }
    }
    /** addMsg 的 raw 模式：仅返回节点，不写 body，给流式填充用 */
    addMsg(role, content, opts = {}) {
        if (opts.raw) {
            const msgs = document.getElementById('chatMessages');
            const wrap = document.createElement('div');
            wrap.className = 'msg ' + role;
            const av = document.createElement('div');
            av.className = 'msg-avatar' + (role === 'user' ? ' user-av' : '');
            av.textContent = role === 'user' ? '你' : '🤖';
            const body = document.createElement('div');
            body.className = 'msg-body';
            body.textContent = '';
            wrap.appendChild(av); wrap.appendChild(body);
            msgs && msgs.appendChild(wrap);
            return wrap;
        }
        return this._addMsgLegacy(role, content);
    }
    _addMsgLegacy(role, content) {
        const msgs = document.getElementById('chatMessages');
        const wrap = document.createElement('div');
        wrap.className = 'msg ' + role;
        const av = document.createElement('div');
        av.className = 'msg-avatar' + (role === 'user' ? ' user-av' : '');
        av.textContent = role === 'user' ? '你' : '🤖';
        const body = document.createElement('div');
        body.className = 'msg-body markdown';
        if (role === 'assistant' && this.renderMarkdown) {
            body.innerHTML = this.renderMarkdown(content || '');
        } else {
            body.textContent = content;
        }
        wrap.appendChild(av); wrap.appendChild(body);
        msgs && msgs.appendChild(wrap);
        msgs && (msgs.scrollTop = msgs.scrollHeight);
        return wrap;
    }
    async chatExternalDirect(loading, model, msg) {
        // fallback：浏览器直连或后端 /proxy/chat（仅当后端不可达时退化使用）
        const messages = this.currentChatHistory
            .filter(m => m.type === 'user' || m.type === 'assistant')
            .map(m => ({ role: m.type, content: m.content }))
            .concat([{ role: 'user', content: msg }]);
        let answer = '';
        let viaProxy = false;
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort('timeout'), 60000);
        try {
            try {
                answer = await this.callOpenAI(model.baseUrl, model.apiKey, model.model, messages, controller.signal);
            } catch (e) {
                viaProxy = true;
                const data = await this.apiJson('/proxy/chat', { baseUrl: model.baseUrl, apiKey: model.apiKey, model: model.model, messages });
                if (data && data.error) throw new Error(String(data.error));
                answer = (data && (data.answer || data.content)) || '';
                if (!answer) throw new Error('代理返回空内容');
            }
        } catch (e) {
            clearTimeout(timer);
            const hint = e.name === 'AbortError' ? '外部模型请求超时（>60s）' : (e.message || String(e));
            throw new Error(hint);
        } finally { clearTimeout(timer); }
        loading.remove();
        const msgNode = this.addMsg('assistant', answer);
        this.appendModelBadge(msgNode, (model.name || '外部') + (viaProxy ? ' · 代理' : ' · 直连'));
        this.pushHistory('assistant', answer);
    }
    async callOpenAI(baseUrl, apiKey, modelId, messages, signal) {
        let url = (baseUrl || '').trim().replace(/\/+$/, '');
        if (!url) throw new Error('缺少 Base URL');
        if (!url.endsWith('/chat/completions')) url += '/chat/completions';
        const res = await fetch(url, {
            method: 'POST',
            signal,
            headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + apiKey },
            body: JSON.stringify({ model: modelId, messages, temperature: 0.7, stream: false })
        });
        if (!res.ok) {
            let detail = '';
            try { detail = (await res.text()).slice(0, 200); } catch (e) {}
            throw new Error('HTTP ' + res.status + ' ' + detail);
        }
        const data = await res.json();
        const c = data && data.choices && data.choices[0] && data.choices[0].message && data.choices[0].message.content;
        return c || '（空回复）';
    }
    welcomeHide() { const w = document.getElementById('welcomeCard'); if (w) w.style.display = 'none'; }
    toggleInput(d) { document.getElementById('messageInput').disabled = d; document.getElementById('sendButton').disabled = d; }
    addLoading(text) {
        const box = document.getElementById('chatMessages');
        const d = document.createElement('div'); d.className = 'msg assistant';
        d.innerHTML = `<div class="msg-avatar"><svg viewBox="0 0 24 24" fill="none"><path d="M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z" fill="white"/></svg></div><div class="msg-body loading-body"><span class="typing-dots"><i></i><i></i><i></i></span><span>${this.escapeHtml(text)}</span></div>`;
        box.appendChild(d); box.scrollTop = box.scrollHeight;
        return d;
    }
    setActiveAgent(a) {
        const info = this.agentInfo(a);
        const text = document.getElementById('activeAgentText');
        if (text) text.textContent = (info ? info.icon + ' ' : '') + (info ? info.name : '自动路由');
        const dot = document.querySelector('#activeAgentPill .dot');
        if (dot) { const c = info ? info.color : '#34d399'; dot.style.background = c; dot.style.boxShadow = '0 0 10px ' + c; }
        const pill = document.getElementById('activeAgentPill');
        if (pill) { pill.classList.remove('pulse'); void pill.offsetWidth; pill.classList.add('pulse'); }
    }

    /* ---------- 知识库 ---------- */
    async uploadKnowledge(file) {
        if (!file) return;
        // 后端限制 100MB（application.yml spring.servlet.multipart.max-file-size），提前拦截，避免 413 空响应
        const MAX_UPLOAD_BYTES = 100 * 1024 * 1024;
        if (file.size > MAX_UPLOAD_BYTES) {
            this.toast('文件过大：超过 100MB 限制', 'error');
            return;
        }
        this._pendingUploadName = file.name;
        this.showOverlay(true, '上传到知识库…', file.name);
        try {
            let message = '';
            if (this.demo) {
                // 演示模式：不发请求，直接落到本地
                const item = { fileName: file.name, chunkCount: Math.floor(Math.random() * 20) + 5, fileSize: file.size || 0, uploadedAt: new Date().toISOString().slice(0, 10) };
                this.store.kb.unshift(item);
                this.persist();
                message = file.name + ' 已入库（演示）';
            } else {
                // 真实模式：multipart/form-data，字段名 file（否则后端 415）
                const fd = new FormData();
                fd.append('file', file, file.name);
                fd.append('path', '');
                const res = await fetch(this.apiBaseUrl + '/knowledge/upload', { method: 'POST', body: fd });
                const txt = await res.text();
                let data = null;
                try { data = JSON.parse(txt); } catch (_) { data = { raw: txt }; }
                // 后端 ApiResponse 形态：{code:500, message:'...', data:null} 表示失败
                if (res.status === 413) throw new Error('文件过大，超过服务器限制（最大 100MB）');
                if (res.status >= 400) throw new Error('HTTP ' + res.status + ' ' + (data && data.message ? data.message : ''));
                if (data && data.code === 500) throw new Error(data.message || '上传失败');
                const payload = data && data.data;
                message = (payload && payload.message) || (data && data.message) || (file.name + ' 已入库');
            }
            this.toast(message, 'success');
            await this.loadKnowledge();
        } catch (e) {
            this.toast('上传失败：' + (e.message || e), 'error');
        } finally {
            this.showOverlay(false);
            this._pendingUploadName = '';
        }
    }
    async syncKnowledge() {
        this.showOverlay(true, '从 GitHub 同步…');
        try { const data = await this.apiJson('/knowledge/sync', {}); this.toast((data && data.message) || '同步完成', 'success'); await this.loadKnowledge(); }
        catch (e) { this.toast('同步失败：' + e.message, 'error'); }
        finally { this.showOverlay(false); }
    }
    async loadKnowledge() {
        const list = document.getElementById('kbList');
        const fmtDate = v => {
            if (!v) return '';
            if (Array.isArray(v)) {
                // JDBC 驱动有时返回 [yyyy, MM, dd, HH, mm, ss, ns] 数组
                const [y, M, d, h = 0, m = 0, s = 0] = v;
                return `${y}-${String(M).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
            }
            return String(v).substring(0, 10);
        };
        const sz = f => f.fileSize || f.sizeBytes || 0;
        try {
            const data = await this.api('/knowledge/list', {});
            // 后端 /api/knowledge/list 的实际形态：ApiResponse({code:200,data:[...]})
            // 经 api() 解析后这里 data 就是数组；但保留对旧 {files:[...]} 的兼容
            const items = Array.isArray(data) ? data : ((data && data.files) || []);
            if (!items.length) { list.innerHTML = '<div class="empty">还没有资料，上传或拖入文件开始吧。</div>'; return; }
            list.innerHTML = items.map(f => `
                <div class="kb-item">
                    <div class="kb-icon">${this.fileEmoji(f.fileName)}</div>
                    <div class="kb-meta">
                        <div class="kb-name">${this.escapeHtml(f.fileName)}</div>
                        <div class="kb-sub">${f.chunkCount || f.chunks || 0} 段 · ${this.fmtSize(sz(f))} · ${f.uploadedAt || fmtDate(f.updatedAt) || ''}</div>
                    </div>
                    <span class="kb-status ${(f.status === 'READY') ? 'ok' : 'warn'}">${f.status === 'READY' ? '已向量化' : (f.status || '处理中')}</span>
                    <button class="kb-del" data-kbdel="${f.id != null ? f.id : ''}" data-name="${this.escapeHtml(f.fileName)}" title="删除该文件">🗑</button>
                </div>`).join('');
            list.querySelectorAll('[data-kbdel]').forEach(b => b.addEventListener('click', () => this.deleteKnowledge(b.dataset.kbdel, b.dataset.name)));
        } catch (e) { list.innerHTML = '<div class="empty">加载失败：' + this.escapeHtml(e.message) + '</div>'; }
    }
    async deleteKnowledge(id, name) {
        if (!confirm('确定删除「' + name + '」？将从本地知识库移除向量与记录（GitHub 备份保留）。')) return;
        try {
            if (this.demo || !id) {
                this.store.kb = (this.store.kb || []).filter(f => f.fileName !== name);
                this.persist();
                this.toast('已删除（演示）', 'success');
                await this.loadKnowledge();
                return;
            }
            const res = await fetch(this.apiBaseUrl + '/knowledge/' + id, { method: 'DELETE' });
            const txt = await res.text();
            let data = null; try { data = JSON.parse(txt); } catch (_) { data = { raw: txt }; }
            if (res.status >= 400) throw new Error('HTTP ' + res.status + (data && data.message ? ' ' + data.message : ''));
            if (data && data.code === 500) throw new Error(data.message || '删除失败');
            this.toast('已删除「' + name + '」', 'success');
            await this.loadKnowledge();
        } catch (e) { this.toast('删除失败：' + (e.message || e), 'error'); }
    }
    fileEmoji(n) { const e = n.toLowerCase().split('.').pop(); return { pdf: '📕', doc: '📘', docx: '📘', ppt: '📙', pptx: '📙', xls: '📗', xlsx: '📗', md: '📝', markdown: '📝', txt: '📄' }[e] || '📄'; }
    fmtSize(b) { if (!b) return ''; const k = 1024, s = ['B', 'KB', 'MB', 'GB']; const i = Math.floor(Math.log(b) / Math.log(k)); return (b / Math.pow(k, i)).toFixed(1) + s[i]; }

    /* ---------- 计划 ---------- */
    async loadPlans() { await this.renderPlans(); await this.loadQuizPlans(); }
    async renderPlans() {
        const wrap = document.getElementById('plansWrap');
        try {
            const plans = await this.api('/plans', {});
            if (!plans || !plans.length) { wrap.innerHTML = '<div class="empty">还没有计划，点「新建计划」或「AI 帮我规划」。</div>'; return; }
            wrap.innerHTML = plans.map(p => this.planCard(p)).join('');
            this.bindPlanCards();
        } catch (e) { wrap.innerHTML = '<div class="empty">加载失败：' + this.escapeHtml(e.message) + '</div>'; }
    }
    planCard(p) {
        const tasks = p.tasks || [];
        const done = tasks.filter(t => t.status === 'DONE').length;
        const total = tasks.length || 1;
        const pct = Math.round(done / total * 100);
        const groups = this.groupTasksByDay(tasks);
        const daysHtml = groups.map(g => {
            const gTasks = g.tasks || [];
            const gDone = gTasks.filter(t => t.status === 'DONE').length;
            const gTotal = gTasks.length;
            const allDone = gTotal > 0 && gDone === gTotal;
            const phaseChip = g.phase ? `<span class="phase-chip">${this.escapeHtml(g.phase)}</span>` : '';
            const dayLabel = g.day != null ? `Day ${g.day}` : '未排期';
            const dateStr = g.scheduledDate ? this.fmtDay(g.scheduledDate) : '';
            const ratio = gTotal ? `<span class="day-ratio ${allDone ? 'all-done' : ''}">${gDone}/${gTotal} 完成</span>` : '';
            const rows = gTasks.map(t => `
                <div class="day-task ${t.status === 'DONE' ? 'done' : ''} ${t.status === 'DOING' ? 'doing' : ''}">
                    <div class="day-task-main">
                        <div class="day-task-title">
                            <span class="day-dot"></span>
                            <span>${this.escapeHtml(t.title)}</span>
                        </div>
                        <div class="day-task-tags">
                            ${t.chapter ? `<span class="chapter-tag">📖 ${this.escapeHtml(t.chapter)}</span>` : ''}
                            ${this.taskTime(t) ? `<span class="chapter-tag hour">⏱ ${this.escapeHtml(this.taskTime(t))}</span>` : ''}
                        </div>
                        ${t.description ? `<div class="day-task-desc">${this.escapeHtml(t.description)}</div>` : ''}
                        ${t.completionNote ? `<div class="day-task-note">✍️ ${this.escapeHtml(t.completionNote)}</div>` : ''}
                        <div class="tl-actions">
                            <button class="mini ${t.status === 'DONE' ? '' : 'primary'}" data-act="DONE" data-p="${p.id}" data-t="${t.id}">完成</button>
                            <button class="mini" data-act="DOING" data-p="${p.id}" data-t="${t.id}">进行中</button>
                            <button class="mini" data-act="TODO" data-p="${p.id}" data-t="${t.id}">重置</button>
                        </div>
                    </div>
                </div>`).join('');
            return `
            <div class="plan-day ${allDone ? 'complete' : ''}">
                <div class="plan-day-head">
                    <span class="day-name">${dayLabel}</span>
                    ${dateStr ? `<span class="day-date">${dateStr}</span>` : ''}
                    ${phaseChip}
                    ${ratio ? `<span class="day-ratio-wrap">${ratio}</span>` : ''}
                </div>
                <div class="plan-day-body">${rows || '<div class="empty small">暂无任务</div>'}</div>
            </div>`;
        }).join('');
        return `
        <div class="plan-card glass-card" data-plan="${p.id}">
            <div class="plan-head">
                <div>
                    <h3>${this.escapeHtml(p.title)}</h3>
                    <div class="plan-sub">${this.escapeHtml(p.subject || '')} ${p.examDate ? '· 考试 ' + this.escapeHtml(p.examDate) : ''}</div>
                    <div class="plan-meta">
                        ${p.priority != null ? `<span class="pm-pill">优先级 ${this.escapeHtml(String(p.priority))} / 5</span>` : ''}
                        ${p.hoursPerDay != null ? `<span class="pm-pill">每天 ${this.escapeHtml(String(p.hoursPerDay))} h</span>` : ''}
                        ${p.totalDays ? `<span class="pm-pill">共 ${this.escapeHtml(String(p.totalDays))} 天</span>` : ''}
                        ${tasks.length ? `<span class="pm-pill">已拆 ${tasks.length} 个任务</span>` : ''}
                    </div>
                    ${p.notes ? `<div class="plan-sub" style="margin-top:6px">备注：${this.escapeHtml(p.notes)}</div>` : ''}
                </div>
                <div class="plan-progress"><div class="ring" style="--p:${pct}"><span>${pct}%</span></div></div>
            </div>
            <div class="plan-bar"><div class="plan-bar-fill" style="width:${pct}%"></div></div>
            <div class="plan-days">${daysHtml || '<div class="empty small">暂无任务</div>'}</div>
            <div class="plan-foot">
                <button class="mini" data-replan="${p.id}">✨ 教练重排</button>
                <button class="mini" data-checkin2="${p.id}">📅 打卡</button>
                <button class="mini" data-dash="${p.id}">📊 看板</button>
                <button class="mini" data-weak="${p.id}">🔥 薄弱点</button>
                <button class="mini danger" data-delplan="${p.id}">删除</button>
            </div>
            <div class="plan-extra" id="extra-${p.id}"></div>
        </div>`;
    }
    taskTime(t) {
        const h = t.expectedHours != null ? t.expectedHours : (t.estimatedTime ? parseFloat(t.estimatedTime) : null);
        if (h == null || isNaN(h)) return '';
        return '约 ' + h + ' 小时';
    }
    groupTasksByDay(tasks) {
        const map = new Map();
        const unscheduled = [];
        for (const t of tasks) {
            if (t.day == null) { unscheduled.push(t); continue; }
            if (!map.has(t.day)) map.set(t.day, { day: t.day, scheduledDate: t.scheduledDate, phase: t.phase, tasks: [] });
            map.get(t.day).tasks.push(t);
        }
        const days = Array.from(map.values()).sort((a, b) => a.day - b.day);
        if (unscheduled.length) days.push({ day: null, scheduledDate: null, phase: null, tasks: unscheduled });
        return days;
    }
    fmtDay(dateStr) {
        if (dateStr == null || dateStr === '') return '';
        let M, d;
        if (Array.isArray(dateStr)) {
            // JDBC 驱动把 LocalDate 序列化成 [yyyy, MM, dd, ...] 数组
            [, M, d] = dateStr;
        } else {
            const m = String(dateStr).match(/^(\d{4})-(\d{2})-(\d{2})/);
            if (!m) return String(dateStr);
            M = m[2]; d = m[3];
        }
        if (M == null || d == null) return String(dateStr);
        return Number(M) + '月' + Number(d) + '日';
    }
    bindPlanCards() {
        document.querySelectorAll('.plan-card').forEach(card => {
            const pid = card.dataset.plan;
            card.querySelectorAll('[data-act]').forEach(b => b.addEventListener('click', async () => {
                await this.api(`/plans/${pid}/tasks/${b.dataset.t}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ status: b.dataset.act }) });
                await this.renderPlans();
            }));
            const checkin = card.querySelector('[data-checkin2]'); if (checkin) checkin.addEventListener('click', () => this.openCheckinModal(pid));
            const replan = card.querySelector('[data-replan]'); if (replan) replan.addEventListener('click', () => this.replan(pid));
            const dash = card.querySelector('[data-dash]'); if (dash) dash.addEventListener('click', () => this.dashboard(pid, card));
            const weak = card.querySelector('[data-weak]'); if (weak) weak.addEventListener('click', () => this.weakness(pid, card));
            const del = card.querySelector('[data-delplan]'); if (del) del.addEventListener('click', async () => { if (confirm('确认删除该计划？')) { await this.api('/plans/' + pid, { method: 'DELETE' }); await this.renderPlans(); } });
        });
    }
    async createPlan() {
        // 替代原来的 prompt() 三连；改为打开专用表单
        this.openCreatePlanModal();
    }
    openCreatePlanModal() {
        const m = document.getElementById('createPlanModal');
        if (!m) return;
        document.getElementById('cpTitle').value = '';
        document.getElementById('cpSubject').value = '';
        document.getElementById('cpExamDate').value = '';
        document.getElementById('cpTotalDays').value = '14';
        document.getElementById('cpHoursPerDay').value = '2';
        document.getElementById('cpPriority').value = '3';
        document.getElementById('cpNotes').value = '';
        m.hidden = false;
        setTimeout(() => document.getElementById('cpTitle').focus(), 80);
    }
    closeCreatePlanModal() { document.getElementById('createPlanModal').hidden = true; }
    async submitCreatePlan() {
        const title = document.getElementById('cpTitle').value.trim();
        const subject = document.getElementById('cpSubject').value.trim();
        const examDate = document.getElementById('cpExamDate').value;
        const totalDays = Number(document.getElementById('cpTotalDays').value) || 14;
        const hoursPerDay = Number(document.getElementById('cpHoursPerDay').value) || 2;
        const priority = Number(document.getElementById('cpPriority').value) || 3;
        const notes = document.getElementById('cpNotes').value.trim();
        const aiOn = document.getElementById('cpAiTasks') && document.getElementById('cpAiTasks').checked;
        if (!title) { this.toast('请填写标题', 'warning'); return; }
        if (aiOn) {
            // AI 按天拆任务：把表单信息交给 /plans/ai-detailed，AI 完善并落库
            this.showOverlay(true, 'AI 规划中…', 'AI 正在按你填写的信息拆解每日任务，约需 30~60 秒');
            try {
                const data = await this.apiJson('/plans/ai-detailed', { title, subject, examDate, totalDays, hoursPerDay, priority, notes, topics: notes });
                const tasks = (data && data.tasks) || [];
                this.toast('已生成 ' + tasks.length + ' 条任务，覆盖 ' + totalDays + ' 天', 'success', 6000);
                this.closeCreatePlanModal();
                await this.renderPlans();
                await this.loadQuizPlans();
            } catch (e) { this.toast('规划失败：' + this.escapeHtml(e.message), 'error'); }
            finally { this.showOverlay(false); }
            return;
        }
        try {
            await this.apiJson('/plans', { title, subject, examDate, totalDays, hoursPerDay, priority, notes, goalText: title });
            this.toast('计划已创建（空计划，可手动加任务）', 'success');
            this.closeCreatePlanModal();
            await this.renderPlans();
            await this.loadQuizPlans();
        } catch (e) { this.toast('失败：' + this.escapeHtml(e.message), 'error'); }
    }
    async aiPlan() {
        // 替代原来单个 prompt()，改为打开专用 AI 表单
        this.openAiPlanModal();
    }
    openAiPlanModal() {
        const m = document.getElementById('aiPlanModal');
        if (!m) return;
        document.getElementById('apTitle').value = '';
        document.getElementById('apSubject').value = '';
        document.getElementById('apExamDate').value = '';
        document.getElementById('apTotalDays').value = '14';
        document.getElementById('apHoursPerDay').value = '2';
        document.getElementById('apPriority').value = '3';
        document.getElementById('apTopics').value = '';
        document.getElementById('apNotes').value = '';
        m.hidden = false;
        setTimeout(() => document.getElementById('apTitle').focus(), 80);
    }
    closeAiPlanModal() { document.getElementById('aiPlanModal').hidden = true; }
    async submitAiPlan() {
        const title = document.getElementById('apTitle').value.trim();
        if (!title) { this.toast('请填写标题', 'warning'); return; }
        const subject = document.getElementById('apSubject').value.trim();
        const examDate = document.getElementById('apExamDate').value;
        const totalDays = Number(document.getElementById('apTotalDays').value) || 14;
        const hoursPerDay = Number(document.getElementById('apHoursPerDay').value) || 2;
        const priority = Number(document.getElementById('apPriority').value) || 3;
        const topics = document.getElementById('apTopics').value.trim();
        const notes = document.getElementById('apNotes').value.trim();
        this.showOverlay(true, 'AI 规划中…', '规划师正在按天拆任务，AI 生成 + 知识库检索约需 30~60 秒');
        try {
            const data = await this.apiJson('/plans/ai-detailed', { title, subject, examDate, totalDays, hoursPerDay, priority, notes, topics });
            const tasks = (data && data.tasks) || [];
            this.toast('已生成 ' + tasks.length + ' 条任务，覆盖 ' + totalDays + ' 天', 'success', 6000);
            this.closeAiPlanModal();
            await this.renderPlans();
            await this.loadQuizPlans();
        } catch (e) { this.toast('规划失败：' + this.escapeHtml(e.message), 'error'); }
        finally { this.showOverlay(false); }
    }
    async replan(pid) {
        this.showOverlay(true, '教练重排中…');
        try { await this.apiJson('/plans/' + pid + '/replan', {}); await this.renderPlans(); this.toast('已重排', 'success'); }
        catch (e) { this.toast('失败：' + e.message, 'error'); }
        finally { this.showOverlay(false); }
    }
    todayStr() {
        const d = new Date();
        return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
    }
    normDate(v) {
        if (v == null) return null;
        if (Array.isArray(v) && v.length >= 3) {
            return String(v[0]).padStart(4, '0') + '-' + String(v[1]).padStart(2, '0') + '-' + String(v[2]).padStart(2, '0');
        }
        return String(v).slice(0, 10);
    }
    async openCheckinModal(pid) {
        const m = document.getElementById('checkinModal');
        if (!m) return;
        this._ciPid = pid;
        document.getElementById('ciText').value = '';
        document.getElementById('ciMinutes').value = '60';
        document.getElementById('ciMood').value = '😀';
        const wrap = document.getElementById('ciTasks');
        wrap.innerHTML = '<div class="empty small">加载今日任务…</div>';
        m.hidden = false;
        const today = this.todayStr();
        try {
            const d = await this.api('/plans/' + pid, {});
            document.getElementById('ciPlanName').textContent = (d && d.plan && d.plan.title) ? d.plan.title : '';
            const tasks = (d && d.tasks) || [];
            const isToday = t => t.scheduledDate && this.normDate(t.scheduledDate) === today;
            let todayTasks = tasks.filter(t => isToday(t) && t.status !== 'DONE');
            if (!todayTasks.length) {
                // 今日无排期任务：回退到最近未完成任务（最多 5 条）
                todayTasks = tasks.filter(t => t.status !== 'DONE').slice(0, 5);
            }
            wrap.innerHTML = todayTasks.length
                ? todayTasks.map(t => `
                    <label class="ci-task">
                        <input type="checkbox" class="ci-check" value="${t.id}" checked />
                        <span class="ci-task-name">${this.escapeHtml(t.title)}</span>
                        <span class="ci-task-day">${t.scheduledDate ? this.fmtDay(t.scheduledDate) : ''}</span>
                    </label>`).join('')
                : '<div class="empty small">今天没有待办任务，可以直接记录学习内容 👍</div>';
        } catch (e) { wrap.innerHTML = '<div class="empty small err">加载失败：' + this.escapeHtml(e.message) + '</div>'; }
    }
    closeCheckinModal() { document.getElementById('checkinModal').hidden = true; this._ciPid = null; }
    async submitCheckin() {
        const pid = this._ciPid;
        if (!pid) { this.closeCheckinModal(); return; }
        const checks = [...document.querySelectorAll('#ciTasks .ci-check:checked')];
        const note = document.getElementById('ciText').value.trim();
        const minutes = Number(document.getElementById('ciMinutes').value) || 0;
        const mood = document.getElementById('ciMood').value;
        if (!checks.length && !note) { this.toast('请勾选完成的任务或填写学习内容', 'warning'); return; }
        try {
            // 1. 勾选的任务置为 DONE，并把「做了什么」写入 completion_note（后端自动带时间戳）
            for (const c of checks) {
                await this.api('/plans/' + pid + '/tasks/' + c.value, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ status: 'DONE', note }) });
            }
            // 2. 写入打卡记录
            await this.apiJson('/plans/' + pid + '/checkin', { date: this.todayStr(), doneTaskIdList: checks.map(c => c.value), completedContent: note, durationMinutes: minutes, mood: mood });
            this.toast('打卡成功 💪', 'success');
            this.closeCheckinModal();
            await this.renderPlans();
        } catch (e) { this.toast('打卡失败：' + this.escapeHtml(e.message), 'error'); }
    }
    async dashboard(pid, card) {
        const extra = card.querySelector('#extra-' + pid);
        try {
            const d = await this.api('/plans/' + pid + '/dashboard', {});
            // 自动重排提示：系统观察执行状态发现停滞，已自动重排剩余任务
            const autoNote = (d.autoReplanned && d.coachNote)
                ? `<div class="auto-replan-note">🤖 ${this.escapeHtml(d.coachNote)}</div>` : '';
            extra.innerHTML = `<div class="extra-card"><h4>进度看板</h4>${autoNote}
                <div class="kv"><span>完成率</span><b>${d.completionRate ?? 0}%</b></div>
                <div class="kv"><span>已完成/总任务</span><b>${(d.doneTasks ?? 0)}/${(d.totalTasks ?? 0)}</b></div>
                <div class="kv"><span>连续打卡</span><b>${d.streak ?? 0} 天</b></div>
                <div class="status-dist">${(d.statusDistribution || []).map(s => `<span class="badge">${this.escapeHtml(s.status)}: ${s.count}</span>`).join('')}</div>
                <button class="mini primary" data-checkin="${pid}">今日打卡</button>
            </div>`;
            if (d.autoReplanned) this.toast('检测到停滞，已自动重排剩余任务', 'info');
            extra.querySelector('[data-checkin]').addEventListener('click', () => this.openCheckinModal(pid));
        } catch (e) { extra.innerHTML = '<div class="extra-card err">' + this.escapeHtml(e.message) + '</div>'; }
    }
    async weakness(pid, card) {
        const extra = card.querySelector('#extra-' + pid);
        try {
            const d = await this.api('/plans/' + pid + '/weakness', {});
            const items = d.weakAreas || [];
            extra.innerHTML = `<div class="extra-card"><h4>薄弱点热力</h4>${(items.length ? items.map(w => `
                <div class="weak-row"><span class="weak-name">${this.escapeHtml(w.topic)}</span>
                <span class="weak-bar"><span style="width:${Math.min(100, (w.weight || 0))}%"></span></span>
                <span class="weak-w">${w.weight || 0}</span></div>`).join('') : '<div class="empty small">暂无数据，多做测验即可生成</div>')}
            </div>`;
        } catch (e) { extra.innerHTML = '<div class="extra-card err">' + this.escapeHtml(e.message) + '</div>'; }
    }

    /* ---------- 测验 ---------- */
    async loadQuizPlans() {
        try {
            const plans = await this.api('/plans', {});
            const sel = document.getElementById('quizPlanSelect');
            sel.innerHTML = '<option value="">— 不绑定计划（仅按主题出） —</option>' +
                (plans || []).map(p => `<option value="${p.id}">${this.escapeHtml(p.title)}${p.subject ? ' · ' + this.escapeHtml(p.subject) : ''}</option>`).join('');
            // 绑联动：选 plan 时加载该 plan 的章节到 quizChapterSelect
            sel.onchange = () => this.loadQuizChapters(sel.value);
        } catch (e) {}
    }
    async loadQuizChapters(planId) {
        const cs = document.getElementById('quizChapterSelect');
        const mb = document.getElementById('manageChapterBtn');
        if (!cs) return;
        if (!planId) {
            cs.disabled = true; cs.innerHTML = '<option value="">— 选中计划后可选章节 —</option>';
            if (mb) mb.disabled = true;
            return;
        }
        if (mb) mb.disabled = false;
        try {
            const list = await this.api('/plans/' + planId + '/chapters', {});
            const opts = ['<option value="">— 不指定章节（按计划全任务出题） —</option>']
                .concat((list || []).map(c => `<option value="${this.escapeHtml(c)}">${this.escapeHtml(c)}</option>`));
            cs.disabled = false;
            cs.innerHTML = opts.join('');
            // 如果没章节，给提示（但仍允许在「管理章节」里手动添加）
            if (!list || list.length === 0) {
                cs.innerHTML = '<option value="">— 该计划暂无章节，可点「管理章节」手动添加 —</option>';
                cs.disabled = true;
            }
        } catch (e) { cs.innerHTML = '<option value="">— 加载章节失败 —</option>'; cs.disabled = true; }
    }
    _quizPlanId() { return (document.getElementById('quizPlanSelect').value || '').trim(); }
    openChapterModal() {
        const pid = this._quizPlanId();
        if (!pid) { this.toast('请先在关联计划里选中一个计划', 'warning'); return; }
        const name = document.getElementById('chapterPlanName');
        if (name) {
            const sel = document.getElementById('quizPlanSelect');
            name.textContent = (sel && sel.selectedOptions && sel.selectedOptions[0]) ? sel.selectedOptions[0].textContent : ('计划 #' + pid);
        }
        document.getElementById('chapterModal').hidden = false;
        this.renderChapterList();
    }
    closeChapterModal() { document.getElementById('chapterModal').hidden = true; }
    async renderChapterList() {
        const pid = this._quizPlanId();
        const box = document.getElementById('chapterList');
        if (!box) return;
        if (!pid) { box.innerHTML = '<div class="chapter-empty">请先选择计划</div>'; return; }
        try {
            const list = await this.api('/plans/' + pid + '/chapters', {});
            box.innerHTML = (list && list.length)
                ? list.map(c => `<span class="chapter-chip">${this.escapeHtml(c)} <button class="rm" data-rm="${this.escapeHtml(c)}" title="删除">×</button></span>`).join('')
                : '<div class="chapter-empty">还没有章节，在下面输入章节名添加，如：二次型</div>';
            box.querySelectorAll('[data-rm]').forEach(b => b.addEventListener('click', () => this.removeChapter(b.dataset.rm)));
        } catch (e) { box.innerHTML = '<div class="chapter-empty">加载失败：' + this.escapeHtml(e.message) + '</div>'; }
    }
    async addChapter() {
        const pid = this._quizPlanId();
        const inp = document.getElementById('chapterInput');
        const name = (inp.value || '').trim();
        if (!pid) { this.toast('请先选择计划', 'warning'); return; }
        if (!name) { this.toast('请输入章节名', 'warning'); inp.focus(); return; }
        try {
            await this.apiJson('/plans/' + pid + '/chapters', { name });
            inp.value = '';
            this.toast('已添加章节：' + name, 'success');
            await this.renderChapterList();
            await this.loadQuizChapters(pid);
        } catch (e) { this.toast('添加失败：' + this.escapeHtml(e.message), 'error'); }
    }
    async removeChapter(name) {
        const pid = this._quizPlanId();
        if (!pid) return;
        try {
            await this.api('/plans/' + pid + '/chapters?name=' + encodeURIComponent(name), { method: 'DELETE' });
            this.toast('已删除章节：' + name, 'info');
            await this.renderChapterList();
            await this.loadQuizChapters(pid);
        } catch (e) { this.toast('删除失败：' + this.escapeHtml(e.message), 'error'); }
    }
    async generateQuiz() {
        const topic = document.getElementById('quizTopic').value.trim();
        const count = Number(document.getElementById('quizCount').value) || 5;
        const planId = (document.getElementById('quizPlanSelect').value || '').trim();
        const chapter = (document.getElementById('quizChapterSelect').value || '').trim();
        if (!topic && !chapter) { this.toast('请填写主题或选择章节', 'warning'); return; }
        this.showOverlay(true, '生成测验中…', 'RAG 检索 + LLM 出题，约需 15~40 秒');
        try {
            const data = await this.apiJson('/quiz/generate', { topic, count, planId: planId || null, chapter: chapter || null });
            const qs = (data && data.questions) || [];
            const useChapter = chapter || (data && data.chapter) || topic;
            this.renderQuizPaper(qs, planId || null, useChapter);
            this.toast(qs.length ? ('已生成 ' + qs.length + ' 题' + (chapter ? ' · 章节：' + chapter : '') + (planId ? ' · 按计划' : '')) : '出题失败，已显示原因', qs.length ? 'success' : 'warning', 5000);
        } catch (e) { this.toast('失败：' + this.escapeHtml(e.message), 'error'); }
        finally { this.showOverlay(false); }
    }
    renderQuizPaper(qs, planId, chapter) {
        const paper = document.getElementById('quizPaper');
        if (!qs.length) { paper.innerHTML = '<div class="empty">没有生成题目</div>'; return; }
        paper.innerHTML = `<div class="paper-head">共 ${qs.length} 题 · <button class="mini primary" id="gradeBtn">交卷批改</button></div>` +
            qs.map((q, i) => `
            <div class="q-item glass-card" data-i="${i}">
                <div class="q-title"><span class="q-no">${i + 1}.</span> ${this.escapeHtml(q.question)} <span class="q-type">${this.escapeHtml(q.type || '')}</span></div>
                <div class="q-options">${(q.options || []).map((o, j) => `
                    <label class="opt"><input type="radio" name="q${i}" value="${this.escapeHtml(o)}"><span>${this.escapeHtml(o)}</span></label>`).join('')}</div>
                <input class="q-answer" type="hidden" value="${this.escapeHtml(q.answer || '')}">
            </div>`).join('');
        paper.querySelector('#gradeBtn').addEventListener('click', () => this.gradeQuiz(qs, planId, chapter));
    }
    async gradeQuiz(qs, planId, chapter) {
        const paper = document.getElementById('quizPaper');
        const answers = qs.map((q, i) => {
            const sel = paper.querySelector(`input[name="q${i}"]:checked`);
            return { question: q.question, userAnswer: sel ? sel.value : '', correctAnswer: q.answer, correct: sel ? sel.value === q.answer : false };
        });
        const score = answers.filter(a => a.correct).length;
        this.showOverlay(true, '批改中…');
        try {
            const data = await this.apiJson('/quiz/grade', { answers, planId: planId || null, chapter });
            paper.insertAdjacentHTML('afterbegin', `<div class="score-banner">得分 ${score}/${qs.length} ${(data && data.weakTopics && data.weakTopics.length) ? '· 薄弱：' + data.weakTopics.map(t => this.escapeHtml(t)).join('、') : ''}</div>`);
            paper.querySelectorAll('.q-item').forEach((el, i) => {
                const a = answers[i]; el.classList.add(a.correct ? 'correct' : 'wrong');
                const mark = document.createElement('div'); mark.className = 'q-feedback';
                mark.innerHTML = a.correct ? '✅ 正确' : `❌ 你的：${this.escapeHtml(a.userAnswer || '未答')} ／ 正确：${this.escapeHtml(a.correctAnswer)}`;
                el.appendChild(mark);
            });
            this.toast('批改完成', 'success');
        } catch (e) { this.toast('批改失败：' + e.message, 'error'); }
        finally { this.showOverlay(false); }
    }

    /* ---------- 画像 / 会话 ---------- */
    async loadProfile() {
        try {
            const p = await this.api('/profile', {}); if (!p) return;
            document.getElementById('pfName').value = p.name || '';
            document.getElementById('pfSubjects').value = p.subjects || '';
            document.getElementById('pfExam').value = p.examDate || '';
            document.getElementById('pfStyle').value = p.learningStyle || '';
            document.getElementById('pfGoals').value = p.goals || '';
        } catch (e) {}
    }
    async saveProfile() {
        const body = {
            name: document.getElementById('pfName').value, subjects: document.getElementById('pfSubjects').value,
            examDate: document.getElementById('pfExam').value, learningStyle: document.getElementById('pfStyle').value, goals: document.getElementById('pfGoals').value
        };
        try { await this.api('/profile', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }); this.toast('画像已保存', 'success'); }
        catch (e) { this.toast('保存失败：' + e.message, 'error'); }
    }
    async loadSessions() {
        const list = document.getElementById('sessionList');
        try {
            const data = await this.api('/memory/sessions', {});
            const sessions = Array.isArray(data) ? data : ((data && data.sessions) || []);
            if (!sessions.length) { list.innerHTML = '<div class="empty small">暂无历史会话</div>'; return; }
            list.innerHTML = sessions.map(s => `<div class="session-item" data-sid="${s.id}" title="点击恢复该会话"><span>${this.escapeHtml(s.title || s.id)}</span><span class="session-agent">${this.escapeHtml(s.agentName || '')}</span></div>`).join('');
            list.querySelectorAll('.session-item').forEach(it => it.addEventListener('click', () => {
                const s = sessions.find(x => String(x.id) === String(it.dataset.sid));
                this.switchSession(s || { id: it.dataset.sid });
            }));
        } catch (e) { list.innerHTML = '<div class="empty small">加载失败</div>'; }
    }
    /** 切换会话：加载该会话消息 → 渲染到对话区 → 跳到对话视图 */
    async switchSession(sess) {
        if (!sess) return;
        const sid = sess.id;
        this.sessionId = sid;
        let messages = [];
        if (this.demo) {
            // 演示 / 本地：从 localStorage 历史里找
            const h = this.chatHistories.find(x => x.id === sid);
            messages = (h && h.messages) ? h.messages.map(m => ({ role: m.type === 'user' ? 'user' : 'assistant', content: m.content })) : [];
        } else {
            try {
                const data = await this.api('/memory/sessions/' + sid, {});
                messages = Array.isArray(data) ? data : ((data && data.messages) || []);
            } catch (e) {
                this.toast('加载会话失败：' + this.escapeHtml(e.message), 'error');
            }
        }
        const box = document.getElementById('chatMessages');
        box.innerHTML = '';
        this.currentChatHistory = [];
        const userMsgs = (messages || []).filter(m => m.role === 'user' || m.role === 'assistant');
        if (userMsgs.length) {
            this.welcomeHide();
            userMsgs.forEach(m => {
                this.addMsg(m.role, m.content || '');
                this.currentChatHistory.push({ type: m.role === 'user' ? 'user' : 'assistant', content: m.content || '', ts: '' });
            });
        } else {
            const w = document.getElementById('welcomeCard'); if (w) w.style.display = '';
        }
        // 跳到对话视图，并把侧栏胶囊还原为该会话的 Agent（不改动用户当前选择）
        this.switchView('chat');
        if (sess.agentName && AGENT_INFO[sess.agentName]) this.setActiveAgent(sess.agentName);
        this.toast(userMsgs.length ? '已切换到会话：' + (sess.title || sid) : '该会话暂无消息', 'info');
    }

    /* ---------- 历史（localStorage 缓存） ---------- */
    loadChatHistories() { try { return JSON.parse(localStorage.getItem('sb_histories') || '[]'); } catch (e) { return []; } }
    saveChatHistories() { try { localStorage.setItem('sb_histories', JSON.stringify(this.chatHistories)); } catch (e) {} }
    pushHistory(type, content) {
        this.currentChatHistory.push({ type, content, ts: new Date().toISOString() });
        if (this.currentChatHistory.length === 1) this.chatHistories.unshift({ id: this.sessionId, title: content.slice(0, 30), messages: [...this.currentChatHistory] });
        else { const h = this.chatHistories.find(x => x.id === this.sessionId); if (h) h.messages = [...this.currentChatHistory]; }
        this.saveChatHistories();
    }
    saveCurrentChat() { const h = this.chatHistories.find(x => x.id === this.sessionId); if (h) { h.messages = [...this.currentChatHistory]; this.saveChatHistories(); } }
    renderHistorySidebar() {}
    newChat() {
        if (this.isBusy) { this.toast('请等待当前对话完成', 'warning'); return; }
        this.sessionId = this.generateSessionId(); this.currentChatHistory = [];
        document.getElementById('chatMessages').innerHTML = '';
        const w = document.getElementById('welcomeCard'); if (w) w.style.display = '';
        this.toast('已开始新对话', 'info');
    }
}

document.addEventListener('DOMContentLoaded', () => { window.__app = new StudyBuddyApp(); });

/* ============ 视觉特效：全屏粒子辉光 + 液态悬停 + 点击水波 ============ */
(function () {
    const canvas = document.getElementById('fx-canvas');
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    const COLORS = ['#7c5cff', '#19c3ff', '#ff5ca8', '#34d399', '#fbbf24'];
    let W = 0, H = 0, particles = [], trail = [], ripples = [], mouse = { x: -9999, y: -9999 };

    function resize() { W = canvas.width = innerWidth; H = canvas.height = innerHeight; }
    function initParticles() {
        const n = Math.max(46, Math.min(120, Math.floor(W * H / 14000)));
        particles = [];
        for (let i = 0; i < n; i++) particles.push({ x: Math.random() * W, y: Math.random() * H, vx: (Math.random() - .5) * .45, vy: (Math.random() - .5) * .45, r: Math.random() * 2 + 1, c: COLORS[i % COLORS.length] });
    }
    resize(); initParticles();
    addEventListener('resize', () => { resize(); initParticles(); });

    function burst(x, y, count, color) {
        for (let i = 0; i < count; i++) { const a = Math.random() * Math.PI * 2, s = Math.random() * 4 + 1.5; trail.push({ x, y, vx: Math.cos(a) * s, vy: Math.sin(a) * s, life: 1, r: Math.random() * 3 + 1.5, c: color || COLORS[i % COLORS.length] }); }
    }
    addEventListener('mousemove', e => {
        mouse.x = e.clientX; mouse.y = e.clientY;
        trail.push({ x: e.clientX, y: e.clientY, vx: (Math.random() - .5) * .7, vy: (Math.random() - .5) * .7, life: 1, r: Math.random() * 2 + 1, c: '#19c3ff' });
        if (trail.length > 150) trail.shift();
    });
    addEventListener('click', e => { burst(e.clientX, e.clientY, 24); ripples.push({ x: e.clientX, y: e.clientY, r: 0, life: 1 }); });
    addEventListener('touchstart', e => { if (e.touches[0]) { burst(e.touches[0].clientX, e.touches[0].clientY, 18); ripples.push({ x: e.touches[0].clientX, y: e.touches[0].clientY, r: 0, life: 1 }); } }, { passive: true });

    function loop() {
        ctx.clearRect(0, 0, W, H);
        for (const p of particles) {
            p.x += p.vx; p.y += p.vy;
            if (p.x < 0 || p.x > W) p.vx *= -1;
            if (p.y < 0 || p.y > H) p.vy *= -1;
            const dx = p.x - mouse.x, dy = p.y - mouse.y, d = Math.hypot(dx, dy);
            if (d < 130) { p.x += dx / d * .7; p.y += dy / d * .7; }
            ctx.globalAlpha = .7; ctx.fillStyle = p.c; ctx.shadowBlur = 12; ctx.shadowColor = p.c;
            ctx.beginPath(); ctx.arc(p.x, p.y, p.r, 0, 6.283); ctx.fill();
        }
        ctx.shadowBlur = 0;
        ctx.globalAlpha = .12; ctx.strokeStyle = '#7c5cff'; ctx.lineWidth = 1;
        for (let i = 0; i < particles.length; i++) for (let j = i + 1; j < particles.length; j++) {
            const a = particles[i], b = particles[j], d = Math.hypot(a.x - b.x, a.y - b.y);
            if (d < 120) { ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke(); }
        }
        for (let i = trail.length - 1; i >= 0; i--) {
            const t = trail[i]; t.x += t.vx; t.y += t.vy; t.life -= .02;
            if (t.life <= 0) { trail.splice(i, 1); continue; }
            ctx.globalAlpha = t.life; ctx.fillStyle = t.c; ctx.shadowBlur = 10; ctx.shadowColor = t.c;
            ctx.beginPath(); ctx.arc(t.x, t.y, t.r, 0, 6.283); ctx.fill();
        }
        ctx.shadowBlur = 0;
        for (let i = ripples.length - 1; i >= 0; i--) {
            const r = ripples[i]; r.r += 6; r.life -= .03;
            if (r.life <= 0) { ripples.splice(i, 1); continue; }
            ctx.globalAlpha = r.life * .5; ctx.strokeStyle = '#19c3ff'; ctx.lineWidth = 2;
            ctx.beginPath(); ctx.arc(r.x, r.y, r.r, 0, 6.283); ctx.stroke();
        }
        ctx.globalAlpha = 1;
        requestAnimationFrame(loop);
    }
    loop();

    // 液态悬停：绑定鼠标坐标（CSS ::before 跟随扩散；@property 使其平滑流动）
    const app = window.__app;
    const SEL = '.nav-item,.chip,.ghost-btn,.primary-btn,.suggest,.mini,.send-btn,.icon-btn,.kb-item,.session-item,.glass-card,.drop-zone,.opt';
    function bindLiquid() {
        document.querySelectorAll(SEL).forEach(el => {
            if (el.dataset.liquidBound) return;
            el.dataset.liquidBound = '1';
            el.addEventListener('mousemove', e => {
                const r = el.getBoundingClientRect();
                el.style.setProperty('--mx', ((e.clientX - r.left) / r.width * 100) + '%');
                el.style.setProperty('--my', ((e.clientY - r.top) / r.height * 100) + '%');
            });
            if (!el.classList.contains('nav-item') && !el.classList.contains('brand')) el.classList.add('fx-liquid');
        });
    }
    bindLiquid();
    const mo = new MutationObserver(() => bindLiquid());
    const pw = document.getElementById('plansWrap'); if (pw) mo.observe(pw, { childList: true, subtree: true });
    const nav = document.getElementById('navList'); if (nav) nav.addEventListener('click', () => setTimeout(bindLiquid, 50));
})();

/* ============ 内置题库（演示用，真实后端会由 LLM 生成） ============ */
const quizLagrange = [
    { question: '拉格朗日中值定理要求函数 f(x) 满足什么条件？', type: '单选题', options: ['A. 在 (a,b) 连续、[a,b] 可导', 'B. 在 [a,b] 连续、在 (a,b) 可导', 'C. 在 [a,b] 可导即可', 'D. 只需在 (a,b) 连续'], answer: 'B. 在 [a,b] 连续、在 (a,b) 可导' },
    { question: '若 f(x)=x² 在 [0,1] 上应用拉格朗日中值定理，则中值点 ξ = ?', type: '单选题', options: ['A. 0', 'B. 1/2', 'C. 1', 'D. 不存在'], answer: 'B. 1/2' },
    { question: '拉格朗日中值定理的几何意义是？', type: '单选题', options: ['A. 曲线上存在一点切线平行于弦 AB', 'B. 曲线最高点必在端点', 'C. 函数单调', 'D. 导数恒为零'], answer: 'A. 曲线上存在一点切线平行于弦 AB' }
];
const quizFund = [
    { question: '微积分基本定理（牛顿-莱布尼茨公式）连接了哪两个概念？', type: '单选题', options: ['A. 极限与连续', 'B. 导数与积分', 'C. 级数与数列', 'D. 向量与矩阵'], answer: 'B. 导数与积分' },
    { question: '若 F(x) 是 f(x) 的一个原函数，则 ∫[a,b] f(x)dx = ?', type: '单选题', options: ['A. F(b)-F(a)', 'B. F(a)-F(b)', 'C. F(b)+F(a)', 'D. f(b)-f(a)'], answer: 'A. F(b)-F(a)' },
    { question: '下列说法正确是？', type: '单选题', options: ['A. 变上限积分 Φ(x)=∫[a,x] f(t)dt 的导数是 f(x)', 'B. 导数与积分无关', 'C. 定积分只依赖被积函数', 'D. 原函数唯一'], answer: 'A. 变上限积分 Φ(x)=∫[a,x] f(t)dt 的导数是 f(x)' }
];
const quizLinear = [
    { question: 'n 阶方阵 A 可逆的充要条件是？', type: '单选题', options: ['A. |A|≠0', 'B. |A|=0', 'C. A 对称', 'D. A 的秩小于 n'], answer: 'A. |A|≠0' },
    { question: '两个矩阵 A(m×n)、B(n×p) 可相乘，结果维度是？', type: '单选题', options: ['A. m×p', 'B. n×n', 'C. m×n', 'D. p×m'], answer: 'A. m×p' },
    { question: '向量组线性相关的含义是？', type: '单选题', options: ['A. 存在不全为零的系数使其线性组合为零', 'B. 所有向量相等', 'C. 向量个数等于维数', 'D. 行列式非零'], answer: 'A. 存在不全为零的系数使其线性组合为零' }
];
