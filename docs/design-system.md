# HostID 设计规范

本文档定义了 HostID 产品的视觉设计规范，确保所有相关产品保持一致的设计风格。

## 设计原则

1. **简洁优雅** - 减少视觉噪音，突出核心内容
2. **一致性** - 统一的色彩、间距、圆角、字体
3. **可访问性** - 良好的对比度，清晰的视觉层次
4. **响应式** - 适配各种屏幕尺寸

## 色彩系统

### CSS 变量

所有颜色通过 CSS 变量定义，支持主题切换：

```css
:root {
  /* 背景色 */
  --color-bg-primary: #f9fafb;      /* 主背景 */
  --color-bg-secondary: #ffffff;     /* 卡片/面板背景 */
  --color-bg-tertiary: #f3f4f6;      /* 悬停/高亮背景 */
  --color-bg-hover: #f3f4f6;         /* 悬停状态背景 */

  /* 文字色 */
  --color-text-primary: #111827;     /* 主要文字 */
  --color-text-secondary: #374151;   /* 次要文字 */
  --color-text-tertiary: #6b7280;    /* 辅助文字 */
  --color-text-quaternary: #9ca3af;  /* 占位符/禁用文字 */
  --color-text-muted: #9ca3af;       /* 弱化文字 */

  /* 边框色 */
  --color-border-primary: #e5e7eb;   /* 主要边框 */
  --color-border-secondary: #d1d5db; /* 次要边框 */

  /* 强调色 */
  --color-accent-primary: #0ea5e9;   /* 主强调色 */
  --color-accent-secondary: #0284c7; /* 悬停强调色 */
  --color-accent-muted: rgba(14, 165, 233, 0.1); /* 强调色背景 */
  --color-accent-text: #ffffff;      /* 强调色上的文字 */

  /* 代码块 */
  --color-code-bg: #f3f4f6;          /* 行内代码背景 */
  --color-code-text: #1f2937;        /* 行内代码文字 */
  --color-pre-bg: #1e293b;           /* 代码块背景 */
  --color-pre-text: #e2e8f0;         /* 代码块文字 */
}
```

### 色彩层次

| 层次 | 用途 | Light 模式 | Dark 模式 |
|------|------|------------|-----------|
| 背景-主 | 页面背景 | `#f9fafb` | `#0a0a0a` |
| 背景-次 | 卡片背景 | `#ffffff` | `#141414` |
| 背景-三 | 悬停背景 | `#f3f4f6` | `#1a1a1a` |
| 文字-主 | 标题/正文 | `#111827` | `#e5e5e5` |
| 文字-次 | 描述文字 | `#374151` | `#a3a3a3` |
| 文字-三 | 辅助信息 | `#6b7280` | `#737373` |
| 强调色 | 按钮/链接 | `#0ea5e9` | `#00d98a` |

## 主题系统

### 预设主题概览

| 主题 ID | 名称 | 强调色 | 适用场景 |
|---------|------|--------|----------|
| `light` | 浅色模式 | `#0ea5e9` (蓝) | 默认主题 |
| `dark` | 深色模式 | `#00d98a` (绿) | 开发者偏好 |
| `sakura` | 樱花 | `#ec4899` (粉) | 动漫风格 |
| `starry-night` | 星空夜景 | `#e94560` (红) | 动漫风格 |
| `forest` | 森林 | `#16a34a` (绿) | 自然清新 |
| `ocean` | 海洋 | `#29b6f6` (蓝) | 深邃沉稳 |
| `sunset` | 日落 | `#f59e0b` (橙) | 温暖舒适 |
| `lavender` | 薰衣草 | `#a855f7` (紫) | 优雅浪漫 |

### 主题完整配色

#### Light (浅色模式)

```css
:root.theme-light {
  --color-bg-primary: #f9fafb;
  --color-bg-secondary: #ffffff;
  --color-bg-tertiary: #f3f4f6;
  --color-bg-hover: #f3f4f6;
  --color-text-primary: #111827;
  --color-text-secondary: #374151;
  --color-text-tertiary: #6b7280;
  --color-text-quaternary: #9ca3af;
  --color-text-muted: #9ca3af;
  --color-border-primary: #e5e7eb;
  --color-border-secondary: #d1d5db;
  --color-accent-primary: #0ea5e9;
  --color-accent-secondary: #0284c7;
  --color-accent-muted: rgba(14, 165, 233, 0.1);
  --color-accent-text: #ffffff;
  --color-code-bg: #f3f4f6;
  --color-code-text: #1f2937;
  --color-pre-bg: #1e293b;
  --color-pre-text: #e2e8f0;
}
```

#### Dark (深色模式)

```css
:root.theme-dark {
  --color-bg-primary: #0a0a0a;
  --color-bg-secondary: #141414;
  --color-bg-tertiary: #1a1a1a;
  --color-bg-hover: #1a1a1a;
  --color-text-primary: #e5e5e5;
  --color-text-secondary: #a3a3a3;
  --color-text-tertiary: #737373;
  --color-text-quaternary: #525252;
  --color-text-muted: #525252;
  --color-border-primary: #262626;
  --color-border-secondary: #333333;
  --color-accent-primary: #00d98a;
  --color-accent-secondary: #00c47a;
  --color-accent-muted: rgba(0, 217, 138, 0.1);
  --color-accent-text: #0a0a0a;
  --color-code-bg: #262626;
  --color-code-text: #e5e5e5;
  --color-pre-bg: #0d1117;
  --color-pre-text: #c9d1d9;
}
```

#### Sakura (樱花)

```css
:root.theme-sakura {
  --color-bg-primary: #fef2f2;
  --color-bg-secondary: #ffffff;
  --color-bg-tertiary: #fce7f3;
  --color-bg-hover: #fce7f3;
  --color-text-primary: #831843;
  --color-text-secondary: #9d174d;
  --color-text-tertiary: #be185d;
  --color-text-quaternary: #ec4899;
  --color-text-muted: #ec4899;
  --color-border-primary: #fbcfe8;
  --color-border-secondary: #f9a8d4;
  --color-accent-primary: #ec4899;
  --color-accent-secondary: #db2777;
  --color-accent-muted: rgba(236, 72, 153, 0.1);
  --color-accent-text: #ffffff;
  --color-code-bg: #fce7f3;
  --color-code-text: #831843;
  --color-pre-bg: #831843;
  --color-pre-text: #fce7f3;
}
```

#### Starry Night (星空夜景)

```css
:root.theme-starry-night {
  --color-bg-primary: #0f0f23;
  --color-bg-secondary: #1a1a2e;
  --color-bg-tertiary: #16213e;
  --color-bg-hover: #16213e;
  --color-text-primary: #eaeaea;
  --color-text-secondary: #c4c4c4;
  --color-text-tertiary: #888888;
  --color-text-quaternary: #666666;
  --color-text-muted: #666666;
  --color-border-primary: #2d2d44;
  --color-border-secondary: #3d3d5c;
  --color-accent-primary: #e94560;
  --color-accent-secondary: #ff6b6b;
  --color-accent-muted: rgba(233, 69, 96, 0.1);
  --color-accent-text: #ffffff;
  --color-code-bg: #1a1a2e;
  --color-code-text: #eaeaea;
  --color-pre-bg: #0a0a1a;
  --color-pre-text: #c4c4c4;
}
```

#### Forest (森林)

```css
:root.theme-forest {
  --color-bg-primary: #f0fdf4;
  --color-bg-secondary: #ffffff;
  --color-bg-tertiary: #dcfce7;
  --color-bg-hover: #dcfce7;
  --color-text-primary: #14532d;
  --color-text-secondary: #166534;
  --color-text-tertiary: #15803d;
  --color-text-quaternary: #22c55e;
  --color-text-muted: #22c55e;
  --color-border-primary: #bbf7d0;
  --color-border-secondary: #86efac;
  --color-accent-primary: #16a34a;
  --color-accent-secondary: #15803d;
  --color-accent-muted: rgba(22, 163, 74, 0.1);
  --color-accent-text: #ffffff;
  --color-code-bg: #dcfce7;
  --color-code-text: #14532d;
  --color-pre-bg: #14532d;
  --color-pre-text: #dcfce7;
}
```

#### Ocean (海洋)

```css
:root.theme-ocean {
  --color-bg-primary: #0c1929;
  --color-bg-secondary: #132f4c;
  --color-bg-tertiary: #1a4971;
  --color-bg-hover: #1a4971;
  --color-text-primary: #e3f2fd;
  --color-text-secondary: #bbdefb;
  --color-text-tertiary: #90caf9;
  --color-text-quaternary: #64b5f6;
  --color-text-muted: #64b5f6;
  --color-border-primary: #1e3a5f;
  --color-border-secondary: #2e5a8a;
  --color-accent-primary: #29b6f6;
  --color-accent-secondary: #0288d1;
  --color-accent-muted: rgba(41, 182, 246, 0.1);
  --color-accent-text: #0c1929;
  --color-code-bg: #132f4c;
  --color-code-text: #e3f2fd;
  --color-pre-bg: #0a1628;
  --color-pre-text: #bbdefb;
}
```

#### Sunset (日落)

```css
:root.theme-sunset {
  --color-bg-primary: #fffbeb;
  --color-bg-secondary: #ffffff;
  --color-bg-tertiary: #fef3c7;
  --color-bg-hover: #fef3c7;
  --color-text-primary: #78350f;
  --color-text-secondary: #92400e;
  --color-text-tertiary: #b45309;
  --color-text-quaternary: #d97706;
  --color-text-muted: #d97706;
  --color-border-primary: #fde68a;
  --color-border-secondary: #fcd34d;
  --color-accent-primary: #f59e0b;
  --color-accent-secondary: #d97706;
  --color-accent-muted: rgba(245, 158, 11, 0.1);
  --color-accent-text: #ffffff;
  --color-code-bg: #fef3c7;
  --color-code-text: #78350f;
  --color-pre-bg: #78350f;
  --color-pre-text: #fef3c7;
}
```

#### Lavender (薰衣草)

```css
:root.theme-lavender {
  --color-bg-primary: #faf5ff;
  --color-bg-secondary: #ffffff;
  --color-bg-tertiary: #f3e8ff;
  --color-bg-hover: #f3e8ff;
  --color-text-primary: #581c87;
  --color-text-secondary: #6b21a8;
  --color-text-tertiary: #7e22ce;
  --color-text-quaternary: #a855f7;
  --color-text-muted: #a855f7;
  --color-border-primary: #e9d5ff;
  --color-border-secondary: #d8b4fe;
  --color-accent-primary: #a855f7;
  --color-accent-secondary: #9333ea;
  --color-accent-muted: rgba(168, 85, 247, 0.1);
  --color-accent-text: #ffffff;
  --color-code-bg: #f3e8ff;
  --color-code-text: #581c87;
  --color-pre-bg: #581c87;
  --color-pre-text: #f3e8ff;
}
```

### 主题配置结构

```typescript
interface ThemeConfig {
  id: string                    // 主题唯一标识
  name: string                  // 中文名称
  nameEn: string                // 英文名称
  description?: string          // 中文描述
  descriptionEn?: string        // 英文描述
  colors: ThemeColors           // 颜色配置
  background?: ThemeBackground  // 背景配置（可选）
  customCSS?: string            // 自定义 CSS（可选）
}

interface ThemeColors {
  bg: {
    primary: string    // 主背景
    secondary: string  // 次背景
    tertiary: string   // 三级背景
    hover: string      // 悬停背景
  }
  text: {
    primary: string    // 主要文字
    secondary: string  // 次要文字
    tertiary: string   // 辅助文字
    quaternary: string // 四级文字
    muted: string      // 弱化文字
  }
  border: {
    primary: string    // 主要边框
    secondary: string  // 次要边框
  }
  accent: {
    primary: string    // 主强调色
    secondary: string  // 悬停强调色
    muted: string      // 强调色背景
    text: string       // 强调色文字
  }
  code?: {
    bg: string         // 行内代码背景
    text: string       // 行内代码文字
  }
  pre?: {
    bg: string         // 代码块背景
    text: string       // 代码块文字
  }
}
```

## 组件规范

### 按钮

**主要按钮**
```css
.btn-primary {
  background-color: var(--color-accent-primary);
  color: var(--color-accent-text);
  font-weight: 500;
  padding: 0.625rem 1rem;
  border-radius: 0.5rem;
  transition: all 0.2s;
}

.btn-primary:hover {
  background-color: var(--color-accent-secondary);
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
```

**次要按钮**
```css
.btn-secondary {
  background-color: var(--color-bg-secondary);
  color: var(--color-text-secondary);
  border: 1px solid var(--color-border-secondary);
  font-weight: 500;
  padding: 0.625rem 1rem;
  border-radius: 0.5rem;
  transition: all 0.2s;
}

.btn-secondary:hover {
  background-color: var(--color-bg-tertiary);
}
```

### 输入框

```css
.input-field {
  width: 100%;
  padding: 0.625rem 1rem;
  border-radius: 0.5rem;
  background-color: var(--color-bg-secondary);
  border: 1px solid var(--color-border-secondary);
  color: var(--color-text-primary);
  transition: all 0.2s;
}

.input-field:focus {
  outline: none;
  border-color: var(--color-accent-primary);
  box-shadow: 0 0 0 2px var(--color-accent-muted);
}

.input-field::placeholder {
  color: var(--color-text-muted);
}
```

### 卡片

```css
.card {
  background-color: var(--color-bg-secondary);
  border: 1px solid var(--color-border-primary);
  border-radius: 0.75rem;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}
```

## 间距系统

使用 Tailwind CSS 默认间距：

| Token | 值 | 用途 |
|-------|-----|------|
| `p-2` | 0.5rem (8px) | 紧凑内边距 |
| `p-4` | 1rem (16px) | 标准内边距 |
| `p-6` | 1.5rem (24px) | 宽松内边距 |
| `p-8` | 2rem (32px) | 卡片内边距 |
| `gap-2` | 0.5rem (8px) | 紧凑间距 |
| `gap-4` | 1rem (16px) | 标准间距 |
| `gap-6` | 1.5rem (24px) | 宽松间距 |

## 圆角系统

| Token | 值 | 用途 |
|-------|-----|------|
| `rounded` | 0.25rem (4px) | 小元素 |
| `rounded-lg` | 0.5rem (8px) | 按钮、输入框 |
| `rounded-xl` | 0.75rem (12px) | 卡片 |
| `rounded-2xl` | 1rem (16px) | 大卡片、弹窗 |
| `rounded-full` | 9999px | 头像、徽章 |

## 字体系统

### 字体族

```css
font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
```

### 字体大小

| Token | 值 | 用途 |
|-------|-----|------|
| `text-xs` | 0.75rem (12px) | 辅助信息 |
| `text-sm` | 0.875rem (14px) | 次要内容 |
| `text-base` | 1rem (16px) | 正文 |
| `text-lg` | 1.125rem (18px) | 小标题 |
| `text-xl` | 1.25rem (20px) | 标题 |
| `text-2xl` | 1.5rem (24px) | 大标题 |

### 字重

| Token | 值 | 用途 |
|-------|-----|------|
| `font-normal` | 400 | 正文 |
| `font-medium` | 500 | 按钮、标签 |
| `font-semibold` | 600 | 小标题 |
| `font-bold` | 700 | 大标题 |

## 动画规范

### 过渡时长

```css
--transition-fast: 150ms;    /* 快速交互 */
--transition-normal: 200ms;  /* 标准过渡 */
--transition-slow: 300ms;    /* 慢速过渡 */
```

### 常用动画

**加载进度条**
```css
@keyframes loading {
  0% { width: 0%; }
  100% { width: 100%; }
}

.animate-loading {
  animation: loading 1.5s ease-in-out forwards;
}
```

**旋转加载**
```css
.animate-spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
```

**脉冲动画**
```css
.animate-pulse {
  animation: pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}
```

## 图标规范

使用 [Lucide Icons](https://lucide.dev/) 图标库。

### 常用图标

| 图标 | 名称 | 用途 |
|------|------|------|
| 📧 | `Mail` | 邮件 |
| 📥 | `Inbox` | 收件箱 |
| 📤 | `Send` | 发送 |
| 🗑️ | `Trash2` | 删除 |
| ⭐ | `Star` | 收藏 |
| 🔍 | `Search` | 搜索 |
| ⚙️ | `Settings` | 设置 |
| 👤 | `User` | 用户 |
| 🌙 | `Moon` | 暗色模式 |
| ☀️ | `Sun` | 亮色模式 |

### 图标尺寸

| 尺寸 | 值 | 用途 |
|------|-----|------|
| `w-4 h-4` | 1rem (16px) | 小图标、按钮内 |
| `w-5 h-5` | 1.25rem (20px) | 标准图标 |
| `w-6 h-6` | 1.5rem (24px) | 大图标 |
| `w-8 h-8` | 2rem (32px) | 特大图标 |

## 响应式断点

使用 Tailwind CSS 默认断点：

| 断点 | 最小宽度 | 用途 |
|------|----------|------|
| `sm` | 640px | 大手机 |
| `md` | 768px | 平板 |
| `lg` | 1024px | 小桌面 |
| `xl` | 1280px | 桌面 |
| `2xl` | 1536px | 大桌面 |

## 最佳实践

### 1. 使用 CSS 变量

始终使用 CSS 变量而非硬编码颜色值：

```css
/* ✅ 正确 */
color: var(--color-text-primary);
background-color: var(--color-bg-secondary);

/* ❌ 错误 */
color: #111827;
background-color: #ffffff;
```

### 2. 语义化命名

使用语义化的类名和变量名：

```css
/* ✅ 正确 */
--color-text-primary
--color-bg-secondary
--color-accent-primary

/* ❌ 错误 */
--color-black
--color-white
--color-blue
```

### 3. 保持一致性

相同功能的组件使用相同的样式：

```css
/* 所有卡片使用统一样式 */
.card {
  background-color: var(--color-bg-secondary);
  border: 1px solid var(--color-border-primary);
  border-radius: 0.75rem;
}
```

### 4. 支持主题切换

确保所有颜色都支持主题切换：

```tsx
// 使用 style 属性动态应用主题色
<div style={{ 
  backgroundColor: 'var(--color-bg-secondary)',
  color: 'var(--color-text-primary)'
}}>
```

## 示例代码

### React 组件示例

```tsx
import { Mail } from 'lucide-react'

export function Card({ title, description }) {
  return (
    <div 
      className="rounded-xl p-6 shadow-sm"
      style={{ 
        backgroundColor: 'var(--color-bg-secondary)',
        border: '1px solid var(--color-border-primary)'
      }}
    >
      <div 
        className="w-12 h-12 rounded-lg flex items-center justify-center mb-4"
        style={{ backgroundColor: 'var(--color-accent-muted)' }}
      >
        <Mail className="w-6 h-6" style={{ color: 'var(--color-accent-primary)' }} />
      </div>
      
      <h3 
        className="text-lg font-semibold mb-2"
        style={{ color: 'var(--color-text-primary)' }}
      >
        {title}
      </h3>
      
      <p style={{ color: 'var(--color-text-tertiary)' }}>
        {description}
      </p>
      
      <button
        className="mt-4 px-4 py-2 rounded-lg font-medium transition-colors"
        style={{ 
          backgroundColor: 'var(--color-accent-primary)',
          color: 'var(--color-accent-text)'
        }}
      >
        Learn More
      </button>
    </div>
  )
}
```

### CSS 模块示例

```css
/* Card.module.css */
.card {
  background-color: var(--color-bg-secondary);
  border: 1px solid var(--color-border-primary);
  border-radius: 0.75rem;
  padding: 1.5rem;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}

.title {
  color: var(--color-text-primary);
  font-size: 1.125rem;
  font-weight: 600;
  margin-bottom: 0.5rem;
}

.description {
  color: var(--color-text-tertiary);
  font-size: 0.875rem;
  line-height: 1.5;
}

.button {
  background-color: var(--color-accent-primary);
  color: var(--color-accent-text);
  padding: 0.625rem 1rem;
  border-radius: 0.5rem;
  font-weight: 500;
  transition: background-color 0.2s;
}

.button:hover {
  background-color: var(--color-accent-secondary);
}
```
