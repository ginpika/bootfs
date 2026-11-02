const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function dashboard() {
    const elements = new Map();
    const listeners = {};
    const intervals = new Map();
    let timer = 0;
    const document = {
        activeElement: null,
        hidden: false,
        getElementById(id) {
            if (!elements.has(id)) elements.set(id, {
                textContent: '', innerHTML: '', dataset: {}, listeners: {},
                classList: {add() {}, remove() {}, toggle() {}},
                addEventListener(name, fn) { this.listeners[name] = fn; }
            });
            return elements.get(id);
        },
        querySelectorAll() { return []; },
        addEventListener(name, fn) { listeners[name] = fn; }
    };
    const sandbox = vm.createContext({
        document, URL, URLSearchParams, Date,
        window: {location: {href: 'http://localhost/dashboard', search: ''}, history: {replaceState() {}}},
        setInterval(fn) { intervals.set(++timer, fn); return timer; },
        clearInterval(id) { intervals.delete(id); }
    });
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/dashboard.js'), 'utf8'), sandbox);
    return {
        sandbox, listeners, intervals,
        element: id => document.getElementById(id),
        init() {
            sandbox.window.bootfsClusterAvailable = false;
            listeners.DOMContentLoaded();
            sandbox.window.bootfsClusterAvailable = true;
        },
        async load(nodes) {
            sandbox.fetch = async () => ({json: async () => ({succeed: true, data: {nodes}})});
            await sandbox.loadStats();
        }
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
