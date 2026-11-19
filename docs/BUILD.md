# 编译与签名

## 1. 安装工具

需要 Python 3.10+、JDK 17、Android SDK Platform 35、Build Tools 35.0.0。`java`、`javac`、`jar`、`keytool` 应在 PATH，或设置 `JAVA_HOME`。

通过 Android Studio 的 SDK Manager 安装指定包；已有 SDK Command-Line Tools 时也可运行：

```shell
sdkmanager "platforms;android-35" "build-tools;35.0.0"
```

包名含分号，PowerShell 中必须保留引号。SDK 安装和许可接受由你完成，构建脚本不会自动接受许可。参见 [Android 官方文档](https://developer.android.com/tools/sdkmanager)。

KSU ZIP 还需要静态链接的 AArch64 监听器。在 Ubuntu 或 WSL 中安装 `gcc-aarch64-linux-gnu`，并在仓库根目录编译：

```shell
aarch64-linux-gnu-gcc -static -Os -s -Wall -Wextra -Werror -o power-events module/power-events.c
```

以下命令假定生成的 `power-events` 位于仓库根目录。

## 2. Windows PowerShell

```powershell
git clone https://github.com/Pb-ctrl/mijia-battery-temperature.git
Set-Location mijia-battery-temperature
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
python scripts/build.py --power-events ./power-events
```

按实际安装位置替换路径。若 JDK 已在 PATH，可不设置 `JAVA_HOME`。安装 Git for Windows 并让 `sh` 在 PATH，可同时执行 Shell 脚本语法检查；缺少 sh 时会明确提示跳过，Linux CI 会执行该项。

## 3. Linux / macOS

```shell
git clone https://github.com/Pb-ctrl/mijia-battery-temperature.git
cd mijia-battery-temperature
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
python3 scripts/build.py --power-events ./power-events
```

macOS 可用 `/usr/libexec/java_home -v 17` 查找已安装 JDK；Linux 路径随安装方式不同。

## 4. 自定义工具路径与离线构建

```shell
python scripts/build.py --power-events ./power-events --sdk /path/to/android-sdk
python scripts/build.py --power-events ./power-events --android-jar /path/to/android.jar --build-tools /path/to/build-tools
python scripts/build.py --power-events ./power-events --xposed-api /path/to/api-82.jar
```

Windows 同样支持这些参数，路径含空格时加引号。

默认首次联网下载 Xposed API 82 到 `.cache/`，并校验 SHA-256；可用最后一个参数提供离线文件：

```text
https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar
f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25
```

该 JAR 仅作编译 classpath，不放进 APK。没有其他 Python 依赖。

## 5. 构建步骤与产物

脚本依次进行逻辑检查、Shell 格式与语法检查、Java 编译、D8 转换、资源编译、APK 对齐、APK 签名验证和 ZIP 打包。每次 Java 编译使用新的中间目录，避免旧匿名类残留。

```text
dist/
  MijiaBatteryTemperature-<version>.apk
  MijiaBatteryTemperature-KSU-<version>.zip
  SHA256SUMS.txt
```

ZIP 的 `module.prop` 在根目录，脚本使用 LF、无 BOM，ZIP 内带 Unix 执行权限。

只运行不依赖 Android SDK 的检查：

```shell
python scripts/build.py --check-only
```

所有主机构建检查都不连接手机、不安装或启动 App，也不验证米家云端行为。

## 6. 发布签名

默认构建在 `.cache/development-signing.p12` 生成本地开发密钥。该目录被 Git 忽略。不同电脑或 CI 生成的签名不同，不能互相覆盖安装。

维护可升级的发布包时，使用自己保存的密钥，通过环境变量传入：

| 变量 | 含义 |
| --- | --- |
| `MIJIA_KEYSTORE` | 密钥库绝对路径 |
| `MIJIA_KEY_ALIAS` | 签名别名 |
| `MIJIA_STORE_PASSWORD` | 密钥库密码 |
| `MIJIA_KEY_PASSWORD` | 可选，省略时使用密钥库密码 |

```shell
python scripts/build.py --power-events ./power-events --signing release
```

密码通过环境变量交给工具，不写进仓库或打印在日志中。不要提交密钥或 `.env` 文件。

**当前手机改版0.2.0 APK 与发布版签名可能不同。** Android 提示签名冲突时不能直接覆盖；需先处理已有温度规则、停用扩展，再卸载旧 APK、安装所需版本并配置作用域。构建脚本不会自动进行这些手机操作。

源码可构建对应功能的 APK，但不承诺与历史 APK 或其他工具版本的 APK 字节一致。签名、JDK、工具和 ZIP 元数据均会影响哈希。

## 7. GitHub Actions

`build.yml` 在 main 推送、Pull Request 和手动运行时构建，使用 JDK 17、Python 3.12 和指定 SDK 包，上传 `dist/` 为工作流产物。

CI 仅用临时开发密钥，不持有发布私钥，不自动创建 Release。维护者可另行上传经过发布签名的安装包及校验文件。不要用 CI 开发包覆盖签名不同的 Release 包。

## 8. 常见错误

- 找不到 Platform / Build Tools：核对 SDK 包和 `ANDROID_HOME`，或传入显式路径。
- 找不到 javac：需要 JDK，不是只有 JRE。
- Xposed API 哈希不匹配：核实文件来源，不要跳过校验。
- 安装后没有菜单：核查支持版本、框架状态、两个作用域以及充电期间是否有有效采样。
- 未充电时状态为停止：这是本版本保留的充电限定行为，不会回退读取其他温度。
