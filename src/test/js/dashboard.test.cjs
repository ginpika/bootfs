const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function dashboard(clusterState) {
    const elements = new Map();
    const events = new EventTarget();
    const intervals = new Map();
    const timeouts = new Map();
    const nodes = [];
    const actions = [];
    let onAction = () => { throw new Error('Unexpected cluster action'); };
    let timer = 0;
    const document = {
        activeElement: null,
        documentElement: {},
        querySelector() { return null; },
        hidden: false,
        getElementById(id) {
            if (!elements.has(id)) elements.set(id, {
                textContent: '', innerHTML: '', dataset: {}, listeners: {}, style: {},
                classList: {add() {}, remove() {}, toggle() {}},
                addEventListener(name, fn) { this.listeners[name] = fn; },
                showModal() { this.open = true; },
                close() { this.open = false; },
                focus() {}
            });
            return elements.get(id);
        },
        querySelectorAll() { return []; },
        addEventListener: events.addEventListener.bind(events),
        dispatchEvent: events.dispatchEvent.bind(events)
    };
    const sandbox = vm.createContext({
        document, URL, URLSearchParams, Date, Event, AbortController,
        window: {location: {href: 'http://localhost/dashboard', search: ''}, history: {replaceState() {}}},
        setInterval(fn) { intervals.set(++timer, fn); return timer; },
        clearInterval(id) { intervals.delete(id); },
        setTimeout(fn, delay) { timeouts.set(++timer, {fn, delay}); return timer; },
        clearTimeout(id) { timeouts.delete(id); },
        async fetch(url, options) {
            if (url === '/api/dashboard/stats') return Response.json({succeed: true, data: {nodes: [...nodes]}});
            if (url === '/api/cluster/status' && clusterState) return Response.json(clusterState);
            assert.ok(['/api/cluster/leave', '/api/cluster/remove'].includes(url), url);
            assert.equal(options.method, 'POST');
            const action = {url, body: JSON.parse(options.body)};
            actions.push(action);
            return onAction(action);
        }
    });
    const scripts = ['security-utils.js', 'i18n.js', 'dashboard.js'];
    if (clusterState) scripts.push('dashboard-cluster.js');
    for (const script of scripts) {
        vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static', script), 'utf8'), sandbox);
    }
    return {
        sandbox, intervals, nodes, actions,
        element: id => document.getElementById(id),
        init() {
            sandbox.window.bootfsClusterAvailable = false;
            events.dispatchEvent(new Event('DOMContentLoaded'));
            if (!clusterState) sandbox.window.bootfsClusterAvailable = true;
        },
        async load(nextNodes) {
            nodes.splice(0, nodes.length, ...nextNodes);
            await sandbox.loadStats();
        },
        async refreshCluster() {
            const timeout = [...timeouts.values()].find(({delay}) => delay === 2500);
            await timeout.fn();
        },
        onAction(handler) { onAction = handler; }
    };
}

function node(id, current, count, ip) {
    return {
        nodeId: id, isCurrent: current, online: true, url: 'http://' + id,
        appStartTime: current ? 1000 : 2000,
        system: {runtime: current ? 'systemd' : 'docker', architecture: 'aarch64', memory: {
            processRssBytes: count * 1024, processHostPercent: count, heapUsedBytes: 512
        }},
        traffic: {totalBytesOut: count * 1024, totalRequests: count},
        requests: {total24h: count, bytes24h: count * 1024, hourly: [], recent: [{
            time: '11-02 10:00:00', method: 'GET', uri: '/only-' + id,
            clientIp: ip, status: 200, bytes: 100, durationMs: 1, category: 'FILE'
        }]}
    };
}

test('clicking a remote node changes all detail sections and polling preserves selection', async () => {
    const page = dashboard();
    const a = node('a', true, 1, '192.0.2.1');
    const b = node('b', false, 2, '2001:db8::2');
    await page.load([a, b]);
    assert.equal(page.element('trafficTotalReqs').textContent, 1);
    assert.match(page.element('nodesGrid').innerHTML, /role="button" tabindex="0" aria-pressed="true" data-node-id="a"/);
    page.init();
    page.element('nodesGrid').listeners.click({target: {closest: () => ({dataset: {nodeId: 'b'}})}});
    assert.equal(page.element('trafficTotalReqs').textContent, 2);
    assert.equal(page.element('reqTotal24h').textContent, 2);
    assert.match(page.element('recentReqBody').innerHTML, /2001:db8::2/);
    assert.doesNotMatch(page.element('recentReqBody').innerHTML, /192\.0\.2\.1/);
    assert.match(page.element('systemStats').innerHTML, /Docker/);
    assert.equal(page.sandbox.uptimeStartTime, 2000);
    await page.load([a, {...b, requests: {...b.requests, total24h: 3}}]);
    assert.equal(page.sandbox.selectedNodeId, 'b');
    assert.equal(page.element('reqTotal24h').textContent, 3);
    assert.equal(page.intervals.size, 2); // one refresh timer and one uptime timer
});

test('offline and older nodes clear all previous statistics and uptime', async () => {
    const page = dashboard();
    await page.load([node('a', true, 1, '192.0.2.1'), node('b', false, 2, '2001:db8::2')]);
    page.sandbox.selectNode('b');
    await page.load([node('a', true, 1, '192.0.2.1'), {nodeId: 'b', online: false}]);
    assert.equal(page.element('trafficTotalReqs').textContent, '--');
    assert.equal(page.element('reqTotal24h').textContent, '--');
    assert.match(page.element('recentReqBody').innerHTML, /节点不可达/);
    assert.doesNotMatch(page.element('recentReqBody').innerHTML, /2001:db8::2/);
    assert.match(page.element('systemStats').innerHTML, /节点不可达/);
    assert.equal(page.sandbox.uptimeStartTime, 0);
    assert.equal(page.intervals.size, 0);
    await page.load([{nodeId: 'b', online: true, isCurrent: true}]);
    assert.match(page.element('selectedNodeStatus').textContent, /请升级节点/);
    assert.equal(page.element('trafficTotalReqs').textContent, '--');
    assert.match(page.element('recentReqBody').innerHTML, /未提供请求统计/);
});

test('supports keyboard selection and escapes requester IP and system metadata', async () => {
    const page = dashboard();
    page.init();
    const b = node('b', false, 2, '<img src=x onerror="alert(1)">');
    b.system.cpuModel = '<script>alert(1)</script>';
    await page.load([node('a', true, 1, '192.0.2.1'), b]);
    let prevented = false;
    page.element('nodesGrid').listeners.keydown({
        key: 'Enter', preventDefault() { prevented = true; },
        target: {closest: () => ({dataset: {nodeId: 'b'}})}
    });
    assert.ok(prevented);
    assert.equal(page.sandbox.selectedNodeId, 'b');
    assert.match(page.element('recentReqBody').innerHTML, /&lt;img/);
    assert.doesNotMatch(page.element('recentReqBody').innerHTML, /<img/);
    assert.match(page.element('systemStats').innerHTML, /&lt;script&gt;/);
});

test('only confirmation removes the captured offline node and refreshes selection and stats', async () => {
    const state = {phase: 'ACTIVE', nodeId: 'a', coordinatorId: 'a', groupId: 'group', peers: {a: 'http://a', b: 'http://b'}};
    const page = dashboard(state);
    const a = node('a', true, 1, '192.0.2.1');
    const b = {...node('b', false, 2, '192.0.2.2'), online: false};
    await page.load([a, b]);
    page.init();
    await new Promise(setImmediate);
    page.sandbox.selectNode('b');

    const open = page.element('clusterRemoveOpen');
    const dialog = page.element('clusterRemoveDialog');
    const confirm = page.element('clusterRemoveConfirm');
    assert.equal(open.disabled, false);
    assert.equal(open.textContent, '移除节点');
    open.listeners.click();
    assert.equal(dialog.open, true);
    assert.equal(page.element('clusterRemoveTarget').textContent, 'b · http://b');
    page.element('clusterRemoveCancel').listeners.click();
    assert.equal(dialog.open, false);
    assert.deepEqual(page.actions, []);

    open.listeners.click();
    page.sandbox.selectNode('a');
    let releaseRemoval;
    page.onAction(() => new Promise(resolve => { releaseRemoval = resolve; }));
    const removing = confirm.listeners.click();
    assert.equal(confirm.disabled, true);
    await confirm.listeners.click();
    assert.deepEqual(page.actions, [{url: '/api/cluster/remove', body: {nodeId: 'b'}}]);
    page.sandbox.selectNode('b');

    let releaseStats;
    const fetch = page.sandbox.fetch;
    page.sandbox.fetch = () => new Promise(resolve => { releaseStats = resolve; });
    const staleStats = page.sandbox.loadStats();
    page.sandbox.fetch = fetch;
    delete state.peers.b;
    state.independent = true;
    page.nodes.pop();
    releaseRemoval(Response.json(state));
    await removing;
    releaseStats(Response.json({succeed: true, data: {nodes: [a, b]}}));
    await staleStats;
    await new Promise(setImmediate);

    assert.equal(dialog.open, false);
    assert.equal(open.disabled, true);
    assert.match(page.element('clusterRemoveFeedback').textContent, /已从集群移除节点 b/);
    assert.equal(page.sandbox.selectedNodeId, 'a');
    assert.doesNotMatch(page.element('nodesGrid').innerHTML, /data-node-id="b"/);
});

test('remote removal on a non-coordinator links to the coordinator for the selected node', async () => {
    const page = dashboard({phase: 'ACTIVE', nodeId: 'a', coordinatorId: 'b', groupId: 'group',
        peers: {a: 'http://a', b: 'https://b.example', 'c/d': 'http://c'}});
    await page.load([node('a', true, 1), node('c/d', false, 2)]);
    page.init();
    await new Promise(setImmediate);
    page.sandbox.selectNode('c/d');
    page.element('clusterRemoveOpen').listeners.click();
    const link = page.element('clusterRemoveCoordinator');
    assert.equal(link.hidden, false);
    assert.equal(link.href, 'https://b.example/dashboard?node=c%2Fd');
    assert.equal(page.element('clusterRemoveConfirm').disabled, true);
    assert.deepEqual(page.actions, []);

    page.element('clusterRemoveCancel').listeners.click();
    page.sandbox.selectNode('a');
    page.element('clusterRemoveOpen').listeners.click();
    const confirm = page.element('clusterRemoveConfirm');
    assert.equal(confirm.disabled, false);
    page.onAction(() => Response.json({error: '请求失败'}, {status: 409}));
    await confirm.listeners.click();
    assert.deepEqual(page.actions, [{url: '/api/cluster/leave', body: {}}]);
    assert.equal(page.element('clusterRemoveDialog').open, true);
    assert.equal(page.element('clusterRemoveMessage').textContent, '退出集群失败：请求失败');
    assert.equal(confirm.disabled, false);
});

test('the Master display follows cluster status and confirmed local departure clears cluster details', async () => {
    const state = {phase: 'ACTIVE', nodeId: 'a', coordinatorId: 'b', groupId: 'group', independent: false,
        peers: {a: 'http://a', b: 'http://b'}};
    const page = dashboard(state);
    const a = node('a', true, 1);
    await page.load([a, {...node('b', false, 2), online: false}]);
    page.init();
    await new Promise(setImmediate);

    const coordinator = page.element('clusterCoordinatorStatus');
    assert.equal(coordinator.hidden, false);
    assert.equal(coordinator.textContent, '调度节点（Master） · b · http://b');
    state.coordinatorId = 'a';
    await page.refreshCluster();
    assert.equal(coordinator.textContent, '调度节点（Master） · a · http://a');

    const open = page.element('clusterRemoveOpen');
    const dialog = page.element('clusterRemoveDialog');
    const confirm = page.element('clusterRemoveConfirm');
    assert.equal(open.textContent, '退出集群');
    assert.equal(open.disabled, false);
    open.listeners.click();
    assert.equal(page.element('clusterRemoveTitle').textContent, '退出集群');
    assert.equal(confirm.textContent, '确认退出');
    assert.deepEqual(page.actions, []);

    page.element('clusterPairingCode').textContent = '12345678';
    page.onAction(() => {
        Object.assign(state, {groupId: 'independent', independent: true, peers: {a: 'http://a'}});
        page.nodes.pop();
        return Response.json(state);
    });
    await confirm.listeners.click();
    await new Promise(setImmediate);
    assert.deepEqual(page.actions, [{url: '/api/cluster/leave', body: {}}]);
    assert.equal(dialog.open, false);
    assert.equal(open.disabled, true);
    assert.equal(page.element('clusterRemoveFeedback').textContent, '本机已退出集群，可继续独立使用。');
    assert.equal(page.element('clusterPairingCode').textContent, '');
    assert.equal(coordinator.hidden, true);
    assert.equal(coordinator.textContent, '');
    assert.equal(page.sandbox.selectedNodeId, 'a');
    assert.doesNotMatch(page.element('nodesGrid').innerHTML, /data-node-id="b"/);
});
