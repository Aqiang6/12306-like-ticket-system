# -*- coding: utf-8 -*-
"""1万人抢1000票（第二阶段：临界区精简后）数据准备
用法: python loadtest_setup.py seats | users | status
"""
import json
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

BASE = 'http://localhost:9001'
MYSQL = r'D:\mysql-9.2.0-winx64\bin\mysql.exe'
RUN_DIR = Path(r'D:\12306-main\test-run')
USERS_CSV = RUN_DIR / 'loadtest_users.csv'
N_USERS = 10000

PAIRS = [
    ('北京南', '济南西', 22300), ('北京南', '南京南', 53300), ('北京南', '杭州东', 67400),
    ('北京南', '宁波', 74500), ('济南西', '南京南', 33300), ('济南西', '杭州东', 47400),
    ('济南西', '宁波', 54500), ('南京南', '杭州东', 14100), ('南京南', '宁波', 21200),
    ('杭州东', '宁波', 7100),
]
NEW_CARRIAGES = ('17', '18', '19')
TARGET_SELLABLE = 1000

def mysql_sql(sql, fetch=False):
    p = subprocess.run(
        [MYSQL, '--default-character-set=utf8mb4', '-uroot', '-p123456', '-h127.0.0.1', '-D', '12306', '-N', '-B'],
        input=sql.encode('utf-8'), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    out = p.stdout.decode('utf-8', errors='replace').strip()
    if p.returncode != 0:
        raise RuntimeError('mysql failed: %s' % p.stderr.decode('utf-8', errors='replace'))
    if not fetch:
        return None
    return [line.split('\t') for line in out.splitlines() if line]

class RedisMini:
    def __init__(self):
        self.sock = socket.create_connection(('127.0.0.1', 6379), timeout=5)
        self.f = self.sock.makefile('rb')
    def cmd(self, *args):
        out = bytearray(b'*%d\r\n' % len(args))
        for a in args:
            b = a if isinstance(a, bytes) else a.encode('utf-8')
            out += b'$%d\r\n%s\r\n' % (len(b), b)
        self.sock.sendall(out)
        return self._read()
    def _read(self):
        line = self.f.readline()
        t, body = line[:1], line[1:-2]
        if t == b'+': return body.decode('utf-8', errors='replace')
        if t == b':': return int(body)
        if t == b'$':
            n = int(body)
            if n == -1: return None
            return self.f.read(n + 2)[:-2].decode('utf-8', errors='replace')
        if t == b'*':
            n = int(body)
            return [self._read() for _ in range(n)] if n != -1 else None
        raise RuntimeError('bad resp')

def seat_numbers():
    for row in range(1, 19):
        for ch in 'ABCDF':
            yield '%02d%s' % (row, ch)

def setup_seats():
    mysql_sql(
        "DELETE FROM t_carriage WHERE train_id=1 AND carriage_number IN ('17','18','19');"
        "DELETE FROM t_seat WHERE train_id=1 AND carriage_number IN ('17','18','19');")
    mysql_sql(
        "INSERT INTO t_carriage (train_id,carriage_number,carriage_type,seat_count,create_time,update_time,del_flag) "
        "VALUES (1,'17',2,90,NOW(),NOW(),0),(1,'18',2,90,NOW(),NOW(),0),(1,'19',2,90,NOW(),NOW(),0);")
    values = []
    for carriage in NEW_CARRIAGES:
        for seat in seat_numbers():
            for (s, e, price) in PAIRS:
                values.append("(1,'%s','%s',2,'%s','%s',%d,0,NOW(),NOW(),0)" % (carriage, seat, s, e, price))
    chunk = 500
    for i in range(0, len(values), chunk):
        mysql_sql("INSERT INTO t_seat (train_id,carriage_number,seat_number,seat_type,start_station,end_station,price,seat_status,create_time,update_time,del_flag) VALUES "
                  + ','.join(values[i:i + chunk]) + ";")
    avail = mysql_sql(
        "SELECT COUNT(*) FROM t_seat WHERE train_id=1 AND seat_type=2 "
        "AND start_station='北京南' AND end_station='杭州东' AND seat_status=0;", fetch=True)[0][0]
    lock_count = int(avail) - TARGET_SELLABLE
    if lock_count > 0:
        lock_seats = list(seat_numbers())[:lock_count]
        seat_list = ','.join("'%s'" % s for s in lock_seats)
        mysql_sql("UPDATE t_seat SET seat_status=1 WHERE train_id=1 AND seat_type=2 "
                  "AND carriage_number='17' AND seat_number IN (%s);" % seat_list)
    avail = mysql_sql(
        "SELECT COUNT(*) FROM t_seat WHERE train_id=1 AND seat_type=2 "
        "AND start_station='北京南' AND end_station='杭州东' AND seat_status=0;", fetch=True)[0][0]
    r = RedisMini()
    stale = r.cmd('KEYS', 'index12306-ticket-service:train_carriage_seat_status:1_*'.encode()) \
           + r.cmd('KEYS', 'index12306-ticket-service:train_station_remaining_ticket:1_*'.encode()) \
           + r.cmd('KEYS', 'index12306-ticket-service:purchase_rate_limiter:1'.encode())
    if stale:
        r.cmd('DEL', *stale)
    print('新增车厢座位行 %d 条；锁定 %d 张后可售 = %s；清理缓存 key %d 个' % (len(values), max(lock_count, 0), avail, len(stale)))

def http(method, path, body=None, token=None, extra_headers=None):
    headers = {'Content-Type': 'application/json'}
    if token: headers['Authorization'] = token
    if extra_headers: headers.update(extra_headers)
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        r = urllib.request.urlopen(req, timeout=30)
        return json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8'))

def login_and_pid(name, passenger_info=None, port=9002):
    r = http('POST', '/api/user-service/v1/login', {'usernameOrMailOrPhone': name, 'password': 'Aa12345678'})
    if r.get('code') != '0':
        raise RuntimeError('login %s: %s' % (name, r))
    token = r['data']['accessToken']
    user_id = r['data']['userId']
    ctx = {'userId': user_id, 'username': name}
    r = http('GET', '/api/user-service/passenger/query?username=' + name, None, token, ctx)
    if r.get('code') != '0' or not r.get('data'):
        if passenger_info:
            real_name, idc, phone = passenger_info
            r2 = http('POST', '/api/user-service/passenger/save',
                      {'realName': real_name, 'idType': 0, 'idCard': idc, 'discountType': 1, 'phone': phone}, token, ctx)
            if r2.get('code') != '0':
                raise RuntimeError('passenger %s: %s' % (name, r2))
            r = http('GET', '/api/user-service/passenger/query?username=' + name, None, token)
    return '%s,%s,%s,%s,%s' % (name, user_id, token, r['data'][0]['id'], port)

def gen_idcard(base17):
    w = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
    m = '10X98765432'
    return base17 + m[sum(int(d) * wi for d, wi in zip(base17, w)) % 11]

def register_if_missing(name, i):
    r = http('POST', '/api/user-service/v1/login', {'usernameOrMailOrPhone': name, 'password': 'Aa12345678'})
    if r.get('code') == '0':
        return
    for _ in range(2):
        r = http('POST', '/api/user-service/register', {
            'username': name, 'password': 'Aa12345678', 'realName': '压测补%d' % i, 'idType': 0,
            'idCard': gen_idcard('1101011985%02d%02d%03d' % (1 + i % 12, 1 + i % 28, 200 + i % 700)),
            'phone': '134%08d' % (82000000 + i), 'mail': name + '@loadtest2.com', 'userType': 1, 'verifyState': 0})
        if r.get('code') == '0':
            return
        r2 = http('POST', '/api/user-service/v1/login', {'usernameOrMailOrPhone': name, 'password': 'Aa12345678'})
        if r2.get('code') == '0':
            return
    raise RuntimeError('register %s still failing: %s' % (name, r))
    idc_base = '1101011990%02d%02d%03d' % (1 + i % 12, 1 + i % 28, 100 + i % 900)
    idc = gen_idcard(idc_base)
    phone = '135%08d' % (61000000 + i)
    r = http('POST', '/api/user-service/register', {
        'username': name, 'password': 'Aa12345678', 'realName': '压测补%04d' % i, 'idType': 0,
        'idCard': idc, 'phone': phone, 'mail': name + '@loadtest.com', 'userType': 1, 'verifyState': 0})
    if r.get('code') != '0':
        raise RuntimeError('register %s: %s' % (name, r))

def setup_users():
    # 排除持有 G35 未支付订单的用户（会被"已购买当前车次"拦截）
    blocked = {x[0] for x in mysql_sql(
        "SELECT DISTINCT t.username FROM t_ticket t WHERE t.train_id=1 AND t.ticket_status=0;", fetch=True)}
    print('G35 未支付订单用户数: %d' % len(blocked))
    names = []
    for i in range(1, 10001):
        n = 'rushuserz%05d' % i
        if n not in blocked:
            names.append(n)
    extra = 0
    while len(names) < N_USERS:
        extra += 1
        names.append('rushuserx%04d' % extra)
    names = names[:N_USERS]

    results = [None] * len(names)
    errors = []
    lock = threading.Lock()
    done = [0]
    def work(idx):
        name = names[idx]
        try:
            line = None
            for attempt_name in (name, 'rushuserzs%05d' % idx):
                try:
                    passenger_info = None
                    if attempt_name.startswith('rushuserx') or attempt_name.startswith('rushuserz'):
                        i = int(attempt_name[9:].lstrip('s'))
                        passenger_info = ('压测补%04d' % i, gen_idcard('1101011987%02d%02d%03d' % (1 + i % 12, 1 + i % 28, 100 + i % 900)),
                                          '132%08d' % (53000000 + i))
                    register_if_missing(attempt_name, (20000 if attempt_name == name else 80000) + idx)
                    line = login_and_pid(attempt_name, passenger_info, 9002 if idx % 2 == 0 else 9012)
                    break
                except Exception:
                    if attempt_name.endswith(('s%05d' % idx)):
                        raise
                    continue
        except Exception as e:
            with lock: errors.append(str(e)[:100])
            return
        results[idx] = line
        with lock:
            done[0] += 1
            if done[0] % 2000 == 0:
                print('  ...%d/%d' % (done[0], len(names)))
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=40) as pool:
        list(pool.map(work, range(len(names))))
    print('token 准备完成：成功 %d，失败 %d，耗时 %.0fs' % (len(names) - len(errors), len(errors), time.time() - t0))
    if errors:
        print('失败样例:', errors[:3]); sys.exit(1)
    USERS_CSV.write_text('\n'.join(x for x in results if x), encoding='utf-8')
    print('CSV: %s' % USERS_CSV)

def status():
    avail = mysql_sql(
        "SELECT COUNT(*) FROM t_seat WHERE train_id=1 AND seat_type=2 "
        "AND start_station='北京南' AND end_station='杭州东' AND seat_status=0;", fetch=True)[0][0]
    unpaid = mysql_sql("SELECT COUNT(*) FROM t_ticket WHERE train_id=1 AND ticket_status=0;", fetch=True)[0][0]
    rows = USERS_CSV and len(USERS_CSV.read_text(encoding='utf-8').strip().splitlines()) if USERS_CSV.exists() else 0
    print('可售=%s 未支付票=%s CSV行数=%s' % (avail, unpaid, rows))

if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'seats': setup_seats()
    elif cmd == 'users': setup_users()
    elif cmd == 'status': status()
    else: print('usage: seats|users|status')
