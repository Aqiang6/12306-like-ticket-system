# -*- coding: utf-8 -*-
"""
12306 一键启动脚本（聚合服务模式）
自动启动所有中间件 + 后端 + 前端，全部就绪后打开浏览器

启动顺序:
  1. MySQL / Redis / RocketMQ（自动启动，已运行则跳过）
  2. 构建后端（如需）
  3. 安装前端依赖（如需）
  4. aggregation-service (9005) -> 等就绪
  5. gateway-service (9000) -> 等就绪
  6. 前端 (8080) -> 等就绪
  7. 打开浏览器 http://localhost:8080
"""
import os
import sys
import time
import socket
import shutil
import subprocess
import tempfile
import webbrowser
from pathlib import Path

# ============================================================
# 配置区
# ============================================================
PROJECT_ROOT = Path(__file__).resolve().parent
CONSOLE_VUE = PROJECT_ROOT / "console-vue"
AGG_JAR = PROJECT_ROOT / "services" / "aggregation-service" / "target" / "index12306-aggregation-service.jar"
GW_JAR = PROJECT_ROOT / "services" / "gateway-service" / "target" / "index12306-gateway-service.jar"

# 中间件路径
ROCKETMQ_HOME = Path(r"D:\rocketmq-all-4.9.5-bin-release")
ROCKETMQ_NAMESRV = ROCKETMQ_HOME / "bin" / "mqnamesrv.cmd"
ROCKETMQ_BROKER = ROCKETMQ_HOME / "bin" / "mqbroker.cmd"
REDIS_EXE = Path(r"C:\Program Files\Redis\redis-server.exe")

# 端口
PORT_MYSQL = 3306
PORT_REDIS = 6379
PORT_ROCKETMQ_NAMESRV = 9876
PORT_ROCKETMQ_BROKER = 10911
PORT_AGG = 9005
PORT_GW = 9000
PORT_FE = 8080

# 超时（秒）
TIMEOUT_MW = 60          # 中间件启动
TIMEOUT_BROKER = 45      # broker
TIMEOUT_AGG = 180        # 聚合服务
TIMEOUT_GW = 90           # 网关
TIMEOUT_FE = 120          # 前端

# MySQL 服务名候选
MYSQL_SERVICES = ["MySQL80", "MySQL57", "MySQL", "mysql"]


# ============================================================
# 工具
# ============================================================
class C:
    G = "\033[92m"
    R = "\033[91m"
    Y = "\033[93m"
    C = "\033[96m"
    B = "\033[1m"
    D = "\033[90m"
    X = "\033[0m"


def info(msg):
    print(f"{C.C}[i]{C.X} {msg}")


def ok(msg):
    print(f"{C.G}[√]{C.X} {msg}")


def warn(msg):
    print(f"{C.Y}[!]{C.X} {msg}")


def fail(msg):
    print(f"{C.R}[X]{C.X} {msg}")


def step(n, total, msg):
    print(f"\n{C.B}[{n}/{total}] {msg}{C.X}")


def is_port_open(port, host="127.0.0.1", timeout=1):
    """探测端口就绪；本机 vite 可能只绑定 IPv6 回环 ::1，故双栈探测"""
    hosts = ("127.0.0.1", "::1") if host in ("127.0.0.1",) else (host,)
    for h in hosts:
        try:
            with socket.create_connection((h, port), timeout=timeout):
                return True
        except (socket.timeout, ConnectionRefusedError, OSError):
            continue
    return False


def wait_for_port(port, name, timeout, hint=""):
    """轮询等待端口就绪"""
    start = time.time()
    dots = 0
    while time.time() - start < timeout:
        if is_port_open(port):
            ok(f"{name} (port {port}) 已就绪，耗时 {int(time.time() - start)}s")
            return True
        if dots == 0:
            info(f"等待 {name} (port {port}) 启动 ... {hint}")
        sys.stdout.write(".")
        sys.stdout.flush()
        dots += 1
        if dots % 30 == 0:
            print(f" ({int(time.time() - start)}s)")
        time.sleep(2)
    print()
    return False


def start_in_new_window(title, command, cwd=None):
    """在新 cmd 窗口启动服务（通过临时 bat 文件避免引号嵌套问题）"""
    lines = ["@echo off"]
    if cwd:
        lines.append(f'cd /d "{cwd}"')
    lines.append(f'title {title}')
    lines.append(command)
    bat_content = "\n".join(lines) + "\n"

    fd, bat_path = tempfile.mkstemp(suffix=".bat", prefix="svc_")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(bat_content)

    subprocess.Popen(
        f'start "{title}" cmd /k ""{bat_path}""',
        shell=True,
        cwd=str(PROJECT_ROOT),
    )


def try_net_start(service_name):
    """尝试启动 Windows 服务，返回是否成功"""
    r = subprocess.run(f'net start "{service_name}"',
                       shell=True, capture_output=True, text=True,
                       encoding="gbk", errors="replace")
    # 已经启动也算成功
    return r.returncode == 0 or "已经启动" in r.stdout or "2182" in str(r.returncode)


def run(cmd, cwd=None):
    """运行命令，Windows 下 npm/yarn/mvn 都是 .cmd，必须用 shell=True"""
    if isinstance(cmd, list):
        cmd = " ".join(f'"{c}"' if " " in c else c for c in cmd)
    try:
        r = subprocess.run(cmd, cwd=cwd, shell=True,
                           stdin=sys.stdin, stdout=sys.stdout, stderr=sys.stderr,
                           text=True, encoding="utf-8", errors="replace")
        return r.returncode
    except Exception as e:
        fail(f"命令执行异常: {e}")
        return 1


# ============================================================
# 中间件启动器
# ============================================================
def ensure_mysql():
    """确保 MySQL 运行"""
    if is_port_open(PORT_MYSQL):
        ok(f"MySQL (port {PORT_MYSQL}) 已运行")
        return True
    info(f"MySQL (port {PORT_MYSQL}) 未运行，尝试启动服务 ...")
    for svc in MYSQL_SERVICES:
        if try_net_start(svc):
            if wait_for_port(PORT_MYSQL, "MySQL", TIMEOUT_MW):
                ok(f"MySQL 服务 '{svc}' 启动成功")
                return True
    fail("MySQL 启动失败，请手动启动 MySQL 服务")
    return False


def ensure_redis():
    """确保 Redis 运行"""
    if is_port_open(PORT_REDIS):
        ok(f"Redis (port {PORT_REDIS}) 已运行")
        return True
    info(f"Redis (port {PORT_REDIS}) 未运行，尝试启动 ...")
    # 1. 尝试 Windows 服务
    if try_net_start("Redis"):
        if wait_for_port(PORT_REDIS, "Redis", 30):
            ok("Redis 服务启动成功")
            return True
    # 2. 直接启动进程
    if REDIS_EXE.exists():
        start_in_new_window("Redis", f'"{REDIS_EXE}"')
        if wait_for_port(PORT_REDIS, "Redis", 30):
            ok("Redis 进程启动成功")
            return True
    fail("Redis 启动失败，请手动启动 Redis")
    return False


def is_rocketmq_broker_running():
    """检测 RocketMQ Broker 进程是否已存在（避免重复启动）"""
    try:
        r = subprocess.run(
            'wmic process where "name=\'java.exe\'" get commandline',
            shell=True, capture_output=True, text=True,
            encoding="gbk", errors="replace"
        )
        return "mqbroker" in (r.stdout or "")
    except Exception:
        return False


def ensure_rocketmq():
    """确保 RocketMQ NameServer + Broker 运行"""
    namesrv_ok = is_port_open(PORT_ROCKETMQ_NAMESRV)
    if namesrv_ok:
        ok(f"RocketMQ NameServer (port {PORT_ROCKETMQ_NAMESRV}) 已运行")
    else:
        if not ROCKETMQ_NAMESRV.exists():
            fail(f"RocketMQ 未安装于 {ROCKETMQ_HOME}")
            return False
        info(f"启动 RocketMQ NameServer (port {PORT_ROCKETMQ_NAMESRV}) ...")
        start_in_new_window(
            f"RocketMQ NameServer ({PORT_ROCKETMQ_NAMESRV})",
            f'call "{ROCKETMQ_NAMESRV}"',
            cwd=str(ROCKETMQ_HOME / "bin")
        )
        if not wait_for_port(PORT_ROCKETMQ_NAMESRV, "RocketMQ NameServer", TIMEOUT_MW):
            fail("RocketMQ NameServer 启动失败")
            return False

    # 启动 Broker（检测进程而非端口，避免重复启动）
    if is_rocketmq_broker_running():
        ok("RocketMQ Broker 进程已存在，跳过启动")
        return True
    info("启动 RocketMQ Broker ...")
    # Java 17+ 需要 --add-opens 允许反射访问 java.nio/jdk.internal.ref，否则 Broker 启动失败
    java_opens = " ".join([
        "--add-opens java.base/java.nio=ALL-UNNAMED",
        "--add-opens java.base/jdk.internal.ref=ALL-UNNAMED",
        "--add-exports java.base/jdk.internal.ref=ALL-UNNAMED",
        "--add-opens java.base/java.lang=ALL-UNNAMED",
        "--add-opens java.base/sun.nio.ch=ALL-UNNAMED",
    ])
    broker_cmd = (
        f'set "JAVA_OPT={java_opens}" && '
        f'call "{ROCKETMQ_BROKER}" -n 127.0.0.1:{PORT_ROCKETMQ_NAMESRV} autoCreateTopicEnable=true'
    )
    start_in_new_window(
        "RocketMQ Broker",
        broker_cmd,
        cwd=str(ROCKETMQ_HOME / "bin")
    )
    info("等待 Broker 注册到 NameServer (15s) ...")
    time.sleep(15)
    ok("RocketMQ Broker 已启动")
    return True


# ============================================================
# 主流程
# ============================================================
def main():
    os.chdir(str(PROJECT_ROOT))

    # 启用 ANSI 颜色（Windows 10+）
    if sys.platform == "win32":
        os.system("")

    print(f"\n{C.B}{'=' * 60}")
    print(f"       12306 一键启动脚本（聚合服务模式）")
    print(f"{'=' * 60}{C.X}\n")
    print(f"项目路径: {PROJECT_ROOT}")
    print()

    # ---- 1. 环境检查 ----
    step(1, 8, "环境检查")

    if not shutil.which("java"):
        fail("未检测到 java，请安装 JDK 17 并加入 PATH")
        return 1
    try:
        r = subprocess.run(["java", "-version"], capture_output=True, text=True,
                           encoding="utf-8", errors="replace")
        ver_line = (r.stderr or r.stdout).strip().splitlines()
        if ver_line:
            print(f"     {ver_line[0]}")
    except Exception:
        pass
    ok("Java 已安装")

    if not shutil.which("node"):
        fail("未检测到 node，请安装 Node.js 16+ 并加入 PATH")
        return 1
    ok("Node.js 已安装")

    use_npm = not shutil.which("yarn")
    if use_npm:
        warn("Yarn 未安装，将使用 npm")
    else:
        ok("Yarn 已安装")

    if not shutil.which("mvn"):
        fail("未检测到 mvn，请安装 Maven 并加入 PATH")
        return 1
    ok("Maven 已就绪")

    # ---- 2. 启动中间件 ----
    step(2, 8, "启动中间件（MySQL / Redis / RocketMQ）")

    if not ensure_mysql():
        input("按回车键退出 ...")
        return 1
    if not ensure_redis():
        input("按回车键退出 ...")
        return 1
    if not ensure_rocketmq():
        input("按回车键退出 ...")
        return 1
    ok("所有中间件就绪")

    # ---- 3. 构建后端 ----
    step(3, 8, "构建后端")

    if AGG_JAR.exists() and GW_JAR.exists():
        ok("后端 jar 已存在，跳过编译")
    else:
        info("执行 Maven 编译（首次启动较慢）...")
        cmd = [
            "mvn", "clean", "package", "-DskipTests",
            "-pl", "services/aggregation-service,services/gateway-service",
            "-am"
        ]
        info(f"命令: {' '.join(cmd)}")
        code = run(cmd)
        if code != 0:
            fail("后端构建失败")
            print()
            print("常见原因:")
            print("  1. JAVA_HOME 未指向 JDK 17")
            print("  2. 网络无法下载 Maven 依赖（建议配置阿里云镜像）")
            input("按回车键退出 ...")
            return 1
    ok("后端构建产物就绪")

    # ---- 4. 前端依赖 ----
    step(4, 8, "准备前端依赖")

    node_modules = CONSOLE_VUE / "node_modules"
    if not node_modules.exists():
        info("首次启动，安装前端依赖 ...")
        if use_npm:
            code = run(["npm", "install", "--legacy-peer-deps"], cwd=str(CONSOLE_VUE))
        else:
            code = run(["yarn", "install"], cwd=str(CONSOLE_VUE))
        if code != 0:
            fail("前端依赖安装失败")
            input("按回车键退出 ...")
            return 1
        ok("前端依赖安装完成")
    else:
        ok("node_modules 已存在，跳过安装")

    port_conflicts = []

    # ---- 5. 启动 aggregation-service ----
    step(5, 8, f"启动 aggregation-service (port {PORT_AGG})")

    if is_port_open(PORT_AGG):
        warn(f"端口 {PORT_AGG} 已被占用，跳过启动")
        port_conflicts.append(PORT_AGG)
    else:
        start_in_new_window(
            f"12306 aggregation-service ({PORT_AGG})",
            f'java -jar -Dfile.encoding=UTF-8 -Dspring.profiles.active=aggregation,dev "{AGG_JAR}"'
        )
        info("已在新窗口启动 aggregation-service")

    if not wait_for_port(PORT_AGG, "aggregation-service", TIMEOUT_AGG):
        fail("aggregation-service 启动失败，请查看新窗口日志")
        print()
        print("常见错误:")
        print("  - 数据库 12306 未初始化: 运行 init-database.bat")
        print("  - Redis/RocketMQ 连接失败: 查看中间件窗口")
        input("按回车键退出 ...")
        return 1

    # ---- 6. 启动 gateway-service ----
    step(6, 8, f"启动 gateway-service (port {PORT_GW})")

    if is_port_open(PORT_GW):
        warn(f"端口 {PORT_GW} 已被占用，跳过启动")
        port_conflicts.append(PORT_GW)
    else:
        start_in_new_window(
            f"12306 gateway-service ({PORT_GW})",
            f'java -jar "{GW_JAR}"'
        )
        info("已在新窗口启动 gateway-service")

    if not wait_for_port(PORT_GW, "gateway-service", TIMEOUT_GW):
        fail("gateway-service 启动失败")
        input("按回车键退出 ...")
        return 1

    # ---- 7. 启动前端 ----
    step(7, 8, f"启动前端 (port {PORT_FE})")

    if is_port_open(PORT_FE):
        warn(f"端口 {PORT_FE} 已被占用，跳过启动")
        port_conflicts.append(PORT_FE)
    else:
        if use_npm:
            start_in_new_window(
                f"12306 frontend ({PORT_FE})",
                "npm run dev",
                cwd=str(CONSOLE_VUE)
            )
        else:
            start_in_new_window(
                f"12306 frontend ({PORT_FE})",
                "yarn dev",
                cwd=str(CONSOLE_VUE)
            )
        info("已在新窗口启动前端")

    if not wait_for_port(PORT_FE, "frontend", TIMEOUT_FE, hint="(Vite 启动很快)"):
        fail("前端启动失败")
        input("按回车键退出 ...")
        return 1

    # ---- 8. 打开浏览器 ----
    step(8, 8, "打开浏览器")

    url = f"http://localhost:{PORT_FE}"
    ok(f"所有服务已就绪，打开浏览器: {url}")
    try:
        webbrowser.open(url)
    except Exception:
        warn(f"浏览器未自动打开，请手动访问: {url}")

    print(f"\n{C.G}{C.B}{'=' * 60}")
    print(f"              启动完成")
    print(f"{'=' * 60}{C.X}\n")
    print(f"  前端控制台 : http://localhost:{PORT_FE}")
    print(f"  网关服务   : http://localhost:{PORT_GW}")
    print(f"  聚合服务   : http://localhost:{PORT_AGG}")
    print()
    print(f"  {C.Y}测试账号: admin / admin123456{C.X}")
    print()
    print("  服务运行在独立 cmd 窗口中，关闭对应窗口即可停止服务")
    print()

    if port_conflicts:
        warn(f"以下端口已被占用（可能是上次未关闭的服务）: {', '.join(str(p) for p in port_conflicts)}")
        warn("如需重新启动，请先关闭对应端口的服务窗口，再重新运行 start.bat")
        input("按回车键退出 ...")
        return 1

    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print(f"\n{C.Y}[!] 用户中断，已启动的服务窗口不受影响{C.X}")
        sys.exit(130)
