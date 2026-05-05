# mjproxy Android App

一个运行在 Android 手机上的妹居物语代理应用，用于拦截并改写发往 LLM 的请求，再转发到上游。

## 当前功能

- 人设覆盖
- 自定义参数
- tool参数传递
- 日记提示词改写
- 图片，文档上传
- 多套人设方案切换
- 路由后缀与协议适配配置
- 聊天记录 / 日记记录保存
- 自定义 API 模式下的聊天转发
- 最近一次日记请求缓存与重发
- 调试导出改写后实际发送的请求体
- 可选清理 system 中的限制行与系统时间标记

## 目录说明

- `app/src/main/java/com/diaryproxy/app/`：主要业务代码
- `app/src/main/res/`：界面与资源
- `app/src/main/assets/persona/`：内置人设 JSON

