// Local USB WebView inspection. Never print request headers, bodies, query values,
// page text, cookies, storage, console payloads, or complete protocol messages.
const path = require('node:path');
const {pathToFileURL} = require('node:url');

function safeUrl(value) {
    try {
        const url = new URL(value);
        if (!['https:', 'http:'].includes(url.protocol)) return url.protocol;
        const origin = url.protocol + '//' + url.hostname + (url.port ? ':' + url.port : '');
        if (!['authserver.nuist.edu.cn', 'jwxt.nuist.edu.cn', 'i.nuist.edu.cn'].includes(url.hostname)) return origin;
        let hideNext = false;
        const parts = url.pathname.split('/').slice(1, 12).map(segment => {
            const hidden = hideNext;
            hideNext = /^(token|ticket|password|session|execution|code|username)$/i.test(segment);
            if (!segment) return '';
            if (hidden) return '<id>';
            if (segment === 'NuistT6Theme') return segment;
            if (!/^[a-zA-Z_.-]{1,64}$/.test(segment) || /^[a-fA-F]{12,}$/.test(segment)) return '<id>';
            return segment;
        });
        return origin + '/' + parts.join('/');
    } catch { return '<unavailable>'; }
}

function errorKind(value) {
    return ['TypeError', 'ReferenceError', 'SyntaxError', 'SecurityError', 'RangeError', 'NetworkError']
        .find(kind => typeof value === 'string' && value.includes(kind)) || 'ScriptError';
}

async function main() {
    const port = Number(process.argv[2] || 9223);
    const seconds = Math.min(60, Math.max(5, Number(process.argv[3] || 45)));
    const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
    const target = targets.find(item => {
        try { return item.type === 'page' && new URL(item.url).hostname === 'authserver.nuist.edu.cn'; } catch { return false; }
    });
    if (!target) { console.log('No school authentication WebView is open.'); return; }
    const endpoint = new URL(target.webSocketDebuggerUrl);
    if (!['127.0.0.1', 'localhost'].includes(endpoint.hostname) || Number(endpoint.port) !== port) throw Error('Unexpected diagnostic endpoint');
    const socket = new WebSocket(endpoint.href);
    await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, {once: true}); socket.addEventListener('error', reject, {once: true}); });
    let sequence = 0;
    const pending = new Map(), requests = new Map();
    const emit = value => console.log(JSON.stringify(value));
    function command(method, params = {}) {
        const id = ++sequence;
        return new Promise((resolve, reject) => {
            const timeout = setTimeout(() => { pending.delete(id); reject(Error('Diagnostic command timed out')); }, 8000);
            pending.set(id, {resolve, reject, timeout});
            socket.send(JSON.stringify({id, method, params}));
        });
    }
    socket.addEventListener('message', event => {
        let message;
        try { message = JSON.parse(event.data); } catch { return; }
        if (message.id) {
            const entry = pending.get(message.id);
            if (entry) {
                pending.delete(message.id); clearTimeout(entry.timeout);
                if (message.error) entry.reject(Error('Diagnostic command unavailable'));
                else entry.resolve(message.result);
            }
            return;
        }
        const p = message.params || {};
        if (message.method === 'Network.requestWillBeSent') {
            const url = safeUrl(p.request?.url);
            requests.set(p.requestId, {url, type: p.type});
            if (requests.size > 500) requests.delete(requests.keys().next().value);
            emit({event: 'request', type: p.type, method: ['GET', 'POST', 'OPTIONS'].includes(p.request?.method) ? p.request.method : 'other', url});
        } else if (message.method === 'Network.responseReceived') {
            emit({event: 'response', status: p.response?.status, type: p.type, url: safeUrl(p.response?.url)});
        } else if (message.method === 'Network.loadingFailed') {
            emit({event: 'failed', ...requests.get(p.requestId), reason: /^net::ERR_[A-Z_]+$/.test(p.errorText) ? p.errorText : 'load failure', blocked: p.blockedReason});
        } else if (message.method === 'Runtime.exceptionThrown') {
            const detail = p.exceptionDetails || {};
            const frames = detail.stackTrace?.callFrames || [];
            emit({event: 'exception', kind: errorKind(detail.exception?.className), source: safeUrl(detail.url || frames[0]?.url), line: detail.lineNumber});
            for (const frame of frames.slice(0, 4)) emit({event: 'stack', source: safeUrl(frame.url), line: frame.lineNumber});
        } else if (message.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(p.type)) {
            emit({event: 'console', level: p.type, kind: errorKind(p.args?.[0]?.value), source: safeUrl(p.stackTrace?.callFrames?.[0]?.url), line: p.stackTrace?.callFrames?.[0]?.lineNumber});
        }
    });
    await command('Runtime.enable');
    await command('Network.enable', {maxPostDataSize: 0, maxTotalBufferSize: 0, maxResourceBufferSize: 0});
    const snapshot = await command('Runtime.evaluate', {expression: `JSON.stringify({ready:document.readyState,jquery:typeof window.jQuery,scripts:Array.from(document.scripts,function(s){return s.src;}).filter(Boolean)})`, returnByValue: true});
    try {
        const data = JSON.parse(snapshot.result?.value || '{}');
        emit({event: 'snapshot', ready: ['loading', 'interactive', 'complete'].includes(data.ready) ? data.ready : 'unknown', jquery: data.jquery === 'function'});
        for (const script of (data.scripts || []).slice(0, 30)) emit({event: 'script', source: safeUrl(script)});
    } catch { emit({event: 'snapshot unavailable'}); }
    emit({event: 'listening', seconds});
    await new Promise(resolve => setTimeout(resolve, seconds * 1000));
    socket.close();
}

module.exports = {safeUrl, errorKind};
if (require.main === module) main().catch(() => { console.error('USB diagnostic connection unavailable. No raw protocol data printed.'); process.exitCode = 1; });
