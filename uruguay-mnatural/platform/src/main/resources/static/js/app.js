document.querySelectorAll('.location-group').forEach(group=>{
 const dep=group.querySelector('.department'),loc=group.querySelector('.locality');if(!dep||!loc)return;
 const update=()=>{Array.from(loc.options).forEach(o=>{const hidden=o.value&&dep.value&&o.dataset.department!==dep.value;o.hidden=!!hidden;o.disabled=!!hidden;if(hidden&&o.selected)loc.value='';});};dep.addEventListener('change',update);update();
});
document.querySelectorAll('.page-link').forEach(link=>{const url=new URL(location.href);url.searchParams.set('page',link.dataset.page);link.href=url.href;});
document.querySelectorAll('[data-confirm]').forEach(button=>button.addEventListener('click',event=>{if(!confirm(button.dataset.confirm))event.preventDefault();}));
document.querySelectorAll('form.once').forEach(form=>form.addEventListener('submit',()=>form.querySelectorAll('button').forEach(b=>{b.disabled=true;b.textContent='Procesando…';})));
document.querySelectorAll('form[method="get"]').forEach(form=>form.addEventListener('submit',()=>{form.querySelectorAll('input,select').forEach(input=>{if(!input.value)input.disabled=true;});}));
const registration = document.querySelector('form[data-registration]');
if (registration) {
 const fields = Array.from(registration.querySelectorAll('input:not([type="hidden"]), select, textarea'));
 const control = name => registration.elements.namedItem(name);
 const touched = new Set(fields.filter(field => field.getAttribute('aria-invalid') === 'true'));
 const message = (field, text) => {
  field.setCustomValidity(text);
  field.setAttribute('aria-invalid', text ? 'true' : 'false');
  document.getElementById(field.id + '-error').textContent = text;
  return !text;
 };
 const validate = field => {
  if (field.disabled) return message(field, '');
  const value = field.type === 'password' ? field.value : field.value.trim();
  let error = '';
  if (field.required && !value) error = field.tagName === 'SELECT' ? 'Seleccioná una opción.' : 'Completá este campo.';
  else if (value) {
   if (field.name === 'firstName' || field.name === 'lastName') {
    if (!/^[\p{L}\p{M}][\p{L}\p{M} .'’\-]*$/u.test(value)) error = 'Usá letras, espacios, apóstrofes o guiones, sin números.';
   } else if (field.type === 'email') {
    if (!value.includes('@')) error = 'El correo debe incluir @, por ejemplo nombre@dominio.com.';
    else if (!/^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$/.test(value) || field.validity.typeMismatch) error = 'Ingresá un correo válido, como nombre@dominio.com.';
   } else if (field.name === 'phone') {
    const digits = value.replace(/[^0-9]/g, '').length;
    if (!/^\+?[0-9 ()-]+$/.test(value) || digits < 6 || digits > 15) error = 'Ingresá entre 6 y 15 dígitos, sin letras. Se permiten + al inicio, espacios, paréntesis y guiones.';
   } else if (field.name === 'taxId' && !/^[0-9]{12}$/.test(value)) error = 'El RUT debe tener exactamente 12 dígitos.';
   else if (field.name === 'password' && (value.length < 8 || new TextEncoder().encode(value).length > 72)) error = 'Usá al menos 8 caracteres y hasta 72 bytes UTF-8.';
   else if (field.name === 'passwordConfirmation' && value !== control('password').value) error = 'Las contraseñas no coinciden.';
   if (!error && field.maxLength > 0 && value.length > field.maxLength) error = `Usá hasta ${field.maxLength} caracteres.`;
  }
  return message(field, error);
 };
 const legalType = control('legalType');
 const updateLegalFields = () => {
  if (!legalType) return;
  const legal = legalType.value === 'LEGAL';
  registration.querySelector('[data-legal-fields]').hidden = !legal;
  ['legalName', 'taxId'].forEach(name => {
   const field = control(name);
   field.disabled = !legal;
   field.required = legal;
   if (!legal) { message(field, ''); touched.delete(field); }
   else if (touched.has(field)) validate(field);
  });
  control('document').required = !legal;
  if (touched.has(control('document'))) validate(control('document'));
 };
 if (legalType) { legalType.addEventListener('change', updateLegalFields); updateLegalFields(); }
 fields.forEach(field => {
  field.addEventListener('input', () => {
   touched.add(field);
   let filtered = false;
   if (field.name === 'phone' || field.name === 'taxId') {
    const clean = field.name === 'phone' ? field.value.replace(/[^0-9+ ()-]/g, '') : field.value.replace(/[^0-9]/g, '').slice(0, 12);
    filtered = clean !== field.value;
    field.value = clean;
   }
   validate(field);
   if (filtered) message(field, field.name === 'phone' ? 'El teléfono no admite letras ni otros símbolos.' : 'El RUT solo admite números.');
   if (field.name === 'password' && (control('passwordConfirmation').value || touched.has(control('passwordConfirmation')))) validate(control('passwordConfirmation'));
  });
  field.addEventListener('blur', () => { touched.add(field); validate(field); });
  field.addEventListener('change', () => { touched.add(field); validate(field); });
 });
 // Keep native validation as the fallback when JavaScript is unavailable.
 registration.noValidate = true;
 registration.addEventListener('submit', event => {
  let firstInvalid;
  fields.forEach(field => { touched.add(field); if (!validate(field) && !firstInvalid) firstInvalid = field; });
  if (firstInvalid) { event.preventDefault(); firstInvalid.focus(); }
 });
}
