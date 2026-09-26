import urllib.request, urllib.parse, json

# Fresh login
login_data = json.dumps({'usernameOrMailOrPhone': 'admin', 'password': 'admin123456'}).encode('utf-8')
login_req = urllib.request.Request(
    'http://localhost:9000/api/user-service/v1/login',
    data=login_data,
    headers={'Content-Type': 'application/json'},
    method='POST'
)
login_resp = urllib.request.urlopen(login_req, timeout=10)
login_result = json.loads(login_resp.read().decode('utf-8'))
token = login_result['data']['accessToken']
print(f'Login OK')
print()

base = 'http://localhost:9000'

def test(name, method, path, data=None, params=None):
    url = base + path
    if params:
        url += '?' + urllib.parse.urlencode(params)
    headers = {'Authorization': token, 'Content-Type': 'application/json'}
    body = json.dumps(data).encode('utf-8') if data else None
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        resp = urllib.request.urlopen(req, timeout=15)
        result = resp.read().decode('utf-8')
        print(f'[OK] {name}: {result[:1200]}')
    except urllib.error.HTTPError as e:
        body_text = e.read().decode('utf-8')
        print(f'[HTTP {e.code}] {name}: {body_text[:1200]}')
    except Exception as e:
        print(f'[ERR] {name}: {e}')
    print()

# 1. Search tickets: Beijing -> Hangzhou
print('=== 1. Search Tickets Beijing -> Hangzhou ===')
test('Search Tickets', 'GET', '/api/ticket-service/ticket/query', params={
    'fromStation': 'BJP',
    'toStation': 'HZH',
    'departure': '北京',
    'arrival': '杭州',
    'departureDate': '2026-09-17'
})

# 2. Query existing passengers
print('=== 2. Query Passengers ===')
test('Query Passengers', 'GET', '/api/user-service/passenger/query', params={'username': 'admin'})
