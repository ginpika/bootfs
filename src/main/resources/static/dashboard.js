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
        var cardStyle = n.isCurrent ? 'border-color: var(--color-accent-primary); border-width: 2px;' : '';
        var cardOpacity = n.online ? '' : 'opacity: 0.55;';
        var cardCls = 'theme-card theme-rounded-xl theme-shadow p-4 w-[340px] max-w-full';

        var header = renderNodeHeader(n);

        if (!n.online) {
            return '<div class="' + cardCls + '" style="' + cardStyle + cardOpacity + '">' +
                header +
                '<p class="text-sm text-center py-6" style="color: var(--color-text-muted);">节点离线，无法获取数据</p>' +
            '</div>';
        }

        return '<div class="' + cardCls + '" style="' + cardStyle + cardOpacity + '">' +
            header +
            renderNodeDisk(n.disk) +
            renderNodeResources(n.resources) +
            renderNodeFileTypes(n.fileTypes) +
        '</div>';
    }).join('');
}

function renderNodeHeader(n) {
    var badge;
    if (n.isCurrent) {
        badge = '<span class="px-2 py-0.5 rounded-full text-xs font-medium" style="background-color: var(--color-accent-muted); color: var(--color-accent-primary);">当前节点</span>';
    } else if (n.online) {
        badge = '<span class="inline-flex items-center gap-1 text-xs font-medium" style="color: var(--color-success);">' +
            '<span class="w-1.5 h-1.5 rounded-full" style="background-color: var(--color-success);"></span>在线</span>';
    } else {
        badge = '<span class="inline-flex items-center gap-1 text-xs font-medium" style="color: var(--color-danger);">' +
            '<span class="w-1.5 h-1.5 rounded-full" style="background-color: var(--color-danger);"></span>离线</span>';
    }

    var shortId = n.nodeId.length > 16 ? n.nodeId.substring(0, 16) + '...' : n.nodeId;
    var host = extractHost(n.url);

    return '<div class="flex items-center justify-between mb-3 gap-2">' +
        '<div class="flex items-center gap-2 min-w-0">' +
            badge +
            '<span class="font-mono text-xs truncate" style="color: var(--color-text-primary);" title="' + escapeHtml(n.nodeId) + '">' + escapeHtml(shortId) + '</span>' +
        '</div>' +
        '<span class="font-mono text-xs whitespace-nowrap" style="color: var(--color-text-muted);" title="' + escapeHtml(n.url || '') + '">' + escapeHtml(host) + '</span>' +
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
    if (!traffic) return;
    document.getElementById('trafficTotal').textContent = formatBytes(traffic.totalBytesOut);
    document.getElementById('trafficTotalReqs').textContent = traffic.totalRequests;

    var list = document.getElementById('trafficCategoryList');
    var cats = traffic.byCategory || [];
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

function renderRequests(reqs) {
    if (!reqs) return;
    document.getElementById('reqTotal24h').textContent = reqs.total24h;
    document.getElementById('reqBytes24h').textContent = formatBytes(reqs.bytes24h);

    var chart = document.getElementById('reqHourlyChart');
    var hourly = reqs.hourly || [];
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
    var recent = reqs.recent || [];
    if (recent.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" class="px-3 py-8 text-center" style="color: var(--color-text-muted);">暂无请求记录</td></tr>';
        return;
    }
    tbody.innerHTML = recent.map(function (r) {
        var statusColor = r.status >= 200 && r.status < 300 ? 'var(--color-success)' : (r.status >= 400 ? 'var(--color-danger)' : 'var(--color-warning)');
        var catLabel = CATEGORY_LABELS[r.category] || r.category;
        return '<tr class="border-t" style="border-color: var(--color-border-primary);">' +
            '<td class="px-2 py-1 font-mono text-xs whitespace-nowrap" style="color: var(--color-text-muted);">' + escapeHtml(r.time) + '</td>' +
            '<td class="px-2 py-1 text-xs whitespace-nowrap" style="color: var(--color-text-secondary);">' + escapeHtml(r.method) + '</td>' +
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
    if (!startTime) return;
    uptimeStartTime = startTime;
    if (uptimeTimer) {
        clearInterval(uptimeTimer);
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
    hideError();
    fetch('/api/dashboard/stats')
        .then(function (r) { return r.json(); })
        .then(function (res) {
            if (!res.succeed) {
                showError(res.message);
                return;
            }
            var data = res.data;
            renderNodes(data.nodes || []);
            renderTraffic(data.traffic);
            renderRequests(data.requests);
            renderUptime(data.appStartTime);
        })
        .catch(function () {
            showError('请求失败');
        });
}

document.addEventListener('DOMContentLoaded', function () {
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
});
