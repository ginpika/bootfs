'use strict';

(() => {
    const t = BootFSI18n.t;
    const reader = document.querySelector('.reader');
    const stage = document.querySelector('#reader-stage');
    const book = document.querySelector('#reader-book');
    const title = document.querySelector('#reader-title');
    const status = document.querySelector('#reader-status');
    const message = document.querySelector('#reader-message');
    const retry = document.querySelector('#reader-retry');
    const range = document.querySelector('#reader-range');
    const total = document.querySelector('#reader-total');
    const previousButton = document.querySelector('#reader-previous');
    const nextButton = document.querySelector('#reader-next');
    const settings = document.querySelector('#reader-settings');
    const drawer = document.querySelector('#reader-drawer');
    const mangaSwitch = document.querySelector('#reader-manga');
    const originalSwitch = document.querySelector('#reader-original');
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
    const mangaPreference = 'bootfs-album-manga';
    const themes = ['light', 'dark', 'sakura', 'starry-night', 'forest', 'ocean', 'sunset', 'lavender'];

    function applyTheme(theme) {
        const root = document.documentElement;
        root.classList.remove(...themes.map(name => 'theme-' + name));
        root.classList.add('theme-' + (themes.includes(theme) ? theme : 'light'));
    }

    if (window.self !== window.top) document.querySelector('#reader-back').hidden = true;
    try {
        applyTheme(localStorage.getItem('tfs-theme'));
        mangaSwitch.checked = localStorage.getItem(mangaPreference) === '1';
    } catch { /* The reader also works in frames without storage access. */ }
    window.addEventListener('storage', event => {
        if (event.key === 'tfs-theme') applyTheme(event.newValue);
    });

    let resources = [];
    let index = 0;
    let current;
    let finishTurn;
    const pageCache = new Map();

    function spreadFor(page) {
        const last = resources.length - 1;
        if (!mangaSwitch.checked || page === 0 || page === last) return [page];
        const left = page - ((page - 1) % 2);
        return left + 1 < last ? [left, left + 1] : [left];
    }

    function imageUrl(page) {
        const url = typeof resources[page] === 'string' ? window.BootFSSecurity.url(resources[page]) : '';
        if (!url || !originalSwitch.checked) return url;
        const match = new URL(url).pathname.match(/\/(?:thumb|p|f)\/([\w-]+)$/);
        return match ? reader.dataset.originalBase + match[1] : url;
    }

    function getPage(page) {
        if (pageCache.has(page)) return pageCache.get(page);

        const slot = document.createElement('div');
        slot.className = 'reader-page';
        slot.dataset.page = page + 1;
        const paper = document.createElement('div');
        paper.className = 'reader-paper';
        const image = new Image();
        image.alt = t('第 {0} 页', page + 1);
        image.draggable = false;
        image.hidden = true;
        const pageStatus = document.createElement('div');
        pageStatus.className = 'reader-page-status';
        const spinner = document.createElement('span');
        spinner.className = 'reader-spinner';
        spinner.setAttribute('role', 'status');
        spinner.setAttribute('aria-label', t('正在加载…'));
        const reload = document.createElement('button');
        reload.type = 'button';
        reload.className = 'reader-button';
        reload.append(retry.firstElementChild.cloneNode(true));
        reload.setAttribute('aria-label', t('重试第 {0} 页', page + 1));

        function load() {
            image.hidden = true;
            pageStatus.hidden = false;
            pageStatus.replaceChildren(spinner);
            const url = imageUrl(page);
            if (url) image.src = url;
            else showError();
        }

        function showError() {
            const error = document.createElement('span');
            error.textContent = t('加载失败');
            pageStatus.replaceChildren(error, reload);
        }

        image.addEventListener('load', () => {
            paper.style.setProperty('--page-ratio', image.naturalWidth / image.naturalHeight);
            image.hidden = false;
            pageStatus.hidden = true;
        });
        image.addEventListener('error', showError);
        reload.addEventListener('click', load);
        paper.append(image, pageStatus);
        slot.append(paper);
        pageCache.set(page, slot);
        load();
        return slot;
    }

    function createSpread(numbers) {
        const layer = document.createElement('div');
        layer.className = 'reader-spread';
        const pages = numbers.map((page, position) => {
            const slot = getPage(page);
            slot.dataset.side = numbers.length === 1 ? 'center' : position === 0 ? 'left' : 'right';
            layer.append(slot);
            return slot;
        });
        return { layer, pages, numbers };
    }

    function preloadAdjacent(numbers) {
        const nearby = new Set(numbers);
        const first = numbers[0];
        const last = numbers.at(-1);
        if (first > 0) spreadFor(first - 1).forEach(page => nearby.add(page));
        if (last < resources.length - 1) spreadFor(last + 1).forEach(page => nearby.add(page));
        for (const page of pageCache.keys()) {
            if (!nearby.has(page)) pageCache.delete(page);
        }
        nearby.forEach(getPage);
    }

    function animateTurn(previous, next, forward) {
        const direction = forward ? -1 : 1;
        const animations = [];
        const timing = { duration: 560, easing: 'cubic-bezier(.45,.12,.22,1)', fill: 'both' };
        previous.layer.classList.add('reader-outgoing');
        previous.layer.inert = true;
        // During the turn the visible leaf has the old page on its front and the new page on its back.
        if (previous.pages.length === 2 && next.pages.length === 2) {
            const leaf = document.createElement('div');
            leaf.className = 'reader-leaf';
            leaf.dataset.direction = forward ? 'forward' : 'backward';
            leaf.setAttribute('aria-hidden', 'true');
            leaf.inert = true;
            const front = document.createElement('div');
            const back = document.createElement('div');
            front.className = back.className = 'reader-face';
            front.append(previous.pages[forward ? 1 : 0]);
            back.append(next.pages[forward ? 0 : 1]);
            leaf.append(front, back);
            book.append(leaf);
            animations.push(leaf.animate([
                { transform: 'perspective(1400px) rotateY(0deg)' },
                { transform: `perspective(1400px) rotateY(${direction * 96}deg)`, offset: .48 },
                { transform: `perspective(1400px) rotateY(${direction * 180}deg)` }
            ], timing));
            for (const face of [front, back]) {
                // Shade the paper without flattening the leaf's 3D faces.
                animations.push(face.firstElementChild.firstElementChild.animate([
                    { filter: 'brightness(1)' }, { filter: 'brightness(.68)' }, { filter: 'brightness(1)' }
                ], timing));
            }
        } else {
            // Centered bookends have their own hinge so the single page never jumps to one side.
            const from = previous.pages.at(forward ? -1 : 0).firstElementChild;
            const to = next.pages.at(forward ? 0 : -1).firstElementChild;
            const hinge = forward ? 'left center' : 'right center';
            animations.push(from.animate([
                { transformOrigin: hinge, transform: 'perspective(1100px) rotateY(0deg)', filter: 'brightness(1)' },
                { transformOrigin: hinge, transform: `perspective(1100px) rotateY(${direction * 86}deg)`, filter: 'brightness(.65)' }
            ], timing));
            animations.push(previous.layer.animate([
                { opacity: 1 }, { opacity: 1, offset: .35 }, { opacity: 0 }
            ], timing));
            animations.push(to.animate([
                { transformOrigin: hinge, transform: `perspective(1100px) rotateY(${-direction * 32}deg)`, opacity: .3 },
                { transformOrigin: hinge, transform: 'perspective(1100px) rotateY(0deg)', opacity: 1 }
            ], timing));
        }

        function settle() {
            if (finishTurn !== settle) return;
            finishTurn = undefined;
            for (const animation of animations) animation.cancel();
            next.layer.append(...next.pages);
            book.replaceChildren(next.layer);
        }

        finishTurn = settle;
        Promise.allSettled(animations.map(animation => animation.finished)).then(settle);
    }

    function render(page, turn = false) {
        finishTurn?.();
        const previous = current;
        const forward = page > index;
        index = page;
        current = createSpread(spreadFor(index));
        const [first, last] = [current.numbers[0], current.numbers.at(-1)];
        book.classList.toggle('is-manga', mangaSwitch.checked);
        previousButton.disabled = first === 0;
        nextButton.disabled = last === resources.length - 1;
        range.textContent = first === last ? first + 1 : `${first + 1}–${last + 1}`;
        total.textContent = ` / ${resources.length}`;
        if (turn && previous && mangaSwitch.checked && !reducedMotion.matches) {
            book.append(current.layer);
            animateTurn(previous, current, forward);
        } else {
            book.replaceChildren(current.layer);
        }
        preloadAdjacent(current.numbers);
    }

    function goTo(page) {
        if (!current) return;
        page = Math.max(0, Math.min(page, resources.length - 1));
        if (!current.numbers.includes(page)) render(page, true);
    }

    function step(direction) {
        if (!current) return;
        const edge = direction < 0 ? current.numbers[0] : current.numbers.at(-1);
        goTo(edge + direction);
    }

    function showStatus(text, canRetry = false) {
        status.hidden = false;
        message.textContent = t(text);
        retry.hidden = !canRetry;
    }

    async function loadAlbum() {
        showStatus('正在加载…');
        try {
            const parts = window.location.pathname.split('/').filter(Boolean);
            const uuid = parts.at(-1) === 'album' ? '' : decodeURIComponent(parts.at(-1) ?? '');
            if (!uuid) {
                showStatus('相册不可用');
                return;
            }
            const response = await fetch(reader.dataset.pagesBase + encodeURIComponent(uuid) + '/pages', { redirect: 'error' });
            if (response.status === 404) {
                showStatus('相册不可用');
                return;
            }
            if (!response.ok) throw new Error('Album request failed');
            const album = await response.json();
            if (typeof album.title === 'string' && album.title.trim()) {
                title.removeAttribute('data-i18n');
                title.textContent = album.title;
                document.title = album.title + ' · BootFS';
            }
            resources = Array.isArray(album.resources) ? album.resources : [];
            if (resources.length === 0) {
                showStatus('暂无图片');
                return;
            }
            status.hidden = true;
            render(0);
        } catch {
            showStatus('加载失败', true);
        }
    }

    previousButton.addEventListener('click', () => step(-1));
    nextButton.addEventListener('click', () => step(1));
    retry.addEventListener('click', loadAlbum);

    settings.addEventListener('click', () => {
        drawer.showModal();
        settings.setAttribute('aria-expanded', 'true');
    });
    drawer.addEventListener('close', () => settings.setAttribute('aria-expanded', 'false'));
    drawer.addEventListener('click', event => {
        if (event.target === drawer) drawer.close();
    });
    mangaSwitch.addEventListener('change', () => {
        try { localStorage.setItem(mangaPreference, mangaSwitch.checked ? '1' : '0'); }
        catch { /* The setting still applies to this reader. */ }
        if (current) render(index);
    });
    originalSwitch.addEventListener('change', () => {
        pageCache.clear();
        if (current) render(index);
    });

    document.addEventListener('keydown', event => {
        if (drawer.open || event.altKey || event.ctrlKey || event.metaKey || !current) return;
        switch (event.key) {
            case 'ArrowUp':
            case 'ArrowLeft': step(-1); break;
            case 'ArrowDown':
            case 'ArrowRight': step(1); break;
            case 'PageUp': goTo(index - 5); break;
            case 'PageDown': goTo(index + 5); break;
            case 'Home': goTo(0); break;
            case 'End': goTo(resources.length - 1); break;
            default: return;
        }
        event.preventDefault();
    });

    let wheelTimer;
    let wheelDistance = 0;
    let wheelTurned = false;
    stage.addEventListener('wheel', event => {
        if (!current || event.ctrlKey || event.metaKey) return;
        event.preventDefault();
        clearTimeout(wheelTimer);
        wheelTimer = setTimeout(() => {
            wheelDistance = 0;
            wheelTurned = false;
        }, 160);
        if (wheelTurned) return;
        const delta = Math.abs(event.deltaX) > Math.abs(event.deltaY) ? event.deltaX : event.deltaY;
        wheelDistance += delta * (event.deltaMode === 0 ? 1 : 16);
        if (Math.abs(wheelDistance) >= 28) {
            wheelTurned = true;
            step(Math.sign(wheelDistance));
        }
    }, { passive: false });

    let touch;
    stage.addEventListener('pointerdown', event => {
        if (event.pointerType !== 'touch' || !event.isPrimary) return;
        touch = { id: event.pointerId, x: event.clientX, y: event.clientY };
    });
    stage.addEventListener('pointercancel', () => { touch = undefined; });
    stage.addEventListener('pointerup', event => {
        if (touch?.id !== event.pointerId) return;
        const dx = event.clientX - touch.x;
        const dy = event.clientY - touch.y;
        touch = undefined;
        if (Math.abs(dx) >= 42 && Math.abs(dx) > Math.abs(dy) * 1.3) step(-Math.sign(dx));
    });

    loadAlbum();
})();
