const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const vm = require('node:vm');

async function page(options = {}) {
    const values = {ssoEnabled:false, allowedRoles:'', localBypassEnabled:true, ssoServerUrl:'', ssoInfoUrl:'', publicSharingEnabled:true, allowPrivateProbes:true,
        s3Enabled:false, s3AccessConfigured:true, s3SecretConfigured:true, s3Bucket:'bootfs', copies:1, writePolicy:'single-local', requiredCopies:0, prepareTimeoutMs:30000,
        webEntrypoint:'http://localhost:8181', forwardHeadersStrategy:'none', internalProxies:'127[.]0[.]0[.]1'};
    const inputs = {};
    for (const [name, value] of Object.entries(values)) inputs[name] = {type:typeof value === 'boolean' ? 'checkbox' : typeof value === 'number' ? 'number' : 'text', value:'', checked:false};
    inputs.s3AccessKey = {type:'text', value:''}; inputs.s3SecretKey = {type:'password', value:''};
    const elements = new Map();
    const element = id => {
        if (!elements.has(id)) elements.set(id, {value:'', textContent:'', checked:false, handlers:{}, addEventListener(type, fn) { this.handlers[type] = fn; }});
        return elements.get(id);
    };
    const form = element('settingsForm'); form.elements = {namedItem: name => inputs[name]}; form.reportValidity = () => true;
    const state = {revision:7, values, pending:options.pending || {}, restartRequired:false, activeProxy:{forwardHeadersStrategy:'none', internalProxies:'127[.]0[.]0[.]1'}};
    const requests = [];
    const context = vm.createContext({console, URL, Uint8Array,
        document:{documentElement: {}, addEventListener() {}, getElementById:element},
        localStorage:{getItem: () => options.language ?? null},
        window:{location:{href:'http://localhost/settings'}, addEventListener() {}},
        crypto:require('node:crypto').webcrypto,
        fetch:async (url, request) => {
            requests.push({url, ...request});
            const ok = request.method === 'GET' || !options.error;
            return {ok, json:async () => ok ? state : {error:options.error}};
        }});
    for (const name of ['i18n/en.js', 'i18n.js', 'settings.js']) {
        vm.runInContext(readFileSync(join(__dirname, '../../main/resources/static', name), 'utf8'), context);
    }
    await new Promise(resolve => setImmediate(resolve));
    return {inputs, element, requests, submit: () => form.handlers.submit({preventDefault(){}})};
}

test('settings form preserves write-only credentials while sending explicit false and typed numbers', async () => {
    const p = await page(); p.inputs.copies.value = '3'; p.inputs.publicSharingEnabled.checked = false;
    assert.equal(p.inputs.s3AccessKey.value, ''); assert.equal(p.inputs.s3SecretKey.value, '');
    await p.submit(); const body = JSON.parse(p.requests.at(-1).body);
    assert.equal(body.values.copies, 3); assert.equal(body.values.publicSharingEnabled, false);
    assert.equal(body.revision, 7); assert.ok(!('s3AccessKey' in body.values)); assert.ok(!('s3SecretKey' in body.values));
    assert.equal(p.element('feedback').textContent, '保存成功。设置已应用于当前节点。');
});

test('explicit clear removes the secret and generated credentials are discarded from fields after save', async () => {
    const p = await page(); p.element('generateS3').handlers.click();
    assert.match(p.inputs.s3AccessKey.value, /^BFS[0-9a-f]{20}$/); assert.equal(p.inputs.s3SecretKey.value.length, 64);
    await p.submit(); const first = JSON.parse(p.requests.at(-1).body);
    assert.equal(first.values.s3SecretKey.length,64); assert.equal(p.inputs.s3SecretKey.value,''); assert.equal(p.inputs.s3AccessKey.value,'');
    p.element('clearSecret').checked = true; await p.submit(); assert.equal(JSON.parse(p.requests.at(-1).body).values.s3SecretKey,'');
});

test('pending controlled changes stay in the form until confirmed, without changing the active node badge', async () => {
    const p = await page({pending:{webEntrypoint:'https://new.example'}});
    assert.equal(p.inputs.webEntrypoint.value,'https://new.example'); assert.equal(p.element('nodeAddress').textContent,'http://localhost:8181');
    assert.equal(p.element('confirmAddressChange').checked,false);
});

test('save conflicts preserve input and render server errors as text', async () => {
    const p = await page({error:'<img src=x onerror=alert(1)> conflict'}); p.inputs.copies.value='9'; await p.submit();
    assert.equal(p.inputs.copies.value,'9'); assert.equal(p.element('feedback').className,'error');
    assert.equal(p.element('feedback').textContent,'<img src=x onerror=alert(1)> conflict');
});

test('English localizes settings feedback and interpolates field names in validation errors', async () => {
    const p = await page({language:'en', error:'设置类型无效：copies'});
    assert.equal(p.element('feedback').textContent, 'Current node settings loaded.');
    await p.submit();
    assert.equal(p.element('feedback').textContent, 'Invalid value type for setting copies');
});
