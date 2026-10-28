# 求职助手

作者：[PlutaB](https://github.com/PlutaB)

Android 求职管理工具，支持公司与岗位管理、岗位评级、投递状态、简历关联、本地备份，以及可选的电脑配套服务。

这是独立的空白公开版本。首次启动没有公司、岗位、招聘链接、个人信息、简历、图片资料、投递记录或登录账户。应用设置页也提供作者 GitHub 链接。

## Android 构建

需要 JDK 21、Android SDK 35。配置 `ANDROID_HOME`，或在本地创建 `android/local.properties` 指定 `sdk.dir`。

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

应用名称为“求职助手”，应用 ID 为 `io.github.plutab.jobassistant`。Release 构建不含签名配置，发布时自行配置签名，切勿提交密钥。

## 使用

在应用里自行添加公司、招聘入口、岗位、个人资料与简历。默认在手机本地管理数据，无需电脑服务。GPT 登录、评分与网站抓取由使用者自行配置、主动启动；没有预置账户或凭据。

公开版不携带自用版的固定招聘入口或公司清单。公司专用 HTTP 适配器的地址已移除并停用，使用用户配置入口的通用网页抓取流程；不同网站的验证码、登录和页面结构可能仍需适配。源码中的 `example.invalid` 是不可访问的地址占位符，不是可用招聘链接。

## 可选电脑服务

需要 Node.js 24+、Python 3，以及使用者自行安装和配置的 Codex CLI。

```sh
npm ci
npm run check
npm run browser:install
npm start
```

服务初次启动使用空白的 `data/` 模板。管理页面默认位于本机 `http://127.0.0.1:4328`。运行数据写入 `runtime/`；该目录禁止提交到仓库。

## 上传 GitHub

上传本目录源码即可。这里没有原项目的 Git 历史、构建缓存、简历、数据库、签名密钥、截图、导出文件和私有开发文档。

```sh
python3 tools/check-public.py
```

该检查验证空白数据和常见敏感文件、凭据。以后添加的个人数据和备份请放在被忽略的本地目录，提交前检查 `git diff --cached`。

本仓库未预设开源许可证；作者可在决定授权方式后添加 LICENSE。
