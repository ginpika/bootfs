# 本地文件夹右栏拖拽上传 — 实施确认与验证

## Summary

用户请求：在列表模式下新增右侧侧栏，通过 File System Access API 打开本地文件夹，渲染扁平文件列表，拖拽文件到左侧列表区触发上传。不需要完整的本地文件管理，只需"渲染 + 拖拽上传"这一核心动作。

**当前状态：代码已全部实施完成**（上一轮会话已完成所有 Edit，本次会话仅做只读验证）。

## Current State Analysis

通过 Grep + Read 验证，所有改动均已落在文件中：

### 1. HTML — [admin-dashboard.html](file:///Users/yuzhou.jin/Documents/worktable/bootfs/src/main/resources/templates/admin-dashboard.html)
- **L171-176**：header Actions 区新增 `#openLocalFolderBtn`（文件夹图标按钮，仅 list 模式显示）
- **L652-672**：`</body>` 前新增 `#localFolderPanel` fixed 浮层侧栏
  - 头部：返回按钮 `#localFolderBack` + 路径显示 `#localFolderPath` + 关闭按钮 `#localFolderClose`
  - 主体：`#localFileList`（文件列表容器）+ `#localEmptyHint`（空文件夹提示）
  - 底部：操作提示"拖拽文件到左侧列表上传"

### 2. CSS — [admin-styles.css](file:///Users/yuzhou.jin/Documents/worktable/bootfs/src/main/resources/static/admin-styles.css)
- **L134-142**：`#localFolderPanel` 默认 `translateX(100%)` 隐藏，`.active` 时 `translateX(0)` 滑出
- **L144-168**：`.local-file-item` 样式（flex 布局、hover 高亮、dragging 半透明、目录图标着色）

### 3. JS — [admin-dashboard.js](file:///Users/yuzhou.jin/Documents/worktable/bootfs/src/main/resources/static/admin-dashboard.js)
- **L61-62**：全局变量 `dirStack`（目录栈）+ `draggingFileHandle`（拖拽中的文件句柄）
- **L79**：`DOMContentLoaded` 调用 `initializeLocalFolderPanel()`
- **L1148-1172**：`switchView` 联动 — grid 模式隐藏按钮 + 移除 panel active；list 模式显示按钮
- **L1632-1672**：`initializeDragAndDrop` 的 body drop 改为 `async`，增加 `draggingFileHandle` 分支
  - `dataTransfer.files` 为空且 `draggingFileHandle` 有值时 → `await handle.getFile()` → `handleFileUpload`
- **L1675-1698**：`initializeLocalFolderPanel` — 获取 DOM、检测 API 支持、绑定事件、阻止 panel 内部 drop 冒泡
- **L1700-1712**：`openLocalFolder` — `showDirectoryPicker()` → 初始化 dirStack → 渲染 → 显示 panel
- **L1714-1769**：`renderLocalDir` — 异步遍历 `entries()`、排序（目录优先）、渲染列表、文件项 draggable + dragstart 存 handle
- **L1771-1781**：`enterLocalDir` / `backLocalDir` — 目录栈 push/pop + 重新渲染

## Proposed Changes

**无需新增改动**。所有代码已实施完成。

## Assumptions & Decisions

1. **fixed 浮层方案**：panel 用 `position: fixed` + `translateX` 滑出，不触碰现有 main/theme-card/listView 的 flex 布局（之前的 flex 链改动已被回滚，不再重做）
2. **Chromium 优先**：依赖 `window.showDirectoryPicker()`，不支持的浏览器（Safari/Firefox）自动隐藏入口按钮
3. **dragstart 不能 await 的限制**：dragstart 事件回调结束即锁定 dataTransfer，因此 dragstart 时仅存储 `FileSystemFileHandle` 到全局变量，drop 时再 `await handle.getFile()` 异步获取 File
4. **拖拽冲突处理**：panel 内部 `drop` 调 `stopPropagation()` 阻止冒泡到 body；body drop 优先读 `dataTransfer.files`，为空且 `draggingFileHandle` 有值时走异步 getFile 分支
5. **XSS 防护**：文件名通过 `textContent` 设置（非 innerHTML），避免恶意文件名注入

## Verification Steps

需在本地 Chromium 浏览器（Chrome/Edge）中验证：

1. **入口显示**：list 模式下 header 出现"打开本地文件夹"按钮；grid 模式下按钮隐藏
2. **打开文件夹**：点击按钮 → 系统文件夹选择器 → 选择后右栏滑出展示文件列表（目录优先、字母排序）
3. **目录导航**：点击子目录进入下一层，路径显示更新；点击返回按钮回到上级
4. **拖拽上传**：拖拽文件到左侧列表区 → 触发 PUT `/f` 上传，进度条正常显示
5. **拖回不触发**：拖拽文件到右栏内部释放 → 不触发上传（stopPropagation 生效）
6. **空文件夹**：打开空文件夹 → 显示"空文件夹"提示
7. **降级**：Safari/Firefox 下按钮不显示（`showDirectoryPicker` 不存在）
