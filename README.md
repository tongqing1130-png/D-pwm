# D-pwm

本地单机 Android 密码管理器。数据只在这台手机上，除了「主动导入 Bitwarden」这一件事，不需要任何网络。

## 它是什么

- 数据只存在手机上；`INTERNET` 权限**唯一**用途是在设置页里登录你自己的 Bitwarden 服务器拉取条目（导入完成即可在系统里关掉网络）。
- 主密码不落盘：`PBKDF2-HMAC-SHA256(600k)` 派生 KEK → 随机 DEK 被 KEK 用 `AES-256-GCM` 包裹 → 每条记录用 DEK 单独加密（独立 nonce，AAD 绑定 `id|版本`，防止密文被调换位置）。
- 保险库里没有明文：`vault.json` 只有 KDF 参数、被包裹的 DEK 和各条目密文。
- 指纹解锁是「再包一层」：DEK 副本用 `AndroidKeyStore` 里一把要求生物识别的 AES 密钥加密；新增/更换指纹会让这一层失效，主密码永远兜底。

## 下载

安装包在 [Releases](../../releases) 里，文件名固定为 `D-pwm.apk`（不带版本号、不带 debug 后缀）。
每个版本一个 tag（如 `v0.10.5`），想回退就下对应 tag 的那个 `D-pwm.apk`。
包内自带 `versionCode`，新包直接覆盖安装即可，数据不会丢。

## 当前版本（0.10.5）

- 自动填充按域名 / 包名精确匹配（无关网页不再弹面板），支持一条目多网址，可要求每次填充都验证指纹或主密码
- 注册新账号后由系统弹「保存这个登录？」，确认即写入保险库
- Bitwarden 应用内登录导入：支持两步验证；Argon2id 账号会明确提示
- WebDAV 自建云同步 + 多设备双向合并（按 `keyId` 认亲，墓碑防删除复活）
- 主题（明亮 / 黑色 / 跟随系统）、文字大小、液态玻璃外观；深色对比度修正
- 会话超时、指纹解锁、TOTP 验证器

- 创建 / 解锁 / 锁定（离开应用自动锁定）；解锁过程有忙碌态，连点不会重复派生密钥
- 条目增删改查、搜索、收藏置顶：登录 / 笔记 / 卡片
- 密码生成器（排除易混字符，保证四类字符齐全）
- 复制到剪贴板 30 秒后自动清空（只清自己写进去的内容；Android 13+ 标记为敏感内容）
- **自动填充**（`AutofillService`）：锁定时先解锁再填；多条候选会弹列表让你选，不会盲填第一条
- **通行密钥服务注册**（`CredentialProviderService`）：系统设置里能选到 MiniVault（Passkey 本身暂不实现，只返回空结果）
- **指纹/人脸解锁**
- **TOTP 验证器**：条目里填 `otpauth://` 链接或 Base32 密钥，列表里直接显示 6 位码和剩余秒数
- **Bitwarden 应用内导入**：设置页填服务器 + 邮箱 + 主密码（账号开了两步验证就再填 6 位动态码），直接登录解密（PBKDF2 账号，含 per-item key 与两种 HKDF 变体的自动判定），不经过 CSV
- 加密备份导出 / 导入、更换主密码（只重新包裹 DEK，不重加密全部条目）

## 尚未实现

- 通行密钥（Passkey / FIDO2）的创建与使用
- Argon2id KDF（当前是 PBKDF2 600k）
- WebDAV / 云端同步（会做成显式开关 + 单独权限页）
- 组织/团队保险库、附件、Send

## 构建

```bash
./build.sh assembleDebug     # 产出 dist/MiniVault-<版本>-debug.apk
./build.sh test              # 加密内核 / TOTP / Bitwarden 密码学单元测试
```

`build.sh` 会把 `JAVA_HOME` / `ANDROID_HOME` / `ANDROID_USER_HOME` / `GRADLE_USER_HOME` / `TMPDIR`
全部指到 ASCII 路径 `E:/AI-agent/DSH-test/_gbt/`：这台机器的沙箱只允许子进程写工作区，
而 Gradle 的 jansi/native 库又撑不住中文路径，两件事凑在一起就必须这么绕。

工具链（不污染系统 PATH）：JDK 21.0.13+9、Gradle 8.13、Android SDK platform-36 + build-tools 36.0.0。

## 代码地图

```
app/src/main/java/com/yg/minivault/
├── MiniVaultApp.kt            应用入口，构造 VaultRepository（单例）
├── crypto/
│   ├── VaultCrypto.kt         PBKDF2 + AES-GCM 内核（纯 JVM，可单测）
│   ├── PasswordGenerator.kt   密码生成与强度评分
│   ├── Totp.kt                RFC 6238，支持 otpauth:// 与裸 Base32
│   └── BiometricSeal.kt       AndroidKeyStore 生物识别包裹层
├── data/
│   ├── VaultItem.kt           条目模型（含 totp、host()）
│   ├── VaultDocument.kt       vault.json 结构 + JSON 编解码
│   ├── VaultStore.kt          原子文件读写（tmp + ATOMIC_MOVE + 收权限）
│   └── VaultRepository.kt     会话大脑：解锁/增删改/导入/备份/生物绑定
├── net/
│   └── BitwardenClient.kt     应用内登录 Bitwarden 并解密条目
├── autofill/
│   ├── VaultAutofillService.kt       填表请求 → Dataset
│   ├── AutofillAuthActivity.kt       锁定时解锁 + 候选选择
│   └── MiniVaultCredentialProvider.kt 通行密钥服务注册（空实现）
└── ui/
    ├── MainActivity.kt        剪贴板、导出/导入、离屏锁定
    ├── App.kt                 路由
    ├── BiometricPromptHelper.kt 平台 BiometricPrompt 封装
    ├── VaultViewModel.kt      状态 + 忙碌门闩
    └── screens/               Setup / Unlock / List / Edit / Settings / TOTP 视图
```

## 安全边界（说清楚，别指望它做不到的事）

- 忘记主密码 = 数据永久丢失。没有找回通道，这是设计而不是缺陷。
- Debug 签名：每次 `assembleDebug` 用同一台机器的 debug keystore，换电脑构建会得到不同签名，覆盖安装会失败（需先卸载，数据会丢 → 先导出备份）。
- 自动填充的字段识别依赖 `inputType` / `autofillHints` / hint 文案；个别应用识别不到就用复制粘贴。