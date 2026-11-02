(function () {
    'use strict';
    if (location.origin !== 'https://authserver.nuist.edu.cn') return;

    // Run before school scripts. Upgrade legacy subresources; never permit HTTP fallback.
    // The observer handles document-start injection before <head> exists.
    var observer;
    function installPolicy() {
        if (!document.head) return false;
        var meta = document.createElement('meta');
        meta.httpEquiv = 'Content-Security-Policy';
        meta.content = 'upgrade-insecure-requests';
        document.head.prepend(meta);
        if (observer) observer.disconnect();
        console.info('NUIST_HTTPS_POLICY_READY');
        return true;
    }
    if (!installPolicy()) {
        observer = new MutationObserver(installPolicy);
        observer.observe(document, {childList: true, subtree: true});
    }

    // Also cover an immediate XHR/fetch before <head> is parsed. Only known HTTPS
    // school services on their default ports are rewritten. No bodies, headers,
    // form fields or credentials are read. The browser performs all networking.
    function secureSchoolUrl(value) {
        if (typeof value !== 'string' && !(value instanceof URL)) return value;
        try {
            var url = new URL(value, location.href);
            if (url.protocol !== 'http:' || url.username || url.password || url.port) return value;
            if (['authserver.nuist.edu.cn', 'jwxt.nuist.edu.cn', 'i.nuist.edu.cn'].indexOf(url.hostname) < 0) return value;
            url.protocol = 'https:';
            return url.href;
        } catch (_) { return value; }
    }
    var originalOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function () {
        var args = Array.prototype.slice.call(arguments);
        if (args.length >= 2) {
            var upgraded = secureSchoolUrl(args[1]);
            if (upgraded !== args[1]) console.info('NUIST_HTTPS_UPGRADE_XHR');
            args[1] = upgraded;
        }
        return Reflect.apply(originalOpen, this, args);
    };
    if (typeof window.fetch === 'function') {
        var originalFetch = window.fetch;
        window.fetch = function () {
            var args = Array.prototype.slice.call(arguments);
            if (args.length) {
                // Leave Request/stream objects intact; the CSP policy handles those.
                var upgraded = secureSchoolUrl(args[0]);
                if (upgraded !== args[0]) console.info('NUIST_HTTPS_UPGRADE_FETCH');
                args[0] = upgraded;
            }
            return Reflect.apply(originalFetch, this, args);
        };
    }
})();
