'use strict';
(() => {
    const themes = ['light', 'dark', 'sakura', 'starry-night', 'forest', 'ocean', 'sunset', 'lavender'];
    const button = document.getElementById('settingsThemeButton');
    const menu = document.getElementById('settingsThemeMenu');
    const options = [...menu.querySelectorAll('[data-settings-theme]')];
    function applyTheme(theme) {
        const selected = themes.includes(theme) ? theme : 'light';
        document.documentElement.classList.remove(...themes.map(name => 'theme-' + name));
        document.documentElement.classList.add('theme-' + selected);
        options.forEach(option => option.setAttribute('aria-pressed', String(option.dataset.settingsTheme === selected)));
    }
    function closeMenu() { menu.hidden = true; button.setAttribute('aria-expanded', 'false'); }
    button.addEventListener('click', () => {
        menu.hidden = !menu.hidden;
        button.setAttribute('aria-expanded', String(!menu.hidden));
    });
    options.forEach(option => option.addEventListener('click', () => {
        applyTheme(option.dataset.settingsTheme);
        try { localStorage.setItem('tfs-theme', option.dataset.settingsTheme); } catch (_) { /* Theme still works without storage. */ }
        closeMenu(); button.focus();
    }));
    document.addEventListener('click', event => { if (!menu.contains(event.target) && !button.contains(event.target)) closeMenu(); });
    document.addEventListener('keydown', event => {
        if (event.key === 'Escape' && !menu.hidden) { closeMenu(); button.focus(); }
    });
    window.addEventListener('storage', event => { if (event.key === 'tfs-theme') applyTheme(event.newValue); });
    try { applyTheme(localStorage.getItem('tfs-theme')); } catch (_) { applyTheme('light'); }

    const scroller = document.getElementById('settingsScroller');
    const links = [...document.querySelectorAll('.settings-nav-link')];
    const sections = links.map(link => document.getElementById(link.hash.slice(1)));
    function highlightSection() {
        let active = sections[0];
        const top = scroller.getBoundingClientRect().top + 64;
        for (const section of sections) if (section.getBoundingClientRect().top <= top) active = section;
        if (scroller.scrollTop > 0 && scroller.scrollHeight - scroller.clientHeight - scroller.scrollTop < 8) active = sections.at(-1);
        links.forEach(link => {
            if (link.hash === '#' + active.id) link.setAttribute('aria-current', 'location');
            else link.removeAttribute('aria-current');
        });
    }
    let frame;
    scroller.addEventListener('scroll', () => {
        if (!frame) frame = requestAnimationFrame(() => { highlightSection(); frame = undefined; });
    }, {passive: true});
    window.addEventListener('resize', highlightSection);
    highlightSection();
})();
