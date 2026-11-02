// Escape untrusted filenames, tags and metadata at the HTML insertion boundary.
window.BootFSSecurity = Object.freeze({
    html(value) {
        return String(value ?? '').replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
    },
    url(value) {
        try {
            const parsed = new URL(String(value), window.location.href);
            return ['http:', 'https:'].includes(parsed.protocol) && !parsed.username && !parsed.password ? parsed.href : '';
        } catch { return ''; }
    }
});
