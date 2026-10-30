# DriveDroid Reborn

一个参考 DriveDroid 0.10.50 交互和界面独立实现的 Android 应用。采用蓝色工具栏、灰色 USB 状态栏、镜像列表、红色添加按钮和三种挂载选项。没有使用原版 APK、应用源码或美术资源，不是原作者发布的版本。

面向用户提供的 **OPPO Find X9 Ultra / Android 17 / 已 Root** 场景。`compileSdk = 37`、`targetSdk = 37`，最低 Android 8。开发过程中只进行电脑端构建、静态分析和模拟测试，没有连接手机、执行 ADB、启动模拟器或在设备上运行应用。页面生命周期测试在电脑端 Robolectric 的 Android 17 环境中执行。

## 0.1.1 启动修复

0.1.0 在首次创建页面时，先调用 `Window.getInsetsController()`，后调用 `setContentView()`。此时 Android 的 `PhoneWindow` 还没有创建 `DecorView`，会在读取状态栏控制器时抛出空指针异常，导致应用启动即退出。

0.1.1 将状态栏控制器访问移动到 `setContentView()` 之后。新增 Android 17 的页面生命周期回归测试，覆盖冷启动、设置与帮助页面切换、Activity 重建、从后台返回及恢复其他页面。修复前已用冷启动测试复现同一异常；修复后再执行这些测试。

此历史版本 `versionCode` 为 2，使用测试签名。从 0.1.2 开始改为正式签名包。

## 0.1.2 检查与修复

- **避免 USB 等待阻塞界面**：读取会话不再争用 Root 操作持有的锁；Root 超时覆盖向 `su` 写入命令的阶段。
- **完善回滚**：ConfigFS 在准备阶段失败也释放镜像；不覆盖已有 LUN、不弹出已切换到其他控制器的连接。恢复失败时不继续显示已确认挂载。
- **文件任务生命周期**：取消排队任务不会创建镜像；前台服务启动被拒绝会释放忙碌状态；过期取消通知会停止空闲服务。网络断开和流关闭在后台执行。
- **持续限制写入**：导入、下载每次写入前检查剩余空间，保留 256 MiB；未知长度流也限制为最多 128 GiB。失败文件保留，避免未经确认删除文件。
- **导出保护**：打开目标时先不截断，检查文件描述符对应的设备号和 inode；禁止导出到原镜像自身，校验导出长度并同步本地文件。
- **文件和页面边界**：长中文/emoji 文件名按 UTF-8 字节限长；无文件名的提供程序返回明确错误；系统文件选择器缺失不会直接崩溃；表单重建保留草稿并关闭旧窗口。
- **下载可信度**：取消默认第三方仓库。网络下载必须手动提供从发布方可信渠道取得的 SHA-256，不自动采用仓库自身的校验值。该功能检查字节一致性，不认证发布者，也不验证 GPG 等发行方数字签名；导入的本地镜像同样由用户确认来源。
- **交付构建**：`versionCode = 3`，关闭调试，使用独立生成的正式签名。禁用纯 Java 模块不需要的 Kotlin 编译集成，正式 APK 的运行时依赖列表为空。构建工具依赖与 APK 运行时依赖应分别审计。

0.1.2 沿用原包名，但正式签名与 0.1.0 / 0.1.1 不同，不能直接覆盖这些测试版。卸载旧测试版前应先导出已有镜像；卸载会清除应用私有数据。

## 0.1.3 镜像卸载与 CRC 排查

当前安装包：`releases/DriveDroid-Reborn-0.1.3-release.apk`，`versionCode = 4`，沿用 0.1.2 正式签名，可覆盖升级。

- 已挂载或保留恢复记录的镜像行右侧增加「卸载」按钮；点击、长按该镜像也可选择「卸载 / 弹出镜像」。卸载会先提示电脑安全弹出，并保留镜像文件。
- 卸载操作会重新核对目标镜像，避免旧页面误卸载另一镜像。
- 可写挂载前实际尝试 `O_RDWR` 打开文件，不截断、不写入文件内容。ConfigFS 与 legacy 后端都在装入镜像后、启用 USB 后回读 `ro` / `cdrom`。
- 内核退回只读或模式不一致时报告失败并回滚。顶部状态详情显示内核实际模式，检测报告记录 `ro` / `cdrom` / `nofua`。
- 新建镜像逐块写零并同步，完整分配所需空间，支持进度显示和取消。长按未挂载的旧镜像，可选择「创建兼容副本（完整分配空间）」；副本需要额外空间，原文件不改变。
- 在电脑出现 CRC / I/O 错误后，可打开「设置 → USB 写入诊断」，或长按当前镜像选择同名操作。报告读取内核 USB/存储/权限相关日志、镜像大小和已分配块数、文件系统与 SELinux 标签，不修改 USB、SELinux 或镜像。日志可能包含本机路径，用户可自行检查后复制。

**CRC 原因尚未通过真机日志确认。** 独立 PyFatFS 检查已验证生产格式化代码生成的 64 MiB、1024 MiB FAT32 镜像能够创建目录、写入约 2 MiB 数据、关闭并重新打开后逐字节读回。完整分配用于排除稀疏文件兼容因素，不代表已经证明或解决手机内核、存储、USB 线或电脑端的 CRC 问题。

建议升级后先卸载原挂载，创建兼容副本，选择副本的「可写 USB」并尝试复制小文件。若仍报 CRC，保留报错现场并采集「USB 写入诊断」，无需开启 ADB。开发过程未连接手机、卸载应用或删除镜像。

## 功能

- 使用系统文件选择器导入 ISO / IMG，复制到应用私有镜像目录。
- 用 Root 验证已有文件的绝对路径，直接使用原文件，避免复制大镜像。
- 镜像列表、名称排序、修改显示名称、查看路径、导出镜像。
- 从列表移除只移除记录，不删除镜像；操作前有确认。
- 单镜像只读 USB、可写 USB、只读 CD-ROM 挂载。
- 优先 ConfigFS，也支持旧式 `android_usb/android0` 接口。
- 切换前持久化恢复记录；失败时尝试回滚；弹出时恢复原 USB 配置。
- 创建 64 MiB–128 GiB 空白磁盘，支持未格式化镜像和 MBR + FAT32。
- HTTPS 下载、进度通知、取消、强制填写发布方 SHA-256 校验值。
- 兼容公开的 DriveDroid JSON 镜像仓库格式，支持发行版选择和自定义仓库地址。
- Root / USB 检测报告，可复制，方便用户自行提供适配信息。

## 构建

环境：JDK 21、Android SDK Platform 37.0、Build Tools 37.0.0。项目使用 AGP 9.2.1 和 Gradle 9.6.0。Android 17 的 Robolectric 测试要求 JDK 21。

1. 在 Android Studio 打开当前目录。
2. 通过 SDK Manager 安装 Android 17 SDK。命令行包名是 `platforms;android-37.0`，不是 `platforms;android-37`。
3. 将 `local.properties` 的 `sdk.dir` 改为自己的 SDK 位置，该文件不进入版本控制。
4. 在 Windows 构建并使用正式签名，不自动安装或启动应用：

```powershell
.\tools\build-release.ps1
```

APK 保存在 `releases/DriveDroid-Reborn-<version>-release.apk`。脚本首次运行时创建 `.signing/release.p12`；凭据使用 Windows DPAPI 保护，签名目录只允许当前账户访问，并被 Git 忽略。后续版本必须沿用该签名密钥。请妥善备份；DPAPI 凭据不能直接移到另一 Windows 账户使用，迁移前需自行安全导出凭据。密码不会写入代码或命令参数，也不会打印。已有版本包不会被脚本覆盖。

需要本地开发测试包时可以执行 `gradlew.bat :app:assembleDebug`，该产物仍是可调试的 Debug 构建，不作为正式交付包。

当境外依赖下载慢或超时时，按当前环境使用代理：

```powershell
$env:HTTP_PROXY = "http://127.0.0.1:10808"
$env:HTTPS_PROXY = "http://127.0.0.1:10808"

.\gradlew.bat `
    "-Dhttp.proxyHost=127.0.0.1" `
    "-Dhttp.proxyPort=10808" `
    "-Dhttps.proxyHost=127.0.0.1" `
    "-Dhttps.proxyPort=10808" `
    :app:assembleDebug
```

HTTP_PROXY / HTTPS_PROXY 用于支持这些变量的工具；JVM 下载另外通过 `-D` 参数配置。此代理只针对开发电脑，未写入手机应用配置。

## 手机上的手动验证

1. 自行安装 APK，打开设置 → USB 设置向导，并在 Root 管理器里授权。
2. 导入一个已知可引导的 Linux hybrid ISO。
3. 使用能传输数据的 USB 线直连电脑，选「只读 USB」。挂载会中断 MTP 和有线 ADB。
4. 先检查电脑是否枚举出磁盘，再验证 BIOS / UEFI 的 USB 启动菜单。
5. 在电脑安全弹出，然后在应用顶部状态栏点「弹出 / 恢复 USB」。

若挂载失败，可以复制应用里的错误信息和 USB 检测报告自行查看。没有进行 Find X9 Ultra 真机验证，不能把成功编译等同于该机型 USB 引导已经可用。

## 实现边界

- 需要内核支持 UDC、ConfigFS/libcomposite 和 USB Mass Storage（通常对应 `CONFIG_USB_CONFIGFS_MASS_STORAGE` / `usb_f_mass_storage`）。Root 无法替代缺失的驱动。
- ColorOS 的 USB HAL 可能自动接管控制器；SELinux 也可能拒绝打开镜像。程序保留恢复记录和错误信息，不会关闭 SELinux、停止 HAL、刷机或修改内核。
- ConfigFS 使用独立的 `dd_reborn` gadget，临时解除原 gadget 的 UDC 绑定，弹出时恢复。当前不提供 ADB 与镜像共存，也不提供多镜像同时挂载。
- USB VID/PID `1d6b:0104` 是 Linux gadget 开发常用标识，适用于当前个人测试版本；正式硬件产品需要合适的标识分配。
- `UDC` 已绑定仅表示内核挂载步骤完成，不能证明电脑已识别、镜像已引导或 Secure Boot 已通过。
- FAT32 镜像有 MBR 分区表，但没有可引导系统；需要电脑工具写入。FAT32 单文件最大 4 GiB 减 1 字节。
- 0.1.3 起新建空白镜像完整分配空间。旧版稀疏镜像不会自动改写，需要时可创建完整分配副本。请给手机保留足够空间。
- 私有目录中的镜像随卸载应用而移除，应先导出。移除列表记录不会释放磁盘空间。
- 取消、失败或进程被终止时，不完整文件会保留、不加入列表；「最近的文件操作」可查看路径。当前没有断点续传和自动清理。
- 没有默认镜像仓库；只在用户配置并点击加载后联网。仅显示 HTTPS 的未压缩 ISO / IMG 链接。用户之前手动配置的仓库地址会保留，但不会自动加载。
- 导入/下载的 256 MiB 空间保留检查不能约束其他应用同时写入；电脑通过可写 USB 修改镜像时也不经过应用的复制循环，请继续留意手机存储空间。
- 导出提供程序需要支持不截断的 `rw` 打开模式，不支持时请改用系统文件应用的本地目录。
- 当前界面为中文，参考原版的布局和风格，并非逐像素复制；原版的启动测试镜像、收费扩展和全部机型兼容策略不在当前实现内。

## 验证

```powershell
.\gradlew.bat :app:lintRelease :app:testDebugUnitTest
python tests/test_usb_script.py
```

JVM 测试检查 FAT32 的 MBR/BPB/FSInfo/FAT、拒绝覆盖现有文件、二进制复制、路径处理和仓库格式解析；Robolectric 测试实际执行 Android 17 的 Application、Activity 生命周期与视图布局。Python 测试使用普通文件模拟 ConfigFS / legacy USB，验证切换、回滚和占用保护；不会操作真实 USB。测试文件保留在 `app/build/test-images` 和 `build/usb-script-tests`，不自动删除。

`tests/test_usb_script.py` 在 Windows 使用 Git for Windows 的 Bash，在 Linux 使用 PATH 中的 Bash。模拟文件系统无法验证实际内核、SELinux、ColorOS HAL 或电脑端引导行为。

0.1.2 已通过 35 项 JVM / Android 17 Robolectric 测试和 10 项 USB 脚本模拟测试，Release Lint 无错误。导出测试为 Robolectric 默认返回相同 inode 的 `fstat` 提供了文件身份测试替身，同时使用实际文件验证复制和原文件保护；不代替真机文件系统验证。

0.1.3 的 JVM 测试共 43 项、USB 脚本模拟测试共 17 项，另外有两种容量的独立 FAT32 读写检查。独立检查可这样运行（会保留约 1.1 GiB 测试镜像，不自动清理）：

```powershell
python -m pip install --target .tools/fatcheck pyfatfs==1.1.0
python tests/test_fat32_interop.py
```

## 代码位置

- `MainActivity.java` / `IconView.java`：原生 Android 界面、交互和自行绘制的图标。
- `UsbController.java` / `RootShell.java`：Root 调用、恢复日志、超时和路径转义。
- `app/src/main/assets/usb.sh`：ConfigFS / legacy 探测、挂载、恢复逻辑。
- `TransferService.java`：导入、导出、下载及前台任务。
- `Fat32Image.java`：纯 Java FAT32 磁盘生成。
- `ImageCatalog.java`：公开 DriveDroid 仓库格式解析。

## 参考资料

- [DriveDroid 官网](https://softwarebakery.com/projects/drivedroid)
- [原作者公开的镜像仓库格式](https://softwarebakery.com/custom-repositories-in-drivedroid)
- [Android 17 SDK 设置](https://developer.android.com/about/versions/17/setup-sdk)
- [Android 17 行为变更](https://developer.android.com/about/versions/17/behavior-changes-17)
- [Linux ConfigFS USB gadget](https://docs.kernel.org/usb/gadget_configfs.html)
- [Linux Mass Storage Gadget](https://docs.kernel.org/usb/mass-storage.html)
