# 本地文件夹右栏拖拽上传

## Context

列表模式是资源管理模式，但当前上传走文件选择对话框，批量场景效率低。新增可折叠右栏侧栏：用户通过 File System Access API（`showDirectoryPicker`）打开本地文件夹，右栏渲染扁平文件列表（子目录可点击进入），拖拽文件到左栏列表区即触发上传，复用现有 `PUT /f` 上传链路与进度 UI。仅列表模式启用。

## 布局方案：fixed 浮层侧栏

右栏 `fixed` 定位贴右侧，默认 `translateX(100%)` 隐藏，打开后 `translateX(0)` 滑出。**不触碰**现有 listView / theme-card / main 布局，零回滚风险。打开时遮住列表右侧约 320px，可随时关闭。

理由：现有 theme-card 无 flex 基础（gridView/listView/paginationRow 直接平铺），flex 并排需重构内部结构，改动面大；浮层方案最贴合"可折叠侧栏滑出"语义且风险最低。动画范式参考现有孤儿 `#sidebar` CSS（[admin-styles.css L123-132](file:///Users/yuzhou.jin/Documents/worktable/bootfs/src/main/resources/static/admin-styles.css#L123-L132)），改为 `translateX(100%)` 从右侧滑出。

## HTML 改动（admin-dashboard.html）

### 1. header Actions 区插入按钮
在 refresh 按钮（L165）之后、Theme Selector（L172）之前插入 `#openLocalFolderBtn`：folder 图标 + "本地文件夹"文本，`theme-icon-btn` 样式。grid 模式下隐藏（switchView 控制）。

### 2. 在根分栏 div（L115）末尾追加右栏结构
`#localFolderPanel`（fixed top-16 right-0 bottom-0 w-80 z-30，默认 translate-x-full）：
- header：`#localFolderPath`（当前路径面包屑）+ `#localFolderBack`（返回上级，栈深>1 时显示）+ 关闭按钮（折叠回 translate-x-full）
- `#localFileList`（overflow-y-auto）：渲染文件/目录项，每项 `draggable="true"`
  - 目录项：`data-kind="dir"`，文件夹图标，点击进入
  - 文件项：`data-kind="file"`，文件类型图标 + 名称 + 大小
- 空状态提示（无文件时显示）

## CSS 改动（admin-styles.css）

在 L132 旁新增：
- `#localFolderPanel` 滑出过渡（`transform: translateX(100%)` ↔ `.active { translateX(0) }`，`transition: transform 0.3s ease`）
- 列表项 hover 高亮、dragging 时的视觉反馈
- 目录项与文件项的图标区分

## JS 改动（admin-dashboard.js）

### 1. 全局变量（现有全局变量区，L58 附近）
- `let dirStack = [];`（FileSystemDirectoryHandle 栈，用于返回上级）
- `let draggingFileHandle = null;`（当前拖拽中的 FileSystemFileHandle）

### 2. 事件绑定（`initializeEventListeners`）
- `#openLocalFolderBtn` click → `openLocalFolder()`
- `#localFolderBack` click → `backLocalDir()`
- panel 关闭按钮 click → 折叠 panel
- `#localFolderPanel` `drop` → `e.stopPropagation()`（阻止冒泡到 body，防止拖回右栏误触发上传）
- `#localFolderPanel` `dragover` → `e.preventDefault()`（允许 drop）

### 3. 新增函数（置于 `initializeDragAndDrop` 之后）
- `openLocalFolder()`：检测 `window.showDirectoryPicker`，不支持则隐藏按钮并 return；`try { const h = await window.showDirectoryPicker(); dirStack = [h]; renderLocalDir(); panel.classList.add('active'); }`，`catch`：AbortError 静默（用户取消），其他 toast 提示
- `renderLocalDir()`：遍历 `dirStack[栈顶].entries()` 异步迭代器收集 entries，排序（目录优先、文件名字母序），渲染 `#localFileList`；为每个文件项绑定 dragstart，目录项绑定 click
- `enterLocalDir(handle)`：`dirStack.push(handle); renderLocalDir();`
- `backLocalDir()`：`dirStack.pop(); renderLocalDir();`
- 文件项 dragstart 处理：`draggingFileHandle = handle; e.dataTransfer.effectAllowed = 'copy'; e.dataTransfer.setData('text/plain', name);`

### 4. 改造现有 body drop（`initializeDragAndDrop` 内，L1645-1652）

**关键修正**：`dragstart` 事件不能 `await`（事件回调结束即锁定 dataTransfer），所以 `handle.getFile()` 不能在 dragstart 异步调用。改为在 drop 时异步获取 File。

将 body drop 回调改为 `async`：
```
let files = e.dataTransfer.files;
if (files.length === 0 && draggingFileHandle) {
    const file = await draggingFileHandle.getFile();
    files = [file];
    draggingFileHandle = null;
}
if (files.length > 0) {
    document.getElementById('uploadFileBtn').files = files;
    handleFileUpload({ target: { files } });
}
```

系统拖拽（dataTransfer.files 非空）走原逻辑；右栏拖拽（files 为空、draggingFileHandle 有值）走异步 getFile 分支。复用 `handleFileUpload`（L1231）的 PUT `/f` 链路与 uploadProgressModal 进度 UI。

### 5. switchView 联动（L1130 附近）
grid 模式：隐藏 `#openLocalFolderBtn` 与 `#localFolderPanel`；list 模式：显示按钮（panel 保持上次开合状态）。

## 拖拽冲突处理

- 右栏文件 dragstart 设全局 `draggingFileHandle`
- body drop 优先读 `dataTransfer.files`（系统拖拽），为空且 `draggingFileHandle` 有值时走右栏分支
- 右栏内部 `drop` `stopPropagation`，防止拖回右栏误触发 body 上传

## 降级

不支持 `showDirectoryPicker`（Safari/Firefox）时：`openLocalFolder` 检测后隐藏 `#openLocalFolderBtn`，不影响其他功能。不做 webkitdirectory 降级（扁平列表无法还原目录结构，与"子目录可进入"体验冲突）。

## 复用的现有函数

- `handleFileUpload`（[admin-dashboard.js L1231](file:///Users/yuzhou.jin/Documents/worktable/bootfs/src/main/resources/static/admin-dashboard.js#L1231)）：上传入口，PUT `/f`，FormData 字段 `file`，串行上传 + 进度 UI
- `initializeDragAndDrop`（[L1623](file:///Users/yuzhou.jin/Documents/worktable/bootfs/src/main/resources/static/admin-dashboard.js#L1623)）：body drop 监听，本次仅小改 drop 回调为 async + draggingFileHandle 分支
- 滑出动画范式：[admin-styles.css L123-132](file:///Users/yuzhou.jin/Documents/worktable/bootfs/src/main/resources/static/admin-styles.css#L123-L132) 孤儿 `#sidebar` CSS

## 后续增强（非本期）

- 多文件拖拽（右栏 ctrl/shift 多选，dragstart 存 handle 数组）
- directoryHandle 持久化到 IndexedDB（刷新后恢复，仍需 requestPermission 重新授权）

## 验证

1. Chromium 启动后，列表模式 header 出现"本地文件夹"按钮
2. 点击 → 选择文件夹 → 右栏从右侧滑出，展示文件列表（目录优先）
3. 点击子目录进入下一层，返回按钮回到上级
4. 拖拽文件到左栏列表 → 触发 PUT /f 上传，进度条正常显示
5. 拖回右栏内部不触发上传
6. grid 模式下按钮和面板隐藏
7. Safari/Firefox 下按钮不显示，其他功能不受影响
