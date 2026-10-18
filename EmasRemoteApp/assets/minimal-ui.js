(function(){
  'use strict';
  function reveal(){
    if(!('IntersectionObserver' in window)||window.matchMedia('(prefers-reduced-motion: reduce)').matches)return;
    const observer=new IntersectionObserver(entries=>{
      for(const entry of entries)if(entry.isIntersecting){entry.target.classList.add('is-visible');observer.unobserve(entry.target);}
    },{threshold:0.05});
    document.querySelectorAll('.card-section').forEach(el=>{el.classList.add('ui-reveal');observer.observe(el);});
  }
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',reveal);else reveal();
})();
