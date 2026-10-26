// Local demo workflow only. Reads development credentials without printing them.
// Requires Playwright and Chromium; see docs/VERIFICACION.md.
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '..');
const file = path.join(root, '.env');
const local = fs.existsSync(file) ? Object.fromEntries(fs.readFileSync(file, 'utf8').split(/\r?\n/).filter(l => l && !l.startsWith('#')).map(l => { const i = l.indexOf('='); return [l.slice(0, i), l.slice(i + 1)]; })) : {};
const config = { ...local, ...process.env };
const base = process.env.WORKSPACE_BASE_URL || 'http://127.0.0.1:8080';
const simulator = process.env.WORKSPACE_SIMULATOR_URL || 'http://127.0.0.1:8081';
const output = process.env.WORKSPACE_SCREENSHOTS || '/tmp/um-workspaces';
fs.mkdirSync(output, { recursive: true });
const csrf = html => { const m = html.match(/name="_csrf"[^>]*value="([^"]+)"/); assert.ok(m, 'CSRF token present'); return m[1]; };
const runtimePassword = crypto.randomBytes(18).toString('hex');
(async () => {
 assert.ok(['127.0.0.1', 'localhost'].includes(new URL(base).hostname) && ['127.0.0.1', 'localhost'].includes(new URL(simulator).hostname), 'Run only against local development services.');
 assert.ok(config.DEMO_PASSWORD && config.ADMIN_PASSWORD && config.PAYMENT_API_KEY, 'Local demo credentials are required.');
 const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
 const contexts = [];
 let tourist, booking;
 async function session(email, password) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'es-UY' });
  contexts.push(ctx);
  const page = await ctx.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.goto(base + '/login');
  await page.locator('[name=username]').fill(email); await page.locator('[name=password]').fill(password);
  await Promise.all([page.waitForURL(url => ['/provider', '/admin', '/tourist/reservations'].includes(url.pathname)), page.getByRole('button', { name: 'Ingresar', exact: true }).click()]);
  return { ctx, page, errors };
 }
 async function screenshot(session, name) {
  for (const [size, width, height] of [['desktop', 1440, 1000], ['mobile', 390, 844]]) {
   await session.page.setViewportSize({ width, height });
   await session.page.screenshot({ path: path.join(output, name + '-' + size + '.png'), fullPage: true });
   assert.ok(await session.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), name + ' must not overflow on ' + size);
   assert.equal(await session.page.locator('.workspace-content h1').count(), 1);
   assert.deepEqual(session.errors, [], name + ' has no browser script errors');
  }
  console.log('PASS: ' + name + ', desktop and mobile without horizontal overflow.');
 }
 async function providerFixture(name) {
  const ctx = await browser.newContext(); contexts.push(ctx);
  const email = 'workspace-' + crypto.randomUUID() + '@example.test';
  const response = await ctx.request.get(base + '/register?role=PROVIDER');
  const result = await ctx.request.post(base + '/register', { form: { _csrf: csrf(await response.text()), role: 'PROVIDER', firstName: 'Lucía', lastName: 'Pereira', email, password: runtimePassword, passwordConfirmation: runtimePassword, document: 'DEMO-ESPACIO', legalType: 'PHYSICAL', commercialName: name, contactEmail: email, phone: '099123456', departmentId: '10', localityId: '10', locationDescription: 'Montevideo, Uruguay', description: 'Caminatas acompañadas para descubrir la naturaleza y las historias del lugar.' } });
  assert.ok(new URL(result.url()).pathname === '/login', 'Demo provider registration succeeds');
  return session(email, runtimePassword);
 }
 try {
  tourist = await session('turista@example.test', config.DEMO_PASSWORD);
  await screenshot(tourist, 'tourist');
  const activityResponse = await tourist.ctx.request.get(base + '/api/v1/activities/1');
  const activity = await activityResponse.json();
  const instance = activity.instances.find(i => i.bookable && (i.availableCapacity === null || i.availableCapacity > 0));
  if (instance) {
   let token = csrf(await tourist.page.content());
   const result = await tourist.ctx.request.post(base + '/api/v1/reservations', { headers: { 'X-CSRF-TOKEN': token, 'Idempotency-Key': crypto.randomUUID() }, data: { instanceId: instance.id, participantCount: 1 } });
   assert.equal(result.status(), 201); booking = await result.json();
   const paymentId = booking.checkoutUrl.split('/').pop();
   const approval = await tourist.ctx.request.post(simulator + '/payments/' + paymentId + '/decision', { headers: { 'X-API-Key': config.PAYMENT_API_KEY }, data: { action: 'APPROVE', callback: 'DELAY' } });
   assert.ok(approval.ok());
   await tourist.ctx.request.post(base + '/tourist/reservations/' + booking.id + '/refresh', { form: { _csrf: token } });
   await tourist.page.goto(base + '/tourist/reservations');
   assert.ok((await tourist.page.locator('.trip-feature h2').textContent()).includes(activity.name));
   await screenshot(tourist, 'tourist-reserved');
  }
  const provider = await session('prestador@example.test', config.DEMO_PASSWORD);
  assert.equal(await provider.page.getByRole('link', { name: /Explorar/ }).count(), 0);
  await screenshot(provider, 'provider');
  const pending = await providerFixture('Río Adentro · Recorridos');
  const denied = await providerFixture('La Huella · Naturaleza');
  await screenshot(pending, 'provider-pending');
  assert.equal(await pending.page.locator('a[href="/provider/activities/new"]').count(), 0);
  const admin = await session(config.ADMIN_EMAIL || 'admin@example.test', config.ADMIN_PASSWORD);
  await admin.page.setViewportSize({ width: 1440, height: 1000 });
  const denyEntry = admin.page.locator('.venture-review').filter({ hasText: 'La Huella · Naturaleza' }).first();
  const action = await denyEntry.locator('form[action$="/deny"]').getAttribute('action');
  const result = await admin.ctx.request.post(base + action, { form: { _csrf: csrf(await admin.page.content()), reason: 'La descripción presentada no permite verificar la propuesta turística.' } });
  assert.ok(result.ok());
  await admin.page.reload();
  await screenshot(admin, 'admin');
  await admin.page.getByRole('button', { name: /^Denegados$/ }).click();
  assert.equal(await admin.page.locator('.venture-review:visible:not([data-venture-state="DENIED"])').count(), 0);
  assert.ok(await admin.page.locator('.venture-review:visible').count() > 0);
  await admin.page.getByRole('button', { name: /^Todos/ }).click();
  await denied.page.reload();
  assert.ok((await denied.page.locator('.denial-reason').textContent()).includes('no permite verificar'));
  assert.equal(await denied.page.locator('form[action="/provider/reapply"]').count(), 0);
  await screenshot(denied, 'provider-denied');
  console.log('PASS: role-specific navigation, actual upcoming reservation, admin filters and preserved denial reason.');
 } finally {
  if (booking && tourist) {
   const response = await tourist.ctx.request.get(base + '/tourist/reservations');
   const result = await tourist.ctx.request.post(base + '/api/v1/reservations/' + booking.id + '/cancellation', { headers: { 'X-CSRF-TOKEN': csrf(await response.text()) } });
   assert.ok(result.ok(), 'Demo reservation cleanup succeeds');
  }
  await Promise.all(contexts.map(ctx => ctx.close()));
  await browser.close();
 }
})().catch(error => {
 let message = String(error.stack || error);
 for (const secret of [config.DEMO_PASSWORD, config.ADMIN_PASSWORD, config.PAYMENT_API_KEY, config.PAYMENT_WEBHOOK_TOKEN, runtimePassword]) {
  if (secret) message = message.split(secret).join('[redacted]');
 }
 console.error(message); process.exitCode = 1;
});
