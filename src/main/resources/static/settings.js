'use strict';
(() => {
    const form = document.getElementById('settingsForm');
    const feedback = document.getElementById('feedback');
    let state, dirty = false;
    const field = name => form.elements.namedItem(name);
    const message = (text, type = '') => { feedback.textContent = text; feedback.className = type; };
    function validationMessage(error) {
        const fieldError = /^(设置类型无效|设置内容过长或包含控制字符)：(\w+)$/.exec(error);
        return fieldError ? BootFSI18n.t(fieldError[1] + '：{0}', fieldError[2]) : BootFSI18n.t(error);
    }
    async function api(path, method = 'GET', data) {
        const response = await fetch(path, {method, credentials: 'same-origin', redirect: 'error', headers: {'Content-Type': 'application/json', 'X-BootFS-Request': '1'}, body: data === undefined ? undefined : JSON.stringify(data)});
        const result = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(validationMessage(result.error || '操作失败，请检查登录状态或稍后重试'));
        return result;
    }
    function render(result) {
        state = result;
        for (const [key, value] of Object.entries(result.values)) {
            const input = field(key);
            if (!input) continue;
            if (input.type === 'checkbox') input.checked = value;
            else input.value = result.pending[key] ?? value;
        }
        field('s3AccessKey').value = ''; field('s3AccessKey').placeholder = result.values.s3AccessConfigured ? BootFSI18n.t('已设置；留空保留') : BootFSI18n.t('尚未设置');
        field('s3SecretKey').value = ''; field('s3SecretKey').type = 'password';
        document.getElementById('secretState').textContent = result.values.s3SecretConfigured ? BootFSI18n.t('已设置；留空保留，填写新值可轮换') : BootFSI18n.t('尚未设置');
        document.getElementById('nodeAddress').textContent = result.values.webEntrypoint;
        document.getElementById('activeProxy').textContent = BootFSI18n.t('当前启动使用：{0}；可信代理：{1}', result.activeProxy.forwardHeadersStrategy, result.activeProxy.internalProxies);
        for (const id of ['clearSecret', 'confirmAddressChange', 'confirmBucketChange']) document.getElementById(id).checked = false;
        document.getElementById('settingsFields').disabled = false;
        document.getElementById('save').disabled = false;
        dirty = false;
        document.getElementById('saveHint').textContent = result.restartRequired ? BootFSI18n.t('部分设置等待重启生效') : BootFSI18n.t('配置已与当前节点同步');
    }
    async function load() {
        try {
            const result = await api('/api/settings'); render(result);
            message(result.transitionError ? BootFSI18n.t('受控变更未执行：{0}', BootFSI18n.t(result.transitionError)) : result.migrationWarning ? BootFSI18n.t(result.migrationWarning) : result.restartRequired ? BootFSI18n.t('设置已保存。代理配置或受控变更等待重启；当前地址与桶名仍保持原值。') : BootFSI18n.t('已加载当前节点设置。'), result.transitionError ? 'error' : '');
        } catch (e) { message(e.message, 'error'); }
    }
    form.addEventListener('input', () => { dirty = true; document.getElementById('saveHint').textContent = BootFSI18n.t('有尚未保存的更改'); });
    form.addEventListener('submit', async event => {
        event.preventDefault(); if (!state || !form.reportValidity()) return;
        const values = {};
        for (const key of Object.keys(state.values)) {
            const input = field(key); if (!input) continue;
            values[key] = input.type === 'checkbox' ? input.checked : input.type === 'number' ? Number(input.value) : input.value;
        }
        if (field('s3AccessKey').value) values.s3AccessKey = field('s3AccessKey').value;
        if (document.getElementById('clearSecret').checked) values.s3SecretKey = '';
        else if (field('s3SecretKey').value) values.s3SecretKey = field('s3SecretKey').value;
        document.getElementById('save').disabled = true;
        try {
            const result = await api('/api/settings', 'PUT', {revision: state.revision, values, confirmAddressChange: document.getElementById('confirmAddressChange').checked, confirmBucketChange: document.getElementById('confirmBucketChange').checked});
            render(result); message(result.restartRequired ? BootFSI18n.t('保存成功。部分设置等待重启生效；请完成重启后检查变更结果。') : BootFSI18n.t('保存成功。设置已应用于当前节点。'), 'success');
        } catch (e) { message(e.message, 'error'); }
        finally { document.getElementById('save').disabled = false; }
    });
    document.getElementById('reload').addEventListener('click', load);
    document.getElementById('testSso').addEventListener('click', async () => {
        const button = document.getElementById('testSso'); button.disabled = true;
        const output = document.getElementById('ssoTestResult'); output.textContent = BootFSI18n.t('正在测试…');
        try { const r = await api('/api/settings/sso/test', 'POST', {url: field('ssoServerUrl').value}); output.textContent = r.authenticated ? BootFSI18n.t('连接成功，当前会话已认证。保存时仍会校验角色与本机访问。') : BootFSI18n.t('连接成功，当前会话未认证。开启前请登录认证服务，或在原始本机连接保留本机访问。'); }
        catch (e) { output.textContent = e.message; }
        finally { button.disabled = false; }
    });
    document.getElementById('generateS3').addEventListener('click', () => {
        const random = size => Array.from(crypto.getRandomValues(new Uint8Array(size)), b => b.toString(16).padStart(2, '0')).join('');
        field('s3AccessKey').value = 'BFS' + random(10); field('s3SecretKey').value = random(32);
        document.getElementById('clearSecret').checked = false; dirty = true;
        document.getElementById('secretState').textContent = BootFSI18n.t('已生成，尚未保存。请复制本次输入后保存。');
        document.getElementById('saveHint').textContent = BootFSI18n.t('新凭证尚未保存');
    });
    document.getElementById('revealSecret').addEventListener('click', () => { field('s3SecretKey').type = field('s3SecretKey').type === 'password' ? 'text' : 'password'; });
    const dialog = document.getElementById('rotateDialog');
    document.getElementById('rotateNode').addEventListener('click', () => dialog.showModal());
    document.getElementById('cancelRotate').addEventListener('click', () => dialog.close());
    document.getElementById('confirmRotate').addEventListener('click', async () => {
        const button = document.getElementById('confirmRotate'); button.disabled = true;
        try { await api('/api/settings/credentials/rotate', 'POST', {confirm: 'ROTATE'}); dialog.close(); message(BootFSI18n.t('节点管理凭证已轮换并持久化。'), 'success'); }
        catch (e) { dialog.close(); message(e.message, 'error'); }
        finally { button.disabled = false; }
    });
    window.addEventListener('beforeunload', event => { if (dirty) { event.preventDefault(); event.returnValue = ''; } });
    load();
})();
