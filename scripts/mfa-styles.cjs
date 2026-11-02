const {safeUrl} = require('./inspect-login.cjs');
async function run() {
    const targets = await (await fetch('http://127.0.0.1:9223/json')).json();
    const target = targets.find(t => { try { return new URL(t.url).hostname === 'authserver.nuist.edu.cn'; } catch { return false; } });
    if (!target) throw Error();
    const socket = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
    let id = 0; const pending = new Map(), sheets = new Map();
    socket.addEventListener('message', event => {
        const value = JSON.parse(event.data);
        if (value.method === 'CSS.styleSheetAdded') sheets.set(value.params.header.styleSheetId, safeUrl(value.params.header.sourceURL));
        if (value.id && pending.has(value.id)) {pending.get(value.id)(value.result);pending.delete(value.id);}
    });
    const command = (method, params={}) => new Promise(resolve=>{const next=++id;pending.set(next,resolve);socket.send(JSON.stringify({id:next,method,params}));});
    await command('DOM.enable'); await command('CSS.enable');
    const {root} = await command('DOM.getDocument',{depth:0});
    const selectors = ['.mobile-page-center-box','.mobile-page-content-box','.mobile-page-white-box'];
    for (const selector of selectors) {
        const {nodeId} = await command('DOM.querySelector',{nodeId:root.nodeId,selector});
        if (!nodeId) continue;
        const matched = await command('CSS.getMatchedStylesForNode',{nodeId});
        for (const {rule} of matched.matchedCSSRules || []) {
            const properties = rule.style.cssProperties.filter(p=>['height','min-height','max-height','overflow','overflow-y','display'].includes(p.name)).map(p=>({name:p.name,value:/^[a-zA-Z0-9() .%*+,\/-]{1,100}$/.test(p.value)?p.value:'other'}));
            if (properties.length) console.log(JSON.stringify({selector,source:sheets.get(rule.styleSheetId),properties}));
        }
    }
    socket.close();
}
run().catch(()=>{console.error('Layout rule inspection unavailable.');process.exitCode=1;});
