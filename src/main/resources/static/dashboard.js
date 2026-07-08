var TYPE_LABELS = {
    image: '图片',
    video: '视频',
    audio: '音频',
    doc: '文档',
    zip: '压缩包',
    other: '其他'
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

function renderDisk(disk, disks) {
    // 当前节点汇总
    document.getElementById('diskUsed').textContent = formatBytes(disk.usedBytes);
    document.getElementById('diskTotal').textContent = formatBytes(disk.totalBytes);
    document.getElementById('diskPercent').textContent = disk.usagePercent + '%';
    var bar = document.getElementById('diskBar');
    bar.style.width = Math.min(100, disk.usagePercent) + '%';
    if (disk.usagePercent >= 90) {
        bar.style.backgroundColor = 'var(--color-danger)';
    } else if (disk.usagePercent >= 75) {
        bar.style.backgroundColor = 'var(--color-warning)';
    } else {
        bar.style.backgroundColor = 'var(--color-accent-primary)';
    }

    // 各节点磁盘详情
    var list = document.getElementById('diskNodeList');
    if (!disks || disks.length === 0) {
        list.innerHTML = '<p class="text-xs" style="color: var(--color-text-muted);">暂无节点数据</p>';
        return;
    }
    list.innerHTML = disks.map(function (d) {
        var nodeId = d.nodeId || '--';
        var shortId = nodeId.length > 12 ? nodeId.substring(0, 12) + '...' : nodeId;
        var pct = d.usagePercent || 0;
        var barColor = pct >= 90 ? 'var(--color-danger)' : (pct >= 75 ? 'var(--color-warning)' : 'var(--color-accent-primary)');
        var path = d.path || '--';
        return '<div>' +
            '<div class="flex items-center justify-between mb-0.5">' +
                '<span class="text-xs font-mono truncate mr-2" style="color: var(--color-text-primary); max-width: 100px;" title="' + escapeHtml(nodeId) + '">' + escapeHtml(shortId) + '</span>' +
                '<span class="text-xs font-medium" style="color: var(--color-text-secondary);">' + pct + '%</span>' +
            '</div>' +
            '<div class="w-full h-1.5 rounded-full overflow-hidden mb-0.5" style="background-color: var(--color-bg-tertiary);">' +
                '<div class="h-full rounded-full transition-all duration-500" style="width: ' + Math.min(100, pct) + '%; background-color: ' + barColor + ';"></div>' +
            '</div>' +
            '<div class="flex justify-between text-xs" style="color: var(--color-text-muted);">' +
                '<span>' + formatBytes(d.usedBytes) + ' / ' + formatBytes(d.totalBytes) + '</span>' +
                '<span class="font-mono truncate ml-2" style="max-width: 80px;" title="' + escapeHtml(path) + '">' + escapeHtml(path.split('/').pop() || path) + '</span>' +
            '</div>' +
        '</div>';
    }).join('');
}

function renderResources(res) {
    document.getElementById('resTotal').textContent = res.total;
    document.getElementById('resMain').textContent = res.mainCount;
    document.getElementById('resReplica').textContent = res.replicaCount;
    document.getElementById('resSize').textContent = formatBytes(res.totalSizeBytes);
}

function renderCluster(cluster) {
    document.getElementById('nodeCount').textContent = cluster.nodeCount;
    document.getElementById('currentNode').textContent = cluster.currentNode || '--';
    document.getElementById('webEntry').textContent = cluster.webEntrypoint || '--';
    var list = document.getElementById('nodeList');
    var nodes = cluster.nodes || [];
    if (nodes.length === 0) {
        list.innerHTML = '<p class="font-mono text-xs" style="color: var(--color-text-muted);">无可用节点</p>';
        return;
    }
    list.innerHTML = nodes.map(function (n) {
        return '<p class="font-mono text-xs break-all" style="color: var(--color-text-secondary);">• ' + escapeHtml(n) + '</p>';
    }).join('');
}

function renderFileTypes(types) {
    var list = document.getElementById('fileTypeList');
    var total = types.reduce(function (s, t) { return s + t.count; }, 0);
    if (total === 0) {
        list.innerHTML = '<p class="text-sm" style="color: var(--color-text-muted);">暂无文件</p>';
        return;
    }
    list.innerHTML = types.map(function (t) {
        var percent = total > 0 ? (t.count * 100 / total) : 0;
        var label = TYPE_LABELS[t.category] || t.category;
        return '<div>' +
            '<div class="flex items-center justify-between mb-1">' +
                '<span class="text-sm" style="color: var(--color-text-primary);">' + label + '</span>' +
                '<span class="text-xs" style="color: var(--color-text-muted);">' + t.count + ' (' + percent.toFixed(1) + '%)</span>' +
            '</div>' +
            '<div class="w-full h-2 rounded-full overflow-hidden" style="background-color: var(--color-bg-tertiary);">' +
                '<div class="h-full rounded-full" style="width: ' + percent + '%; background-color: var(--color-accent-primary);"></div>' +
            '</div>' +
        '</div>';
    }).join('');
}

var CATEGORY_LABELS = {
    FILE: '文件服务',
    HLS: '流媒体服务',
    S3: 'S3 接口',
    OTHER: '静态资源和其他'
};

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
            renderDisk(data.disk, data.disks);
            renderResources(data.resources);
            renderCluster(data.cluster);
            renderFileTypes(data.fileTypes);
            renderTraffic(data.traffic);
            renderRequests(data.requests);
            renderUptime(data.appStartTime);
        })
        .catch(function (err) {
            showError('请求失败');
        });
}

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

function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
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
