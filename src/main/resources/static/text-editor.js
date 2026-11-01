// Local, dependency-free text editing. Never interpret file content as HTML.
window.TextFileEditor = (() => {
    const MAX_BYTES = 2 * 1024 * 1024;
    const configExtensions = new Set(['yaml', 'yml', 'toml', 'ini', 'conf', 'cfg', 'properties', 'env']);
    const dataExtensions = new Set(['json', 'jsonc', 'xml']);
    const textExtensions = new Set(['txt', 'text', 'md', 'markdown', 'log', 'csv', 'tsv', 'rst']);
    const codeExtensions = new Set(['js', 'mjs', 'cjs', 'ts', 'jsx', 'tsx', 'css', 'scss', 'less', 'html', 'htm', 'java', 'py', 'sh', 'bash', 'zsh', 'sql', 'c', 'h', 'cpp', 'hpp', 'go', 'rs', 'rb', 'php', 'kt', 'kts', 'swift', 'vue', 'svelte']);
    const configNames = new Set(['dockerfile', 'makefile', '.gitignore', '.gitattributes', '.editorconfig', '.npmrc', '.yarnrc', '.bashrc', '.zshrc']);
    const encoder = new TextEncoder();
    let elements;
    let state = null;
    let session = 0;
    let previousFocus;
    let previousOverflow;
    let metricsFrame;
    let tabIndents = true;

    function fileType(fileName) {
        if (!fileName) return null;
        const name = fileName.split(/[\\/]/).pop().toLowerCase();
        const extension = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1) : '';
        if (configNames.has(name) || name === '.env' || name.startsWith('.env.') || configExtensions.has(extension)) return { kind: 'config', label: '配置文件' };
        if (dataExtensions.has(extension)) return { kind: 'data', label: '结构化文本' };
        if (textExtensions.has(extension)) return { kind: 'text', label: '文本文件' };
        if (codeExtensions.has(extension)) return { kind: 'code', label: '代码文件' };
        return null;
    }

    function icon(type) {
        const paths = {
            config: '<path d="M4 7h9m4 0h3M4 17h3m4 0h9"/><circle cx="15" cy="7" r="2"/><circle cx="9" cy="17" r="2"/>',
            data: '<path d="M9 4H7a2 2 0 00-2 2v3a3 3 0 01-2 3 3 3 0 012 3v3a2 2 0 002 2h2m6-16h2a2 2 0 012 2v3a3 3 0 002 3 3 3 0 00-2 3v3a2 2 0 01-2 2h-2"/><path d="M12 8h.01M12 12h.01M12 16h.01"/>',
            text: '<path d="M4 5h16M4 10h16M4 15h10M4 20h7"/>',
            code: '<path d="M8 6l-6 6 6 6m8-12l6 6-6 6m-3-15l-2 18"/>'
        };
        return `<div class="text-file-icon text-file-icon-${type.kind}" title="${type.label}" role="img" aria-label="${type.label}"><svg width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24" aria-hidden="true">${paths[type.kind]}</svg></div>`;
    }

    function isOpen() {
        return !!elements && !elements.modal.classList.contains('hidden');
    }

    function dirty() {
        return !!state?.loaded && (elements.content.value !== state.baseline || state.encoding !== state.baselineEncoding);
    }

    function serializedContent() {
        return state.bom + elements.content.value.replace(/\n/g, state.newline);
    }

    function encodedSize(content) {
        if (state.encoding !== 'GBK') return encoder.encode(content).length;
        // Representable GBK characters use one byte for ASCII and two otherwise.
        // The server's strict encoder rejects characters outside GBK without replacing them.
        let bytes = 0;
        for (const character of content) bytes += character.codePointAt(0) <= 0x7f ? 1 : 2;
        return bytes;
    }

    function updateEncodingLabel() {
        elements.encoding.textContent = `${state.encoding}${state.bom ? ' BOM' : ''} · ${state.newline === '\r\n' ? 'CRLF' : 'LF'}`;
    }

    function error(message = '') {
        elements.error.textContent = message;
        elements.error.classList.toggle('hidden', !message);
    }

    function updateStatus() {
        const changed = dirty();
        const tooLarge = state?.loaded && encodedSize(serializedContent()) > MAX_BYTES;
        elements.save.disabled = !state?.loaded || state.saving || !changed || tooLarge;
        elements.save.textContent = state?.saving ? '正在保存…' : '保存';
        elements.reload.disabled = !!state?.saving || !!state?.loading;
        elements.charset.disabled = !!state?.saving || !!state?.loading;
        elements.surface.setAttribute('aria-busy', String(!!state?.loading || !!state?.saving));
        elements.status.textContent = state?.saving ? '正在保存…' : state?.loading ? '正在加载…' : !state?.loaded ? '加载失败' : tooLarge ? '内容超过 2 MiB，无法保存' : changed ? '有未保存的修改' : '已保存';
    }

    function updateMetrics() {
        cancelAnimationFrame(metricsFrame);
        metricsFrame = requestAnimationFrame(() => {
            const value = elements.content.value;
            const lines = value.split('\n').length;
            if (elements.numbers.dataset.lines !== String(lines)) {
                elements.numbers.textContent = Array.from({ length: lines }, (_, index) => index + 1).join('\n');
                elements.numbers.dataset.lines = String(lines);
            }
            const position = elements.content.selectionStart;
            const line = value.slice(0, position).split('\n').length;
            const column = position - (position === 0 ? -1 : value.lastIndexOf('\n', position - 1));
            elements.cursor.textContent = `行 ${line}，列 ${column} · 共 ${lines} 行`;
            elements.numbers.scrollTop = elements.content.scrollTop;
        });
    }

    async function readResponse(response) {
        if (response.redirected || response.status === 401 || response.status === 403) throw new Error('登录已过期或没有编辑权限，请重新登录后重试。');
        const json = response.headers.get('content-type')?.includes('application/json');
        const payload = json ? await response.json() : null;
        if (!response.ok) {
            if (response.status === 409) throw new Error('文件内容或信息已变化。你的修改已保留，请先复制内容再重新加载；若已生成新版本，请刷新列表后重新打开。');
            if (response.status === 413) throw new Error('文件超过 2 MiB，无法在内置编辑器中打开或保存。');
            if (response.status === 415) throw new Error('此文件无法按 UTF-8 或 GBK 读取，可能含有二进制内容。也可以切换读取编码后重试。');
            if (response.status === 404) throw new Error('文件不存在或已被删除。');
            throw new Error(payload?.message || '操作失败，请稍后重试。');
        }
        if (!payload || typeof payload.version !== 'string') throw new Error('服务器返回了无效响应，请重新登录或稍后重试。');
        return payload;
    }

    async function load() {
        const activeSession = ++session;
        state.readEncoding = elements.charset.value;
        state.loading = true;
        state.loaded = false;
        elements.content.disabled = true;
        elements.content.value = '';
        error();
        updateStatus();
        updateMetrics();
        try {
            const query = state.readEncoding === 'auto' ? '' : `?encoding=${encodeURIComponent(state.readEncoding)}`;
            const response = await fetch(`/api/file/${encodeURIComponent(state.uuid)}/text${query}`, { cache: 'no-store' });
            const data = await readResponse(response);
            if (activeSession !== session || !isOpen()) return;
            if (typeof data.content !== 'string') throw new Error('服务器未返回文本内容。');
            if (data.fileName) elements.name.textContent = data.fileName;
            state.version = data.version;
            state.encoding = data.encoding === 'GBK' ? 'GBK' : 'UTF-8';
            state.baselineEncoding = data.detectedEncoding || state.encoding;
            state.newline = data.content.includes('\r\n') ? '\r\n' : '\n';
            state.bom = data.content.startsWith('\uFEFF') ? '\uFEFF' : '';
            elements.content.value = data.content.slice(state.bom.length);
            // Textareas normalize CRLF to LF; serialize with the original newline style.
            state.baseline = elements.content.value;
            state.loaded = true;
            elements.content.disabled = false;
            updateEncodingLabel();
            elements.content.focus();
        } catch (failure) {
            if (activeSession !== session || !isOpen()) return;
            error(failure.message || '读取失败，请检查网络后重试。');
        } finally {
            if (activeSession === session && isOpen()) {
                state.loading = false;
                updateStatus();
                updateMetrics();
            }
        }
    }

    function open(file) {
        const type = fileType(file.fileName);
        if (!type) return;
        if (isOpen() && !close()) return;
        previousFocus = document.activeElement;
        previousOverflow = document.body.style.overflow;
        document.body.style.overflow = 'hidden';
        state = { uuid: file.uuid, loaded: false, loading: true, saving: false };
        elements.charset.value = 'auto';
        elements.name.textContent = file.fileName;
        elements.format.textContent = type.label;
        elements.modal.classList.remove('hidden');
        elements.close.focus();
        load();
    }

    function close() {
        if (!isOpen()) return true;
        if (state?.saving) return false;
        if (dirty() && !window.confirm('有未保存的修改，确定放弃修改并关闭吗？')) return false;
        ++session;
        elements.modal.classList.add('hidden');
        elements.content.value = '';
        document.body.style.overflow = previousOverflow;
        state = null;
        if (previousFocus?.isConnected) previousFocus.focus();
        return true;
    }

    async function save() {
        if (!state?.loaded || state.saving || elements.save.disabled) return;
        state.saving = true;
        const activeSession = session;
        const savedValue = elements.content.value;
        const previousUuid = state.uuid;
        // Keep the editor read-only during the request; failures preserve the entire draft.
        elements.content.readOnly = true;
        error();
        updateStatus();
        try {
            const response = await fetch(`/api/file/${encodeURIComponent(state.uuid)}/text`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ content: serializedContent(), version: state.version, encoding: state.encoding })
            });
            const data = await readResponse(response);
            if (activeSession !== session) return;
            if (typeof data.uuid !== 'string' || !data.uuid) throw new Error('保存响应缺少文件版本信息，请重新加载确认。');
            state.uuid = data.uuid;
            state.version = data.version;
            state.encoding = data.encoding || state.encoding;
            state.baselineEncoding = data.detectedEncoding || state.encoding;
            updateEncodingLabel();
            if (data.fileName) elements.name.textContent = data.fileName;
            state.baseline = savedValue;
            selectedFiles.delete(previousUuid);
            updateSelectedToolbar();
            loadFileList();
            showToast('文本已保存', 'success');
        } catch (failure) {
            if (activeSession === session) error(failure.message || '保存失败，请检查网络后重试。');
        } finally {
            if (activeSession === session) {
                state.saving = false;
                elements.content.readOnly = false;
                updateStatus();
                elements.content.focus();
            }
        }
    }

    function indent(event) {
        const editor = elements.content;
        // Tab indents in the editor; Shift+Tab removes one indentation level.
        if (event.key !== 'Tab' || !tabIndents || editor.disabled || editor.readOnly) return;
        event.preventDefault();
        const { selectionStart: start, selectionEnd: end, value } = editor;
        if (!event.shiftKey && !value.slice(start, end).includes('\n')) {
            editor.setRangeText('    ', start, end, 'end');
        } else {
            const lineStart = start === 0 ? 0 : value.lastIndexOf('\n', start - 1) + 1;
            const block = value.slice(lineStart, end);
            const transformed = event.shiftKey ? block.replace(/^( {1,4}|\t)/gm, '') : block.replace(/^/gm, '    ');
            editor.setRangeText(transformed, lineStart, end, 'select');
        }
        editor.dispatchEvent(new Event('input', { bubbles: true }));
    }

    function toggleTabMode() {
        tabIndents = !tabIndents;
        elements.tabMode.textContent = tabIndents ? 'Tab 缩进' : 'Tab 导航';
        elements.tabMode.setAttribute('aria-pressed', String(tabIndents));
    }

    document.addEventListener('DOMContentLoaded', () => {
        const byId = suffix => document.getElementById(`textEditor${suffix}`);
        elements = { modal: byId('Modal'), name: byId('FileName'), format: byId('Format'), status: byId('Status'), error: byId('Error'), surface: byId('Surface'), content: byId('Content'), numbers: byId('LineNumbers'), cursor: byId('Cursor'), encoding: byId('Encoding'), charset: byId('Charset'), save: byId('Save'), reload: byId('Reload'), tabMode: byId('TabMode'), close: byId('Close'), cancel: byId('Cancel') };
        elements.close.addEventListener('click', close);
        elements.cancel.addEventListener('click', close);
        elements.save.addEventListener('click', save);
        elements.tabMode.addEventListener('click', toggleTabMode);
        elements.charset.addEventListener('change', () => {
            if (!state) return;
            if (dirty() && !window.confirm('切换读取编码会重新加载文件并放弃当前修改，是否继续？')) {
                elements.charset.value = state.readEncoding;
                return;
            }
            load();
        });
        elements.reload.addEventListener('click', () => {
            if (!dirty() || window.confirm('重新加载会放弃当前修改，是否继续？')) load();
        });
        elements.content.addEventListener('input', () => { updateStatus(); updateMetrics(); });
        elements.content.addEventListener('select', updateMetrics);
        elements.content.addEventListener('click', updateMetrics);
        elements.content.addEventListener('keyup', updateMetrics);
        elements.content.addEventListener('scroll', () => { elements.numbers.scrollTop = elements.content.scrollTop; });
        elements.content.addEventListener('keydown', indent);
        elements.modal.addEventListener('keydown', event => {
            event.stopPropagation();
            if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
                event.preventDefault();
                save();
            } else if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'm') {
                event.preventDefault();
                toggleTabMode();
            } else if (event.key === 'Escape') {
                event.preventDefault();
                close();
            } else if (event.key === 'Tab' && !event.defaultPrevented) {
                const controls = Array.from(elements.modal.querySelectorAll('button:not(:disabled), textarea:not(:disabled), select:not(:disabled)'));
                const first = controls[0];
                const last = controls[controls.length - 1];
                if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
                if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
            }
        });
        window.addEventListener('beforeunload', event => {
            if (dirty() || state?.saving) { event.preventDefault(); event.returnValue = ''; }
        });
    });

    return { fileType, icon, open, isOpen };
})();
