const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/login-mfa-layout.js'), 'utf8');

function boot({height = '0px', maxHeight = '0px', viewport = 714, ready = 'loading', pathname = '/authserver/reAuthCheck/reAuthLoginView.do', origin = 'https://authserver.nuist.edu.cn', hidden = false, missing = false} = {}) {
    const listeners = {}, documentListeners = {}, viewportListeners = {}, changes = [], logs = [], queue = [];
    const center = {style: {setProperty: (...args) => changes.push(['center', ...args])}};
    const content = {style: {setProperty: (...args) => changes.push(['content', ...args])}};
    const window = {innerHeight: viewport, addEventListener: (name, callback) => {listeners[name] = callback;},
        visualViewport: {addEventListener: (name, callback) => {viewportListeners[name] = callback;}}};
    const document = {readyState: ready, addEventListener: (name, callback) => {documentListeners[name] = callback;},
        querySelector: name => missing ? null : name === '.mobile-page-center-box' ? center : content};
    // Form, authentication state, storage and cookies must not be inspected.
    Object.defineProperty(document, 'cookie', {get() {throw Error('Cookie access');}});
    Object.defineProperty(document, 'body', {get() {throw Error('Form/body access');}});
    const sandbox = {window, document, location: {origin, pathname}, getComputedStyle: element => ({display: hidden ? 'none' : 'block',height:element===center?height:'540px',maxHeight:element===content?maxHeight:'none'}),
        requestAnimationFrame: callback => queue.push(callback), console: {info: line => logs.push(line)}};
    vm.runInNewContext(source, sandbox, {timeout:1000});
    const flush = () => {while(queue.length) queue.shift()();};
    return {changes, logs, window, queue, listeners, viewportListeners, flush, ready() {documentListeners.DOMContentLoaded?.();flush();}};
}

test('repairs both confirmed collapsed containers after DOM readiness', () => {
    const env = boot();
    assert.equal(env.changes.length, 0);
    env.ready();
    assert.deepEqual(env.changes, [['center','height','670px','important'],['center','overflow-y','auto','important'],['content','max-height','670px','important']]);
    assert.deepEqual(env.logs, ['NUIST_MFA_LAYOUT_REPAIRED']);
});
test('normally sized school pages are left intact', () => {
    const env = boot({height:'670px',maxHeight:'670px'});env.ready();
    assert.equal(env.changes.length,0);assert.equal(env.logs.length,0);
});
test('unrelated pages and foreign or HTTP origins are untouched', () => {
    for (const options of [{pathname:'/authserver/login'},{origin:'http://authserver.nuist.edu.cn'},{origin:'https://authserver.nuist.edu.cn.evil.example'}]) {
        const env = boot(options);env.ready();assert.equal(env.changes.length,0);assert.equal(Object.keys(env.listeners).length,0);
    }
});
test('hidden or missing containers are not forced visible', () => {
    for (const options of [{hidden:true},{missing:true}]) {const env=boot(options);env.ready();assert.equal(env.changes.length,0);}
});
test('resize follows keyboard/orientation changes and coalesces events', () => {
    const env=boot();env.ready();env.changes.length=0;env.window.innerHeight=500;
    env.listeners.resize();env.viewportListeners.resize();assert.equal(env.queue.length,1);env.flush();
    assert.equal(env.changes[0][2],'456px');assert.equal(env.changes[2][2],'456px');assert.equal(env.logs.length,1);
});
test('zero initial viewport waits for a later real layout', () => {
    const env=boot({viewport:0});env.ready();assert.equal(env.changes.length,0);
    env.window.innerHeight=714;env.listeners.resize();env.flush();assert.equal(env.changes[0][2],'670px');
});
test('already loaded MFA documents can be repaired without another DOM event', () => {
    const env=boot({ready:'complete'});env.flush();assert.equal(env.changes.length,3);
});
