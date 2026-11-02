const test = require('node:test');
const assert = require('node:assert/strict');
const {safeUrl, errorKind} = require('../inspect-login.cjs');

test('USB trace drops query, fragment, userinfo and opaque path IDs', () => {
    const result = safeUrl('https://u:SECRET@authserver.nuist.edu.cn/authserver/execution/e12s3?ticket=TOKEN#CODE');
    assert.equal(result, 'https://authserver.nuist.edu.cn/authserver/execution/<id>');
    for (const token of ['SECRET', 'TOKEN', 'CODE', 'e12s3']) assert.equal(result.includes(token), false);
});
test('public static script paths remain useful without parameters', () => {
    assert.equal(safeUrl('https://authserver.nuist.edu.cn/authserver/NuistT6Theme/static/mobile/js/login.js?v=SECRET'),
        'https://authserver.nuist.edu.cn/authserver/NuistT6Theme/static/mobile/js/login.js');
});
test('foreign origins and non-HTTP URLs never reveal a path or content', () => {
    assert.equal(safeUrl('https://foreign.example/private?token=SECRET'), 'https://foreign.example');
    assert.equal(safeUrl('data:text/html,SECRET'), 'data:');
    assert.equal(safeUrl('blob:https://authserver.nuist.edu.cn/SECRET'), 'blob:');
});
test('console errors are reduced to fixed categories', () => {
    assert.equal(errorKind('TypeError: SECRET'), 'TypeError');
    assert.equal(errorKind('SECRET'), 'ScriptError');
});
