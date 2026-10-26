const ventureFilters = document.querySelectorAll('[data-venture-filter]');
if (ventureFilters.length) {
 const entries = Array.from(document.querySelectorAll('[data-venture-state]'));
 const empty = document.querySelector('[data-filter-empty]');
 ventureFilters.forEach(button => button.addEventListener('click', () => {
  const state = button.dataset.ventureFilter;
  ventureFilters.forEach(filter => filter.setAttribute('aria-pressed', String(filter === button)));
  let count = 0;
  entries.forEach(entry => {
   entry.hidden = state !== 'ALL' && entry.dataset.ventureState !== state;
   if (!entry.hidden) count++;
  });
  empty.hidden = count !== 0 || entries.length === 0;
 }));
}
