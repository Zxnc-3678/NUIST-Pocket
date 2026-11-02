// Read only fixed MFA state flags and layout geometry. Never read form values,
// page text/HTML, cookies, storage or the full reAuthParams object.
async function run() {
    const targets = await (await fetch('http://127.0.0.1:9223/json')).json();
    const target = targets.find(t => { try { return new URL(t.url).hostname === 'authserver.nuist.edu.cn'; } catch { return false; } });
    if (!target) throw Error();
    const socket = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, {once:true}); socket.addEventListener('error', reject, {once:true}); });
    const action = process.argv[2] || 'inspect';
    const layoutExpression = `(function(){
      var e=document.querySelector('.mobile-page-center-box');
      var before=e?e.getBoundingClientRect().height:null;
      ${action === 'try-layout' ? "if(e){e.style.setProperty('height',Math.max(200,innerHeight-44)+'px','important');e.style.setProperty('overflow-y','auto','important');} var content=document.querySelector('.mobile-page-content-box');if(content)content.style.setProperty('max-height',Math.max(200,innerHeight-44)+'px','important');" : ''}
      var s=e?getComputedStyle(e):null;
      var v=document.querySelector('meta[name=viewport]');
      return JSON.stringify({innerHeight:innerHeight,clientHeight:document.documentElement.clientHeight,
        visualHeight:visualViewport?visualViewport.height:null,before:before,after:e?e.getBoundingClientRect().height:null,
        computed:s?{height:s.height,maxHeight:s.maxHeight,overflowY:s.overflowY}:null,
        boxes:['.mobile-page-center-box','.mobile-page-content-box','.mobile-page-white-box'].map(function(q){var x=document.querySelector(q);if(!x)return{selector:q};var c=getComputedStyle(x),r=x.getBoundingClientRect();return{selector:q,height:r.height,cssHeight:c.height,maxHeight:c.maxHeight,minHeight:c.minHeight,overflow:c.overflowY};}),
        viewportFields:v?v.content.split(',').map(function(p){var a=p.trim().split('=');return {key:['width','height','initial-scale','maximum-scale','minimum-scale','user-scalable'].indexOf(a[0])>=0?a[0]:'other',value:/^(device-width|device-height|yes|no|[0-9.]+)$/.test(a[1])?a[1]:'other'};}):[]});
    })()`;
    const expression = action === 'layout' || action === 'try-layout' ? layoutExpression : `(function(){
      var p = window.reAuthParams || {};
      var type = String(p.reAuthType || '');
      var ids = ['reAuthDec','userNameDiv','pwdType','dynamicType','questionDiv','combinedQqDiv','combinedWxDiv','otpDiv','otpDivBind','faceAuthShowDiv','casLoginForm'];
      return JSON.stringify({ready:document.readyState,initView:typeof window.initView,
        noMethod:p.noneAuthType === '1',type:/^\\d{1,2}$/.test(type)?type:'other-or-empty',
        ipAnomalyPositive:Number(p.ipAnomaly)>0,jqueryReady:!!(window.jQuery&&window.jQuery.isReady),
        viewport:{width:innerWidth,height:innerHeight},
        elements:ids.map(function(id){var e=document.getElementById(id);if(!e)return{id:id,exists:false};
          var chain=[];for(var i=0;e&&i<6;i++,e=e.parentElement){var c=getComputedStyle(e),r=e.getBoundingClientRect();
            chain.push({tag:e.tagName,display:c.display,visibility:c.visibility,opacity:c.opacity,
              top:Math.round(r.top),left:Math.round(r.left),width:Math.round(r.width),height:Math.round(r.height),transform:c.transform==='none'?'none':'present'});}
          return{id:id,exists:true,ancestors:chain};})});
    })()`;
    const response = await new Promise((resolve, reject) => {
        const timer = setTimeout(()=>reject(Error()),8000);
        socket.addEventListener('message', event=>{const value=JSON.parse(event.data);if(value.id===1){clearTimeout(timer);resolve(value);}});
        socket.send(JSON.stringify({id:1,method:'Runtime.evaluate',params:{expression,returnByValue:true}}));
    });
    const data = JSON.parse(response.result?.result?.value || '{}');
    console.log(JSON.stringify(data));
    socket.close();
}
run().catch(()=>{console.error('MFA structural probe unavailable.');process.exitCode=1;});
