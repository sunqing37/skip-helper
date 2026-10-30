# 构建与安装

## 默认流程

1. 查看实际改动；只做 review 且代码未变时，不重复编译。
2. 使用根目录 `build.py`，默认 `compile`：只执行目标变体的 Kotlin/Java 编译，不生成 APK、不安装、不启动。
3. 脚本先获取 Gradle 用户目录中的协作锁，再通过 JDK `jps` 和对应 Gradle 的 `--status` 检查当前用户的其他构建；检测到工作中客户端/daemon 时等待，默认最多 600 秒。无法确认状态时报错，不停别人的进程。
4. 本项目脚本之间互斥；未使用该锁的 IDE/其他工程可能在检查后开始构建。脚本无法保证跨用户、远端或不可见进程的全局互斥，不得借此忽略会话中已经知道的构建。
5. 代码变化后的最小编译通过即止。完整打包必须来自明确用户请求；`--allow-full-build` 用来表达这次人工确认，不是代理绕过审批的开关。

## 命令

```powershell
# 默认最小编译（提前配置 JAVA_HOME，或确保 PATH 中的 Java 来自完整 JDK 17+）
python -X utf8 build.py
python -X utf8 build.py compile --variant release

# 仅预览，不创建日志/锁，不构建或操作手机
python -X utf8 build.py debug --dry-run

# 用户已明确批准 APK 打包时
python -X utf8 build.py debug --allow-full-build

# 同时获得安装授权；不启动应用
python -X utf8 build.py debug --allow-full-build -i --serial SERIAL

# 复用已有 APK，不重复构建
python -X utf8 build.py install --serial SERIAL
python -X utf8 build.py install --apk path\to\signed.apk --serial SERIAL
```

- 示例中的 `SERIAL` 和 `path\to\signed.apk` 是占位符，使用时替换为自己的设备序列号和文件路径；不要将真实值写入仓库文档。
- `--launch` 是额外明确的启动操作；当前用户要求自行测试，不应自动添加。
- 多设备必须 `--serial`；未授权/离线设备报错，不猜设备。不卸载、不清数据、不自动降级、不替用户开启无障碍。
- `--java-home` > `JAVA_HOME` > PATH；需要完整 JDK 17+（含 jps）。`--sdk-root` 可临时指定 SDK，不改全局环境或 local.properties。
- 优先复用 Wrapper 同版本的缓存 Gradle bin/all 分发；没有时走 Wrapper 下载。`--gradle-home` 可指定已解压目录，但必须匹配 Wrapper 版本。
- Windows 直接调用 Java launcher/Wrapper，不拼装 cmd/shell 命令；所有日志和 Python/Gradle 输出指定 UTF-8。
- `.build/logs/` 保存日志；`.build/last-build.json` 保存源码/环境摘要和产物摘要。同一动作未变化且产物仍有效时复用，不重复执行 Gradle。源码构建中变化则拒绝缓存与安装。
- `release` 默认未配置签名，生成的包不能直接作为正式安装包。人工签名验收后再通过 `install --apk` 使用；不要自动管理密钥。
- `install` 使用已有产物，**不保证产物包含尚未打包的源码修改**。最小编译完成后，旧 APK 不会自动更新。

## 验证边界

本项目不增加自动化测试；不默认 clean、lint、test、assemble、install 或启动。脚本的帮助、dry-run 和语法检查不构建 App。真机和视觉结果由用户手动验证；不得据编译成功断言广告覆盖率和性能提升。
