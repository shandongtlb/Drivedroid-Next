# DriveDroid Reborn

一个参考 DriveDroid 0.10.50 交互和界面独立实现的 Android 应用。采用蓝色工具栏、灰色 USB 状态栏、镜像列表、红色添加按钮和三种挂载选项。没有使用原版 APK、应用源码或美术资源，不是原作者发布的版本。

面向用户提供的 **OPPO Find X9 Ultra / Android 17 / 已 Root** 场景。`compileSdk = 37`、`targetSdk = 37`，最低 Android 8。开发过程中只进行电脑端构建、静态分析和模拟测试，没有连接手机、执行 ADB、启动模拟器或在设备上运行应用。页面生命周期测试在电脑端 Robolectric 的 Android 17 环境中执行。

## 0.1.1 启动修复

0.1.0 在首次创建页面时，先调用 `Window.getInsetsController()`，后调用 `setContentView()`。此时 Android 的 `PhoneWindow` 还没有创建 `DecorView`，会在读取状态栏控制器时抛出空指针异常，导致应用启动即退出。

0.1.1 将状态栏控制器访问移动到 `setContentView()` 之后。新增 Android 17 的页面生命周期回归测试，覆盖冷启动、设置与帮助页面切换、Activity 重建、从后台返回及恢复其他页面。修复前已用冷启动测试复现同一异常；修复后再执行这些测试。

新版本 `versionCode` 为 2，沿用原包名及测试签名，可以直接覆盖安装，保留已有数据。版本明确的 APK 位于 `releases/DriveDroid-Reborn-0.1.1-debug.apk`。

## 功能

- 使用系统文件选择器导入 ISO / IMG，复制到应用私有镜像目录。
- 用 Root 验证已有文件的绝对路径，直接使用原文件，避免复制大镜像。
- 镜像列表、名称排序、修改显示名称、查看路径、导出镜像。
- 从列表移除只移除记录，不删除镜像；操作前有确认。
- 单镜像只读 USB、可写 USB、只读 CD-ROM 挂载。
- 优先 ConfigFS，也支持旧式 `android_usb/android0` 接口。
- 切换前持久化恢复记录；失败时尝试回滚；弹出时恢复原 USB 配置。
- 创建 64 MiB–128 GiB 空白磁盘，支持未格式化镜像和 MBR + FAT32。
- HTTPS 下载、进度通知、取消、可选 SHA-256 校验。
- 兼容公开的 DriveDroid JSON 镜像仓库格式，支持发行版选择和自定义仓库地址。
- Root / USB 检测报告，可复制，方便用户自行提供适配信息。

## 构建

环境：JDK 21、Android SDK Platform 37.0、Build Tools 37.0.0。项目使用 AGP 9.2.1 和 Gradle 9.6.0。Android 17 的 Robolectric 测试要求 JDK 21。

1. 在 Android Studio 打开当前目录。
2. 通过 SDK Manager 安装 Android 17 SDK。命令行包名是 `platforms;android-37.0`，不是 `platforms;android-37`。
3. 将 `local.properties` 的 `sdk.dir` 改为自己的 SDK 位置，该文件不进入版本控制。
4. 构建，不自动安装或启动应用：

```powershell
.\gradlew.bat :app:assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。这是测试签名版本，不是正式发布签名。

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
- 空白镜像采用稀疏文件，创建时保守检查可用空间，随后实际占用会随电脑写入增加。请给手机保留足够空间。
- 私有目录中的镜像随卸载应用而移除，应先导出。移除列表记录不会释放磁盘空间。
- 取消、失败或进程被终止时，不完整文件会保留、不加入列表；「最近的文件操作」可查看路径。当前没有断点续传和自动清理。
- 默认镜像列表源 `https://dd.hexed.pw/repo.json` 由第三方社区维护，用户主动点击才联网加载。仅显示 HTTPS 的未压缩 ISO / IMG 下载，允许更换仓库。
- 当前界面为中文，参考原版的布局和风格，并非逐像素复制；原版的启动测试镜像、收费扩展和全部机型兼容策略不在当前实现内。

## 验证

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
python tests/test_usb_script.py
```

JVM 测试检查 FAT32 的 MBR/BPB/FSInfo/FAT、拒绝覆盖现有文件、二进制复制、路径处理和仓库格式解析；Robolectric 测试实际执行 Android 17 的 Application、Activity 生命周期与视图布局。Python 测试使用普通文件模拟 ConfigFS / legacy USB，验证切换、回滚和占用保护；不会操作真实 USB。测试文件保留在 `app/build/test-images` 和 `build/usb-script-tests`，不自动删除。

`tests/test_usb_script.py` 在 Windows 使用 Git for Windows 的 Bash，在 Linux 使用 PATH 中的 Bash。模拟文件系统无法验证实际内核、SELinux、ColorOS HAL 或电脑端引导行为。

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
