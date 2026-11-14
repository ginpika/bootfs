const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function ui() {
    const context = vm.createContext({URL, URLSearchParams, TextEncoder, console, localStorage: {getItem() {return null;}},
        document: {documentElement: {}, addEventListener() {}, getElementById() {return {addEventListener() {}};}}});
    context.window = context;
    context.location = {href: 'https://files.example/', search: ''};
    context.addEventListener = () => {};
    for (const name of ['security-utils.js', 'i18n.js', 'text-editor.js', 'clash-files.js', 'admin-dashboard.js']) {
        vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static', name), 'utf8'), context);
    }
    return context;
}

test('filenames and tags cannot create executable elements or attributes in any file list view', () => {
    const app = ui();
    const payload = '"><img src=x onerror=alert(1)>\'&';
    const file = {uuid: 'safe-id', fileName: payload + '.png', size: 12, tags: [{namespace: 'manual', name: payload}]};
    for (const render of ['createWaterfallCard', 'createGridCard', 'createListRow']) {
        const result = app[render](file);
        assert.ok(!result.includes(payload), render);
        assert.ok(!result.includes('<img src=x onerror=alert(1)>'), render);
        assert.ok(result.includes('&lt;img'), render);
    }
});

test('media URL interpolation rejects executable schemes and escapes attribute delimiters', () => {
    const app = ui();
    for (const url of ['javascript:alert(1)', 'data:text/html,<script>alert(1)</script>', '//user:password@evil.example/']) {
        assert.equal(app.BootFSSecurity.url(url), '');
    }
    assert.equal(app.BootFSSecurity.url('/thumb/safe'), 'https://files.example/thumb/safe');
    const html = app.createWaterfallCard({uuid: 'safe', fileName: 'album.zip', albumAvailable: '1', thumbUrl: 'javascript:alert(1)', tags: []});
    assert.ok(!html.includes('javascript:'));
});

test('dragging a file sends its object key and owner without selecting other aliases', async () => {
    const app = ui();
    const file = {uuid: 'same-id', objectKey: 'reports/original.txt', originNodeId: 'node-b'};
    const alias = {uuid: file.uuid, objectKey: 'reports/alias.txt', originNodeId: 'node-a'};
    const icon = (key, resource) => {
        let selected = false;
        return {
            dataset: {kind: 'file', key, uuid: resource.uuid, movable: 'true'},
            classList: {
                toggle(name, value) { if (name === 'selected') selected = value; },
                contains(name) { return name === 'selected' && selected; }
            },
            setAttribute() {}
        };
    };
    app.items = [icon('file', file), icon('alias', alias)];
    app.records = [['file', file], ['alias', alias]];
    app.document.querySelectorAll = () => app.items;
    let body;
    app.fetch = async (url, request) => {
        assert.equal(url, '/api/folders/move');
        body = JSON.parse(request.body);
        return {ok: true, json: async () => ({moved: 1})};
    };
    vm.runInContext(`
        currentView = 'icons';
        iconRecords = new Map(records);
        updateSelectedToolbar = syncIconSelection;
        clearSelection = () => {};
        showToast = () => {};
        loadFileList = () => {};
        selectOnlyIcon(items[0]);
    `, app);
    await app.moveSelectionTo('archive/');
    assert.deepEqual(body, {destination: 'archive/', files: [file], folders: []});
});
