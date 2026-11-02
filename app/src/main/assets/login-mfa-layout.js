(function () {
    'use strict';
    if (location.origin !== 'https://authserver.nuist.edu.cn' ||
        location.pathname !== '/authserver/reAuthCheck/reAuthLoginView.do') return;

    // The school's mobile MFA uses 100vh for both these containers. On the tested
    // WebView those CSS values resolve to zero although innerHeight is positive.
    // Only repair the confirmed pair of collapsed containers, never the form.
    var repaired = false;
    var scheduled = false;
    function repair() {
        scheduled = false;
        var viewport = window.innerHeight;
        if (!Number.isFinite(viewport) || viewport <= 88) return;
        var center = document.querySelector('.mobile-page-center-box');
        var content = document.querySelector('.mobile-page-content-box');
        if (!center || !content) return;
        if (!repaired) {
            var centerStyle = getComputedStyle(center);
            var contentStyle = getComputedStyle(content);
            if (centerStyle.display === 'none' || contentStyle.display === 'none' ||
                parseFloat(centerStyle.height) !== 0 || parseFloat(contentStyle.maxHeight) !== 0) return;
        }
        var available = Math.round(viewport - 44) + 'px';
        center.style.setProperty('height', available, 'important');
        center.style.setProperty('overflow-y', 'auto', 'important');
        content.style.setProperty('max-height', available, 'important');
        if (!repaired) console.info('NUIST_MFA_LAYOUT_REPAIRED');
        repaired = true;
    }
    function schedule() {
        if (!scheduled) { scheduled = true; requestAnimationFrame(repair); }
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', schedule, {once: true});
    else schedule();
    window.addEventListener('load', schedule, {once: true});
    window.addEventListener('resize', schedule);
    if (window.visualViewport) window.visualViewport.addEventListener('resize', schedule);
})();
