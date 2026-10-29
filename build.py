#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Skip Helper build entry point. Python 3.10+, standard library only.

Default: compile Kotlin/Java only. APK builds require --allow-full-build.
Installation never launches the app unless --launch is explicitly supplied.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
from contextlib import contextmanager
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUTPUT = ROOT / ".build"
APP_ID = "dev.skiphelper"
WINDOWS = os.name == "nt"
EXE = ".exe" if WINDOWS else ""


class BuildError(Exception):
    pass


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("action", nargs="?", default="compile", choices=["compile", "debug", "release", "install"])
    parser.add_argument("--variant", default="debug", choices=["debug", "release"], help="compile 的变体")
    parser.add_argument("--allow-full-build", action="store_true", help="明确批准生成 APK")
    parser.add_argument("-i", "--install", action="store_true", help="APK 构建后覆盖安装，保留数据")
    parser.add_argument("--launch", action="store_true", help="安装后启动应用；默认不启动")
    parser.add_argument("--serial", help="ADB 设备序列号；多设备时必填")
    parser.add_argument("--apk", type=Path, help="install 使用的已有 APK；默认 debug 产物")
    parser.add_argument("--java-home", type=Path, help="JDK 17+ 目录，优先于 JAVA_HOME")
    parser.add_argument("--sdk-root", type=Path, help="Android SDK 目录（不改写 local.properties）")
    parser.add_argument("--gradle-home", type=Path, help="可选的已解压 Gradle 目录，必须匹配 Wrapper 版本")
    parser.add_argument("--wait-seconds", type=int, default=600, help="等待其他构建的上限，默认 600 秒；0 为立即报错")
    parser.add_argument("--dry-run", action="store_true", help="只显示计划，不构建、安装、启动、写日志或锁")
    args = parser.parse_args()
    if args.wait_seconds < 0:
        parser.error("--wait-seconds 不能小于 0")
    if args.action == "compile" and (args.install or args.launch):
        parser.error("最小编译不生成 APK；使用 debug --allow-full-build -i 或 install")
    if args.action == "release" and args.install:
        parser.error("release 默认未签名；请自行签名后使用 install --apk 指定签名包")
    if args.apk and args.action != "install":
        parser.error("--apk 只能与 install 一起使用")
    if args.launch and not (args.install or args.action == "install"):
        parser.error("--launch 必须配合安装操作")
    if args.action in ("debug", "release") and not args.allow_full_build and not args.dry_run:
        parser.error("完整打包需人工确认：添加 --allow-full-build。仅检查编译请使用 compile。")
    return args


def capture(command: list[str], env: dict[str, str], timeout: int = 30) -> str:
    try:
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise BuildError(f"无法执行 {Path(command[0]).name}: {exc}") from exc
    if result.returncode:
        raise BuildError(f"命令失败（{result.returncode}）：{Path(command[0]).name}\n{result.stdout[-4000:]}")
    return result.stdout


def prepare(args: argparse.Namespace) -> tuple[dict[str, str], Path | None]:
    env = os.environ.copy()
    env["PYTHONUTF8"] = "1"
    env["PYTHONIOENCODING"] = "utf-8"
    # An explicit SDK overrides local configuration only for this process via Gradle -P.
    if args.sdk_root:
        sdk = args.sdk_root.resolve()
        if not (sdk / "platform-tools").is_dir():
            raise BuildError(f"无效 SDK 目录：{sdk}")
        env["ANDROID_HOME"] = env["ANDROID_SDK_ROOT"] = str(sdk)
    if args.action == "install":
        return env, None
    configured = args.java_home or (Path(env["JAVA_HOME"]) if env.get("JAVA_HOME") else None)
    located = shutil.which("java")
    java = (configured.resolve() / "bin" / f"java{EXE}") if configured else (Path(located).resolve() if located else None)
    if java is None or not java.is_file():
        raise BuildError("未找到 JDK，请传入 --java-home 或设置 JAVA_HOME。")
    version = capture([str(java), "-version"], env)
    match = re.search(r'version "(\d+)(?:\.(\d+))?', version)
    if not match or int(match[1]) < 17:
        raise BuildError("需要 JDK 17+；请用 --java-home 指定，不修改全局 Java 设置。")
    env["JAVA_HOME"] = str(java.parent.parent)
    env["PATH"] = str(java.parent) + os.pathsep + env.get("PATH", "")
    return env, java


def wrapper_version() -> str:
    content = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8-sig")
    match = re.search(r"gradle-([\d.]+)-(?:bin|all)\.zip", content)
    if not match:
        raise BuildError("无法从 Wrapper 解析 Gradle 版本。")
    return match[1]


def cached_home(user_home: Path, version: str) -> Path | None:
    for kind in ("bin", "all"):
        directory = user_home / "wrapper/dists" / f"gradle-{version}-{kind}"
        for candidate in sorted(directory.glob(f"*/gradle-{version}")):
            if (candidate / "lib" / f"gradle-gradle-cli-main-{version}.jar").is_file() or (candidate / "lib" / f"gradle-launcher-{version}.jar").is_file():
                return candidate
    return None


def launcher(java: Path, version: str, home: Path | None) -> list[str]:
    base = [str(java), "-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8"]
    if home:
        for name in (f"gradle-gradle-cli-main-{version}.jar", f"gradle-launcher-{version}.jar"):
            jar = home / "lib" / name
            if jar.is_file():
                return base + ["-classpath", str(jar), "org.gradle.launcher.GradleMain"]
        raise BuildError(f"指定目录不是 Gradle {version}：{home}")
    jar = ROOT / "gradle/wrapper/gradle-wrapper.jar"
    if not jar.is_file():
        raise BuildError("Gradle Wrapper JAR 缺失。")
    return base + ["-classpath", str(jar), "org.gradle.wrapper.GradleWrapperMain"]


@contextmanager
def build_lock(user_home: Path, deadline: float):
    user_home.mkdir(parents=True, exist_ok=True)
    path = user_home / "skip-helper-build.lock"
    # Keep the inode/file after unlocking: unlinking permits races with waiting processes.
    with path.open("a+b") as handle:
        handle.seek(0, os.SEEK_END)
        if not handle.tell():
            handle.write(b"0"); handle.flush()
        acquired = False
        try:
            while not acquired:
                handle.seek(0)
                try:
                    if WINDOWS:
                        import msvcrt
                        msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                    else:
                        import fcntl
                        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    acquired = True
                except OSError:
                    wait(deadline, "另一个 build.py 正在运行")
            yield
        finally:
            if acquired:
                handle.seek(0)
                if WINDOWS:
                    import msvcrt
                    msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(handle, fcntl.LOCK_UN)


def wait(deadline: float, reason: str):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise BuildError(f"等待超时：{reason}。未停止其他进程，也未启动构建。")
    print(f"等待：{reason}（剩余 {int(remaining)} 秒）", flush=True)
    time.sleep(min(10, remaining))


def wait_for_builds(java: Path, env: dict[str, str], user_home: Path, deadline: float,
                    current_version: str, current_launcher: list[str]):
    jps = java.parent / f"jps{EXE}"
    if not jps.is_file():
        raise BuildError("JDK 缺少 jps，无法检查其他会话；请提供完整 JDK。")
    while True:
        processes = capture([str(jps), "-lvm"], env)
        clients = [line.split()[0] for line in processes.splitlines()
                   if re.search(r"org\.gradle\.(?:wrapper\.GradleWrapperMain|launcher\.GradleMain)", line)]
        versions = set(re.findall(r"org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon\s+([\d.]+)", processes))
        busy = [f"Gradle 客户端 PID {pid}" for pid in clients]
        for version in sorted(versions):
            if version == current_version:
                command = current_launcher
            else:
                home = cached_home(user_home, version)
                if not home:
                    raise BuildError(f"检测到 Gradle {version} 守护进程但找不到分发目录，无法确认其状态。")
                command = launcher(java, version, home)
            status = capture(command + ["--status"], env)
            rows = re.findall(r"(?m)^\s*(\d+)\s+([A-Z]+)\s+", status)
            if not rows:
                # A daemon can exit between jps and --status. Recheck before failing closed.
                latest = capture([str(jps), "-lvm"], env)
                if re.search(r"GradleDaemon\s+" + re.escape(version) + r"(?:\s|$)", latest):
                    raise BuildError(f"无法解析 Gradle {version} 的状态，未开始构建。")
            for pid, state in rows:
                if state not in ("IDLE", "STOPPED"):
                    busy.append(f"Gradle {version} PID {pid}: {state}")
        if not busy:
            return
        wait(deadline, "; ".join(busy))


def fingerprint(env: dict[str, str], java: Path, gradle: list[str], sdk: Path | None) -> str:
    digest = hashlib.sha256()
    files = set(ROOT.glob("*.gradle*")) | {ROOT / "gradle.properties", ROOT / "local.properties"}
    files.update(p for folder in (ROOT / "gradle", ROOT / "app") for p in folder.rglob("*")
                 if p.is_file() and "build" not in p.relative_to(ROOT).parts and
                 (folder.name == "gradle" or "src" in p.relative_to(ROOT).parts or p.suffix in (".kts", ".gradle", ".pro")))
    user_home = Path(env.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
    files.add(user_home / "gradle.properties")
    files.update(p for p in (user_home / "init.d").glob("*") if p.is_file())
    for path in sorted(files):
        if path.is_file():
            digest.update(str(path).encode("utf-8")); digest.update(path.read_bytes())
    # Hash environment-sensitive values, never print their contents.
    values = {key: env.get(key, "") for key in ("JAVA_HOME", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "GRADLE_OPTS", "ANDROID_HOME", "ANDROID_SDK_ROOT")}
    digest.update(json.dumps([values, gradle, str(sdk), capture([str(java), "-version"], env)], sort_keys=True).encode("utf-8"))
    return digest.hexdigest()


def stream(command: list[str], env: dict[str, str], log: Path) -> None:
    log.parent.mkdir(parents=True, exist_ok=True)
    print(f"日志：{log}", flush=True)
    try:
        with log.open("w", encoding="utf-8") as output:
            with subprocess.Popen(command, cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True, encoding="utf-8", errors="replace", bufsize=1) as process:
                try:
                    for line in process.stdout:
                        print(line, end="", flush=True); output.write(line)
                    result = process.wait()
                except KeyboardInterrupt:
                    process.terminate()  # Only this script's child, never other build processes.
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
                    raise
        if result:
            raise BuildError(f"Gradle 失败（{result}），请查看日志；不会安装旧包。")
    except OSError as exc:
        raise BuildError(f"无法运行构建：{exc}") from exc


def apk_for(variant: str) -> Path:
    metadata = ROOT / f"app/build/outputs/apk/{variant}/output-metadata.json"
    if not metadata.is_file():
        raise BuildError(f"未找到 {variant} APK 产物清单。")
    info = json.loads(metadata.read_text(encoding="utf-8"))
    elements = info.get("elements", [])
    if info.get("applicationId") != APP_ID or len(elements) != 1:
        raise BuildError("APK 清单与本项目不匹配或存在多个产物，请明确指定。")
    apk = (metadata.parent / elements[0]["outputFile"]).resolve()
    if apk.parent != metadata.parent.resolve() or not apk.is_file():
        raise BuildError("APK 清单包含无效产物路径。")
    return apk


def classes_signature(directory: Path) -> str | None:
    files = sorted(directory.rglob("*.class"))
    if not files:
        return None
    digest = hashlib.sha256()
    for path in files:
        digest.update(str(path.relative_to(directory)).encode("utf-8"))
        digest.update(path.read_bytes())
    return digest.hexdigest()


def adb_path(env: dict[str, str], sdk_override: Path | None) -> str:
    sdks = [sdk_override]
    local = ROOT / "local.properties"
    if local.is_file():
        match = re.search(r"(?m)^sdk\.dir\s*=\s*(.+)$", local.read_text(encoding="utf-8-sig"))
        if match:
            sdks.append(Path(match[1].strip().replace("\\:", ":").replace("\\\\", "\\")))
    sdks += [Path(env[key]) for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT") if env.get(key)]
    for sdk in sdks:
        if sdk and (sdk / "platform-tools" / f"adb{EXE}").is_file():
            return str(sdk / "platform-tools" / f"adb{EXE}")
    executable = shutil.which("adb", path=env.get("PATH"))
    if not executable:
        raise BuildError("未找到 adb，请指定 --sdk-root 或配置 SDK/PATH。")
    return executable


def install(apk: Path, args: argparse.Namespace, env: dict[str, str]):
    if not apk.is_file() or apk.suffix.lower() != ".apk":
        raise BuildError(f"APK 不存在：{apk}")
    adb = adb_path(env, args.sdk_root)
    devices = re.findall(r"(?m)^(\S+)\s+(device|offline|unauthorized)\b", capture([adb, "devices"], env))
    if args.serial:
        if (args.serial, "device") not in devices:
            raise BuildError(f"设备 {args.serial} 未连接或未授权。")
        serial = args.serial
    else:
        if len(devices) != 1 or devices[0][1] != "device":
            raise BuildError("需要唯一已授权设备；多设备请使用 --serial。")
        serial = devices[0][0]
    command = [adb, "-s", serial]
    print(f"覆盖安装到 {serial}：{apk}")
    result = capture(command + ["install", "-r", str(apk)], env, timeout=180)
    print(result.strip())
    if "Success" not in result:
        raise BuildError("设备未返回安装成功；不启动应用。")
    if args.launch:
        print(capture(command + ["shell", "am", "start", "-n", APP_ID + "/.MainActivity"], env).strip())


def main() -> int:
    args = arguments()
    env, java = prepare(args)
    if args.action == "install":
        apk = args.apk.resolve() if args.apk else apk_for("debug")
        if args.dry_run:
            print(f"计划：仅安装 {apk}；启动={args.launch}；设备={args.serial or '必须唯一已授权'}")
        else:
            install(apk, args, env)
        return 0
    variant = args.variant if args.action == "compile" else args.action
    tasks = [f":app:compile{variant.title()}Kotlin", f":app:compile{variant.title()}JavaWithJavac"] if args.action == "compile" else [f":app:assemble{variant.title()}"]
    user_home = Path(env.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))).resolve()
    version = wrapper_version()
    home = args.gradle_home.resolve() if args.gradle_home else cached_home(user_home, version)
    command = launcher(java, version, home)
    build_command = command + tasks + ["--console=plain", "--max-workers=2"]
    if args.sdk_root:
        build_command += [f"-Pandroid.injected.sdk.dir={args.sdk_root.resolve()}"]
    print(f"JDK：{env['JAVA_HOME']}\nGradle：{version} ({home or 'Wrapper 首次下载'})\n任务：{' '.join(tasks)}")
    if args.dry_run:
        print(f"仅预览；安装={args.install}；启动={args.launch}；不会生成或修改产物。")
        return 0
    deadline = time.monotonic() + args.wait_seconds
    with build_lock(user_home, deadline):
        wait_for_builds(java, env, user_home, deadline, version, command)
        digest = fingerprint(env, java, command, args.sdk_root)
        state_path = OUTPUT / "last-build.json"
        try:
            state = json.loads(state_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            state = {}
        if not isinstance(state, dict):
            state = {}
        key = f"{args.action}:{variant}"
        previous = state.get(key)
        if not isinstance(previous, dict):
            previous = {}
        classes = ROOT / f"app/build/tmp/kotlin-classes/{variant}"
        signature = classes_signature(classes)
        cached = previous.get("fingerprint") == digest and signature is not None and previous.get("classes_sha256") == signature
        if cached and args.action != "compile":
            try:
                apk = apk_for(variant)
                cached = hashlib.sha256(apk.read_bytes()).hexdigest() == previous.get("apk_sha256")
            except (BuildError, OSError, ValueError):
                cached = False
        if cached:
            print("源码与上次成功构建一致，复用产物，不重复构建。")
        else:
            log = OUTPUT / "logs" / f"{datetime.now():%Y%m%d-%H%M%S}-{args.action}.log"
            stream(build_command, env, log)
            if fingerprint(env, java, command, args.sdk_root) != digest:
                raise BuildError("构建期间源码或环境发生变化，本次结果不缓存、不安装；请先结束其他修改。")
            entry = {"fingerprint": digest, "log": str(log), "classes_sha256": classes_signature(classes)}
            if args.action != "compile":
                apk = apk_for(variant)
                entry["apk_sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
                state[f"compile:{variant}"] = dict(entry)
            state[key] = entry
            OUTPUT.mkdir(exist_ok=True)
            temporary = state_path.with_suffix(".tmp")
            temporary.write_text(json.dumps(state, indent=2, ensure_ascii=False), encoding="utf-8")
            temporary.replace(state_path)
        if args.action != "compile":
            apk = apk_for(variant)
            print(f"APK：{apk}")
            if args.action == "release":
                print("release 未配置签名，发布前需要人工签名和验收。")
            if args.install:
                install(apk, args, env)
    return 0


if __name__ == "__main__":
    for channel in (sys.stdout, sys.stderr):
        if hasattr(channel, "reconfigure"):
            channel.reconfigure(encoding="utf-8", errors="replace")
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("已取消；未操作其他会话的构建。", file=sys.stderr)
        raise SystemExit(130)
    except (BuildError, OSError, ValueError) as error:
        print(f"错误：{error}", file=sys.stderr)
        raise SystemExit(1)
