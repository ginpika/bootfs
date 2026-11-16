'use strict';

(() => {
    const byId = id => document.getElementById(id);
    const { t, duration } = BootFSI18n;
    const availableList = byId('availableList');
    const playlistList = byId('playlistList');
    const search = byId('videoSearch');
    const sourceFilter = byId('sourceFilter');
    const saveButton = byId('saveSchedule');
    const addAll = byId('addAll');
    const clear = byId('clearSchedule');
    const rowTemplate = byId('videoRow');
    const status = byId('saveStatus');
    const actionLabels = { add: '添加到节目单', remove: '移出节目单', up: '上移', down: '下移' };

    let videos = new Map();
    let playlist = [];
    let savedPlaylist = [];
    let loading = false;
    let ready = false;
    let saving = false;
    let saved = false;
    let saveFailed = false;
    const sortables = [];

    async function request(path, options) {
        const response = await fetch(path, { cache: 'no-store', ...options });
        if (!response.ok) throw new Error('TV request failed');
        return response.json();
    }

    function availableVideos() {
        const selected = new Set(playlist);
        const term = search.value.trim().toLocaleLowerCase();
        return [...videos.values()].filter(video => video.available && !selected.has(video.key)
            && (!sourceFilter.value || sourceFilter.value === video.nodeId)
            && (!term || [video.fileName, video.nodeId].some(value => value.toLocaleLowerCase().includes(term))));
    }

    function selectedVideo(key) {
        if (videos.has(key)) return videos.get(key);
        const parts = key.split(':');
        return { key, nodeId: parts.length > 1 ? parts[0] : '', fileName: parts.at(-1), available: false };
    }

    function changed() {
        return playlist.length !== savedPlaylist.length || playlist.some((key, index) => key !== savedPlaylist[index]);
    }

    function emptyRow(message) {
        const row = document.createElement('li');
        row.className = 'schedule-empty';
        row.textContent = t(message);
        return row;
    }

    function rowFor(video, index = -1) {
        const row = rowTemplate.content.firstElementChild.cloneNode(true);
        const inQueue = index >= 0;
        row.dataset.key = video.key;
        if (inQueue) row.dataset.index = index;
        row.classList.toggle('is-unavailable', !video.available);
        row.querySelector('.schedule-video-icon').hidden = inQueue;
        row.querySelector('.schedule-position').hidden = !inQueue;
        if (inQueue) row.querySelector('.schedule-order').textContent = index + 1;

        const name = row.querySelector('.schedule-filename');
        name.textContent = video.fileName;
        name.title = video.fileName;
        const source = row.querySelector('.schedule-source');
        source.textContent = [video.nodeId && t('节点 {0}', video.nodeId),
            !video.available && t('资源暂不可用')].filter(Boolean).join(' · ');
        source.title = source.textContent;
        const length = row.querySelector('.schedule-duration');
        length.textContent = Number.isFinite(video.totalDuration) ? duration(video.totalDuration) : '—';
        length.title = length.textContent;

        for (const button of row.querySelectorAll('button[data-action]')) {
            const action = button.dataset.action;
            button.hidden = inQueue === (action === 'add');
            button.title = t(actionLabels[action]);
            button.setAttribute('aria-label', button.title + ' · ' + video.fileName);
            button.disabled = saving || (action === 'up' && index === 0)
                || (action === 'down' && index === playlist.length - 1);
        }
        return row;
    }

    function render() {
        const available = availableVideos();
        const scheduled = playlist.map(selectedVideo);
        const filtered = search.value.trim() || sourceFilter.value;
        const catalogRows = available.map(video => rowFor(video));
        const scheduleRows = scheduled.map(rowFor);
        if (loading && !ready) {
            catalogRows.push(emptyRow('加载中...'));
            scheduleRows.push(emptyRow('加载中...'));
        } else if (ready) {
            if (!catalogRows.length) catalogRows.push(emptyRow(filtered ? '未找到匹配的视频' : '暂无可选视频'));
            if (!scheduleRows.length) scheduleRows.push(emptyRow('节目单为空'));
        }
        availableList.replaceChildren(...catalogRows);
        playlistList.replaceChildren(...scheduleRows);

        byId('availableCount').textContent = ready ? t('{0} 个', available.length) : '';
        byId('playlistCount').textContent = ready ? t('{0} 个', playlist.length) : '';
        addAll.textContent = t(filtered ? '添加匹配项' : '添加全部');
        addAll.disabled = !ready || saving || !available.length;
        clear.disabled = !ready || saving || !playlist.length;
        saveButton.disabled = !ready || saving;
        search.disabled = !ready;
        sourceFilter.disabled = !ready;
        status.textContent = saving ? t('正在保存…') : saveFailed ? t('保存失败')
            : saved ? t('保存成功') : changed() ? t('有未保存的修改') : '';
        status.dataset.error = String(saveFailed);

        const summary = byId('playlistStats');
        summary.hidden = !scheduled.length;
        const seconds = scheduled.reduce((sum, video) => sum + (video.totalDuration || 0), 0);
        const segments = scheduled.reduce((sum, video) => sum + (video.segmentCount || 0), 0);
        summary.textContent = t('总时长 {0} · {1} 个切片', duration(seconds), segments);
        for (const sortable of sortables) sortable.option('disabled', saving || !ready);
        document.body.classList.toggle('can-drag', sortables.length > 0 && !saving && ready);
    }

    async function load() {
        if (loading) return;
        loading = true;
        byId('loadNotice').hidden = true;
        render();
        try {
            const [catalog, schedule] = await Promise.all([request('/tv/api/videos'), request('/tv/api/playlist')]);
            if (!Array.isArray(catalog) || !Array.isArray(schedule)) throw new Error('Invalid TV catalog or playlist');
            videos = new Map(catalog.map(video => [video.key, video]));
            playlist = schedule;
            savedPlaylist = [...playlist];
            const nodes = [...new Set(catalog.map(video => video.nodeId))].sort();
            for (const node of nodes) sourceFilter.add(new Option(node, node));
            ready = true;
        } catch {
            byId('loadNotice').hidden = false;
        } finally {
            loading = false;
            render();
        }
    }

    function edit() {
        saved = false;
        saveFailed = false;
        render();
    }

    function rowAction(event) {
        const button = event.target.closest('button[data-action]');
        if (!button || !ready || saving) return;
        const row = button.closest('.schedule-row');
        const action = button.dataset.action;
        const index = Number(row.dataset.index);
        const availableIndex = [...availableList.children].indexOf(row);
        let focus;
        if (action === 'add') {
            playlist.push(row.dataset.key);
            focus = () => availableList.children[Math.min(availableIndex, availableList.children.length - 1)]
                ?.querySelector('button');
        } else if (action === 'remove') {
            playlist.splice(index, 1);
            focus = () => playlistList.children[Math.min(index, playlist.length - 1)]?.querySelector('[data-action="remove"]');
        } else {
            const destination = index + (action === 'up' ? -1 : 1);
            [playlist[index], playlist[destination]] = [playlist[destination], playlist[index]];
            focus = () => {
                const updated = playlistList.children[destination];
                const control = updated.querySelector('[data-action="' + action + '"]');
                return control.disabled ? updated : control;
            };
        }
        edit();
        (focus() || search).focus({ preventScroll: true });
    }

    async function save() {
        if (!ready || saving) return;
        saving = true;
        saved = false;
        saveFailed = false;
        render();
        try {
            const result = await request('/tv/api/playlist', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'X-BootFS-Request': '1' },
                body: JSON.stringify(playlist),
            });
            if (!result.success) throw new Error('TV playlist save failed');
            savedPlaylist = [...playlist];
            saved = true;
        } catch {
            saveFailed = true;
        } finally {
            saving = false;
            render();
        }
    }

    if (typeof Sortable === 'function') {
        const dragOptions = {
            group: 'tv-schedule',
            draggable: '.schedule-row',
            animation: 150,
            onEnd(event) {
                if (event.from === event.to && event.oldIndex === event.newIndex) return;
                playlist = [...playlistList.querySelectorAll('.schedule-row')].map(row => row.dataset.key);
                queueMicrotask(edit);
            },
        };
        sortables.push(new Sortable(availableList, { ...dragOptions, handle: '.schedule-video-icon', sort: false }));
        sortables.push(new Sortable(playlistList, { ...dragOptions, handle: '.schedule-position' }));
    }

    search.addEventListener('input', render);
    sourceFilter.addEventListener('change', render);
    availableList.addEventListener('click', rowAction);
    playlistList.addEventListener('click', rowAction);
    addAll.addEventListener('click', () => {
        playlist.push(...availableVideos().map(video => video.key));
        edit();
    });
    clear.addEventListener('click', () => {
        playlist = [];
        edit();
    });
    byId('retryLoad').addEventListener('click', load);
    saveButton.addEventListener('click', save);
    load();
})();
