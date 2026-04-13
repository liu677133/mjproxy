# mjproxy Android App

一个运行在 Android 手机上的本地代理应用，用于拦截并改写发往 LLM 的请求，再转发到自定义上游接口。

## 当前功能

- 自定义 API 模式下的聊天转发
- 普通日记 / 节日日记提示词改写
- 普通聊天与互动小剧场的人设覆盖
- 多套人设方案切换
- 路由后缀与协议适配配置
- 本地聊天记录 / 日记记录保存
- 最近一次日记请求缓存与重发
- 调试导出改写后实际发送的请求体
- 可选清理 system 中的限制行与系统时间标记

## 构建

项目使用 Android Gradle Plugin，建议直接使用 Gradle Wrapper：

```powershell
.\gradlew.bat assembleDebug
```

首次构建前请确保本机已安装 Android SDK。`local.properties` 不会提交到仓库，需要在本地自行生成或由 Android Studio 自动生成。

## 目录说明

- `app/src/main/java/com/diaryproxy/app/`：主要业务代码
- `app/src/main/res/`：界面与资源
- `app/src/main/assets/persona/`：内置人设 JSON

## 注意

- 仓库默认不包含本地构建产物、APK、调试日志、截图与 `local.properties`
- 如需安装到模拟器或真机，请自行构建生成 APK
