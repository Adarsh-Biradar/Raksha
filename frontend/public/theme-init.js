// Apply preference before first paint; compatible with the self-only script policy.
(function(){
 var mode;try{mode=localStorage.getItem('raksha-theme');}catch(e){}
 if(mode!=='light'&&mode!=='dark')mode=window.matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light';
 document.documentElement.dataset.theme=mode;
 document.querySelector('meta[name="theme-color"]').setAttribute('content',mode==='dark'?'#0d1713':'#f7f9f8');
})();