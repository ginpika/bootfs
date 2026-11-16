'use strict';

(() => {
    const byId = id => document.getElementById(id);
    const t = BootFSI18n.t;
    const stage = byId('tvStage');
    const video = byId('tvPlayer');
    const placeholder = byId('streamPlaceholder');
    const retry = byId('streamRetry');
    const layer = byId('danmakuLayer');
    const toggle = byId('danmakuToggle');
    const log = byId('chatLog');
    const list = byId('chatMessages');
    const form = byId('chatForm');
    const input = byId('chatInput');
    const send = byId('chatSend');
    const timeFormat = new Intl.DateTimeFormat(BootFSI18n.language, { hour: '2-digit', minute: '2-digit' });
    const requestHeaders = { 'X-BootFS-Request': '1' };
    let player;
    let streamVersion;
    let lastStatus;
    let chatReady = false;
    let posting = false;
    let danmaku = !matchMedia('(prefers-reduced-motion: reduce)').matches;
    let laneReadyAt = [];
    let pumpTimer;
    const queue = [];
    const seen = new Map();
    const pending = new Map();
    let lastFeed = [];

    async function json(path, options = {}) {
        const response = await fetch(path, { cache: 'no-store', ...options, headers: { ...requestHeaders, ...options.headers } });
        if (!response.ok) throw Object.assign(new Error(response.statusText), { status: response.status });
        return response.json();
    }

    function notice(message, retryable = false) {
        byId('streamNotice').textContent = t(message);
        retry.hidden = !retryable;
        placeholder.hidden = false;
        if (layer.parentNode !== stage) stage.append(layer);
    }

    function playbackError() {
        if (lastStatus?.live) notice('暂时无法播放直播', true);
    }

    function startPlayback(version) {
        const url = '/tv/playlist.m3u8?v=' + encodeURIComponent(version);
        if (typeof videojs === 'function' && !player) {
            player = videojs(video, {
                language: BootFSI18n.language,
                liveui: true,
                responsive: true,
                controlBar: { progressControl: false },
                html5: { vhs: { overrideNative: !video.canPlayType('application/vnd.apple.mpegurl') } },
            });
            player.on('error', playbackError);
        }
        placeholder.hidden = true;
        if (player) {
            player.el().append(layer);
            player.src({ src: url, type: 'application/vnd.apple.mpegurl' });
            player.play()?.catch(() => {});
        } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
            video.src = url;
            video.play().catch(() => {});
        } else {
            playbackError();
        }
    }

    function renderStatus(status) {
        lastStatus = status;
        const live = status.live;
        stage.classList.toggle('is-live', live);
        byId('streamBadge').classList.toggle('is-live', live);
        byId('streamState').textContent = t(live ? '直播中' : '未开播');
        byId('programTitle').textContent = status.currentProgram?.fileName || '—';
        byId('programNode').textContent = status.currentProgram ? t('节点 {0}', status.currentProgram.nodeId) : '';
        byId('programCount').textContent = status.playlistSize;
        byId('programDuration').textContent = BootFSI18n.duration(status.totalDuration);
        if (!live) {
            if (streamVersion !== undefined) {
                if (player) player.reset();
                else {
                    video.removeAttribute('src');
                    video.load();
                }
            }
            streamVersion = undefined;
            notice('暂无直播节目');
        } else if (streamVersion !== status.streamVersion) {
            streamVersion = status.streamVersion;
            startPlayback(streamVersion);
        }
    }

    async function pollStatus() {
        try {
            renderStatus(await json('/tv/status'));
        } catch {
            byId('streamState').textContent = t('连接中断');
            byId('streamBadge').classList.remove('is-live');
            if (!lastStatus?.live) notice('暂时无法获取直播状态');
        } finally {
            setTimeout(pollStatus, 4000);
        }
    }

    retry.addEventListener('click', () => startPlayback(streamVersion));
    video.addEventListener('error', playbackError);

    function setDanmaku(enabled) {
        danmaku = enabled;
        toggle.setAttribute('aria-pressed', String(enabled));
        if (enabled) return;
        clearTimeout(pumpTimer);
        queue.length = 0;
        laneReadyAt = [];
        layer.replaceChildren();
    }

    function pumpDanmaku() {
        clearTimeout(pumpTimer);
        const width = layer.clientWidth;
        if (!queue.length || !width) return;
        const laneCount = Math.max(1, Math.floor((layer.clientHeight - 8) / 30));
        const now = performance.now();
        const speed = Math.max(85, width / 8);
        for (let lane = 0; lane < laneCount && queue.length; lane++) {
            if ((laneReadyAt[lane] || 0) > now) continue;
            const item = document.createElement('span');
            item.className = 'tv-danmaku-item';
            item.textContent = queue.shift().text;
            item.style.top = 8 + lane * 30 + 'px';
            layer.append(item);
            const textWidth = item.getBoundingClientRect().width;
            const distance = width + textWidth;
            const duration = distance / speed * 1000;
            item.style.setProperty('--danmaku-distance', -distance + 'px');
            item.style.setProperty('--danmaku-duration', duration + 'ms');
            item.addEventListener('animationend', () => item.remove(), { once: true });
            setTimeout(() => item.remove(), duration + 250);
            laneReadyAt[lane] = now + (textWidth + 26) / speed * 1000;
        }
        if (queue.length) {
            pumpTimer = setTimeout(pumpDanmaku, Math.max(20, Math.min(...laneReadyAt.slice(0, laneCount)) - now));
        }
    }

    function showDanmaku(message) {
        if (!danmaku) return;
        queue.push(message);
        if (queue.length > 50) queue.shift();
        pumpDanmaku();
    }

    setDanmaku(danmaku);
    toggle.addEventListener('click', () => setDanmaku(!danmaku));

    function remember(message, animate) {
        if (seen.get(message.id)) return;
        seen.set(message.id, animate);
        if (seen.size > 512) seen.delete(seen.keys().next().value);
        if (animate) showDanmaku(message);
    }

    function messageItem(message) {
        const item = document.createElement('li');
        const meta = document.createElement('div');
        const author = document.createElement('strong');
        const time = document.createElement('time');
        const text = document.createElement('p');
        const date = new Date(message.sentAt);
        meta.className = 'tv-chat-message-meta';
        author.textContent = message.author;
        time.dateTime = date.toISOString();
        time.textContent = timeFormat.format(date);
        text.textContent = message.text;
        meta.append(author, time);
        item.append(meta, text);
        return item;
    }

    let displayed = [];
    function renderChat() {
        const feed = new Map(lastFeed.map(message => [message.id, message]));
        for (const [id, item] of pending) {
            if (item.expiresAt > performance.now()) feed.set(id, item.message);
            else pending.delete(id);
        }
        const ordered = [...feed.values()].sort((a, b) => a.sentAt - b.sentAt || a.id.localeCompare(b.id)).slice(-100);
        if (ordered.length !== displayed.length || ordered.some((message, i) => message.id !== displayed[i])) {
            const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 50;
            list.replaceChildren(...ordered.map(messageItem));
            displayed = ordered.map(message => message.id);
            if (atBottom) log.scrollTop = log.scrollHeight;
        }
        byId('chatEmpty').hidden = ordered.length > 0;
    }

    async function pollChat() {
        try {
            lastFeed = await json('/tv/chat');
            for (const message of lastFeed) {
                if (!seen.has(message.id)) remember(message, chatReady);
                pending.delete(message.id);
            }
            chatReady = true;
            byId('chatConnection').textContent = '';
            byId('chatEmptyText').textContent = t('暂无消息');
            renderChat();
        } catch {
            byId('chatConnection').textContent = t('正在重连…');
            byId('chatEmptyText').textContent = t('暂时无法载入聊天');
        } finally {
            setTimeout(pollChat, 1800);
        }
    }

    function updateSend() {
        send.disabled = posting || !input.value.trim();
    }

    async function chatSession() {
        try {
            const response = await fetch('/tv/chat/session', { cache: 'no-store', headers: requestHeaders });
            if (!response.ok && response.status !== 401) throw new Error(response.statusText);
            const session = await response.json();
            form.hidden = !session.authenticated;
            byId('chatGuest').hidden = session.authenticated;
            const login = byId('chatLogin');
            const url = session.loginUrl && window.BootFSSecurity.url(session.loginUrl);
            login.hidden = !url;
            if (url) login.href = url;
            byId('chatHint').textContent = t(url ? '登录后即可发言' : '聊天发言需要 SSO 登录');
        } catch {
            byId('chatHint').textContent = t('暂时无法确认登录状态');
        }
    }

    input.addEventListener('input', updateSend);
    form.addEventListener('submit', async event => {
        event.preventDefault();
        const text = input.value.trim();
        if (posting || !text) return;
        posting = true;
        updateSend();
        const error = byId('chatError');
        error.hidden = true;
        try {
            const message = await json('/tv/chat', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ text }),
            });
            if (input.value.trim() === text) input.value = '';
            if (!seen.has(message.id)) pending.set(message.id, { message, expiresAt: performance.now() + 5000 });
            remember(message, true);
            renderChat();
        } catch (failure) {
            if (failure.status === 401) chatSession();
            error.textContent = t(failure.status === 429 ? '发送太快，请稍后再试' : '消息发送失败');
            error.hidden = false;
        } finally {
            posting = false;
            updateSend();
        }
    });

    pollStatus();
    pollChat();
    chatSession();
})();
