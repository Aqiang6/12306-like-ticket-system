# -*- coding: utf-8 -*-
"""多场景区间复用压测数据准备。
用法: python loadtest_setup.py seats | users | routes [scenario] | warmup | status | verify [scenario] [post] | close
"""
import datetime
import json
import random
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

if sys.platform == 'win32':
    sys.stdout.reconfigure(encoding='utf-8')

BASE = 'http://localhost:9001'
TICKET_BASE = 'http://localhost:9002'
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

# 车次共 5 站 4 个相邻站段，1000 个物理座位 → 座位×站段总容量 4000 张票
STATION_PAIRS = [(dep, arr) for dep, arr, _price in PAIRS]
SEG_PAIRS = [('北京南', '济南西'), ('济南西', '南京南'), ('南京南', '杭州东'), ('杭州东', '宁波')]  # 相邻站段（坐一站）
HALF_PAIRS = [('北京南', '南京南'), ('南京南', '宁波')]      # 前半程 2 站段 / 后半程 2 站段
FULL_PAIR = ('北京南', '宁波')                              # 全程 4 站段

# 理论成交上限 = 1000 座 × 每张票占用的站段数取倒数复用：
# 每人一站 → 每座卖 4 张 = 4000；半程 → 每座 2 张 = 2000；全程 → 每座 1 张 = 1000；
# 随机 → 介于 1000（全是全程）与 4000（全是一站）之间。
SCENARIOS = {
    'seg':    dict(desc='每人只坐一站（4 个相邻站段均分）', segments=4, max=1000 * 4, floor=2800,
                   assign=lambda i, rng: SEG_PAIRS[i % 4]),
    'half':   dict(desc='每人坐半程（前/后半程各 5000 人）', segments=2, max=1000 * 2, floor=1500,
                   assign=lambda i, rng: HALF_PAIRS[i % 2]),
    'full':   dict(desc='每人坐全程（北京南→宁波）', segments=1, max=1000 * 1, floor=800,
                   assign=lambda i, rng: FULL_PAIR),
    'random': dict(desc='每人从 10 个区间随机购票', segments=None, max=1000 * 4, floor=1200,
                   assign=lambda i, rng: rng.choice(STATION_PAIRS)),
    'even':   dict(desc='10 个区间均分（固定对照场景）', segments=None, max=1000 * 4, floor=1200,
                   assign=lambda i, rng: STATION_PAIRS[i % 10]),
}
NAME2CODE = {'北京南': 'VNP', '济南西': 'JGK', '南京南': 'NKH', '杭州东': 'HGH', '宁波': 'NGH'}

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
    def cmd_raw(self, *args):
        """同 cmd，但批量回复保留原始 bytes：二进制安全（GBK 乱码键等）。"""
        out = bytearray(b'*%d\r\n' % len(args))
        for a in args:
            b = a if isinstance(a, bytes) else a.encode('utf-8')
            out += b'$%d\r\n%s\r\n' % (len(b), b)
        self.sock.sendall(out)
        return self._read(decode=False)
    def _read(self, decode=True):
        line = self.f.readline()
        t, body = line[:1], line[1:-2]
        if t == b'+':
            return body.decode('utf-8', errors='replace') if decode else body
        if t == b':': return int(body)
        if t == b'$':
            n = int(body)
            if n == -1: return None
            raw = self.f.read(n + 2)[:-2]
            return raw.decode('utf-8', errors='replace') if decode else raw
        if t == b'*':
            n = int(body)
            return [self._read(decode) for _ in range(n)] if n != -1 else None
        raise RuntimeError('bad resp')

def seat_numbers():
    for row in range(1, 19):
        for ch in 'ABCDF':
            yield '%02d%s' % (row, ch)

# 座位可用性按车次站点顺序判断区间重叠，与 SeatMapper.xml 保持一致。
# 默认统计区间为 PAIRS[2]（北京南→杭州东），其他区间用 free_seat_sql(dep, arr) 生成。
FREE_SEAT_FROM = ("FROM t_seat s "
                  "JOIN t_train_station requested_start ON requested_start.train_id=s.train_id "
                  "AND requested_start.departure='北京南' AND requested_start.del_flag=0 "
                  "JOIN t_train_station requested_end ON requested_end.train_id=s.train_id "
                  "AND requested_end.arrival='杭州东' AND requested_end.del_flag=0 "
                  "WHERE s.train_id=1 AND s.seat_type=2 AND s.del_flag=0 "
                  "AND NOT EXISTS (SELECT STRAIGHT_JOIN 1 FROM t_ticket t "
                  "JOIN t_train_station sold_start ON sold_start.train_id=t.train_id "
                  "AND sold_start.departure=t.departure AND sold_start.del_flag=0 "
                  "JOIN t_train_station sold_end ON sold_end.train_id=t.train_id "
                  "AND sold_end.arrival=t.arrival AND sold_end.del_flag=0 "
                  "WHERE t.train_id=s.train_id AND t.carriage_number=s.carriage_number "
                  "AND t.seat_number=s.seat_number AND t.ticket_status IN (0,1,2) AND t.del_flag=0 "
                  "AND CAST(sold_start.sequence AS UNSIGNED) <= CAST(requested_end.sequence AS UNSIGNED) "
                  "AND CAST(sold_end.sequence AS UNSIGNED) >= CAST(requested_start.sequence AS UNSIGNED))")
FREE_SEAT_SQL = "SELECT COUNT(*) " + FREE_SEAT_FROM + ";"


def free_seat_sql(departure, arrival):
    sql = FREE_SEAT_SQL.replace("requested_start.departure='北京南'",
                                "requested_start.departure='%s'" % departure)
    return sql.replace("requested_end.arrival='杭州东'",
                       "requested_end.arrival='%s'" % arrival)


def stations_of_train():
    return [row[0] for row in mysql_sql(
        "SELECT departure FROM t_train_station WHERE train_id=1 ORDER BY id;", fetch=True)]


OVERSELL_SQL = (
    "SELECT COUNT(*) FROM t_ticket a JOIN t_ticket b "
    "ON a.train_id=b.train_id AND a.carriage_number=b.carriage_number AND a.seat_number=b.seat_number AND a.id<b.id "
    "JOIN t_train_station ass ON ass.train_id=a.train_id AND ass.departure=a.departure AND ass.del_flag=0 "
    "JOIN t_train_station ase ON ase.train_id=a.train_id AND ase.arrival=a.arrival AND ase.del_flag=0 "
    "JOIN t_train_station bss ON bss.train_id=b.train_id AND bss.departure=b.departure AND bss.del_flag=0 "
    "JOIN t_train_station bse ON bse.train_id=b.train_id AND bse.arrival=b.arrival AND bse.del_flag=0 "
    "WHERE a.train_id=1 AND a.ticket_status IN (0,1,2) AND b.ticket_status IN (0,1,2) AND a.del_flag=0 AND b.del_flag=0 "
    "AND CAST(ass.sequence AS UNSIGNED) <= CAST(bse.sequence AS UNSIGNED) "
    "AND CAST(ase.sequence AS UNSIGNED) >= CAST(bss.sequence AS UNSIGNED);")

# 与服务端 SeatBitMapUtil/SeatBitMapAssembler 同口径的位图可售统计：
# 二等座 18 行 × 6 列位（E 列非物理座位恒为占用，跳过），区间内所有相邻站段 bit 全 0 才算可售。
BITMAP_FREE_LUA = """
local count = 0
for _, key in ipairs(KEYS) do
  for seat = 0, 107 do
    if seat % 6 ~= 4 then
      local free = true
      for segment = tonumber(ARGV[2]), tonumber(ARGV[3]) - 1 do
        if redis.call('GETBIT', key, seat * tonumber(ARGV[1]) + segment) ~= 0 then
          free = false
          break
        end
      end
      if free then count = count + 1 end
    end
  end
end
return count
"""

def setup_seats():
    mysql_sql(
        "DELETE FROM t_carriage WHERE train_id=1 AND carriage_number IN ('17','18','19');"
        "DELETE FROM t_seat WHERE train_id=1 AND carriage_number IN ('17','18','19');"
        "DELETE FROM t_ticket WHERE passenger_id=0 AND ticket_status=1 AND username IS NULL;")
    mysql_sql(
        "INSERT INTO t_carriage (train_id,carriage_number,carriage_type,seat_count,create_time,update_time,del_flag) "
        "VALUES (1,'17',2,90,NOW(),NOW(),0),(1,'18',2,90,NOW(),NOW(),0),(1,'19',2,90,NOW(),NOW(),0);")
    # t_seat 已是物理座位注册表（一座位一行），只插座位本体
    values = []
    for carriage in NEW_CARRIAGES:
        for seat in seat_numbers():
            values.append("(1,'%s','%s',2,NOW(),NOW(),0)" % (carriage, seat))
    chunk = 500
    for i in range(0, len(values), chunk):
        mysql_sql("INSERT INTO t_seat (train_id,carriage_number,seat_number,seat_type,create_time,update_time,del_flag) VALUES "
                  + ','.join(values[i:i + chunk]) + ";")
    avail = mysql_sql(FREE_SEAT_SQL, fetch=True)[0][0]
    lock_count = int(avail) - TARGET_SELLABLE
    if lock_count < 0:
        raise RuntimeError('可售票不足 1000：%s；请先清理历史压测订单或增加测试车厢' % avail)
    if lock_count > 0:
        # 用整程占用的"锁定票"账本记录扣减多余可售（模拟旧模型整座预锁）
        first = mysql_sql("SELECT departure FROM t_train_station WHERE train_id=1 ORDER BY id ASC LIMIT 1;", fetch=True)[0][0]
        last = mysql_sql("SELECT departure FROM t_train_station WHERE train_id=1 ORDER BY id DESC LIMIT 1;", fetch=True)[0][0]
        lock_seats = mysql_sql(
            "SELECT s.carriage_number,s.seat_number " + FREE_SEAT_FROM
            + " ORDER BY s.carriage_number,s.seat_number LIMIT %d;" % lock_count, fetch=True)
        lock_values = []
        for carriage, seat in lock_seats:
            lock_values.append("(1,'%s','%s',0,1,'%s','%s',NOW(),NOW(),0)" % (carriage, seat, first, last))
        for i in range(0, len(lock_values), 500):
            mysql_sql("INSERT INTO t_ticket (train_id,carriage_number,seat_number,passenger_id,ticket_status,departure,arrival,create_time,update_time,del_flag) VALUES "
                      + ','.join(lock_values[i:i + 500]) + ";")
    avail = mysql_sql(FREE_SEAT_SQL, fetch=True)[0][0]
    r = RedisMini()
    # 二进制安全 KEYS/DEL：站名含中文，历史版本可能留下 GBK 编码键，解码往返会导致 DEL 打不中
    stale = r.cmd_raw('KEYS', b'index12306-ticket-service:train_carriage_seat_status:1_*') \
           + r.cmd_raw('KEYS', b'index12306-ticket-service:train_station_remaining_ticket:1_*') \
           + r.cmd_raw('KEYS', b'index12306-ticket-service:train_station_carriage_remaining_ticket:1_*') \
           + r.cmd_raw('KEYS', b'index12306-ticket-service:purchase_rate_limiter:1*')
    if stale:
        r.cmd_raw('DEL', *stale)
    left = sum(len(r.cmd_raw('KEYS', p)) for p in (
        b'index12306-ticket-service:train_carriage_seat_status:1_*',
        b'index12306-ticket-service:train_station_remaining_ticket:1_*',
        b'index12306-ticket-service:train_station_carriage_remaining_ticket:1_*',
        b'index12306-ticket-service:train_interval_sold_out:1*'))
    print('新增车厢座位行 %d 条；锁定 %d 张后可售 = %s；清理缓存 key %d 个（残留 %d）'
          % (len(values), max(lock_count, 0), avail, len(stale), left))
    if left:
        raise RuntimeError('仍有 %d 个缓存键删除失败，请检查 Redis' % left)

def http(method, path, body=None, token=None, extra_headers=None, base=BASE):
    headers = {'Content-Type': 'application/json'}
    if token: headers['Authorization'] = token
    if extra_headers: headers.update(extra_headers)
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
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

def unique_idcard(i, first_year):
    # 同一批 10000 个乘车人不能复用身份证号，重复购票校验按身份证号而非用户名判断。
    return gen_idcard('110101%04d0101%03d' % (first_year + i // 900, 100 + i % 900))

def register_if_missing(name, i):
    r = http('POST', '/api/user-service/v1/login', {'usernameOrMailOrPhone': name, 'password': 'Aa12345678'})
    if r.get('code') == '0':
        return
    for _ in range(2):
        r = http('POST', '/api/user-service/register', {
            'username': name, 'password': 'Aa12345678', 'realName': '压测补%d' % i, 'idType': 0,
            'idCard': unique_idcard(i % N_USERS, 1960),
            'phone': '136%08d' % (62000000 + i), 'mail': name + '@loadtest2.com', 'userType': 1, 'verifyState': 0})
        if r.get('code') == '0':
            return
        r2 = http('POST', '/api/user-service/v1/login', {'usernameOrMailOrPhone': name, 'password': 'Aa12345678'})
        if r2.get('code') == '0':
            return
    raise RuntimeError('register %s still failing: %s' % (name, r))

def setup_users():
    # 排除持有 G35 未支付订单的用户（会被"已购买当前车次"拦截）
    blocked = {x[0] for x in mysql_sql(
        "SELECT DISTINCT t.username FROM t_ticket t WHERE t.train_id=1 AND t.ticket_status=0;", fetch=True)}
    print('G35 未支付订单用户数: %d' % len(blocked))
    names = []
    for i in range(1, 10001):
        n = 'rushuserq%05d' % i
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
            for attempt in range(4):
                try:
                    passenger_info = None
                    if name.startswith(('rushuserx', 'rushuserq')):
                        i = int(name[9:])
                        passenger_info = ('压测补%04d' % i, unique_idcard(idx, 1980),
                                          '137%08d' % (53000000 + idx))
                    register_if_missing(name, 20000 + idx)
                    line = login_and_pid(name, passenger_info, 9002 if idx % 2 == 0 else 9012)
                    break
                except Exception:
                    if attempt == 3:
                        raise
                    time.sleep((attempt + 1) * 0.5)
        except Exception as e:
            with lock: errors.append(str(e)[:100])
            return
        departure, arrival, _ = PAIRS[idx % len(PAIRS)]
        results[idx] = '%s,%s,%s' % (line, departure, arrival)
        with lock:
            done[0] += 1
            if done[0] % 2000 == 0:
                print('  ...%d/%d' % (done[0], len(names)))
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=16) as pool:
        list(pool.map(work, range(len(names))))
    print('token 准备完成：成功 %d，失败 %d，耗时 %.0fs' % (len(names) - len(errors), len(errors), time.time() - t0))
    if errors:
        print('失败样例:', errors[:3]); sys.exit(1)
    USERS_CSV.write_text('\n'.join(x for x in results if x), encoding='utf-8')
    print('CSV: %s' % USERS_CSV)

def assign_routes(scenario='even'):
    """复用 CSV 已有登录态，按场景给 1 万用户分配购票区间。"""
    sc = SCENARIOS[scenario]
    rng = random.Random(20260928)
    lines = USERS_CSV.read_text(encoding='utf-8').strip().splitlines()
    if len(lines) != N_USERS:
        raise RuntimeError('CSV must contain %d users, got %d' % (N_USERS, len(lines)))
    assigned = []
    for idx, line in enumerate(lines):
        parts = line.split(',')
        if len(parts) < 5:
            raise RuntimeError('invalid user CSV row %d' % (idx + 1))
        departure, arrival = sc['assign'](idx, rng)
        assigned.append(','.join(parts[:5] + [departure, arrival]))
    USERS_CSV.write_text('\n'.join(assigned), encoding='utf-8')
    print('场景[%s] %s：已分配 %d 人' % (scenario, sc['desc'], len(assigned)))


def warmup():
    """压测前预热：逐区间触发一次余票查询（登记活跃车次 + 装载票价/展示缓存），
    并等待展示缓存与账本对齐，避免开局令牌桶容量读到冷缓存 0 造成误拒。"""
    date = (datetime.date.today() + datetime.timedelta(days=3)).isoformat()
    for departure, arrival, _price in PAIRS:
        qs = urllib.parse.urlencode({'fromStation': NAME2CODE[departure], 'toStation': NAME2CODE[arrival],
                                     'departureDate': date})
        try:
            urllib.request.urlopen(TICKET_BASE + '/api/ticket-service/ticket/query?' + qs, timeout=30).read()
        except Exception as exc:
            print('预热查询失败 %s→%s: %s' % (departure, arrival, exc))
            sys.exit(1)
    deadline = time.time() + 25
    r = RedisMini()
    pending = list(PAIRS)
    while time.time() < deadline and pending:
        pending = [(d, a) for d, a, _ in PAIRS
                   if (r.cmd('HGET', display_key(d, a), '2') or '') != str(TARGET_SELLABLE)]
        if pending:
            time.sleep(1)
    if pending:
        print('警告：展示缓存未在 25s 内对齐：%s' % pending)
        sys.exit(1)
    print('预热完成：10 个区间展示缓存均 = %d' % TARGET_SELLABLE)


def display_key(departure, arrival):
    return 'index12306-ticket-service:train_station_remaining_ticket:1_%s_%s' % (departure, arrival)


def sold_by_interval():
    rows = mysql_sql(
        "SELECT departure, arrival, COUNT(*) FROM t_ticket WHERE train_id=1 "
        "AND username LIKE 'rushuser%' AND ticket_status IN (0,1,2) AND del_flag=0 "
        "GROUP BY departure, arrival;", fetch=True)
    return {(row[0], row[1]): int(row[2]) for row in rows}


def bitmap_consistency(r, stations):
    """位图与账本逐区间核对（check_bitmap_stock 同口径），返回不一致区间数。"""
    carriages = [row[0] for row in mysql_sql(
        "SELECT carriage_number FROM t_carriage WHERE train_id=1 AND carriage_type=2;", fetch=True)]
    keys = ['index12306-ticket-service:train_carriage_seat_status:1_%s' % c for c in carriages]
    missing = [k for k in keys if r.cmd('EXISTS', k) == 0]
    if missing:
        raise RuntimeError('位图 Key 缺失 %d 个：%s' % (len(missing), missing[:5]))
    inconsistencies = 0
    for departure, arrival, _price in PAIRS:
        sql_count = int(mysql_sql(free_seat_sql(departure, arrival), fetch=True)[0][0])
        bitmap_count = r.cmd('EVAL', BITMAP_FREE_LUA, str(len(keys)), *keys,
                             str(len(stations)), str(stations.index(departure)), str(stations.index(arrival)))
        if sql_count != bitmap_count:
            inconsistencies += 1
            print('  [不一致] %s→%s 账本=%s 位图=%s' % (departure, arrival, sql_count, bitmap_count))
    return inconsistencies


def verify(scenario='even', post=False):
    """场景压测验证，输出 RESULT 行供 run_scenarios.py 汇总。
    post=False（关单前）：分区间成交、座位复用率、理论座位容量上限核对、超卖与三口径一致性。
    post=True（关单后）：订单全部关闭、各区间余票恢复 1000、三口径一致。"""
    sc = SCENARIOS[scenario]
    stations = stations_of_train()
    r = RedisMini()
    oversell = int(mysql_sql(OVERSELL_SQL, fetch=True)[0][0])
    sold_by_pair = sold_by_interval()
    total_sold = sum(sold_by_pair.values())
    if post:
        # 关单风暴后位图清理与展示缓存按 3s 周期收敛、对账任务 60s 一轮，轮询等待最多 90s 再判定
        deadline = time.time() + 90
        while True:
            recover_bad = []
            for departure, arrival, _price in PAIRS:
                free = int(mysql_sql(free_seat_sql(departure, arrival), fetch=True)[0][0])
                display = r.cmd('HGET', display_key(departure, arrival), '2')
                if free != TARGET_SELLABLE or str(display) != str(TARGET_SELLABLE):
                    recover_bad.append((departure, arrival, free, display))
            if not recover_bad or time.time() >= deadline:
                break
            time.sleep(3)
        for departure, arrival, free, display in recover_bad:
            print('  [未恢复] %s→%s 账本可售=%s 展示缓存=%s' % (departure, arrival, free, display))
        inconsistent = bitmap_consistency(r, stations)
        if inconsistent > 0:
            # 等一个对账周期（60s）后复核一次：取消释放与对账并发时的瞬态幻影占座会在此窗口收敛
            print('  位图存在瞬态偏差，等待 65s 对账周期后复核…')
            time.sleep(65)
            inconsistent = bitmap_consistency(r, stations)
        verdict = 'PASS' if total_sold == 0 and oversell == 0 and inconsistent == 0 and not recover_bad else 'FAIL'
        print('关单后：残留成交=%d 未恢复区间=%d 超卖=%d 位图不一致=%d → %s'
              % (total_sold, len(recover_bad), oversell, inconsistent, verdict))
        print('RESULT scenario=%s phase=post sold=%d oversell=%d inconsistent=%d recover_bad=%d verdict=%s'
              % (scenario, total_sold, oversell, inconsistent, len(recover_bad), verdict))
        if verdict == 'FAIL':
            sys.exit(1)
        return
    inconsistent = bitmap_consistency(r, stations)
    distinct_seats = int(mysql_sql(
        "SELECT COUNT(DISTINCT carriage_number, seat_number) FROM t_ticket WHERE train_id=1 "
        "AND username LIKE 'rushuser%' AND ticket_status IN (0,1,2) AND del_flag=0;", fetch=True)[0][0])
    print('场景[%s] %s，理论成交上限 = 1000 座 × %s = %d'
          % (scenario, sc['desc'], sc['segments'] if sc['segments'] else '1~4 站段', sc['max']))
    for departure, arrival, _price in PAIRS:
        sold = sold_by_pair.get((departure, arrival), 0)
        free = int(mysql_sql(free_seat_sql(departure, arrival), fetch=True)[0][0])
        display = r.cmd('HGET', display_key(departure, arrival), '2')
        print('  %s→%s 成交=%s 可售=%s 展示缓存=%s' % (departure, arrival, sold, free, display))
    reuse = total_sold / distinct_seats if distinct_seats else 0.0
    print('成交合计=%d，占用物理座位=%d（座位复用率 %.2f），超卖=%d，位图不一致=%d'
          % (total_sold, distinct_seats, reuse, oversell, inconsistent))
    verdict = 'PASS'
    if oversell or inconsistent or total_sold > sc['max'] or total_sold < sc['floor']:
        verdict = 'FAIL'
    print('RESULT scenario=%s phase=pre sold=%d max=%d floor=%d reuse=%.2f oversell=%d inconsistent=%d verdict=%s'
          % (scenario, total_sold, sc['max'], sc['floor'], reuse, oversell, inconsistent, verdict))
    if verdict == 'FAIL':
        sys.exit(1)

def status():
    avail = mysql_sql(FREE_SEAT_SQL, fetch=True)[0][0]
    unpaid = mysql_sql("SELECT COUNT(*) FROM t_ticket WHERE train_id=1 AND ticket_status=0;", fetch=True)[0][0]
    rows = USERS_CSV and len(USERS_CSV.read_text(encoding='utf-8').strip().splitlines()) if USERS_CSV.exists() else 0
    print('北京南→杭州东可售=%s 未支付票=%s CSV行数=%s' % (avail, unpaid, rows))
    print('各区间可售（账本口径）：')
    r = None
    for departure, arrival, _price in PAIRS:
        free = mysql_sql(free_seat_sql(departure, arrival), fetch=True)[0][0]
        if r is None:
            r = RedisMini()
        display = r.cmd('HGET',
                        'index12306-ticket-service:train_station_remaining_ticket:1_%s_%s' % (departure, arrival), '2')
        print('  %s→%s 可售=%s 展示缓存=%s' % (departure, arrival, free, display))


def close_loadtest_orders():
    """通过购票服务取消本轮 CSV 用户的待支付订单，并释放 DB 与 Redis 席位（不等 RocketMQ 自动关单）。"""
    users = {row[0]: row for row in (line.split(',') for line in USERS_CSV.read_text(encoding='utf-8').splitlines())}
    pending = []
    for shard in range(16):
        pending.extend(mysql_sql(
            "SELECT order_sn,username FROM t_order_%d WHERE train_id=1 AND status=0 "
            "AND username LIKE 'rushuser%%' AND create_time >= DATE_SUB(NOW(), INTERVAL 2 HOUR)" % shard, fetch=True))
    targets = [(order_sn, users[username]) for order_sn, username in pending if username in users]
    print('准备手动关闭本轮待支付订单 %d 笔' % len(targets))
    done = [0]
    errors = []
    lock = threading.Lock()
    def close_one(item):
        order_sn, (username, user_id, token, _passenger_id, port, *_route) = item
        try:
            for attempt in range(3):
                try:
                    result = http('POST', '/api/ticket-service/ticket/cancel', {'orderSn': order_sn},
                                  token, {'userId': user_id, 'username': username},
                                  base='http://localhost:' + port)
                    if result.get('code') != '0':
                        raise RuntimeError(str(result)[:200])
                    break
                except Exception:
                    if attempt == 2:
                        raise
                    time.sleep(attempt + 1)
            with lock:
                done[0] += 1
                if done[0] % 100 == 0:
                    print('  ...已关闭 %d/%d' % (done[0], len(targets)))
        except Exception as exc:
            with lock:
                errors.append('%s: %s' % (order_sn, str(exc)[:180]))
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(close_one, targets))
    print('手动关闭完成：成功 %d，失败 %d' % (done[0], len(errors)))
    if errors:
        print('失败样例:', errors[:3])
        sys.exit(1)

if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    arg = sys.argv[2] if len(sys.argv) > 2 else ''
    arg2 = sys.argv[3] if len(sys.argv) > 3 else ''
    if cmd == 'seats': setup_seats()
    elif cmd == 'users': setup_users()
    elif cmd == 'routes': assign_routes(arg or 'even')
    elif cmd == 'status': status()
    elif cmd == 'warmup': warmup()
    elif cmd == 'verify': verify(arg or 'even', post=(arg2 == 'post'))
    elif cmd == 'close': close_loadtest_orders()
    else: print('usage: seats|users|routes [seg|half|full|random|even]|status|warmup|verify [scenario] [post]|close')
