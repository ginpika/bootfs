'use strict';

const BootFSI18n = (() => {
    const languages = { 'zh-CN': '简体中文', en: 'English' };
    const defaultLanguage = 'zh-CN';
    const storageKey = 'tfs-language';
    const languageParam = 'lang';
    const url = new URL(window.location.href);
    const requested = url.searchParams.get(languageParam);
    let language = Object.hasOwn(languages, requested) ? requested : defaultLanguage;

    try {
        if (language === requested) {
            localStorage.setItem(storageKey, language);
            url.searchParams.delete(languageParam);
            window.history.replaceState(window.history.state, '', url);
        } else {
            const saved = localStorage.getItem(storageKey);
            if (Object.hasOwn(languages, saved)) language = saved;
        }
    } catch { /* Keep the URL preference when browser storage is unavailable. */ }

    const catalog = language === 'en' ? window.BootFSEnglish : null;
    if (!catalog) language = defaultLanguage;
    document.documentElement.lang = language;

    // The original Chinese copy is the message key and the fallback.
    function t(source, ...values) {
        const message = catalog && Object.hasOwn(catalog, source) ? catalog[source] : source;
        if (!values.length) return message;
        return message.replace(/\{(\d+)\}/g, (placeholder, index) =>
            index < values.length ? String(values[index]) : placeholder);
    }

    function html(source, ...values) {
        return window.BootFSSecurity.html(t(source, ...values));
    }

    function duration(seconds) {
        if (!Number.isFinite(seconds) || seconds < 0) return t('0秒');
        const hours = Math.floor(seconds / 3600);
        const minutes = Math.floor((seconds % 3600) / 60);
        const remaining = Math.floor(seconds % 60);
        if (hours > 0) return t('{0}小时{1}分{2}秒', hours, minutes, remaining);
        if (minutes > 0) return t('{0}分{1}秒', minutes, remaining);
        return t('{0}秒', remaining);
    }

    function translatePage() {
        if (catalog) {
            document.querySelectorAll('[data-i18n]').forEach(element => {
                const source = element.textContent.trim();
                if (Object.hasOwn(catalog, source)) element.textContent = catalog[source];
            });
            document.querySelectorAll('[data-i18n-attrs]').forEach(element => {
                for (const attribute of element.dataset.i18nAttrs.split(/\s+/)) {
                    const source = element.getAttribute(attribute);
                    if (Object.hasOwn(catalog, source)) element.setAttribute(attribute, catalog[source]);
                }
            });
        }
        document.querySelectorAll('[data-duration-seconds]').forEach(element => {
            element.textContent = duration(Number(element.dataset.durationSeconds));
        });
    }

    function initializePicker(picker) {
        const button = picker.querySelector('#languageMenuBtn');
        const menu = picker.querySelector('#languageDropdown');
        const options = [...menu.querySelectorAll('[data-language]')];
        const label = button.querySelector('span');
        label.textContent = languages[language];
        label.lang = language;
        button.setAttribute('aria-label', t('语言：{0}', languages[language]));

        function setOpen(open) {
            menu.hidden = !open;
            button.setAttribute('aria-expanded', String(open));
        }

        button.addEventListener('click', () => setOpen(menu.hidden));
        button.addEventListener('keydown', event => {
            if (event.key === 'ArrowDown') {
                event.preventDefault();
                setOpen(true);
                options.find(option => option.dataset.language === language)?.focus();
            }
        });
        options.forEach(option => {
            const selected = option.dataset.language;
            option.setAttribute('aria-pressed', String(language === selected));
            option.addEventListener('click', () => {
                setOpen(false);
                button.focus();
                if (selected === language || !Object.hasOwn(languages, selected)) return;
                // Navigate before saving so canceling an unsaved-changes prompt keeps the current language.
                const next = new URL(window.location.href);
                next.searchParams.set(languageParam, selected);
                window.location.replace(next.href);
            });
        });
        document.addEventListener('click', event => {
            if (!menu.hidden && !picker.contains(event.target)) setOpen(false);
        }, true);
        document.addEventListener('focusin', event => {
            if (!menu.hidden && !picker.contains(event.target)) setOpen(false);
        });
        picker.addEventListener('keydown', event => {
            if (event.key === 'Escape' && !menu.hidden) {
                event.preventDefault();
                event.stopPropagation();
                setOpen(false);
                button.focus();
            }
        });
    }

    document.addEventListener('DOMContentLoaded', () => {
        translatePage();
        const picker = document.querySelector('.language-picker');
        if (picker) initializePicker(picker);
    });

    return Object.freeze({ language, t, html, duration });
})();
