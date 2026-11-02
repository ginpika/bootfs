var TYPE_LABELS = {
    image: '图片',
    video: '视频',
    audio: '音频',
    doc: '文档',
    zip: '压缩包',
    other: '其他'
};

var CATEGORY_LABELS = {
    FILE: '文件服务',
    HLS: '流媒体服务',
    S3: 'S3 接口',
    OTHER: '静态资源和其他'
};

var dashboardNodes = [];
var selectedNodeId = new URLSearchParams(window.location.search).get('node');
var statsLoading = false;

function setTheme(theme) {
    document.documentElement.classList.forEach(function (c) {
        if (c.startsWith('theme-')) document.documentElement.classList.remove(c);
    });
    document.documentElement.classList.add('theme-' + theme);
    try { localStorage.setItem('tfs-theme', theme); } catch (e) {}
    document.getElementById('themeDropdown').classList.add('hidden');
}

function formatBytes(bytes) {
    if (bytes == null || isNaN(bytes) || bytes < 0) return '--';
    if (bytes === 0) return '0 B';
    var units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
    var i = Math.floor(Math.log(bytes) / Math.log(1024));
    if (i >= units.length) i = units.length - 1;
    var val = bytes / Math.pow(1024, i);
    return (val >= 100 ? val.toFixed(0) : val.toFixed(1)) + ' ' + units[i];
}

function showError(msg) {
    var banner = document.getElementById('errorBanner');
    document.getElementById('errorMsg').textContent = '加载失败: ' + (msg || '未知错误');
    banner.classList.remove('hidden');
}

function hideError() {
    document.getElementById('errorBanner').classList.add('hidden');
}

function escapeHtml(str) {
    if (!str) return '';
    return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function extractHost(url) {
    if (!url) return '--';
    var match = String(url).match(/^https?:\/\/([^/]+)/);
    return match ? match[1] : url;
}

function diskBarColor(pct) {
    return pct >= 90 ? 'var(--color-danger)' : (pct >= 75 ? 'var(--color-warning)' : 'var(--color-accent-primary)');
}

function ringSvg(percent, size, strokeColor, centerTop, centerBottom) {
    var dash = Math.min(100, Math.max(0, percent));
    var r = 15.915;
    return '<svg viewBox="0 0 36 36" style="width:' + size + ';height:' + size + ';display:block;">' +
        '<circle cx="18" cy="18" r="' + r + '" fill="none" stroke-width="3.5" style="stroke: var(--color-bg-tertiary);"/>' +
        '<circle cx="18" cy="18" r="' + r + '" fill="none" stroke-width="3.5" stroke-linecap="round" transform="rotate(-90 18 18)" ' +
        'stroke-dasharray="' + dash + ' 100" style="stroke: ' + strokeColor + '; transition: stroke-dasharray 0.6s ease;"/>' +
        (centerTop != null ? '<text x="18" y="19.5" text-anchor="middle" font-size="8" font-weight="700" style="fill: var(--color-text-primary); font-family: Space Grotesk, system-ui, sans-serif;">' + centerTop + '</text>' : '') +
        (centerBottom != null ? '<text x="18" y="23.5" text-anchor="middle" font-size="3.2" style="fill: var(--color-text-muted); font-family: Outfit, system-ui, sans-serif;">' + centerBottom + '</text>' : '') +
    '</svg>';
}

// ============ Section 1: 节点详情 ============

function renderNodes(nodes) {
    var grid = document.getElementById('nodesGrid');
    if (!nodes || nodes.length === 0) {
        grid.innerHTML = '<p class="text-sm w-full" style="color: var(--color-text-muted);">无节点数据</p>';
        return;
    }
    grid.innerHTML = nodes.map(function (n) {
        var cardOpacity = n.online ? '' : 'opacity: 0.65;';
        var cardCls = 'dashboard-node theme-card theme-rounded-xl theme-shadow p-4 w-[340px] max-w-full';
        var attributes = ' role="button" tabindex="0" aria-pressed="' + (n.nodeId === selectedNodeId) +
            '" data-node-id="' + escapeHtml(n.nodeId) + '" aria-label="查看节点 ' + escapeHtml(n.nodeId) + ' 的统计"';

        var header = renderNodeHeader(n);

        if (!n.online) {
            return '<div class="' + cardCls + '"' + attributes + ' style="' + cardOpacity + '">' +
                header +
                '<p class="text-sm text-center py-6" style="color: var(--color-text-muted);">节点不可达，无法获取数据</p>' +
            '</div>';
        }

        return '<div class="' + cardCls + '"' + attributes + ' style="' + cardOpacity + '">' +
            header +
            renderNodeDisk(n.disk) +
            renderNodeResources(n.resources) +
            renderNodeFileTypes(n.fileTypes) +
        '</div>';
    }).join('');
}

function selectNode(nodeId) {
    if (!dashboardNodes.some(function (node) { return node.nodeId === nodeId; })) return;
    selectedNodeId = nodeId;
    var url = new URL(window.location.href);
    url.searchParams.set('node', nodeId);
    window.history.replaceState(null, '', url);
    // 更新选中态时保留键盘焦点；轮询时才重绘卡片内容。
    document.querySelectorAll('#nodesGrid [data-node-id]').forEach(function (card) {
        card.setAttribute('aria-pressed', String(card.dataset.nodeId === nodeId));
    });
    renderSelectedNode();
}

function renderSelectedNode() {
    var node = dashboardNodes.find(function (item) { return item.nodeId === selectedNodeId; });
    var available = node && node.online;
    document.getElementById('selectedNodeTitle').textContent = node ? '节点统计 · ' + node.nodeId : '节点统计';
    document.getElementById('selectedNodeStatus').textContent = !node ? '暂无节点数据' :
        (node.isCurrent ? '当前访问节点 · ' : '远程节点 · ') + (node.url || '') +
        (!available ? ' · 节点不可达，统计暂不可用' :
            (!node.requests ? ' · 该节点未提供流量与请求数据，请升级节点' :
                ' · 统计仅属于此节点 · 最近刷新 ' + new Date().toLocaleTimeString()));
    renderSystem(available ? node.system : null, available ? '该节点未提供系统信息，请升级节点' : '节点不可达，系统信息暂不可用');
    renderTraffic(available ? node.traffic : null);
    renderRequests(available ? node.requests : null,
        available ? '该节点未提供请求统计' : '节点不可达，请求统计暂不可用');
    renderUptime(available ? node.appStartTime : null);
}

function systemRow(label, value) {
    return '<div class="flex justify-between items-start gap-3 py-1 text-xs">' +
        '<dt class="shrink-0" style="color: var(--color-text-muted);">' + escapeHtml(label) + '</dt>' +
        '<dd class="text-right break-words min-w-0" style="color: var(--color-text-primary);">' + escapeHtml(value == null ? '--' : String(value)) + '</dd></div>';
}

function systemCard(title, content) {
    return '<div class="theme-card theme-rounded-xl theme-shadow p-4"><h3 class="text-sm font-semibold font-display mb-3" ' +
        'style="color: var(--color-text-primary);">' + escapeHtml(title) + '</h3>' + content + '</div>';
}

function renderSystem(system, emptyMessage) {
    var container = document.getElementById('systemStats');
    if (!system) {
        container.innerHTML = systemCard('系统资源', '<p class="text-xs" style="color: var(--color-text-muted);">' + escapeHtml(emptyMessage) + '</p>');
        return;
    }
    var memory = system.memory || {};
    var percent = memory.processHostPercent;
    var runtimeLabels = {docker: 'Docker', systemd: 'systemd', container: '容器', standalone: '直接运行'};
    var jvm = '<p class="text-2xl font-bold font-display" style="color: var(--color-accent-primary);">' + formatBytes(memory.processRssBytes) + '</p>' +
        '<p class="text-xs mb-3" style="color: var(--color-text-muted);">进程实际驻留内存（RSS） · 占宿主机 ' +
        (percent == null ? '--' : Number(percent).toFixed(2) + '%') + '</p>' +
        '<dl>' + systemRow('堆已用 / 上限', formatBytes(memory.heapUsedBytes) + ' / ' + formatBytes(memory.heapMaxBytes)) +
        systemRow('堆已提交', formatBytes(memory.heapCommittedBytes)) +
        systemRow('非堆已用', formatBytes(memory.nonHeapUsedBytes)) + '</dl>' +
        '<p class="text-xs mt-2" style="color: var(--color-text-muted);">RSS 包含堆、非堆及本地内存等；无法采集的指标显示 --。</p>';
    var host = '<dl>' + systemRow('CPU 型号', system.cpuModel) +
        systemRow('架构 / 宿主机逻辑 CPU', (system.architecture || '--') + ' / ' + (system.logicalProcessors || '--')) +
        systemRow('JVM 可用 CPU', system.availableProcessors) +
        systemRow('宿主机内存总量', formatBytes(memory.hostTotalBytes)) +
        systemRow('宿主机已用 / 可用', formatBytes(memory.hostUsedBytes) + ' / ' + formatBytes(memory.hostAvailableBytes));
    if (memory.containerLimitBytes != null || memory.containerUsedBytes != null || ['docker', 'container'].includes(system.runtime)) {
        host += systemRow('cgroup 内存用量', formatBytes(memory.containerUsedBytes)) +
            systemRow('cgroup 内存限额', memory.containerLimitBytes == null ? '未限制或不可用' : formatBytes(memory.containerLimitBytes));
    }
    host += '</dl>';
    var runtime = '<dl>' + systemRow('运行环境', runtimeLabels[system.runtime] || system.runtime) +
        systemRow('操作系统', (system.os || '--') + ' ' + (system.osVersion || '')) +
        systemRow('Java 版本', system.javaVersion) + systemRow('虚拟机', system.jvmName) + systemRow('进程 PID', system.pid) + '</dl>';
    container.innerHTML = systemCard('JVM 进程内存', jvm) + systemCard('硬件与内存资源', host) + systemCard('节点运行环境', runtime);
}

function renderNodeHeader(n) {
    var badge;
    if (n.isCurrent) {
        badge = '<span class="shrink-0 whitespace-nowrap px-2 py-0.5 rounded-full text-xs font-medium" style="background-color: var(--color-accent-muted); color: var(--color-accent-primary);">当前节点</span>';
    } else if (n.online) {
        badge = '<span class="inline-flex shrink-0 whitespace-nowrap items-center gap-1 text-xs font-medium" style="color: var(--color-success);">' +
            '<span class="w-1.5 h-1.5 rounded-full" style="background-color: var(--color-success);"></span>在线</span>';
    } else {
        badge = '<span class="inline-flex shrink-0 whitespace-nowrap items-center gap-1 text-xs font-medium" style="color: var(--color-danger);">' +
            '<span class="w-1.5 h-1.5 rounded-full" style="background-color: var(--color-danger);"></span>离线</span>';
    }

    var shortId = n.nodeId.length > 16 ? n.nodeId.substring(0, 16) + '...' : n.nodeId;
    var host = extractHost(n.url);

    return '<div class="flex items-center justify-between mb-3 gap-2">' +
        '<div class="flex flex-1 items-center gap-2 min-w-0">' +
            badge +
            '<span class="font-mono text-xs truncate" style="color: var(--color-text-primary);" title="' + escapeHtml(n.nodeId) + '">' + escapeHtml(shortId) + '</span>' +
        '</div>' +
        '<span class="font-mono text-xs shrink-0 max-w-[45%] truncate" style="color: var(--color-text-muted);" title="' + escapeHtml(n.url || '') + '">' + escapeHtml(host) + '</span>' +
    '</div>';
}

function renderNodeDisk(disk) {
    if (!disk) return '';
    var pct = disk.usagePercent || 0;
    var path = disk.path || '--';
    return '<div class="flex flex-col items-center my-4">' +
        ringSvg(pct, '6rem', diskBarColor(pct), pct.toFixed(0) + '%') +
        '<p class="text-xs mt-2" style="color: var(--color-text-secondary);"><span style="color: var(--color-text-primary); font-weight: 600;">' + formatBytes(disk.usedBytes) + '</span> / ' + formatBytes(disk.totalBytes) + '</p>' +
        '<p class="font-mono text-xs truncate mt-0.5 max-w-full" style="color: var(--color-text-muted);" title="' + escapeHtml(path) + '">' + escapeHtml(path) + '</p>' +
    '</div>';
}

function renderNodeResources(res) {
    if (!res) return '';
    return '<div class="grid grid-cols-3 gap-1.5 mb-3">' +
        '<div class="p-1.5 rounded-lg text-center" style="background-color: var(--color-bg-tertiary);">' +
            '<p class="text-xs mb-0.5" style="color: var(--color-text-muted);">资源</p>' +
            '<p class="text-sm font-bold font-display" style="color: var(--color-text-primary);">' + (res.total != null ? res.total : '--') + '</p>' +
        '</div>' +
        '<div class="p-1.5 rounded-lg text-center" style="background-color: var(--color-bg-tertiary);">' +
            '<p class="text-xs mb-0.5" style="color: var(--color-text-muted);">主</p>' +
            '<p class="text-sm font-bold font-display" style="color: var(--color-accent-primary);">' + (res.mainCount != null ? res.mainCount : '--') + '</p>' +
        '</div>' +
        '<div class="p-1.5 rounded-lg text-center" style="background-color: var(--color-bg-tertiary);">' +
            '<p class="text-xs mb-0.5" style="color: var(--color-text-muted);">副本</p>' +
            '<p class="text-sm font-bold font-display" style="color: var(--color-text-primary);">' + (res.replicaCount != null ? res.replicaCount : '--') + '</p>' +
        '</div>' +
    '</div>' +
    '<div class="p-1.5 rounded-lg mb-3" style="background-color: var(--color-bg-tertiary);">' +
        '<div class="flex items-center justify-between">' +
            '<span class="text-xs" style="color: var(--color-text-muted);">文件总大小</span>' +
            '<span class="text-sm font-bold font-display" style="color: var(--color-text-primary);">' + formatBytes(res.totalSizeBytes) + '</span>' +
        '</div>' +
    '</div>';
}

function renderNodeFileTypes(fileTypes) {
    if (!fileTypes || fileTypes.length === 0) return '';
    var items = fileTypes.filter(function (t) { return t.count > 0; });
    if (items.length === 0) return '';
    var badges = items.map(function (t) {
        var label = TYPE_LABELS[t.category] || t.category;
        return '<span class="inline-flex items-center px-1.5 py-0.5 rounded text-xs" style="background-color: var(--color-bg-tertiary); color: var(--color-text-secondary);">' +
            label + ' <span class="font-semibold ml-1" style="color: var(--color-text-primary);">' + t.count + '</span></span>';
    });
    return '<div>' +
        '<p class="text-xs mb-1.5" style="color: var(--color-text-muted);">文件类型</p>' +
        '<div class="flex flex-wrap gap-1">' + badges.join('') + '</div>' +
    '</div>';
}

// ============ Section 3: 本节点流量与请求 ============

function renderTraffic(traffic) {
    document.getElementById('trafficTotal').textContent = formatBytes(traffic && traffic.totalBytesOut);
    document.getElementById('trafficTotalReqs').textContent = traffic ? traffic.totalRequests : '--';

    var list = document.getElementById('trafficCategoryList');
    var cats = traffic && traffic.byCategory || [];
    if (cats.length === 0) {
        list.innerHTML = '<p class="text-sm" style="color: var(--color-text-muted);">暂无数据</p>';
        return;
    }
    list.innerHTML = cats.map(function (c) {
        var label = c.label || CATEGORY_LABELS[c.category] || c.category;
        return '<div>' +
            '<div class="flex items-center justify-between mb-1">' +
                '<span class="text-sm font-medium" style="color: var(--color-text-primary);">' + escapeHtml(label) + '</span>' +
                '<span class="text-sm font-semibold" style="color: var(--color-accent-primary);">' + formatBytes(c.bytesOut) + '</span>' +
            '</div>' +
            '<div class="flex items-center justify-between mb-1">' +
                '<span class="text-xs" style="color: var(--color-text-muted);">' + c.requests + ' 次请求</span>' +
                '<span class="text-xs" style="color: var(--color-text-muted);">' + c.percent + '%</span>' +
            '</div>' +
            '<div class="w-full h-2 rounded-full overflow-hidden" style="background-color: var(--color-bg-tertiary);">' +
                '<div class="h-full rounded-full transition-all duration-300" style="width: ' + c.percent + '%; background-color: var(--color-accent-primary);"></div>' +
            '</div>' +
        '</div>';
    }).join('');
}

function renderRequests(reqs, emptyMessage) {
    document.getElementById('reqTotal24h').textContent = reqs ? reqs.total24h : '--';
    document.getElementById('reqBytes24h').textContent = formatBytes(reqs && reqs.bytes24h);

    var chart = document.getElementById('reqHourlyChart');
    var hourly = reqs && reqs.hourly || [];
    var maxCount = 1;
    hourly.forEach(function (h) { if (h.count > maxCount) maxCount = h.count; });
    if (hourly.length === 0) {
        chart.innerHTML = '<p class="text-sm w-full text-center" style="color: var(--color-text-muted);">暂无数据</p>';
    } else {
        chart.innerHTML = hourly.map(function (h) {
            var heightPct = maxCount > 0 ? Math.max(2, (h.count * 100 / maxCount)) : 0;
            var title = h.hour + ' | ' + h.count + ' 次 | ' + formatBytes(h.bytes);
            return '<div title="' + escapeHtml(title) + '" class="flex-1 rounded-t transition-all duration-300 cursor-pointer" style="height: ' + heightPct + '%; min-height: 2px; background-color: var(--color-accent-primary); opacity: 0.85;"></div>';
        }).join('');
    }

    var tbody = document.getElementById('recentReqBody');
    var recent = reqs && reqs.recent || [];
    if (recent.length === 0) {
        tbody.innerHTML = '<tr><td colspan="8" class="px-3 py-8 text-center" style="color: var(--color-text-muted);">' +
            escapeHtml(reqs ? '暂无请求记录' : (emptyMessage || '请求统计暂不可用')) + '</td></tr>';
        return;
    }
    tbody.innerHTML = recent.map(function (r) {
        var statusColor = r.status >= 200 && r.status < 300 ? 'var(--color-success)' : (r.status >= 400 ? 'var(--color-danger)' : 'var(--color-warning)');
        var catLabel = CATEGORY_LABELS[r.category] || r.category;
        return '<tr class="border-t" style="border-color: var(--color-border-primary);">' +
            '<td class="px-2 py-1 font-mono text-xs whitespace-nowrap" style="color: var(--color-text-muted);">' + escapeHtml(r.time) + '</td>' +
            '<td class="px-2 py-1 text-xs whitespace-nowrap" style="color: var(--color-text-secondary);">' + escapeHtml(r.method) + '</td>' +
            '<td class="px-2 py-1 font-mono text-xs whitespace-nowrap" style="color: var(--color-text-secondary);">' + escapeHtml(r.clientIp || '--') + '</td>' +
            '<td class="px-2 py-1 font-mono text-xs truncate" style="color: var(--color-accent-primary); max-width: 260px;" title="' + escapeHtml(r.uri) + '">' + escapeHtml(r.uri) + '</td>' +
            '<td class="px-2 py-1 text-xs whitespace-nowrap" style="color: var(--color-text-secondary);">' + escapeHtml(catLabel) + '</td>' +
            '<td class="px-2 py-1 text-right text-xs font-medium whitespace-nowrap" style="color: ' + statusColor + ';">' + r.status + '</td>' +
            '<td class="px-2 py-1 text-right text-xs whitespace-nowrap" style="color: var(--color-text-secondary);">' + formatBytes(r.bytes) + '</td>' +
            '<td class="px-2 py-1 text-right text-xs whitespace-nowrap" style="color: var(--color-text-muted);">' + r.durationMs + 'ms</td>' +
        '</tr>';
    }).join('');
}

// ============ 运行时长 ============

var uptimeStartTime = 0;
var uptimeTimer = null;

function renderUptime(startTime) {
    uptimeStartTime = startTime || 0;
    if (uptimeTimer) {
        clearInterval(uptimeTimer);
        uptimeTimer = null;
    }
    if (!startTime) {
        document.getElementById('uptimeText').textContent = '运行时长暂不可用';
        return;
    }
    updateUptimeText();
    uptimeTimer = setInterval(updateUptimeText, 1000);
}

function updateUptimeText() {
    var el = document.getElementById('uptimeText');
    if (!el || !uptimeStartTime) return;
    el.textContent = '运行中 · ' + formatUptime(Date.now() - uptimeStartTime);
}

function formatUptime(ms) {
    if (ms < 0) ms = 0;
    var totalSec = Math.floor(ms / 1000);
    var days = Math.floor(totalSec / 86400);
    var hours = Math.floor((totalSec % 86400) / 3600);
    var mins = Math.floor((totalSec % 3600) / 60);
    var secs = totalSec % 60;
    if (days > 0) {
        return days + '天 ' + hours + '时 ' + mins + '分';
    }
    if (hours > 0) {
        return hours + '时 ' + mins + '分 ' + secs + '秒';
    }
    return mins + '分 ' + secs + '秒';
}

// ============ 数据加载 ============

function loadStats() {
    if (window.bootfsClusterAvailable === false || statsLoading) return;
    statsLoading = true;
    hideError();
    return fetch('/api/dashboard/stats')
        .then(function (r) { return r.json(); })
        .then(function (res) {
            if (!res.succeed) {
                showError(res.message);
                return;
            }
            dashboardNodes = res.data.nodes || [];
            if (!dashboardNodes.some(function (node) { return node.nodeId === selectedNodeId; })) {
                var current = dashboardNodes.find(function (node) { return node.isCurrent; }) || dashboardNodes[0];
                selectedNodeId = current ? current.nodeId : null;
            }
            var focused = document.activeElement && document.activeElement.dataset.nodeId;
            renderNodes(dashboardNodes);
            if (focused) {
                var card = Array.from(document.querySelectorAll('#nodesGrid [data-node-id]')).find(function (item) { return item.dataset.nodeId === focused; });
                if (card) card.focus();
            }
            renderSelectedNode();
        })
        .catch(function () {
            showError('请求失败');
        })
        .finally(function () { statsLoading = false; });
}

document.addEventListener('DOMContentLoaded', function () {
    var nodesGrid = document.getElementById('nodesGrid');
    nodesGrid.addEventListener('click', function (event) {
        var card = event.target.closest('[data-node-id]');
        if (card) selectNode(card.dataset.nodeId);
    });
    nodesGrid.addEventListener('keydown', function (event) {
        if (event.key !== 'Enter' && event.key !== ' ') return;
        var card = event.target.closest('[data-node-id]');
        if (card) { event.preventDefault(); selectNode(card.dataset.nodeId); }
    });
    document.getElementById('themeMenuBtn').addEventListener('click', function (e) {
        e.stopPropagation();
        document.getElementById('themeDropdown').classList.toggle('hidden');
    });
    document.addEventListener('click', function (e) {
        var dd = document.getElementById('themeDropdown');
        if (dd && !dd.classList.contains('hidden') && !dd.contains(e.target) && e.target !== document.getElementById('themeMenuBtn')) {
            dd.classList.add('hidden');
        }
    });
    loadStats();
    setInterval(function () { if (!document.hidden) loadStats(); }, 15000);
});
