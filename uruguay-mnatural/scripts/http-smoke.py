#!/usr/bin/env python3
"""Real backend-to-backend HTTP workflow; isolated H2 fixtures, never bank cards."""
import os, re, time, json, subprocess, uuid, threading
import xml.etree.ElementTree as ET
from pathlib import Path
import urllib.request, urllib.error, urllib.parse, http.cookiejar

class Session:
 def __init__(self):
  self.headers={}
  self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 def request(self,method,url,headers=None,data=None,json=None,timeout=20):
  headers={**self.headers,**(headers or {})}
  if json is not None:
   data=__import__('json').dumps(json).encode();headers['Content-Type']='application/json'
  elif data is not None:
   data=urllib.parse.urlencode(data).encode();headers['Content-Type']='application/x-www-form-urlencoded'
  request=urllib.request.Request(url,data=data,headers=headers,method=method)
  try:r=self.opener.open(request,timeout=timeout)
  except urllib.error.HTTPError as error:r=error
  class Response:
   status_code=r.code
   text=r.read().decode()
   def json(self):return __import__('json').loads(self.text)
  return Response()
 def get(self,url,**kw):return self.request('GET',url,**kw)
 def post(self,url,**kw):return self.request('POST',url,**kw)

def check(r,status=200):
 assert r.status_code==status,(r.status_code,r.text[:500]);return r

def token(text):return re.search(r'name="_csrf"[^>]*value="([^"]+)"',text).group(1)
project=Path(__file__).resolve().parents[1]
root=project/'target'/'tmp';root.mkdir(parents=True,exist_ok=True)
env=dict(os.environ,DATABASE_PASSWORD='smoke-db-only',ADMIN_PASSWORD='smoke-admin-only',DEMO_PASSWORD='smoke-demo-only',DEMO_DATA='true',PAYMENT_API_KEY='smoke-api-only',PAYMENT_WEBHOOK_TOKEN='smoke-webhook-only',PAYMENT_BASE_URL='http://localhost:18081',PAYMENT_CALLBACK_URL='http://localhost:18080/api/v1/payment-events')
base='http://localhost:18080';sim='http://localhost:18081';session=Session();api=Session();api.headers['X-API-Key']=env['PAYMENT_API_KEY']
processes=[];logs=[]
def login(s,email,password):
 page=check(s.get(base+'/login'));check(s.post(base+'/login',data={'username':email,'password':password,'_csrf':token(page.text)}))
def mode(value):check(api.post(sim+'/simulation/mode',json={'mode':value}))
try:
 for module,main,port in [('payment-simulator','uy.edu.tic.payments.PaymentSimulatorApplication',18081),('platform','uy.edu.tic.platform.PlatformApplication',18080)]:
  report=next((project/module/'target/surefire-reports').glob('TEST-*.xml'))
  props={p.attrib['name']:p.attrib['value'] for p in ET.parse(report).findall('.//property')}
  log=open(root/f'{module}-smoke.log','w');logs.append(log)
  cmd=['java','-cp',props['java.class.path'],main,f'--server.port={port}',f'--spring.datasource.url=jdbc:h2:mem:{module};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1','--spring.datasource.username=sa','--spring.datasource.password=smoke-db-only']
  if module=='payment-simulator':cmd.append('--spring.profiles.active=simulation-control')
  processes.append(subprocess.Popen(cmd,env=env,stdout=log,stderr=subprocess.STDOUT))
 for port in (18080,18081):
  for _ in range(120):
   try:
    if session.get(f'http://localhost:{port}/actuator/health',timeout=1).status_code==200:break
   except (urllib.error.URLError,TimeoutError,OSError):pass
   assert all(p.poll() is None for p in processes),'Service startup failed; inspect smoke logs'
   time.sleep(.5)
  else:raise AssertionError('Startup timed out')
 login(session,'turista@example.test',env['DEMO_PASSWORD'])
 csrf=token(check(session.get(base+'/tourist/reservations')).text)
 headers={'X-CSRF-TOKEN':csrf}
 fixture='9900000000004242';decline_number='9900000000000005'
 def card(number):return {'holder':'Turista de prueba','number':number,'cvv':'731','expiryMonth':12,'expiryYear':2099}
 def register(s,csrf,number):
  response=check(s.post(base+'/api/v1/payment-methods',headers={'X-CSRF-TOKEN':csrf,'Idempotency-Key':str(uuid.uuid4())},json=card(number)))
  assert number not in response.text and 'cvv' not in response.text
  return response.json()['id']
 check(session.post(base+'/api/v1/payment-methods',headers={**headers,'Idempotency-Key':str(uuid.uuid4())},json=card('4111111111111111')),400)
 approved=register(session,csrf,fixture);declined=register(session,csrf,decline_number)
 methods=check(session.get(base+'/api/v1/payment-methods'))
 assert fixture not in methods.text and decline_number not in methods.text and 'externalToken' not in methods.text and 'cvv' not in methods.text
 assert 'value="731"' not in check(session.get(base+'/tourist/payment-methods')).text
 activity=check(session.get(base+'/api/v1/activities/1')).json();instance=activity['instances'][0]['id']
 def book(s=session,token=csrf):
  return check(s.post(base+'/api/v1/reservations',headers={'X-CSRF-TOKEN':token,'Idempotency-Key':str(uuid.uuid4())},json={'instanceId':instance,'participantCount':1}),201).json()
 def detail(r,s=session):return check(s.get(base+'/api/v1/reservations/'+r['id'])).json()
 def pay(r,method,key=None,s=session,token=csrf,status=200,**extra):
  return check(s.post(base+f"/api/v1/reservations/{r['id']}/pay",headers={'X-CSRF-TOKEN':token,'Idempotency-Key':key or str(uuid.uuid4())},json={'methodId':method,**extra}),status)
 def refresh(r):check(session.post(base+f"/tourist/reservations/{r['id']}/refresh",data={'_csrf':csrf}));return detail(r)
 def cancel(r):return check(session.post(base+f"/api/v1/reservations/{r['id']}/cancellation",headers=headers)).json()
 r=book();assert r['status']=='PENDING_PAYMENT' and r['attempts']==[] and 'checkoutUrl' not in r
 check(api.get(sim+'/payments/by-reference/'+r['id']),404)
 check(session.get(sim+'/checkout/'+r['id']),401);check(api.get(sim+'/checkout/'+r['id']),404)
 check(api.post(sim+'/payments/'+str(uuid.uuid4())+'/decision',json={'action':'APPROVE'}),404)
 assert 'Ir al simulador' not in check(session.get(base+'/tourist/reservations/'+r['id'])).text
 pay(r,declined,'forbidden-decision',status=400,action='APPROVE')
 assert detail(r)['attempts']==[]
 rejected=pay(r,declined,'decline-key').json()
 assert rejected['status']=='PENDING_PAYMENT' and rejected['paymentStatus']=='REJECTED' and len(rejected['attempts'])==1,rejected
 pay(r,declined,'decline-key');assert len(detail(r)['attempts'])==1
 paid=pay(r,approved,'approve-key').json();assert paid['status']=='CONFIRMED' and paid['code'].startswith('UM-') and len(paid['attempts'])==2,paid
 pay(r,approved,'approve-key');pay(r,approved,'another-charge',status=409)
 assert len(detail(r)['attempts'])==2
 # Duplicate authenticated events verify canonical state and are recorded once.
 attempt=next(a for a in paid['attempts'] if a['status']=='APPROVED')
 remote=check(api.get(sim+'/payments/by-reference/'+attempt['id'])).json();event={'eventId':str(uuid.uuid4()),'paymentId':remote['paymentId'],'merchantReference':attempt['id'],'status':'APPROVED'}
 for _ in range(2):check(session.post(base+'/api/v1/payment-events',headers={'X-Webhook-Token':env['PAYMENT_WEBHOOK_TOKEN']},json=event))
 print('PASS: tokenization, masked metadata, explicit PAY, automatic rejection/approval, retry, idempotency and duplicate canonical events.',flush=True)
 # Another account cannot borrow a token or pay a foreign reservation.
 other=Session();page=check(other.get(base+'/register'));email='smoke-'+str(uuid.uuid4())+'@example.test'
 check(other.post(base+'/register',data={'_csrf':token(page.text),'firstName':'Otro','lastName':'Turista','email':email,'document':'PRUEBA','password':'SmokeOnly123!','passwordConfirmation':'SmokeOnly123!','role':'TOURIST'}))
 login(other,email,'SmokeOnly123!');other_csrf=token(check(other.get(base+'/tourist/reservations')).text);other_r=book(other,other_csrf)
 pay(other_r,approved,s=other,token=other_csrf,status=403);pay(r,approved,s=other,token=other_csrf,status=403)
 check(other.post(base+f"/api/v1/reservations/{other_r['id']}/cancellation",headers={'X-CSRF-TOKEN':other_csrf}))
 provider=Session();login(provider,'prestador@example.test',env['DEMO_PASSWORD']);page=check(provider.get(base+'/provider/activities/1/edit'))
 check(provider.post(base+'/provider/activities',data={'_csrf':token(page.text),'activityId':'1','name':'Senderismo entre sierras','description':'Recorrido de prueba','unitPrice':'1200.00','durationMinutes':'120','bookingLeadMinutes':'30','departmentId':'8','localityId':'8','locationDescription':'Minas','conditions':'Prueba','categoryIds':'1','published':'true','fullRefundMinutes':'525600','lateRefundPercent':'35'}))
 original=cancel(r);assert original['cancellation']['refundedAmount']==1200,original
 r=book();mode('UNAVAILABLE');uncertain=pay(r,approved).json();assert uncertain['status']=='PAYMENT_UNCERTAIN' and len(uncertain['attempts'])==1
 pay(r,declined,status=409);mode('NORMAL');assert refresh(r)['status']=='CONFIRMED'
 assert len(detail(r)['attempts'])==1
 mode('LOST_RESPONSE');pending=cancel(r);assert pending['cancellation']['completedAt'] is None
 mode('NORMAL');refunded=refresh(r);assert refunded['cancellation']['refundedAmount']==420 and refunded['status']=='PAYMENT_CANCELLED',refunded
 r=book();mode('LOST_RESPONSE');lost=pay(r,approved).json();assert lost['status']=='PAYMENT_UNCERTAIN',lost
 mode('NORMAL');recovered=refresh(r);assert recovered['status']=='CONFIRMED' and len(recovered['attempts'])==1,recovered
 cancel(r)
 print('PASS: ownership, uncertainty blocks new charges, outage recovery, lost authorization/refund responses, partial and full refunds.',flush=True)
 # Cancellation arrives before a delayed authorization. Its tombstone must prevent the charge.
 r=book();mode('TIMEOUT');result={}
 def delayed():
  try:result['payment']=pay(r,approved).json()
  except BaseException as error:result['error']=error
 thread=threading.Thread(target=delayed);thread.start()
 for _ in range(50):
  if detail(r)['attempts']:break
  time.sleep(.1)
 cancelled=cancel(r);assert cancelled['status']=='PAYMENT_CANCELLED' and cancelled['cancellation']['refundedAmount']==0,cancelled
 thread.join(15);assert not thread.is_alive();assert 'error' not in result,result
 time.sleep(2.5);mode('NORMAL');assert detail(r)['status']=='PAYMENT_CANCELLED'
 remote=check(api.get(sim+'/payments/by-reference/'+detail(r)['attempts'][0]['id'])).json();assert remote['status']=='CANCELLED'
 paid=book();pay(paid,approved);unpaid=book();page=check(provider.get(base+'/provider'))
 for _ in range(2):check(provider.post(base+f'/provider/activities/1/instances/{instance}/cancel',data={'_csrf':token(page.text)}))
 assert detail(paid)['cancellation']['refundedAmount']==1200
 assert detail(unpaid)['cancellation']['refundedAmount']==0 and detail(unpaid)['attempts']==[]
 check(session.post(base+'/api/v1/reservations',headers={**headers,'Idempotency-Key':str(uuid.uuid4())},json={'instanceId':instance,'participantCount':1}),400)
 check(session.post(base+f'/api/v1/payment-methods/{declined}/deactivate',headers=headers))
 print('PASS: delayed authorization vs cancellation, no charge after cancellation, provider full refunds, unpaid cancellation and closed-instance capacity.',flush=True)
 for module in ('platform','payment-simulator'):
  text=(root/f'{module}-smoke.log').read_text()
  assert fixture not in text and decline_number not in text,'Card numbers must never appear in service logs'
 print('HTTP SMOKE SUCCESS: independent services; no browser checkout, no card numbers in service logs.',flush=True)
finally:
 for p in processes:p.terminate()
 for p in processes:
  try:p.wait(timeout=10)
  except subprocess.TimeoutExpired:p.kill()
 for log in logs:log.close()
