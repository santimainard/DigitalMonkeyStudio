document.querySelectorAll('.location-group').forEach(group=>{
 const dep=group.querySelector('.department'),loc=group.querySelector('.locality');if(!dep||!loc)return;
 const update=()=>{Array.from(loc.options).forEach(o=>{const hidden=o.value&&dep.value&&o.dataset.department!==dep.value;o.hidden=!!hidden;o.disabled=!!hidden;if(hidden&&o.selected)loc.value='';});};dep.addEventListener('change',update);update();
});
document.querySelectorAll('.page-link').forEach(link=>{const url=new URL(location.href);url.searchParams.set('page',link.dataset.page);link.href=url.href;});
document.querySelectorAll('[data-confirm]').forEach(button=>button.addEventListener('click',event=>{if(!confirm(button.dataset.confirm))event.preventDefault();}));
document.querySelectorAll('form.once').forEach(form=>form.addEventListener('submit',()=>form.querySelectorAll('button').forEach(b=>{b.disabled=true;b.textContent='Procesando…';})));
document.querySelectorAll('form[method="get"]').forEach(form=>form.addEventListener('submit',()=>{form.querySelectorAll('input,select').forEach(input=>{if(!input.value)input.disabled=true;});}));
