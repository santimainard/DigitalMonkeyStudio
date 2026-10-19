#!/usr/bin/env python3
"""Prueba HTTP local después de mvnw verify. Requiere Python 3 y Java, sin paquetes Python extra."""
import os, re, time, json, subprocess, uuid
import xml.etree.ElementTree as ET
from pathlib import Path
import urllib.request, urllib.error, urllib.parse, http.cookiejar, json as json_module

class Response:
 def __init__(self,r):
  self.status_code=r.code
  self.text=r.read().decode()
 def json(self): return json_module.loads(self.text)
class Session:
 def __init__(self):
  self.headers={}
  self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 def get(self,url,**kw): return self.request('GET',url,**kw)
 def post(self,url,**kw): return self.request('POST',url,**kw)
 def request(self,method,url,headers=None,data=None,json=None,timeout=15):
  headers={**self.headers,**(headers or {})}
  if json is not None:
   data=json_module.dumps(json).encode(); headers['Content-Type']='application/json'
  elif data is not None:
   data=urllib.parse.urlencode(data).encode(); headers['Content-Type']='application/x-www-form-urlencoded'
  req=urllib.request.Request(url,data=data,headers=headers,method=method)
  try: return Response(self.opener.open(req,timeout=timeout))
  except urllib.error.HTTPError as e: return Response(e)


project = Path(__file__).resolve().parents[1]
root = project / 'target'
(root / 'tmp').mkdir(parents=True, exist_ok=True)
env = dict(os.environ, DATABASE_PASSWORD='smoke-db-only', ADMIN_PASSWORD='smoke-admin-only',
           DEMO_PASSWORD='smoke-demo-only', DEMO_DATA='true', PAYMENT_API_KEY='smoke-api-only',
           PAYMENT_WEBHOOK_TOKEN='smoke-webhook-only', PAYMENT_BASE_URL='http://localhost:18081',
           PAYMENT_PUBLIC_URL='http://localhost:18081', PLATFORM_PUBLIC_URL='http://localhost:18080',
           PAYMENT_CALLBACK_URL='http://localhost:18080/api/v1/payment-events')
processes = []
logs = []
base = 'http://localhost:18080'
sim = 'http://localhost:18081'
session = Session()
session.trust_env = False
api = Session()
api.trust_env = False
api.headers['X-API-Key'] = env['PAYMENT_API_KEY']

def check(response, status=200):
    assert response.status_code == status, (response.status_code, response.text[:1500])
    return response

def token(text):
    return re.search(r'name="_csrf"[^>]*value="([^"]+)"', text).group(1)

def get_reservation(id):
    return check(session.get(base + '/api/v1/reservations/' + id)).json()

try:
    for module, main, port in [('payment-simulator','uy.edu.tic.payments.PaymentSimulatorApplication',18081),
                               ('platform','uy.edu.tic.platform.PlatformApplication',18080)]:
        report = next((project/module/'target/surefire-reports').glob('TEST-*.xml'))
        props = {p.attrib['name']:p.attrib['value'] for p in ET.parse(report).findall('.//property')}
        log = open(root/'tmp'/f'{module}-smoke.log', 'w')
        logs.append(log)
        cmd = ['java','-cp',props['java.class.path'],main,f'--server.port={port}',
               f'--spring.datasource.url=jdbc:h2:mem:{module};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1',
               '--spring.datasource.username=sa','--spring.datasource.password=smoke-db-only']
        processes.append(subprocess.Popen(cmd,env=env,stdout=log,stderr=subprocess.STDOUT))
    for port in (18080,18081):
        for attempt in range(120):
            try:
                if session.get(f'http://localhost:{port}/actuator/health', timeout=1).status_code == 200:
                    break
            except (urllib.error.URLError, TimeoutError, OSError):
                pass
            assert all(p.poll() is None for p in processes), 'Service startup failed; inspect smoke logs'
            time.sleep(.5)
        else:
            raise AssertionError('Service startup timed out')
    print('Both HTTP services started.', flush=True)
    login = check(session.get(base+'/login'))
    check(session.post(base+'/login', data={'username':'turista@example.test','password':env['DEMO_PASSWORD'],
                                          '_csrf':token(login.text)}))
    activity = check(session.get(base+'/api/v1/activities/1')).json()
    instance = activity['instances'][0]['id']
    csrf = token(check(session.get(base+f'/tourist/prepare?instanceId={instance}')).text)
    headers={'X-CSRF-TOKEN':csrf,'Idempotency-Key':str(uuid.uuid4())}
    r = check(session.post(base+'/api/v1/reservations', headers=headers,
                          json={'instanceId':instance,'participantCount':2}),201).json()
    assert r['status']=='PENDING_PAYMENT', r
    assert r['totalAmount']==2400, r
    replay = check(session.post(base+'/api/v1/reservations', headers=headers,
                               json={'instanceId':instance,'participantCount':2}),201).json()
    assert replay['id']==r['id']
    payment = r['checkoutUrl'].rsplit('/',1)[-1]
    check(session.get(r['checkoutUrl']))
    check(api.post(sim+f'/payments/{payment}/decision',json={'action':'APPROVE','callback':'DUPLICATE'}))
    for attempt in range(30):
        paid=get_reservation(r['id'])
        if paid['status']=='CONFIRMED': break
        time.sleep(.1)
    assert paid['status']=='CONFIRMED' and paid['code'].startswith('UM-'), paid
    assert not paid['canReview'], paid
    detail = check(session.get(base+'/api/v1/activities/1')).json()
    assert detail['instances'][0]['availableCapacity']==10, detail
    print('PASS: login, CSRF, checkout, HTTP payment, duplicate webhook, idempotency, capacity and review gate.',flush=True)
    check(api.post(sim+'/simulation/mode',json={'mode':'UNAVAILABLE'}))
    headers['Idempotency-Key']=str(uuid.uuid4())
    uncertain = check(session.post(base+'/api/v1/reservations',headers=headers,
                                  json={'instanceId':instance,'participantCount':1}),201).json()
    assert uncertain['status']=='PAYMENT_UNCERTAIN', uncertain
    detail=check(session.get(base+'/api/v1/activities/1')).json()
    assert detail['instances'][0]['availableCapacity']==9
    check(api.post(sim+'/simulation/mode',json={'mode':'NORMAL'}))
    check(session.post(base+f"/tourist/reservations/{uncertain['id']}/refresh",data={'_csrf':csrf}))
    pending=get_reservation(uncertain['id'])
    assert pending['status']=='PENDING_PAYMENT', pending
    payment=pending['checkoutUrl'].rsplit('/',1)[-1]
    check(api.post(sim+f'/payments/{payment}/decision',json={'action':'REJECT','callback':'DELAY'}))
    check(session.post(base+f"/tourist/reservations/{uncertain['id']}/refresh",data={'_csrf':csrf}))
    assert get_reservation(uncertain['id'])['status']=='REJECTED'
    detail=check(session.get(base+'/api/v1/activities/1')).json()
    assert detail['instances'][0]['availableCapacity']==10
    print('PASS: remote outage, retained capacity, recovery by reference, delayed callback, rejection and released capacity.',flush=True)
    print('HTTP SMOKE SUCCESS (H2 test database; real independent HTTP services).',flush=True)
finally:
    for p in processes: p.terminate()
    for p in processes:
        try: p.wait(timeout=10)
        except subprocess.TimeoutExpired: p.kill()
    for log in logs: log.close()
