// Run against a started platform. Requires jsdom 26.1.0 on NODE_PATH (see VERIFICACION.md).
const assert = require('node:assert/strict');
const { JSDOM } = require('jsdom');
const base = process.argv[2] || 'http://127.0.0.1:8080';
(async () => {
 const script = await (await fetch(base + '/js/app.js')).text();
 const css = await (await fetch(base + '/css/app.css')).text();
 assert.match(css, /\.field-error\{color:#a32121/);
 for (const role of ['TOURIST', 'PROVIDER']) {
  const html = await (await fetch(base + '/register?role=' + role)).text();
  const dom = new JSDOM(html, { url: base + '/register', runScripts: 'outside-only' });
  const { window } = dom;
  window.TextEncoder = TextEncoder;
  window.eval(script);
  const document = window.document, form = document.querySelector('[data-registration]');
  const field = name => form.elements.namedItem(name);
  const emit = (name, type) => field(name).dispatchEvent(new window.Event(type, { bubbles: true }));
  const fill = (name, value) => { field(name).value = value; emit(name, 'input'); };
  const error = name => document.getElementById(name + '-error').textContent;
  const submit = () => form.dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true }));
  assert.ok(form.noValidate); // Custom inline messages replace native popups while JS is active.
  assert.equal(submit(), false);
  assert.equal(document.activeElement.id, 'firstName');
  fill('firstName', 'Ana123'); assert.ok(error('firstName'));
  fill('firstName', 'María-José'); assert.equal(error('firstName'), '');
  fill('lastName', 'O’Neill');
  fill('email', 'sin-arroba'); assert.match(error('email'), /@/);
  assert.equal(field('email').getAttribute('aria-invalid'), 'true');
  fill('email', 'a@example..com'); assert.ok(error('email'));
  fill('email', 'ana@example.test'); assert.equal(error('email'), '');
  fill('document', 'ABC-123');
  fill('password', 'á'.repeat(40)); assert.match(error('password'), /72 bytes/);
  fill('password', 'TestOnly123!');
  fill('passwordConfirmation', 'Different123!'); assert.match(error('passwordConfirmation'), /no coinciden/);
  fill('passwordConfirmation', 'TestOnly123!'); assert.equal(error('passwordConfirmation'), '');
  fill('password', 'Changed123!'); assert.match(error('passwordConfirmation'), /no coinciden/);
  fill('passwordConfirmation', 'Changed123!');
  if (role === 'PROVIDER') {
   const legal = document.querySelector('[data-legal-fields]');
   assert.ok(legal.hidden); assert.ok(field('taxId').disabled); assert.ok(field('legalName').disabled);
   assert.equal(new window.FormData(form).has('taxId'), false);
   field('legalType').value = 'LEGAL'; emit('legalType', 'change');
   assert.equal(legal.hidden, false); assert.equal(field('taxId').disabled, false);
   assert.ok(field('taxId').required); assert.ok(field('legalName').required);
   emit('legalName', 'blur'); assert.ok(error('legalName'));
   fill('legalName', 'Empresa de prueba'); assert.equal(error('legalName'), '');
   fill('taxId', 'abc123'); assert.equal(field('taxId').value, '123'); assert.ok(error('taxId'));
   fill('taxId', '123456789012'); assert.equal(error('taxId'), '');
   field('legalType').value = 'PHYSICAL'; emit('legalType', 'change');
   assert.ok(legal.hidden); assert.ok(field('legalName').disabled); assert.ok(field('document').required);
   assert.equal(new window.FormData(form).has('taxId'), false);
   assert.equal(new window.FormData(form).has('legalName'), false);
   fill('phone', '099ABC123456'); assert.equal(field('phone').value, '099123456'); assert.match(error('phone'), /letras/);
   fill('phone', '123'); assert.ok(error('phone'));
   fill('phone', '+598 (99) 123-456'); assert.equal(error('phone'), '');
   fill('commercialName', 'Empresa de prueba'); fill('contactEmail', 'contacto@example.test');
   field('departmentId').value = '10'; emit('departmentId', 'change');
   field('localityId').value = '10'; emit('localityId', 'change');
   fill('locationDescription', 'Montevideo'); fill('description', 'Datos de prueba');
  }
  assert.equal(submit(), true, role + ': a valid form must not be blocked');
  dom.window.close();
  console.log('PASS: ' + role + ' inline errors, required fields, password matching' + (role === 'PROVIDER' ? ', phone filtering and legal-person fields.' : '.'));
 }
})().catch(error => { console.error(error); process.exitCode = 1; });
