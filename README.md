# MP3 播放器

一个简洁的 Android MP3 播放器，支持按文件名排序播放、记忆播放进度、连续播放下一曲。

## 功能

- 选择文件夹后自动列出所有 MP3 文件，按文件名排序
- 播放/暂停、上一曲/下一曲控制
- 拖动进度条跳转播放位置
- 实时显示当前播放时间和总时长
- 播完一曲自动播放下一曲
- **每个文件夹独立记忆播放进度**：播放中每 5 秒保存一次位置到硬盘（`commit()` 同步写入），暂停/停止时立即保存，重启手机后恢复到上次位置
- 下次打开自动加载上次选的文件夹

## 权限

- `MANAGE_EXTERNAL_STORAGE` — 所有文件访问权限，只需授权一次，无需每次选文件夹弹确认框
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — 后台播放服务

## 技术栈

- Kotlin, Android SDK 35 (Android 15)
- MediaPlayer + Foreground Service
- SharedPreferences（`commit()` 同步写盘，保证位置不丢失）
- RecyclerView 显示曲目列表
- 内置目录浏览器（不依赖 SAF OpenDocumentTree）

## 项目结构

```
app/src/main/java/com/buzz/mp3player/
├── App.kt              — Application，初始化 PlaybackStore
├── MainActivity.kt     — 主界面，播放控制、文件夹选择、进度显示
├── PlaybackService.kt  — 前台服务，MediaPlayer 生命周期管理
├── PlaybackStore.kt    — SharedPreferences 持久化存储（按文件夹独立保存）
└── TrackAdapter.kt     — RecyclerView 曲目列表适配器
```

## 构建 & 安装

```bash
# 构建
./gradlew assembleDebug

# 安装到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次打开会请求「所有文件访问」权限，授权后点「选择文件夹」浏览目录，选择包含 MP3 文件的文件夹即可开始播放。