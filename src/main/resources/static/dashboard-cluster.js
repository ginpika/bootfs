(function () {
    'use strict';

    window.bootfsClusterAvailable = false;
    document.addEventListener('DOMContentLoaded', function () {
        var dialog = document.getElementById('clusterLinkDialog');
        var input = document.getElementById('clusterLinkUrl');
        var codeInput = document.getElementById('clusterLinkCode');
        var generate = document.getElementById('clusterPairingGenerate');
        var ownCode = document.getElementById('clusterPairingCode');
        var submit = document.getElementById('clusterLinkSubmit');
        var message = document.getElementById('clusterLinkMessage');
        var summary = document.getElementById('clusterSummary');
        var coordinatorStatus = document.getElementById('clusterCoordinatorStatus');
        var removeOpen = document.getElementById('clusterRemoveOpen');
        var removeDialog = document.getElementById('clusterRemoveDialog');
        var removeTitle = document.getElementById('clusterRemoveTitle');
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
            removeOpen.textContent = selectedNodeId && selectedNodeId === local?.nodeId ? BootFSI18n.t('退出集群') : BootFSI18n.t('移除节点');
            removeOpen.disabled = !isClusterMember(selectedNodeId) || posting || removing || Boolean(target);
            if (!removeDialog.open) return;
            var self = removal.nodeId === local.nodeId;
            var canRemove = isClusterMember(removal.nodeId) && local.groupId === removal.groupId;
            var needsCoordinator = canRemove && !self && local.nodeId !== local.coordinatorId;
            var coordinatorEndpoint = needsCoordinator && local.peers[local.coordinatorId];
            var coordinatorUrl = coordinatorEndpoint && window.BootFSSecurity.url(coordinatorEndpoint);
            removeTitle.textContent = self ? BootFSI18n.t('退出集群') : BootFSI18n.t('从集群中移除节点');
            removeCoordinator.hidden = !coordinatorUrl;
            if (coordinatorUrl) {
                removeCoordinator.href = coordinatorUrl.replace(/\/+$/, '') + '/dashboard?node=' + encodeURIComponent(removal.nodeId);
            }
            removeDescription.textContent = !canRemove ? BootFSI18n.t('集群成员状态已变更，请关闭后重新选择节点。') : needsCoordinator ?
                BootFSI18n.t('请在调度节点的仪表盘操作。') : self ? BootFSI18n.t('确认后，本机将退出集群并继续独立提供文件服务。') :
                BootFSI18n.t('确认后，集群将停止通过该节点访问文件。该节点会保留本地文件。');
            removeConfirm.textContent = self ? (removing ? BootFSI18n.t('正在退出…') : BootFSI18n.t('确认退出')) : (removing ? BootFSI18n.t('正在移除…') : BootFSI18n.t('确认移除'));
            removeConfirm.disabled = !canRemove || needsCoordinator || removing;
            removeCancel.disabled = removing;
        }

        function updateControls() {
            var enabled = local && local.phase === 'ACTIVE';
            submit.disabled = !enabled || posting || removing || Boolean(target);
            input.disabled = posting || Boolean(target);
            codeInput.disabled = posting || Boolean(target);
            generate.disabled = !enabled || issuing || removing;
            submit.textContent = posting ? BootFSI18n.t('正在连接…') : (target ? BootFSI18n.t('连接中…') : BootFSI18n.t('连接'));
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
                    throw new Error(BootFSI18n.t('无法读取组网状态，请检查登录状态后重试'));
                }
                var result = await response.json();
                if (!response.ok) {
                    var error = new Error(result.error ? BootFSI18n.t(result.error) : BootFSI18n.t('组网请求失败（HTTP {0}）', response.status));
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
                summary.textContent = BootFSI18n.t('当前沿用 {0} 存储模式', state.metadataMode || BootFSI18n.t('原有'));
                showMessage(BootFSI18n.t('当前数据可继续使用。要启用节点配对，请先通过备份恢复迁移到新的动态节点。'), false);
            } else if (state.phase === 'ACTIVE') {
                summary.textContent = BootFSI18n.t('本机独立读写 · {0} 个组网成员', Object.keys(state.peers || {}).length) + (state.pendingNotifications ? ' ' + BootFSI18n.t('· 成员通知待同步') : '');
            } else {
                summary.textContent = phaseLabel(state) + (state.lastError ? BootFSI18n.t('：{0}', state.lastError) : '');
                if (!target) showMessage(summary.textContent, Boolean(state.lastError));
            }
            var coordinatorId = state.phase === 'ACTIVE' && !state.independent && state.coordinatorId;
            var coordinatorEndpoint = coordinatorId && state.peers?.[coordinatorId];
            coordinatorStatus.hidden = !coordinatorId;
            coordinatorStatus.textContent = coordinatorId ? BootFSI18n.t('调度节点（Master） ·') + ' ' + coordinatorId +
                (coordinatorEndpoint ? ' · ' + coordinatorEndpoint : '') : '';
            var nextKey = window.bootfsClusterAvailable ? state.phase + JSON.stringify(state.peers || {}) : '';
            if (nextKey && (reloadStats || nextKey !== statsKey)) loadStats(reloadStats);
            statsKey = nextKey;
            updateControls();
        }

        function phaseLabel(state) {
            return {
                ACTIVE: BootFSI18n.t('节点在线'), FROZEN: BootFSI18n.t('正在合并资源'), ATTACHING: BootFSI18n.t('正在加入集群'),
                LEAVING: BootFSI18n.t('正在安全下线'), OFFLINE: BootFSI18n.t('节点已下线'), REMOVED: BootFSI18n.t('节点已被移除')
            }[state.phase] || BootFSI18n.t('正在等待节点就绪');
        }

        async function refresh() {
            if (checking || posting || removing) return;
            checking = true;
            var currentGeneration = generation;
            clearTimeout(timer);
            try {
                var current = await request('status');
                if (currentGeneration !== generation || posting) return;
                renderLocal(current);
                if (codeExpiresAt && Date.now() >= codeExpiresAt) {
                    ownCode.textContent = BootFSI18n.t('本机验证码已过期，请重新生成。'); codeExpiresAt = 0;
                }
                if (target) {
                    var state = await request('link/status?url=' + encodeURIComponent(target));
                    if (currentGeneration !== generation || posting) return;
                    renderLocal(state.local);
                    if (state.connected) {
                        rememberTarget('');
                        showMessage(BootFSI18n.t('连接成功，两端已加入同一集群。'));
                        loadStats();
                    } else if (state.pending) {
                        showMessage(BootFSI18n.t('成员目录正在同步，本机资源仍可正常使用…'));
                        summary.textContent = BootFSI18n.t('成员目录正在同步');
                    } else {
                        var joining = [state.local, state.remote].find(function (node) {
                            return node.phase === 'FROZEN' || node.phase === 'ATTACHING';
                        });
                        if (joining) {
                            var detail = joining.lastError ? BootFSI18n.t('，后台正在重试：{0}', joining.lastError) : BootFSI18n.t('，请稍候…');
                            showMessage(phaseLabel(joining) + detail, Boolean(joining.lastError));
                            summary.textContent = phaseLabel(joining) + detail;
                        } else {
                            rememberTarget('');
                            showMessage(submissionError || BootFSI18n.t('尚未完成互联，请检查两端状态后重新连接。'), true);
                        }
                    }
                }
            } catch (error) {
                if (currentGeneration !== generation || posting) return;
                if (!local) window.bootfsClusterAvailable = false;
                var detail = error.name === 'AbortError' ? BootFSI18n.t('请求超时') : error.message;
                showMessage(BootFSI18n.t(target ? '暂时无法确认连接状态，正在重试：{0}' : '无法读取组网状态：{0}', detail), true);
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
            updateControls();
            try {
                var state = self ? await request('leave', {}) : await request('remove', {nodeId: nodeId});
                if (self) {
                    clearPairing();
                    showMessage('');
                }
                renderLocal(state, true);
                if (!self && selectedNodeId === nodeId) selectNode(state.nodeId);
                removeDialog.close();
                removeFeedback.textContent = self ? BootFSI18n.t('本机已退出集群，可继续独立使用。') : BootFSI18n.t('已从集群移除节点 {0}。', nodeId);
            } catch (error) {
                var detail = error.name === 'AbortError' ? BootFSI18n.t('请求超时，请刷新后确认节点状态。') : error.message;
                removeMessage.textContent = BootFSI18n.t(self ? '退出集群失败：{0}' : '移除节点失败：{0}', detail);
            } finally {
                removing = false;
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
                ownCode.textContent = result.code + ' ' + BootFSI18n.t('· 5 分钟内有效，仅供一次配对');
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
                showMessage(BootFSI18n.t('请输入有效的 http:// 或 https:// BootFS 地址，不含账号、查询参数或片段。'), true);
                input.focus();
                return;
            }
            var code = codeInput.value.trim();
            if (!/^[0-9]{8}$/.test(code)) { showMessage(BootFSI18n.t('请输入对方生成的 8 位验证码。'), true); codeInput.focus(); return; }
            posting = true;
            generation++;
            clearTimeout(timer);
            submissionError = '';
            rememberTarget(url.href.replace(/\/+$/, ''));
            showMessage(BootFSI18n.t('正在连接指定节点…'));
            updateControls();
            try {
                await request('link', {url: target, code: code});
                showMessage(BootFSI18n.t('已建立组网关系，正在确认双方成员状态…'));
            } catch (error) {
                submissionError = error.name === 'AbortError' ? BootFSI18n.t('连接请求超时，请检查节点是否可达。') : error.message;
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
