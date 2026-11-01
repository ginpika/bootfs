window.ClashFiles = (() => {
    const BATCH_SIZE = 8;
    let elements;
    let state;
    let session = 0;
    const requests = new Set();

    function isConfig(file) {
        return !!file && /\.ya?ml$/i.test(file.fileName || '') && (file.tags || []).some(tag => {
            const name = typeof tag === 'string' ? tag.split(':').pop() : tag?.name;
            return typeof name === 'string' && name.trim().toLowerCase() === 'clash';
        });
    }

    function icon() {
        return '<div class="text-file-icon clash-file-icon" title="Clash 配置"><span class="clash-cat-mark" role="img" aria-label="Clash 小猫"></span></div>';
    }

    function reset() {
        ++session;
        requests.forEach(controller => controller.abort());
        requests.clear();
        state = null;
        if (elements) {
            elements.section.classList.add('hidden');
            elements.rows.replaceChildren();
        }
    }

    function showError(message = '') {
        elements.error.textContent = message;
        elements.error.classList.toggle('hidden', !message);
    }

    async function request(path, options = {}) {
        const controller = new AbortController();
        requests.add(controller);
        try {
            const response = await fetch(path, { cache: 'no-store', ...options, signal: controller.signal });
            if (response.redirected || response.status === 401 || response.status === 403) {
                const failure = new Error('登录已过期或没有访问权限，请重新登录后重试。');
                failure.fatal = true;
                throw failure;
            }
            const payload = response.headers.get('content-type')?.includes('application/json') ? await response.json() : null;
            if (!response.ok) {
                const failure = new Error(payload?.message || '无法读取 Clash 配置或检测延迟，请稍后重试。');
                failure.fatal = response.status < 500 && response.status !== 429;
                throw failure;
            }
            if (!payload || !Array.isArray(payload.nodes)) throw new Error('服务器返回了无效的节点信息。');
            return payload;
        } finally {
            requests.delete(controller);
        }
    }

    function createNode(node) {
        const row = document.createElement('tr');
        const info = document.createElement('td');
        const name = document.createElement('div');
        name.className = 'clash-node-name';
        name.textContent = node.name || node.server;
        const address = document.createElement('div');
        address.className = 'clash-node-address';
        const server = String(node.server || '');
        address.textContent = `${server.includes(':') && !server.startsWith('[') ? `[${server}]` : server}:${node.port} · ${String(node.type || '').toUpperCase()}`;
        info.append(name, address);
        const latency = document.createElement('td');
        latency.className = 'clash-node-latency';
        latency.textContent = '等待检测…';
        row.append(info, latency);
        elements.rows.append(row);
        return latency;
    }

    function renderLatency(node) {
        const cell = state.cells.get(String(node.id));
        if (!cell) return;
        cell.className = 'clash-node-latency';
        const labels = { timeout: '超时', unreachable: '无法连接', unresolved: 'DNS 失败', busy: '繁忙，请重试' };
        if (node.status === 'ok' && Number.isFinite(node.latencyMs) && node.latencyMs >= 0) {
            cell.textContent = `${Math.round(node.latencyMs)} ms`;
            cell.classList.add(node.latencyMs < 200 ? 'clash-latency-fast' : 'clash-latency-slow');
        } else {
            cell.textContent = labels[node.status] || '检测失败';
            cell.classList.add('clash-latency-failed');
        }
        cell.title = node.checkedAt ? `检测时间：${new Date(node.checkedAt).toLocaleTimeString()}` : '';
    }

    async function measure(activeSession) {
        if (!state || state.running || activeSession !== session) return;
        state.running = true;
        elements.refresh.disabled = true;
        showError();
        state.cells.forEach(cell => { cell.textContent = '等待检测…'; cell.className = 'clash-node-latency'; });
        let nextOffset = 0;
        let finished = 0;
        let latestCheck = 0;
        let failed = false;
        let stopped = false;
        elements.status.textContent = `正在检测 0 / ${state.nodes.length} 个节点…`;
        const worker = async () => {
            while (activeSession === session && !stopped && nextOffset < state.nodes.length) {
                const offset = nextOffset;
                nextOffset += BATCH_SIZE;
                const nodes = state.nodes.slice(offset, offset + BATCH_SIZE);
                try {
                    const data = await request(`/api/file/${encodeURIComponent(state.uuid)}/clash/latency?offset=${offset}&limit=${BATCH_SIZE}`, { method: 'POST' });
                    if (activeSession !== session) return;
                    if (data.version !== state.version) {
                        const failure = new Error('文件已更新，请重新打开详情后检测。');
                        failure.fatal = true;
                        throw failure;
                    }
                    data.nodes.forEach(renderLatency);
                    latestCheck = Math.max(latestCheck, Number(data.checkedAt) || 0);
                } catch (failure) {
                    if (activeSession !== session || failure.name === 'AbortError') return;
                    nodes.forEach(node => renderLatency({ id: node.id, status: 'failed' }));
                    showError(failure.message);
                    failed = true;
                    stopped = stopped || !!failure.fatal;
                }
                finished += nodes.length;
                elements.status.textContent = `正在检测 ${finished} / ${state.nodes.length} 个节点…`;
            }
        };
        await Promise.all([worker(), worker()]);
        if (activeSession !== session) return;
        state.running = false;
        state.cells.forEach(cell => { if (cell.textContent === '等待检测…') cell.textContent = '未检测'; });
        elements.refresh.disabled = !state.nodes.length;
        elements.status.textContent = `${stopped ? '检测中止' : failed ? '部分检测失败' : '检测完成'} · ${finished} / ${state.nodes.length} 个节点${latestCheck ? ` · ${new Date(latestCheck).toLocaleTimeString()}` : ''}`;
    }

    async function load(file) {
        reset();
        if (!elements || !isConfig(file)) return;
        const activeSession = session;
        state = { uuid: file.uuid, cells: new Map(), nodes: [], running: false, loaded: false };
        elements.section.classList.remove('hidden');
        elements.status.textContent = '正在解析节点…';
        elements.refresh.disabled = true;
        elements.warnings.classList.add('hidden');
        elements.subscription.value = `${location.origin}/p/${encodeURIComponent(file.uuid)}?p=vless`;
        showError();
        try {
            const data = await request(`/api/file/${encodeURIComponent(file.uuid)}/clash`);
            if (activeSession !== session) return;
            state.version = data.version;
            state.nodes = data.nodes;
            state.loaded = true;
            data.nodes.forEach(node => state.cells.set(String(node.id), createNode(node)));
            const warnings = Array.isArray(data.warnings) ? data.warnings : [];
            elements.warnings.textContent = warnings.join('\n');
            elements.warnings.classList.toggle('hidden', !warnings.length);
            if (!data.nodes.length) {
                elements.status.textContent = '配置中没有可检测的代理节点。';
                return;
            }
            await measure(activeSession);
        } catch (failure) {
            if (activeSession !== session || failure.name === 'AbortError') return;
            elements.status.textContent = '解析失败';
            elements.refresh.disabled = false;
            showError(failure.message);
        }
    }

    document.addEventListener('DOMContentLoaded', () => {
        const get = suffix => document.getElementById(`detailClash${suffix}`);
        elements = { section: get('Section'), rows: get('Nodes'), status: get('Status'), error: get('Error'), warnings: get('Warnings'), refresh: get('Refresh'), subscription: get('Subscription'), copy: get('Copy') };
        elements.refresh.addEventListener('click', () => {
            if (!state) return;
            if (state.loaded) measure(session);
            else load({ uuid: state.uuid, fileName: 'clash.yaml', tags: [{ name: 'clash' }] });
        });
        elements.copy.addEventListener('click', async () => {
            try {
                if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(elements.subscription.value);
                else {
                    elements.subscription.focus();
                    elements.subscription.select();
                    if (!document.execCommand('copy')) throw new Error('copy failed');
                }
                showToast('订阅地址已复制', 'success');
            } catch { showToast('复制失败，请选中订阅地址手动复制', 'error'); }
        });
    });

    return { isConfig, icon, reset, load };
})();
