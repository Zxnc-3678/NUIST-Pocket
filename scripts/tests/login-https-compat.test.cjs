const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/login-https-compat.js'), 'utf8');
function boot({headReady = false, origin = 'https://authserver.nuist.edu.cn', hasFetch = true} = {}) {
    const calls = [], logs = [], policies = [];
    let callback, disconnected = false;
    const head = {prepend: meta => policies.push(meta)};
    const document = {head: headReady ? head : null, createElement: tag => ({tag})};
    class XHR { open(...args) { calls.push({receiver: this, args}); return 73; } }
    class Observer {
        constructor(cb) { callback = cb; }
        observe(target, options) { assert.equal(target, document); assert.equal(options.subtree, true); }
        disconnect() { disconnected = true; }
    }
    const window = {fetch: hasFetch ? function (...args) { calls.push({receiver: this, args}); return 'fetch-result'; } : undefined};
    const sandbox = {window, document, URL, XMLHttpRequest: XHR, MutationObserver: Observer,
        location: {origin, href: origin + '/authserver/login'}, console: {info: msg => logs.push(msg)}};
    // These throw if the compatibility layer ever inspects credentials or page content.
    Object.defineProperty(document, 'cookie', {get() { throw Error('Cookie access'); }});
    Object.defineProperty(document, 'body', {get() { throw Error('DOM body access'); }});
    Object.defineProperty(sandbox, 'localStorage', {get() { throw Error('Storage access'); }});
    vm.runInNewContext(source, sandbox, {timeout: 1000});
    return {XHR, window, calls, logs, policies, ready() { document.head = head; callback?.(); },
        get disconnected() { return disconnected; }};
}

test('XHR is upgraded before the DOM exists; arguments, receiver and return value survive', () => {
    const env = boot();
    const xhr = new env.XHR();
    assert.equal(xhr.open('POST', 'http://authserver.nuist.edu.cn/authserver/example?token=SENTINEL', true, 'u', 'p'), 73);
    assert.equal(env.calls[0].receiver, xhr);
    assert.deepEqual(env.calls[0].args, ['POST', 'https://authserver.nuist.edu.cn/authserver/example?token=SENTINEL', true, 'u', 'p']);
    assert.equal(env.policies.length, 0);
    env.ready();
    assert.equal(env.policies[0].httpEquiv, 'Content-Security-Policy');
    assert.equal(env.policies[0].content, 'upgrade-insecure-requests');
    assert.equal(env.disconnected, true);
});

test('fetch upgrades its URL and forwards the original options without inspecting a body', () => {
    const env = boot({headReady: true});
    const options = Object.defineProperty({}, 'body', {get() { throw Error('Body inspected'); }});
    assert.equal(env.window.fetch('http://authserver.nuist.edu.cn/api?ticket=SENTINEL', options), 'fetch-result');
    assert.equal(env.calls[0].args[0], 'https://authserver.nuist.edu.cn/api?ticket=SENTINEL');
    assert.equal(env.calls[0].args[1], options);
    assert.equal(env.calls[0].receiver, env.window);
    assert.equal(env.policies.length, 1);
});

test('foreign hosts, spoofed suffixes, userinfo and custom ports are never rewritten', () => {
    const env = boot();
    for (const url of ['http://evil.example/x', 'http://authserver.nuist.edu.cn.evil.example/x',
        'http://u:p@authserver.nuist.edu.cn/x', 'http://authserver.nuist.edu.cn:8080/x',
        'https://authserver.nuist.edu.cn/x', '/relative', 'data:text/plain,example']) {
        new env.XHR().open('GET', url);
        assert.equal(env.calls.at(-1).args[1], url);
    }
});

test('explicit default port and URL objects become the standard HTTPS endpoint', () => {
    const env = boot();
    env.window.fetch(new URL('http://jwxt.nuist.edu.cn:80/example?a=%2B'));
    assert.equal(env.calls[0].args[0], 'https://jwxt.nuist.edu.cn/example?a=%2B');
});

test('Request-like objects and stream bodies remain opaque and unchanged', () => {
    const env = boot();
    const request = Object.defineProperty({}, 'url', {get() { throw Error('Request inspected'); }});
    env.window.fetch(request);
    assert.equal(env.calls[0].args[0], request);
});

test('script has no effects outside the exact HTTPS authentication origin', () => {
    for (const origin of ['http://authserver.nuist.edu.cn', 'https://authserver.nuist.edu.cn.evil.example', 'https://jwxt.nuist.edu.cn']) {
        const env = boot({origin, headReady: true});
        new env.XHR().open('GET', 'http://authserver.nuist.edu.cn/x');
        assert.equal(env.calls[0].args[1], 'http://authserver.nuist.edu.cn/x');
        assert.equal(env.policies.length, 0);
        assert.equal(env.logs.length, 0);
    }
});

test('diagnostic markers contain no URL, query, body or request credentials', () => {
    const env = boot({headReady: true});
    new env.XHR().open('GET', 'http://authserver.nuist.edu.cn/x?ticket=SENTINEL');
    env.window.fetch('http://i.nuist.edu.cn/x?code=SENTINEL');
    assert.deepEqual(env.logs, ['NUIST_HTTPS_POLICY_READY', 'NUIST_HTTPS_UPGRADE_XHR', 'NUIST_HTTPS_UPGRADE_FETCH']);
});

test('missing fetch does not prevent XHR or CSP support', () => {
    const env = boot({hasFetch: false, headReady: true});
    new env.XHR().open('GET', 'http://authserver.nuist.edu.cn/x');
    assert.equal(env.calls[0].args[1], 'https://authserver.nuist.edu.cn/x');
    assert.equal(env.window.fetch, undefined);
});
