## UI Copywriting & Content Rules (Strictly Enforced)

### 1. Zero Meta-Commentary (严禁元叙述与自我解释)
- **NEVER** render descriptive explanations, feature introductions, or "how it works" summaries inside the UI (e.g., subtitles, helper texts, footers, empty states, or card descriptions) unless explicitly requested in the prompt.
- Treat the UI as a **production product**, not a demo or tutorial. Assume the user already knows what the page is for.
- **Forbidden Patterns (绝对禁止出现以下句式与内容):**
  - Explaining the page's purpose: *"This is a dashboard for managing..."*, *"这是一个用于管理……的界面"*
  - Explaining technical implementation or capabilities: *"You can filter items by clicking..."*, *"支持通过……来实现……"*, *"Powered by real-time sync..."*
  - AI filler & marketing fluff: *"Welcome to your workspace"*, *"Seamlessly organize..."*, *"Effortlessly track..."*

### 2. Microcopy & Density Standards (文案极简原则)
- **Labels over Sentences:** Use concise nouns or 1–3 word action verbs (`Save`, `Filter`, `Active Nodes`) instead of full sentences.
- **Empty States:** Only state the current status + 1 primary action button (e.g., `"No projects yet"` + `[+ New Project]`). Do NOT write a paragraph explaining why projects are useful.
- **Tooltips & Helper Text:** Only allowed for destructive warnings, non-obvious keyboard shortcuts, or strict input format constraints (e.g., `"Max 20MB, PNG/JPG"`).

### 3. Bad vs. Good Examples
- **BAD (Do NOT do this):**
  - Header: `File Manager`
  - Subtitle: `This interface allows you to upload, organize, and preview your local files using fast search indexing.`
- **GOOD (Do this):**
  - Header: `Files`
  - Subtitle: *(Omitted entirely)*