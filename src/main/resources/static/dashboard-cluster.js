(function () {
    'use strict';

    window.bootfsClusterAvailable = false;
    document.addEventListener('DOMContentLoaded', function () {
        var dialog = document.getElementById('clusterLinkDialog');
        var input = document.getElementById('clusterLinkUrl');
        var codeInput = document.getElementById('clusterLinkCode');
        var generate = document.getElementById('clusterPairingGenerate');
        var ownCode = document.getElementById('clusterPairingCode');
        var leave = document.getElementById('clusterLeave');
        var submit = document.getElementById('clusterLinkSubmit');
        var message = document.getElementById('clusterLinkMessage');
        var summary = document.getElementById('clusterSummary');
        var removeOpen = document.getElementById('clusterRemoveOpen');
        var removeDialog = document.getElementById('clusterRemoveDialog');
        var removeTarget = document.getElementById('clusterRemoveTarget');
        var removeDescription = document.getElementById('clusterRemoveDescription');
        var removeCoordinator = document.getElementById('clusterRemoveCoordinator');
        var removeMessage = document.getElementById('clusterRemoveMessage');
        var removeFeedback = document.getElementById('clusterRemoveFeedback');
        var removeCancel = document.getElementById('clusterRemoveCancel');
        var removeConfirm = document.getElementById('clusterRemoveConfirm');
        var local = null;
        var target = '';
        var posting = false;
        var checking = false;
        var timer;
        var statsKey = '';
        var submissionError = '';
        var generation = 0;
        var issuing = false;
        var leaving = false;
        var removing = false;
        var removal;
        var codeExpiresAt = 0;
        var storageKey = 'bootfs-dashboard-link';
        try { target = sessionStorage.getItem(storageKey) || ''; } catch (ignored) {}
        input.value = target;

        function rememberTarget(url) {
            target = url;
            try {
                if (url) sessionStorage.setItem(storageKey, url);
                else sessionStorage.removeItem(storageKey);
            } catch (ignored) {}
        }

        function showMessage(text, error) {
            message.textContent = text;
            message.style.color = error ? 'var(--color-danger)' : 'var(--color-text-secondary)';
        }

        function isClusterMember(nodeId) {
            return nodeId && local?.phase === 'ACTIVE' && !local.independent &&
                Object.prototype.hasOwnProperty.call(local.peers || {}, nodeId);
        }

        function updateRemovalControls() {
            removeOpen.disabled = !isClusterMember(selectedNodeId) || posting || leaving || removing || Boolean(target);
            if (!removeDialog.open) return;
            var canRemove = isClusterMember(removal.nodeId) && local.groupId === removal.groupId;
            var self = canRemove && removal.nodeId === local.nodeId;
            var needsCoordinator = canRemove && !self && local.nodeId !== local.coordinatorId;
            var coordinatorEndpoint = needsCoordinator && local.peers[local.coordinatorId];
            var coordinatorUrl = coordinatorEndpoint && window.BootFSSecurity.url(coordinatorEndpoint);
            removeCoordinator.hidden = !coordinatorUrl;
            if (coordinatorUrl) {
                removeCoordinator.href = coordinatorUrl.replace(/\/+$/, '') + '/dashboard?node=' + encodeURIComponent(removal.nodeId);
            }
            removeDescription.textContent = !canRemove ? '该节点已不在当前集群中。' : needsCoordinator ?
                '请在协调节点的仪表盘操作。' : self ? '确认后，本机将退出集群并继续独立提供文件服务。' :
                '确认后，集群将停止通过该节点访问文件。该节点会保留本地文件。';
            removeConfirm.disabled = !canRemove || needsCoordinator || removing;
            removeCancel.disabled = removing;
        }

        function updateControls() {
            var enabled = local && local.phase === 'ACTIVE';
            submit.disabled = !enabled || posting || leaving || Boolean(target);
            input.disabled = posting || Boolean(target);
            codeInput.disabled = posting || Boolean(target);
            generate.disabled = !enabled || issuing || leaving;
            leave.hidden = !enabled || local.independent;
            leave.disabled = leaving || posting;
            submit.textContent = posting ? '正在连接…' : (target ? '连接中…' : '连接');
            updateRemovalControls();
        }

        async function request(path, body) {
            var controller = new AbortController();
            var timeout = setTimeout(function () { controller.abort(); }, 40000);
            try {
                var response = await fetch('/api/cluster/' + path, {
                    method: body ? 'POST' : 'GET',
                    credentials: 'same-origin', redirect: 'error', signal: controller.signal,
                    headers: body ? {'Content-Type': 'application/json', 'X-BootFS-Request': '1'} : {},
                    body: body ? JSON.stringify(body) : undefined
                });
                if (!response.headers.get('content-type')?.includes('application/json')) {
                    throw new Error('无法读取组网状态，请检查登录状态后重试');
                }
                var result = await response.json();
                if (!response.ok) {
                    var error = new Error(result.error || '组网请求失败（HTTP ' + response.status + '）');
                    error.rejected = true;
                    throw error;
                }
                return result;
            } finally { clearTimeout(timeout); }
        }

        function renderLocal(state, reloadStats) {
            local = state;
            window.bootfsClusterAvailable = state.phase === 'ACTIVE' || state.phase === 'STATIC';
            if (state.phase === 'STATIC') {
                summary.textContent = '当前沿用 ' + (state.metadataMode || '原有') + ' 存储模式';
                showMessage('当前数据可继续使用。要启用节点配对，请先通过备份恢复迁移到新的动态节点。', false);
            } else if (state.phase === 'ACTIVE') {
                summary.textContent = '本机独立读写 · ' + Object.keys(state.peers || {}).length + ' 个组网成员' + (state.pendingNotifications ? ' · 成员通知待同步' : '');
            } else {
                summary.textContent = phaseLabel(state) + (state.lastError ? '：' + state.lastError : '');
                if (!target) showMessage(summary.textContent, Boolean(state.lastError));
            }
            var nextKey = window.bootfsClusterAvailable ? state.phase + JSON.stringify(state.peers || {}) : '';
            if (nextKey && (reloadStats || nextKey !== statsKey)) loadStats(reloadStats);
            statsKey = nextKey;
            updateControls();
        }

        function phaseLabel(state) {
            return {
                ACTIVE: '节点在线', FROZEN: '正在合并资源', ATTACHING: '正在加入集群',
                LEAVING: '正在安全下线', OFFLINE: '节点已下线', REMOVED: '节点已被移除'
            }[state.phase] || '正在等待节点就绪';
        }

        async function refresh() {
            if (checking || posting || leaving || removing) return;
            checking = true;
            var currentGeneration = generation;
            clearTimeout(timer);
            try {
                var current = await request('status');
                if (currentGeneration !== generation || posting) return;
                renderLocal(current);
                if (codeExpiresAt && Date.now() >= codeExpiresAt) {
                    ownCode.textContent = '本机验证码已过期，请重新生成。'; codeExpiresAt = 0;
                }
                if (target) {
                    var state = await request('link/status?url=' + encodeURIComponent(target));
                    if (currentGeneration !== generation || posting) return;
                    renderLocal(state.local);
                    if (state.connected) {
                        rememberTarget('');
                        showMessage('连接成功，两端已加入同一集群。');
                        loadStats();
                    } else if (state.pending) {
                        showMessage('成员目录正在同步，本机资源仍可正常使用…');
                        summary.textContent = '成员目录正在同步';
                    } else {
                        var joining = [state.local, state.remote].find(function (node) {
                            return node.phase === 'FROZEN' || node.phase === 'ATTACHING';
                        });
                        if (joining) {
                            var detail = joining.lastError ? '，后台正在重试：' + joining.lastError : '，请稍候…';
                            showMessage(phaseLabel(joining) + detail, Boolean(joining.lastError));
                            summary.textContent = phaseLabel(joining) + detail;
                        } else {
                            rememberTarget('');
                            showMessage(submissionError || '尚未完成互联，请检查两端状态后重新连接。', true);
                        }
                    }
                }
            } catch (error) {
                if (currentGeneration !== generation || posting) return;
                if (!local) window.bootfsClusterAvailable = false;
                var detail = error.name === 'AbortError' ? '请求超时' : error.message;
                showMessage((target ? '暂时无法确认连接状态，正在重试：' : '无法读取组网状态：') + detail, true);
                summary.textContent = message.textContent;
                if (submissionError && error.rejected) {
                    rememberTarget('');
                    showMessage(submissionError, true);
                }
            } finally {
                checking = false;
                updateControls();
                timer = setTimeout(refresh, 2500);
            }
        }

        document.getElementById('clusterLinkOpen').addEventListener('click', function () {
            dialog.showModal();
            if (!input.disabled) input.focus();
            refresh();
        });
        document.getElementById('clusterLinkClose').addEventListener('click', function () { dialog.close(); });
        function clearPairing() {
            rememberTarget('');
            codeInput.value = '';
            ownCode.textContent = '';
            codeExpiresAt = 0;
        }

        leave.addEventListener('click', async function () {
            leaving = true; generation++; updateControls();
            try {
                renderLocal(await request('leave', {})); clearPairing();
                showMessage('已退出集群。本机文件、元数据和索引保持可用，现在可以连接其他集群。');
            } catch (error) { showMessage(error.message, true); }
            finally { leaving = false; updateControls(); refresh(); }
        });
        document.addEventListener('dashboard-node-selected', updateRemovalControls);
        removeOpen.addEventListener('click', function () {
            if (removeOpen.disabled) return;
            removal = {nodeId: selectedNodeId, groupId: local.groupId};
            removeTarget.textContent = removal.nodeId + ' · ' + local.peers[removal.nodeId];
            removeMessage.textContent = '';
            removeFeedback.textContent = '';
            removeDialog.showModal();
            updateRemovalControls();
        });
        removeCancel.addEventListener('click', function () { removeDialog.close(); });
        removeDialog.addEventListener('cancel', function (event) {
            if (removing) event.preventDefault();
        });
        removeConfirm.addEventListener('click', async function () {
            if (!removeDialog.open || removeConfirm.disabled) return;
            var nodeId = removal.nodeId;
            var self = nodeId === local.nodeId;
            removing = true;
            generation++;
            removeMessage.textContent = '';
            removeConfirm.textContent = '正在移除…';
            updateRemovalControls();
            try {
                var state = await request('remove', {nodeId: nodeId});
                if (self) clearPairing();
                renderLocal(state, true);
                if (!self && selectedNodeId === nodeId) selectNode(state.nodeId);
                removeDialog.close();
                removeFeedback.textContent = self ? '本机已退出集群，可继续独立使用。' : '已从集群移除节点 ' + nodeId + '。';
            } catch (error) {
                var detail = error.name === 'AbortError' ? '请求超时，请刷新后确认节点状态。' : error.message;
                removeMessage.textContent = '移除节点失败：' + detail;
            } finally {
                removing = false;
                removeConfirm.textContent = '确认移除';
                updateControls();
                if (removeDialog.open && !removeConfirm.disabled) removeConfirm.focus();
                refresh();
            }
        });
        generate.addEventListener('click', async function () {
            issuing = true; updateControls();
            try {
                var result = await request('pairing/code', {});
                codeExpiresAt = result.expiresAt;
                ownCode.textContent = result.code + ' · 5 分钟内有效，仅供一次配对';
            } catch (error) { ownCode.textContent = error.message; }
            finally { issuing = false; updateControls(); }
        });
        dialog.addEventListener('click', function (event) {
            if (event.target === dialog) {
                var bounds = dialog.getBoundingClientRect();
                if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) dialog.close();
            }
        });
        document.getElementById('clusterLinkForm').addEventListener('submit', async function (event) {
            event.preventDefault();
            if (submit.disabled) return;
            var url;
            try {
                url = new URL(input.value.trim());
                if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw new Error();
            } catch (invalid) {
                showMessage('请输入有效的 http:// 或 https:// BootFS 地址，不含账号、查询参数或片段。', true);
                input.focus();
                return;
            }
            var code = codeInput.value.trim();
            if (!/^[0-9]{8}$/.test(code)) { showMessage('请输入对方生成的 8 位验证码。', true); codeInput.focus(); return; }
            posting = true;
            generation++;
            clearTimeout(timer);
            submissionError = '';
            rememberTarget(url.href.replace(/\/+$/, ''));
            showMessage('正在连接指定节点…');
            updateControls();
            try {
                await request('link', {url: target, code: code});
                showMessage('已建立组网关系，正在确认双方成员状态…');
            } catch (error) {
                submissionError = error.name === 'AbortError' ? '连接请求超时，请检查节点是否可达。' : error.message;
                showMessage(submissionError, true);
            } finally {
                codeInput.value = '';
                posting = false;
                refresh();
            }
        });
        refresh();
    });
})();
