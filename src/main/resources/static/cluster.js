'use strict';
const byId = id => document.getElementById(id);
async function request(path, body) {
    const response = await fetch('/api/cluster/' + path, {redirect:'error', headers:body ? {'Content-Type':'application/json'} : {}, method:body ? 'POST' : 'GET', body:body ? JSON.stringify(body) : undefined});
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || '请求未完成，请稍后重试');
    return result;
}
const labels = {ACTIVE:'节点在线', FROZEN:'正在合并资源', ATTACHING:'正在加入集群', LEAVING:'正在安全下线', OFFLINE:'节点已下线，重启后重新加入', REMOVED:'节点已从集群移除，重启后重新加入', STATIC:'当前使用固定部署模式'};
let updating = false;
async function refresh() {
    if (updating) return;
    updating = true;
    try {
        const state = await request('status');
        byId('state').textContent = labels[state.phase] || state.phase;
        byId('identity').textContent = state.nodeId ? '本节点：' + state.nodeId + ' · 集群：' + state.groupId : '请使用动态部署模式启用组网。';
        byId('message').textContent = state.lastError || '';
        byId('leave').disabled = state.phase !== 'ACTIVE';
        byId('connect').querySelector('button').disabled = state.phase !== 'ACTIVE' || !state.independent;
        byId('members').replaceChildren(...Object.keys(state.peers || {}).map(node => {const item = document.createElement('li'); item.textContent = node; return item;}));
        byId('discovered').replaceChildren(...(state.discovered || []).map(peer => {
            const item = document.createElement('li'); item.textContent = peer.nodeId + ' · ' + peer.endpoint + ' ';
            const button = document.createElement('button'); button.textContent = '连接'; button.disabled = state.phase !== 'ACTIVE' || !state.independent;
            button.onclick = () => connect(peer.endpoint); item.append(button); return item;
        }));
    } catch (error) { byId('message').textContent = error.message; }
    finally { updating = false; }
}
async function connect(url) {try { await request('connect', {url}); await refresh(); } catch(error) {byId('message').textContent = error.message;}}
byId('connect').onsubmit = event => {event.preventDefault(); connect(byId('endpoint').value);};
byId('leave').onclick = async () => {try {await request('leave', {}); await refresh();} catch(error) {byId('message').textContent = error.message;}};
byId('versions').onsubmit = async event => {
    event.preventDefault();
    try {
        const records = await request('versions?namespace=' + encodeURIComponent(byId('namespace').value) + '&key=' + encodeURIComponent(byId('version-key').value));
        byId('version-list').replaceChildren(...records.map(record => {
            const item = document.createElement('li');
            if (record.namespace === 'metadata') { item.textContent = '版本 ' + record.version + ' · ' + record.value; return item; }
            const value = JSON.parse(record.value);
            const link = document.createElement('a'); link.textContent = '版本 ' + record.version + ' · ' + (value.fileName || value.key || value.uuid);
            link.href = '/f/' + encodeURIComponent(value.uuid); item.append(link); return item;
        }));
        if (!records.length) byId('version-list').textContent = '尚无冲突版本';
    } catch(error) {byId('message').textContent = error.message;}
};
refresh(); setInterval(refresh, 2500);
