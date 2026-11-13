// TFS Admin Dashboard - Modern JavaScript Implementation

// Theme System
const THEMES = [
    { id: 'light', name: '浅色模式', accent: '#0ea5e9' },
    { id: 'dark', name: '深色模式', accent: '#00d98a' },
    { id: 'sakura', name: '樱花', accent: '#ec4899' },
    { id: 'starry-night', name: '星空夜景', accent: '#e94560' },
    { id: 'forest', name: '森林', accent: '#16a34a' },
    { id: 'ocean', name: '海洋', accent: '#29b6f6' },
    { id: 'sunset', name: '日落', accent: '#f59e0b' },
    { id: 'lavender', name: '薰衣草', accent: '#a855f7' }
];

function initTheme() {
    const savedTheme = localStorage.getItem('tfs-theme') || 'light';
    applyTheme(savedTheme);
}

function setTheme(themeId) {
    applyTheme(themeId);
    localStorage.setItem('tfs-theme', themeId);
    
    document.cookie = `tfs-theme=${themeId};path=/;max-age=31536000`;
    
    const dropdown = document.getElementById('themeDropdown');
    if (dropdown) {
        dropdown.classList.add('hidden');
    }
}

function applyTheme(themeId) {
    const root = document.documentElement;
    root.className = '';
    root.classList.add(`theme-${themeId}`);
    
    updateThemeOptionStyles();
}

function updateThemeOptionStyles() {
    const currentTheme = localStorage.getItem('tfs-theme') || 'light';
    document.querySelectorAll('.theme-option').forEach(btn => {
        const themeId = btn.getAttribute('onclick').match(/'([^']+)'/)?.[1];
        if (themeId === currentTheme) {
            btn.style.backgroundColor = 'var(--color-accent-muted)';
            btn.style.color = 'var(--color-accent-primary)';
        } else {
            btn.style.backgroundColor = 'transparent';
            btn.style.color = 'var(--color-text-secondary)';
        }
    });
}

// Global state
let currentPage = 1;
let totalPages = 1;
let pageSize = 10;
let selectedFiles = new Set();
let selectedFolders = new Set();
let currentView = localStorage.getItem('tfs-view') || 'list'; // 'list', 'grid' (waterfall) or 'icons'
if (currentView !== 'grid' && currentView !== 'icons') currentView = 'list';
let showAllFiles = localStorage.getItem('tfs-show-all') === 'true';
let searchQuery = showAllFiles ? 'meta:all' : '';
let currentFolder = new URLSearchParams(window.location.search).get('folder') || '';
let folderRequestVersion = 0;
let dirStack = []; // FileSystemDirectoryHandle 栈，用于本地文件夹右栏返回上级
let draggingFileHandle = null; // 当前拖拽中的 FileSystemFileHandle
let storedDirHandle = null; // 持久化的根目录句柄（IndexedDB）

// Grid view (waterfall) specific state
let gridPage = 0;
let gridHasMore = true;
let gridLoading = false;
const GRID_PAGE_SIZE = 10;

const GRID_TARGET_COUNT = 10;
const WATERFALL_COLUMNS = 6;
let gridTotal = 0;

// NSFW blur toggle state (true = blurred/hidden by default)
let nsfwBlurActive = true;

// Initialize dashboard
document.addEventListener('DOMContentLoaded', function() {
    initTheme();
    initializeEventListeners();
    restoreViewState();
    initializeLocalFolderPanel();
    initializeIconBrowser();
    loadFileList();
    initializeDragAndDrop();
});

// 从 localStorage 恢复视图状态（视图模式 + 只显示主存储），避免刷新后回到默认
function restoreViewState() {
    applyViewChrome(currentView, false);

    // Restore NSFW blur state from localStorage
    const savedNsfw = localStorage.getItem('tfs-nsfw-blur');
    if (savedNsfw === 'false') {
        nsfwBlurActive = false;
        const nsfwToggleBtn = document.getElementById('nsfwToggleBtn');
        if (nsfwToggleBtn) nsfwToggleBtn.classList.add('active');
    }

    if (showAllFiles) {
        const btn = document.getElementById('showAllBtn');
        if (btn) {
            btn.style.backgroundColor = 'var(--color-accent-muted)';
            btn.style.color = 'var(--color-accent-primary)';
        }
        const searchInput = document.getElementById('searchInput');
        if (searchInput) searchInput.value = 'meta:all';
    }

    restoreLocalPanel();
}

// Initialize event listeners
function initializeEventListeners() {
    // View toggle
    document.querySelectorAll('.view-toggle-btn').forEach(button => {
        button.addEventListener('click', () => switchView(button.dataset.view));
    });
    
    // NSFW toggle
    const nsfwToggleBtn = document.getElementById('nsfwToggleBtn');
    if (nsfwToggleBtn) {
        nsfwToggleBtn.addEventListener('click', () => {
            nsfwBlurActive = !nsfwBlurActive;
            if (!nsfwBlurActive) {
                nsfwToggleBtn.classList.add('active');
            } else {
                nsfwToggleBtn.classList.remove('active');
            }
            localStorage.setItem('tfs-nsfw-blur', nsfwBlurActive ? 'true' : 'false');
            applyNsfwBlurToAll();
        });
    }
    
    // Theme menu toggle
    const themeMenuBtn = document.getElementById('themeMenuBtn');
    const themeDropdown = document.getElementById('themeDropdown');
    if (themeMenuBtn && themeDropdown) {
        themeMenuBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            themeDropdown.classList.toggle('hidden');
            updateThemeOptionStyles();
        });
        
        document.addEventListener('click', (e) => {
            if (!themeDropdown.contains(e.target) && !themeMenuBtn.contains(e.target)) {
                themeDropdown.classList.add('hidden');
            }
        });
    }

    // Upload menu toggle (split button dropdown)
    const uploadMenuBtn = document.getElementById('uploadMenuBtn');
    const uploadDropdown = document.getElementById('uploadDropdown');
    if (uploadMenuBtn && uploadDropdown) {
        uploadMenuBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            uploadDropdown.classList.toggle('hidden');
        });

        document.addEventListener('click', (e) => {
            if (!uploadDropdown.contains(e.target) && !uploadMenuBtn.contains(e.target)) {
                uploadDropdown.classList.add('hidden');
            }
        });

        // 选中任意项后收起下拉
        uploadDropdown.addEventListener('click', () => {
            uploadDropdown.classList.add('hidden');
        });
    }

    // Search with autocomplete
    const searchInput = document.getElementById('searchInput');
    const searchSuggestions = document.getElementById('searchSuggestions');
    const suggestionList = document.getElementById('suggestionList');
    let searchTimeout = null;
    
    function performSearch() {
        searchQuery = searchInput.value.trim();
        currentPage = 1;
        gridPage = 0;
        if (searchSuggestions) {
            searchSuggestions.classList.add('hidden');
        }
        loadFileList();
    }

    const homeBtn = document.getElementById('homeBtn');
    if (homeBtn) {
        homeBtn.addEventListener('click', () => {
            openFolder('');
        });
    }
    
    if (searchInput) {
        searchInput.addEventListener('input', function(e) {
            const query = e.target.value.trim();
            
            clearTimeout(searchTimeout);
            
            if (query.length < 2) {
                if (searchSuggestions) {
                    searchSuggestions.classList.add('hidden');
                }
                return;
            }
            
            searchTimeout = setTimeout(() => {
                fetchSearchSuggestions(query);
            }, 300);
        });
        
        searchInput.addEventListener('keydown', function(e) {
            if (e.key === 'Enter') {
                e.preventDefault();
                performSearch();
            } else if (e.key === 'Escape') {
                if (searchSuggestions) {
                    searchSuggestions.classList.add('hidden');
                }
            }
        });
        
        searchInput.addEventListener('focus', function(e) {
            const query = e.target.value.trim();
            if (query.length >= 2) {
                fetchSearchSuggestions(query);
            }
        });
    }
    
    const searchBtn = document.getElementById('searchBtn');
    if (searchBtn) {
        searchBtn.addEventListener('click', performSearch);
    }
    
    if (searchSuggestions) {
        document.addEventListener('click', function(e) {
            if (!searchInput.contains(e.target) && !searchSuggestions.contains(e.target)) {
                searchSuggestions.classList.add('hidden');
            }
        });
    }
    
    function fetchSearchSuggestions(query) {
        fetch(`/searchSuggestions?q=${encodeURIComponent(query)}`)
            .then(response => response.json())
            .then(data => {
                if (data && data.length > 0 && suggestionList && searchSuggestions) {
                    renderSuggestions(data);
                    searchSuggestions.classList.remove('hidden');
                } else if (searchSuggestions) {
                    searchSuggestions.classList.add('hidden');
                }
            })
            .catch(err => {
                console.error('Search suggestions error:', err);
                if (searchSuggestions) {
                    searchSuggestions.classList.add('hidden');
                }
            });
    }
    
    function renderSuggestions(suggestions) {
        if (!suggestionList) return;
        suggestionList.innerHTML = '';
        suggestions.forEach(file => {
            const item = document.createElement('div');
            item.className = 'search-suggestion-item';
            item.innerHTML = `
                <div class="file-name">${BootFSSecurity.html(file.fileName)}</div>
                <div class="file-meta">${file.uuid} · ${formatFileSize(file.size || 0)}</div>
            `;
            item.addEventListener('click', () => {
                if (searchInput) {
                    searchInput.value = file.fileName;
                }
                if (searchSuggestions) {
                    searchSuggestions.classList.add('hidden');
                }
                searchQuery = file.fileName;
                currentPage = 1;
                loadFileList();
            });
            suggestionList.appendChild(item);
        });
    }
    
    // Action buttons
    document.getElementById('showAllBtn').addEventListener('click', toggleShowAll);
    document.getElementById('hlsConvertBtn').addEventListener('click', convertToHLS);
    document.getElementById('publishToGalleryBtn').addEventListener('click', publishToGallery);
    document.getElementById('batchDownloadBtn').addEventListener('click', batchDownload);
    document.getElementById('batchDeleteBtn').addEventListener('click', showDeleteConfirm);
    document.getElementById('tsUploadBtn').addEventListener('click', showTsUploadModal);
    
    // Upload
    document.getElementById('uploadFileBtn').addEventListener('change', handleFileUpload);
    document.getElementById('newFolderBtn')?.addEventListener('click', createFolder);
    document.getElementById('newFolderForm')?.addEventListener('submit', submitCreateFolder);
    document.getElementById('newFolderCancel')?.addEventListener('click', closeCreateFolder);
    document.getElementById('newFolderModal')?.addEventListener('keydown', event => {
        if (event.key === 'Escape') { event.preventDefault(); closeCreateFolder(); }
    });
    document.getElementById('comicUploadBtn').addEventListener('change', handleComicUpload);
    
    // Pagination
    document.getElementById('prevPage').addEventListener('click', () => changePage(-1));
    document.getElementById('nextPage').addEventListener('click', () => changePage(1));
    document.getElementById('pageSizeSelect').addEventListener('change', (e) => changePageSize(e.target.value));
    document.getElementById('jumpPageInput').addEventListener('keydown', (e) => {
        if (e.key === 'Enter') jumpToPage();
    });
    
    // Select all checkbox
    document.getElementById('selectAll').addEventListener('change', handleSelectAll);
    
    // User menu dropdown
    const userMenuBtn = document.getElementById('userMenuBtn');
    const userDropdown = document.getElementById('userDropdown');
    if (userMenuBtn && userDropdown) {
        userMenuBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            userDropdown.classList.toggle('hidden');
        });
        
        // Close dropdown when clicking outside
        document.addEventListener('click', (e) => {
            if (!userMenuBtn.contains(e.target) && !userDropdown.contains(e.target)) {
                userDropdown.classList.add('hidden');
            }
        });
    }
    
    // Delete confirmation
    document.getElementById('confirmDeleteBtn').addEventListener('click', confirmDelete);

    // Rename confirmation
    document.getElementById('confirmRenameBtn').addEventListener('click', confirmRename);
    const renameInput = document.getElementById('renameInput');
    renameInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            e.preventDefault();
            confirmRename();
        } else if (e.key === 'Escape') {
            closeRenameModal();
        }
    });
    
    // Cancel selection
    document.getElementById('cancelSelection').addEventListener('click', clearSelection);
    
    // Keyboard shortcuts
    document.addEventListener('keydown', function(e) {
        if (TextFileEditor.isOpen()) return;
        // Delete: Delete selected
        if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'a' && currentView === 'icons') {
            const tag = document.activeElement && document.activeElement.tagName;
            if (tag !== 'INPUT' && tag !== 'TEXTAREA') {
                e.preventDefault();
                selectAllIcons();
            }
        }
        if (e.key === 'Delete' && (selectedFiles.size > 0 || selectedFolders.size > 0)) {
            e.preventDefault();
            showDeleteConfirm();
        }
        // Escape: Clear selection
        if (e.key === 'Escape') {
            clearSelection();
            closeImagePreview();
            closeAudioPreview();
            closeDeleteModal();
        }
        // Arrow keys: navigate image preview when modal is open (grid mode only)
        const imagePreviewOpen = !document.getElementById('imagePreviewModal').classList.contains('hidden');
        if (imagePreviewOpen && currentView !== 'list') {
            // Let video.js handle arrow keys when the player is focused
            const target = e.target;
            const isVideoControl = target && target.closest && (target.closest('.video-js') || target.tagName === 'VIDEO');
            if (!isVideoControl) {
                if (e.key === 'ArrowLeft') {
                    e.preventDefault();
                    previewPreviousImage();
                } else if (e.key === 'ArrowRight') {
                    e.preventDefault();
                    previewNextImage();
                }
            }
        }
    });
}

// A typed search retains global full-text search across folders.
function isGlobalFileSearch() {
    return searchQuery.replace(/meta:(all|local)\b/gi, '').trim().length > 0;
}

function fileListUrl(params) {
    if (isGlobalFileSearch()) return `/queryPageOffset?${params}`;
    params.set('folder', currentFolder);
    return `/api/folders?${params}`;
}

function normalizeFolderRows(data) {
    if (!data || !Array.isArray(data.rows)) return data;
    data.rows = data.rows.map(file => {
        if (file.folder || !file.fileName?.endsWith('/')) return file;
        const path = file.fileName;
        const name = path.slice(0, -1).split('/').pop();
        return {...file, folder: true, folderPath: path, fileName: name || '/', size: 0};
    });
    return data;
}

function renderFolderBreadcrumbs() {
    const container = document.getElementById('folderBreadcrumbs');
    if (!container) return;
    container.replaceChildren();
    const addCrumb = (name, path) => {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'theme-text-btn break-all';
        button.textContent = name;
        button.dataset.folderPath = path;
        button.addEventListener('click', () => openFolder(path));
        container.appendChild(button);
    };
    addCrumb('全部文件', '');
    let path = '';
    const segments = currentFolder.endsWith('/') ? currentFolder.slice(0, -1).split('/') : [];
    for (const segment of segments) {
        path += segment + '/';
        const separator = document.createElement('span');
        separator.textContent = '›';
        separator.setAttribute('aria-hidden', 'true');
        container.appendChild(separator);
        addCrumb(segment || '/', path);
    }
    if (isGlobalFileSearch()) {
        const label = document.createElement('span');
        label.textContent = '· 所有文件夹的搜索结果';
        container.appendChild(label);
    }
}

function openFolder(path) {
    currentFolder = path;
    currentPage = 1;
    searchQuery = showAllFiles ? 'meta:all' : '';
    document.getElementById('searchInput').value = searchQuery;
    clearSelection();
    const url = new URL(window.location.href);
    if (path) url.searchParams.set('folder', path);
    else url.searchParams.delete('folder');
    history.replaceState(null, '', url);
    gridLoading = false;
    loadFileList();
}

function createFolder() {
    const modal = document.getElementById('newFolderModal');
    modal.dataset.folder = currentFolder;
    document.getElementById('newFolderName').value = '';
    document.getElementById('newFolderError').textContent = '';
    document.getElementById('newFolderSubmit').disabled = false;
    modal.classList.remove('hidden');
    document.getElementById('newFolderName').focus();
}

function closeCreateFolder() {
    document.getElementById('newFolderModal').classList.add('hidden');
    document.getElementById('newFolderBtn').focus();
}

async function submitCreateFolder(event) {
    event.preventDefault();
    const name = document.getElementById('newFolderName').value;
    const error = document.getElementById('newFolderError');
    if (!name.trim() || /[\/\\\x00-\x1f\x7f]/.test(name) || name === '.' || name === '..') {
        error.textContent = '请输入有效的文件夹名称，不含斜杠或控制字符。';
        return;
    }
    const submit = document.getElementById('newFolderSubmit');
    submit.disabled = true;
    error.textContent = '';
    try {
        const response = await fetch('/api/folders', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({folder: document.getElementById('newFolderModal').dataset.folder || '', name})
        });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        closeCreateFolder();
        showToast('文件夹已创建', 'success');
        refreshFileList();
    } catch (failure) {
        error.textContent = '创建文件夹失败，请重试。';
    } finally {
        submit.disabled = false;
    }
}

function folderIcon(list = false) {
    return `<svg class="${list ? 'w-5 h-5' : 'w-8 h-8 flex-shrink-0'}" style="color: var(--color-accent-${list ? 'secondary' : 'primary'});" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="${list ? '2' : '1.5'}" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z"></path></svg>`;
}

function listTags(tags, editable = true) {
    return `<div class="flex items-center gap-1.5 flex-wrap" style="max-width: 240px;">
        ${(tags || []).map(tag => {
            const label = tag.namespace && tag.namespace !== 'manual' ? `${tag.namespace}:${tag.name}` : tag.name;
            const indexFormat = tag.namespace + ':' + tag.name;
            return `<span class="tag-badge" title="${BootFSSecurity.html(label)}">
                <span class="tag-badge-text">${BootFSSecurity.html(label)}</span>
                ${editable ? `<button class="tag-delete-btn" data-tag="${BootFSSecurity.html(indexFormat)}" title="删除标签">
                    <svg width="8" height="8" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
                </button>` : ''}
            </span>`;
        }).join('')}
        <button class="tag-add-btn" title="${editable ? '添加标签' : '请在来源节点管理此文件夹'}" ${editable ? '' : 'disabled'}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>
        </button>
    </div>`;
}

function createFolderRow(folder) {
    const editable = folder.managed !== false;
    const disabled = editable ? '' : 'disabled title="请在来源节点管理此文件夹"';
    return `<tr class="folder-row" style="background-color: var(--color-bg-secondary); border-color: var(--color-border-primary);" data-folder-path="${BootFSSecurity.html(folder.folderPath)}">
        <td class="w-12 px-4 py-4 whitespace-nowrap"></td>
        <td class="px-6 py-4">
            <div class="flex items-center">
                <button type="button" class="folder-open-btn list-thumbnail flex-shrink-0 h-10 w-10 overflow-hidden theme-rounded-lg" title="打开文件夹">
                    <span class="h-10 w-10 theme-rounded-lg flex items-center justify-center" style="background-color: var(--color-bg-tertiary);">${folderIcon(true)}</span>
                </button>
                <div class="ml-4">
                    <button type="button" class="folder-open-btn text-sm font-medium text-left theme-text-btn" style="color: var(--color-text-primary);">${escapeFileName(folder.fileName || '')}</button>
                    <div class="text-xs" style="color: var(--color-text-muted);">${BootFSSecurity.html(String(folder.uuid || '—'))}</div>
                </div>
            </div>
        </td>
        <td class="created-col px-4 py-3 whitespace-nowrap text-sm" style="color: var(--color-text-secondary);">${folder.createdAt ? formatDateTime(folder.createdAt) : '—'}</td>
        <td class="tag-col px-4 py-3 whitespace-nowrap">${listTags(folder.tags, editable)}</td>
        <td class="px-6 py-4 whitespace-nowrap"><div class="flex space-x-1"><span class="px-2 py-1 text-xs rounded-md" style="background-color: var(--color-bg-tertiary); color: var(--color-text-secondary);">文件夹</span></div></td>
        <td class="px-6 py-4 whitespace-nowrap text-sm" style="color: var(--color-text-secondary);">—</td>
        <td class="px-6 py-4 whitespace-nowrap text-sm font-medium"><div class="flex space-x-2">
            <button type="button" class="folder-open-btn theme-text-btn" style="color: var(--color-accent-primary);">打开</button>
            <button type="button" class="folder-rename-btn theme-text-btn disabled:opacity-50" style="color: var(--color-text-secondary);" ${disabled}>重命名</button>
            <button type="button" class="folder-delete-btn theme-text-btn disabled:opacity-50" style="color: var(--color-danger);" ${disabled}>删除</button>
        </div></td>
    </tr>`;
}

function createFolderCard(folder) {
    return `<div class="waterfall-card theme-card theme-rounded-xl overflow-hidden cursor-pointer p-4" role="button" tabindex="0" aria-label="${BootFSSecurity.html('打开文件夹 ' + folder.fileName)}">
        <div class="flex justify-center py-8">${folderIcon()}</div>
        <p class="text-sm font-medium truncate theme-page-text-primary" title="${BootFSSecurity.html(folder.fileName)}">${BootFSSecurity.html(folder.fileName || '/')}</p>
        <p class="text-xs mt-1 theme-page-text-muted">文件夹</p>
    </div>`;
}

// Load file list
function loadFileList() {
    if (currentView === 'list') {
        loadListView();
    } else if (currentView === 'icons') {
        loadIconView(true);
    } else {
        loadGridView(true); // reset = true
    }
}

// Load list view (paginated)
function loadListView() {
    const requestVersion = ++folderRequestVersion;
    renderFolderBreadcrumbs();
    showLoadingOverlay();
    
    const params = new URLSearchParams({
        pageNumber: (currentPage - 1) * pageSize,
        pageSize: pageSize,
        search: searchQuery
    });
    
    fetch(fileListUrl(params))
        .then(response => {
            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }
            return response.json().then(normalizeFolderRows);
        })
        .then(data => {
            if (requestVersion !== folderRequestVersion) return;
            if (data && data.rows) {
                renderFileList(data);
                updateStats(data);
                updatePagination(data);
            } else {
                console.error('Invalid data format:', data);
            }
        })
        .catch(error => {
            if (requestVersion !== folderRequestVersion) return;
            console.error('Error loading files:', error);
            if (error.message && !error.message.includes('Cannot read properties')) {
                showToast('加载文件列表失败', 'error');
            }
        })
        .finally(() => {
            if (requestVersion === folderRequestVersion) hideLoadingOverlay();
        });
}

// Load grid view (waterfall with load more button)
function loadGridView(reset = false) {
    if (gridLoading && !reset) return;
    
    if (reset) {
        folderRequestVersion++;
        renderFolderBreadcrumbs();
        gridPage = 0;
        gridHasMore = true;
        gridLoading = false;
        gridTotal = 0;
        previewMediaFiles = [];
        
        const container = document.getElementById('waterfallContainer');
        if (container) {
            container.innerHTML = '';
            for (let i = 0; i < WATERFALL_COLUMNS; i++) {
                const column = document.createElement('div');
                column.className = 'flex-1 min-w-0 flex flex-col gap-4';
                container.appendChild(column);
            }
        }
        const noMoreEl = document.getElementById('noMoreContent');
        if (noMoreEl) {
            noMoreEl.classList.add('hidden');
        }
        const loadMoreBtn = document.getElementById('loadMoreBtn');
        if (loadMoreBtn) {
            loadMoreBtn.classList.remove('hidden');
        }
        
        const loadMoreButton = document.getElementById('loadMoreButton');
        const loadMoreSpinner = document.getElementById('loadMoreSpinner');
        const loadMoreText = document.getElementById('loadMoreText');
        
        if (loadMoreButton) {
            loadMoreButton.disabled = false;
        }
        if (loadMoreSpinner) {
            loadMoreSpinner.classList.add('hidden');
        }
        if (loadMoreText) {
            loadMoreText.textContent = '加载更多';
        }
    }
    
    if (!gridHasMore) {
        const noMoreEl = document.getElementById('noMoreContent');
        if (noMoreEl) {
            noMoreEl.classList.remove('hidden');
        }
        const loadMoreBtn = document.getElementById('loadMoreBtn');
        if (loadMoreBtn) {
            loadMoreBtn.classList.add('hidden');
        }
        return;
    }
    
    gridLoading = true;
    
    const loadMoreButton = document.getElementById('loadMoreButton');
    const loadMoreSpinner = document.getElementById('loadMoreSpinner');
    const loadMoreText = document.getElementById('loadMoreText');
    
    if (loadMoreButton) {
        loadMoreButton.disabled = true;
    }
    if (loadMoreSpinner) {
        loadMoreSpinner.classList.remove('hidden');
    }
    if (loadMoreText) {
        loadMoreText.textContent = '加载中...';
    }
    
    loadMediaFilesRecursively(GRID_TARGET_COUNT);
}

async function loadMediaFilesRecursively(needCount) {
    const requestVersion = folderRequestVersion;
    if (needCount <= 0 || !gridHasMore) {
        finishLoading();
        return;
    }
    
    const params = new URLSearchParams({
        pageNumber: gridPage * GRID_PAGE_SIZE,
        pageSize: GRID_PAGE_SIZE, 
        search: searchQuery
    });

    try {
        const response = await fetch(fileListUrl(params));
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        const data = normalizeFolderRows(await response.json());
        if (requestVersion !== folderRequestVersion) return;
        
        if (data && data.rows) {
            gridTotal = data.total || gridTotal;
            
            const mediaFiles = data.rows.filter(file => file.folder || isImage(file) || isVideo(file) || isAlbum(file));
            previewMediaFiles = previewMediaFiles.concat(mediaFiles.filter(file => isImage(file) || isVideo(file)));
            
            if (mediaFiles.length > 0) appendToWaterfall(mediaFiles, requestVersion);
            
            gridPage++;
            
            const fetchedCount = data.rows.length;
            const remainingNeed = needCount - mediaFiles.length;
            
            gridHasMore = (gridPage * GRID_PAGE_SIZE) < gridTotal && fetchedCount > 0;
            updateStats(data);
            
            if (remainingNeed > 0 && gridHasMore) {
                await loadMediaFilesRecursively(remainingNeed);
            } else {
                finishLoading();
            }
        } else {
            gridHasMore = false;
            finishLoading();
        }
    } catch (error) {
        if (requestVersion !== folderRequestVersion) return;
        console.error('Error loading grid:', error);
        gridLoading = false;
        
        const loadMoreButton = document.getElementById('loadMoreButton');
        const loadMoreSpinner = document.getElementById('loadMoreSpinner');
        const loadMoreText = document.getElementById('loadMoreText');
        
        if (loadMoreButton) {
            loadMoreButton.disabled = false;
        }
        if (loadMoreSpinner) {
            loadMoreSpinner.classList.add('hidden');
        }
        if (loadMoreText) {
            loadMoreText.textContent = '加载更多';
        }
        
        showToast('加载失败', 'error');
    }
}

function finishLoading() {
    gridLoading = false;
    
    const loadMoreButton = document.getElementById('loadMoreButton');
    const loadMoreSpinner = document.getElementById('loadMoreSpinner');
    const loadMoreText = document.getElementById('loadMoreText');
    const loadMoreBtn = document.getElementById('loadMoreBtn');
    const noMoreEl = document.getElementById('noMoreContent');
    
    if (loadMoreButton) {
        loadMoreButton.disabled = false;
    }
    if (loadMoreSpinner) {
        loadMoreSpinner.classList.add('hidden');
    }
    if (loadMoreText) {
        loadMoreText.textContent = '加载更多';
    }
    
    if (!gridHasMore) {
        if (noMoreEl) {
            noMoreEl.classList.remove('hidden');
        }
        if (loadMoreBtn) {
            loadMoreBtn.classList.add('hidden');
        }
    } else {
        if (noMoreEl) {
            noMoreEl.classList.add('hidden');
        }
        if (loadMoreBtn) {
            loadMoreBtn.classList.remove('hidden');
        }
    }
}

function appendToWaterfall(files, requestVersion) {
    const container = document.getElementById('waterfallContainer');
    if (!container) return;
    const columns = Array.from(container.children);
    if (columns.length === 0) return;
    const gap = parseFloat(getComputedStyle(columns[0]).rowGap) || 0;
    const heights = columns.map(column => column.childElementCount ? column.offsetHeight + gap : 0);
    const template = document.createElement('template');
    template.innerHTML = files.map(createWaterfallCard).join('');
    const cards = Array.from(template.content.children);

    // Measure the whole batch in one layout pass before assigning columns in file order.
    columns[0].appendChild(template.content);
    const cardHeights = cards.map(card => card.offsetHeight);
    const fragments = columns.map(() => document.createDocumentFragment());
    cards.forEach((card, index) => {
        const file = files[index];
        const columnIndex = heights.indexOf(Math.min(...heights));
        heights[columnIndex] += cardHeights[index] + gap;
        card.addEventListener('click', () => {
            if (file.folder) openFolder(file.folderPath);
            else previewFile(file);
        });
        if (file.folder) card.addEventListener('keydown', event => {
            if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                openFolder(file.folderPath);
            }
        });
        card.querySelector('.waterfall-delete-btn')?.addEventListener('click', event => {
            event.stopPropagation();
            selectedFiles.clear();
            selectedFiles.add(file.uuid);
            showDeleteConfirm();
        });
        card.classList.add('fade-in-card');
        applyNsfwBlurToItem(card);
        fragments[columnIndex].appendChild(card);
        const cover = card.querySelector('.album-cover');
        if (cover) loadWaterfallAlbumCover(cover, file, requestVersion);
    });
    columns.forEach((column, index) => column.appendChild(fragments[index]));
}

function loadWaterfallAlbumCover(cover, file, requestVersion) {
    const placeholder = cover.querySelector('.album-cover-placeholder');
    const initialImage = cover.querySelector('img');
    async function loadPoster() {
        const poster = await fetchAlbumPoster(file.uuid);
        if (requestVersion !== folderRequestVersion) return;
        const url = poster && BootFSSecurity.url(poster);
        if (!url || url === initialImage?.src) return;
        const image = document.createElement('img');
        image.alt = file.fileName || '';
        image.className = 'w-full h-full object-cover';
        image.loading = 'lazy';
        image.decoding = 'async';
        image.addEventListener('load', () => placeholder.classList.add('hidden'), {once: true});
        image.addEventListener('error', () => image.remove(), {once: true});
        cover.prepend(image);
        image.src = url;
    }

    if (initialImage) {
        initialImage.addEventListener('error', () => {
            initialImage.remove();
            placeholder.classList.remove('hidden');
            if (requestVersion === folderRequestVersion) loadPoster();
        }, {once: true});
    } else {
        loadPoster();
    }
}

// Render file list
function renderFileList(data) {
    const listContainer = document.getElementById('fileListTableBody');
    
    previewMediaFiles = (data && data.rows) ? data.rows.filter(file => isImage(file) || isVideo(file)) : [];
    
    if (!data || !data.rows || data.rows.length === 0) {
        listContainer.innerHTML = `
            <tr>
                <td colspan="7" class="px-6 py-12 text-center theme-page-text-secondary">
                    暂无文件
                </td>
            </tr>
        `;
        return;
    }
    
    // Render list view
    listContainer.innerHTML = data.rows.map(file => createListRow(file)).join('');
    listContainer.querySelectorAll('.folder-row').forEach(row => {
        const folder = data.rows.find(file => file.folder && file.folderPath === row.dataset.folderPath);
        row.querySelectorAll('.folder-open-btn').forEach(button => {
            button.addEventListener('click', () => openFolder(folder.folderPath));
        });
        row.querySelector('.folder-rename-btn').addEventListener('click', () => showFolderRenameModal(folder));
        row.querySelector('.folder-delete-btn').addEventListener('click', () => showFolderDeleteConfirm(folder));
        row.querySelector('.tag-add-btn')?.addEventListener('click', () => showTagInput(row, folder.uuid, folder));
        row.querySelectorAll('.tag-delete-btn').forEach(button => {
            button.addEventListener('click', () => removeTag(folder.uuid, button.dataset.tag, folder));
        });
    });
    
    // Add event listeners to list rows
    listContainer.querySelectorAll('.list-row').forEach(row => {
        applyNsfwBlurToItem(row);
        const uuid = row.dataset.uuid;
        const file = data.rows.find(f => f.uuid === uuid);
        
        row.querySelector('.list-checkbox').addEventListener('change', (e) => {
            toggleFileSelection(uuid, e.target.checked);
        });
        
        row.querySelector('.list-delete-btn').addEventListener('click', (e) => {
            e.stopPropagation();
            selectedFiles.clear();
            selectedFiles.add(uuid);
            showDeleteConfirm();
        });

        row.querySelector('.list-detail-btn').addEventListener('click', (e) => {
            e.stopPropagation();
            showFileDetail(uuid);
        });

        row.querySelector('.list-rename-btn').addEventListener('click', (e) => {
            e.stopPropagation();
            showRenameModal(uuid, file.fileName);
        });

        row.querySelector('.list-edit-btn')?.addEventListener('click', (e) => {
            e.stopPropagation();
            TextFileEditor.open(file);
        });

        const previewBtn = row.querySelector('.list-preview-btn');
        if (previewBtn && (isImage(file) || isVideo(file) || isAudio(file))) {
            previewBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                previewFile(file);
            });
        }

        // Tag events
        const addTagBtn = row.querySelector('.tag-add-btn');
        if (addTagBtn) {
            addTagBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                showTagInput(row, uuid);
            });
        }
        row.querySelectorAll('.tag-badge .tag-delete-btn').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                const tagIndexFormat = btn.getAttribute('data-tag');
                removeTag(uuid, tagIndexFormat);
            });
        });
    });
}

// Create waterfall card HTML
function createWaterfallCard(file) {
    if (file.folder) return createFolderCard(file);
    const isImageFile = isImage(file);
    const isVideoFile = isVideo(file);
    const isAlbumFile = isAlbum(file);
    const thumbnail = hasThumb(file) ? BootFSSecurity.url(getThumbUrl(file)) : '';
    const albumCover = isAlbumFile && (file.thumbUrl || file.poster);
    const albumCoverUrl = albumCover ? BootFSSecurity.url(albumCover) : '';
    
    return `
        <div class="waterfall-card theme-card theme-rounded-xl overflow-hidden cursor-pointer group" data-uuid="${file.uuid}" data-type="${isAlbumFile ? 'album' : (isImageFile ? 'image' : 'video')}">
            <div class="relative w-full">
                ${isAlbumFile ? `
                    <div class="relative w-full aspect-[3/4] album-cover" style="background-color: var(--color-bg-tertiary);" data-album-uuid="${file.uuid}">
                        ${albumCoverUrl ? `
                            <img src="${BootFSSecurity.html(albumCoverUrl)}"
                                 alt="${BootFSSecurity.html(file.fileName)}"
                                 class="w-full h-full object-cover"
                                 loading="lazy" decoding="async">
                        ` : ''}
                        <div class="album-cover-placeholder absolute inset-0 flex items-center justify-center ${albumCoverUrl ? 'hidden' : ''}" style="background-color: var(--color-bg-hover);">
                            <svg class="w-16 h-16" style="color: var(--color-text-muted);" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z"></path>
                            </svg>
                        </div>
                    </div>
                ` : isImageFile ? `
                    ${thumbnail ? `
                        <img src="${BootFSSecurity.html(thumbnail)}"
                             alt="${BootFSSecurity.html(file.fileName)}"
                             class="waterfall-image w-full h-auto object-cover"
                             width="3" height="4" loading="lazy" decoding="async"
                             onerror="this.onerror=null;this.src='data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%2248%22 height=%2248%22 viewBox=%220 0 24 24%22 fill=%22none%22 stroke=%22%2394a3b8%22 stroke-width=%222%22 stroke-linecap=%22round%22 stroke-linejoin=%22round%22%3E%3Crect x=%223%22 y=%223%22 width=%2218%22 height=%2218%22 rx=%222%22 ry=%222%22/%3E%3Ccircle cx=%228.5%22 cy=%228.5%22 r=%221.5%22/%3E%3Cpolyline points=%2221 15 16 10 5 21%22/%3E%3C/svg%3E';">
                    ` : `
                        <div class="w-full aspect-[3/4] flex items-center justify-center" style="background-color: var(--color-bg-tertiary);">
                            <svg class="w-16 h-16" style="color: var(--color-text-muted);" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z"></path>
                            </svg>
                        </div>
                    `}
                ` : isVideoFile ? `
                    <div class="relative w-full aspect-video video-card-container" data-video-uuid="${file.uuid}" style="background-color: var(--color-bg-tertiary);">
                        ${thumbnail ? `
                            <img src="${BootFSSecurity.html(thumbnail)}"
                                 alt="${BootFSSecurity.html(file.fileName)}"
                                 class="w-full h-full object-cover video-thumbnail"
                                 loading="lazy" decoding="async"
                                 onerror="this.style.display='none';this.parentElement.querySelector('.video-placeholder').style.display='flex';">
                        ` : ''}
                        <div class="video-placeholder w-full h-full items-center justify-center ${thumbnail ? 'hidden' : 'flex'}" style="background-color: var(--color-bg-tertiary);">
                            <svg class="w-16 h-16 theme-page-text-muted" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z"></path>
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                            </svg>
                        </div>
                        <div class="absolute inset-0 flex items-center justify-center pointer-events-none video-play-icon">
                            <div class="w-12 h-12 theme-rounded-full flex items-center justify-center shadow-lg" style="background-color: var(--color-bg-secondary);">
                                <svg class="w-5 h-5" style="color: var(--color-text-primary);" fill="currentColor" viewBox="0 0 24 24">
                                    <path d="M8 5v14l11-7z"></path>
                                </svg>
                            </div>
                        </div>
                    </div>
                ` : ''}
                
                <div class="absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity">
                    <button class="waterfall-delete-btn theme-icon-btn theme-btn-danger">
                        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"></path>
                        </svg>
                    </button>
                </div>
            </div>
            
            <div class="p-3">
                <p class="text-sm font-medium truncate" style="color: var(--color-text-primary);" title="${BootFSSecurity.html(file.fileName)}">${BootFSSecurity.html(file.fileName)}</p>
                ${(file.tags && file.tags.length > 0) ? `
                <div class="flex items-center gap-1 mt-1.5 flex-wrap">
                    ${file.tags.map(tag => {
                        const label = tag.namespace && tag.namespace !== 'manual' ? `${tag.namespace}:${tag.name}` : tag.name;
                        return `<span class="waterfall-tag-badge" title="${BootFSSecurity.html(label)}">${BootFSSecurity.html(label)}</span>`;
                    }).join('')}
                </div>
                ` : ''}
                <div class="flex items-center justify-between mt-1">
                    <span class="text-xs" style="color: var(--color-text-tertiary);">${isAlbumFile ? '图集' : (isImageFile ? '图片' : '视频')}</span>
                    <div class="flex items-center space-x-2">
                        ${file.hlsAvailable ? '<span class="text-xs" style="color: var(--color-success);">HLS可用</span>' : ''}
                        ${file.isPublicAccess ? '<span class="text-xs" style="color: var(--color-success);">公开</span>' : ''}
                    </div>
                </div>
            </div>
        </div>
    `;
}

// Create grid card HTML
function createGridCard(file) {
    const isSelected = selectedFiles.has(file.uuid);
    const isImageFile = isImage(file);
    const isVideoFile = isVideo(file);
    
    return `
        <div class="file-card theme-card theme-rounded-xl ${isSelected ? 'ring-2' : ''}" style="${isSelected ? 'box-shadow: inset 0 0 0 2px var(--color-accent-primary);' : ''}" data-uuid="${file.uuid}">
            <!-- Thumbnail -->
            <div class="card-preview-area relative aspect-square overflow-hidden" style="background-color: var(--color-bg-tertiary);">
                ${isImageFile ? `
                    ${hasThumb(file) ? `
                        <img src="${BootFSSecurity.html(BootFSSecurity.url(getThumbUrl(file)))}"
                             alt="${BootFSSecurity.html(file.fileName)}"
                             class="w-full h-full object-cover zoom-image"
                             onerror="this.onerror=null;this.src='data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%2248%22 height=%2248%22 viewBox=%220 0 24 24%22 fill=%22none%22 stroke=%22%2394a3b8%22 stroke-width=%222%22 stroke-linecap=%22round%22 stroke-linejoin=%22round%22%3E%3Crect x=%223%22 y=%223%22 width=%2218%22 height=%2218%22 rx=%222%22 ry=%222%22/%3E%3Ccircle cx=%228.5%22 cy=%228.5%22 r=%221.5%22/%3E%3Cpolyline points=%2221 15 16 10 5 21%22/%3E%3C/svg%3E';">
                    ` : `
                        <div class="w-full h-full flex items-center justify-center">
                            <svg class="w-16 h-16" style="color: var(--color-text-muted);" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z"></path>
                            </svg>
                        </div>
                    `}
                ` : isVideoFile ? `
                    <div class="w-full h-full relative" style="background-color: var(--color-bg-tertiary);">
                        <video src="/p/${file.uuid}" 
                               class="w-full h-full object-cover"
                               muted
                               preload="metadata"
                               onloadeddata="this.currentTime=0.1;"
                               onerror="this.style.display='none';">
                        </video>
                        <div class="absolute inset-0 flex items-center justify-center pointer-events-none">
                            <div class="w-12 h-12 theme-rounded-full flex items-center justify-center shadow-lg" style="background-color: var(--color-bg-secondary);">
                                <svg class="w-5 h-5" style="color: var(--color-text-primary);" fill="currentColor" viewBox="0 0 24 24">
                                    <path d="M8 5v14l11-7z"></path>
                                </svg>
                            </div>
                        </div>
                    </div>
                ` : `
                    <div class="w-full h-full flex items-center justify-center" style="background-color: var(--color-bg-tertiary);">
                        <svg class="w-12 h-12 theme-page-text-muted" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"></path>
                        </svg>
                    </div>
                `}
                
                <!-- Overlay -->
                <div class="thumbnail-overlay absolute inset-0 opacity-0 hover:opacity-100 transition-opacity flex items-end p-3">
                    <div class="flex space-x-2 w-full">
                        ${isImageFile ? `
                            <button class="preview-btn theme-btn-secondary theme-btn-sm flex-1">
                                <svg class="w-4 h-4 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"></path>
                                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"></path>
                                </svg>
                                预览
                            </button>
                        ` : ''}
                        <button class="download-btn theme-btn-secondary theme-btn-sm flex-1">
                            <svg class="w-4 h-4 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"></path>
                            </svg>
                            下载
                        </button>
                        <button class="delete-btn theme-icon-btn theme-btn-danger theme-btn-sm">
                            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"></path>
                            </svg>
                        </button>
                    </div>
                </div>
                
                <!-- Checkbox -->
                <label class="absolute top-2 left-2">
                    <input type="checkbox" class="file-checkbox w-5 h-5 theme-rounded cursor-pointer" ${isSelected ? 'checked' : ''}>
                </label>
                
                <!-- Badges -->
                <div class="absolute bottom-2 right-2 flex flex-col space-y-1">
                    ${file.copyOf ? '<span class="badge px-2 py-1 text-white text-xs theme-rounded-md font-medium" style="background-color: var(--color-accent-primary);">副本</span>' : ''}
                    ${file.hlsAvailable ? '<span class="badge px-2 py-1 text-white text-xs theme-rounded-md font-medium" style="background-color: var(--color-success);">HLS可用</span>' : ''}
                    ${file.albumAvailable ? '<span class="badge px-2 py-1 text-white text-xs theme-rounded-md font-medium" style="background-color: #06b6d4;">图集</span>' : ''}
                    ${file.isPublicAccess ? '<span class="badge px-2 py-1 text-white text-xs theme-rounded-md font-medium" style="background-color: var(--color-bg-tertiary); color: var(--color-text-secondary);">公开</span>' : ''}
                </div>
            </div>
            
            <!-- Info -->
            <div class="p-3">
                <h3 class="text-sm font-medium truncate theme-page-text-primary" title="${BootFSSecurity.html(file.fileName)}">${BootFSSecurity.html(file.fileName)}</h3>
                <p class="text-xs mt-1 truncate theme-page-text-muted">${formatFileSize(file.size || 0)}</p>
                ${!file.copyOf ? `
                    <div class="mt-2 flex space-x-1">
                        ${!file.hlsAvailable && isVideoFile ? `
                            <button class="hls-convert-btn theme-btn-quiet flex-1" style="color: var(--color-success); background-color: rgba(16, 185, 129, 0.1);">
                                HLS 转码
                            </button>
                        ` : ''}
                        ${!file.isPublicAccess && isImageFile ? `
                            <button class="public-access-btn theme-btn-quiet flex-1" style="color: #a855f7; background-color: rgba(168, 85, 247, 0.1);">
                                发布
                            </button>
                        ` : ''}
                    </div>
                ` : ''}
            </div>
        </div>
    `;
}

// Create list row HTML
function createListRow(file) {
    if (file.folder) return createFolderRow(file);
    const isSelected = selectedFiles.has(file.uuid);
    const isImageFile = isImage(file);
    const isVideoFile = isVideo(file);
    const isAudioFile = isAudio(file);
    const textType = TextFileEditor.fileType(file.fileName);
    const isClashFile = ClashFiles.isConfig(file);
    const tags = file.tags || [];
    
    return `
        <tr class="list-row ${isSelected ? 'selected' : ''}" style="background-color: ${isSelected ? 'var(--color-accent-muted)' : 'var(--color-bg-secondary)'}; border-color: var(--color-border-primary);" data-uuid="${file.uuid}">
            <td class="w-12 px-4 py-4 whitespace-nowrap">
                <input type="checkbox" class="list-checkbox w-4 h-4 rounded theme-input" ${isSelected ? 'checked' : ''}>
            </td>
            <td class="px-6 py-4">
                <div class="flex items-center">
                    <div class="list-thumbnail flex-shrink-0 h-10 w-10 overflow-hidden theme-rounded-lg">
                        ${isClashFile ? ClashFiles.icon() : textType ? TextFileEditor.icon(textType) : isImageFile ? `
                            ${hasThumb(file) ? `
                                <img class="h-10 w-10 theme-rounded-lg object-cover"
                                     src="${BootFSSecurity.html(BootFSSecurity.url(getThumbUrl(file)))}"
                                     alt="" width="40" height="40" loading="lazy" decoding="async"
                                     onerror="this.onerror=null;this.src='data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%2240%22 height=%2240%22 viewBox=%220 0 24 22%22 fill=%22none%22 stroke=%22%2394a3b8%22 stroke-width=%222%22 stroke-linecap=%22round%22 stroke-linejoin=%22round%22%3E%3Crect x=%223%22 y=%223%22 width=%2218%22 height=%2218%22 rx=%222%22 ry=%222%22/%3E%3Ccircle cx=%228.5%22 cy=%228.5%22 r=%221.5%22/%3E%3Cpolyline points=%2221 15 16 10 5 21%22/%3E%3C/svg%3E';">
                            ` : `
                                <div class="h-10 w-10 theme-rounded-lg flex items-center justify-center" style="background-color: var(--color-bg-tertiary);">
                                    <svg class="w-5 h-5" style="color: var(--color-accent-secondary);" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z"></path>
                                    </svg>
                                </div>
                            `}
                        ` : isVideoFile ? `
                            <div class="h-10 w-10 theme-rounded-lg flex items-center justify-center" style="background-color: var(--color-bg-tertiary);">
                                <svg class="w-5 h-5" style="color: var(--color-accent-secondary);" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z"></path>
                                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                                </svg>
                            </div>
                        ` : isAudioFile ? `
                            <div class="h-10 w-10 theme-rounded-lg flex items-center justify-center" style="background-color: var(--color-bg-tertiary);">
                                <svg class="w-5 h-5" style="color: var(--color-accent-secondary);" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 18V5l12-2v13"></path>
                                    <circle cx="6" cy="18" r="3" stroke-width="2"></circle>
                                    <circle cx="18" cy="16" r="3" stroke-width="2"></circle>
                                </svg>
                            </div>
                        ` : `
                            <div class="h-10 w-10 theme-rounded-lg flex items-center justify-center" style="background-color: var(--color-bg-tertiary);">
                                <svg class="w-5 h-5" style="color: var(--color-accent-secondary);" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"></path>
                                </svg>
                            </div>
                        `}
                    </div>
                    <div class="ml-4">
                        <div class="text-sm font-medium" style="color: var(--color-text-primary);">${escapeFileName(file.fileName || '')}</div>
                        <div class="text-xs" style="color: var(--color-text-muted);">${file.uuid}</div>
                    </div>
                </div>
            </td>
            <td class="created-col px-4 py-3 whitespace-nowrap text-sm" style="color: var(--color-text-secondary);">${file.createdAt ? formatDateTime(file.createdAt) : '—'}</td>
            <td class="tag-col px-4 py-3 whitespace-nowrap">
                ${listTags(tags)}
            </td>
            <td class="px-6 py-4 whitespace-nowrap">
                <div class="flex space-x-1">
                    ${file.copyOf ? '<span class="px-2 py-1 text-xs rounded-md" style="background-color: var(--color-accent-muted); color: var(--color-accent-primary);">副本</span>' : ''}
                    ${file.hlsAvailable ? '<span class="px-2 py-1 text-xs rounded-md" style="background-color: rgba(16, 185, 129, 0.1); color: var(--color-success);">HLS</span>' : ''}
                    ${file.albumAvailable ? '<span class="px-2 py-1 text-xs rounded-md" style="background-color: rgba(6, 182, 212, 0.1); color: #06b6d4;">图集</span>' : ''}
                    ${file.isPublicAccess ? '<span class="px-2 py-1 text-xs rounded-md" style="background-color: var(--color-bg-tertiary); color: var(--color-text-secondary);">公开</span>' : ''}
                </div>
            </td>
            <td class="px-6 py-4 whitespace-nowrap text-sm" style="color: var(--color-text-secondary);">
                ${formatFileSize(file.size || 0)}
            </td>
            <td class="px-6 py-4 whitespace-nowrap text-sm font-medium">
                <div class="flex space-x-2">
                    ${textType ? '<button class="list-edit-btn theme-text-btn" style="color: var(--color-accent-primary);">编辑</button>' : `<button class="list-preview-btn theme-text-btn" style="color: var(--color-accent-primary); ${(isImageFile || isVideoFile || isAudioFile) ? '' : 'visibility: hidden;'}">${isImageFile ? '预览' : '播放'}</button>`}
                    <button class="list-detail-btn theme-text-btn" style="color: var(--color-text-secondary);">详情</button>
                    <button class="list-rename-btn theme-text-btn" style="color: var(--color-text-secondary);">重命名</button>
                    <a href="/f/${file.uuid}" download class="theme-text-btn" style="color: var(--color-accent-primary);">下载</a>
                    <button class="list-delete-btn theme-text-btn" style="color: var(--color-danger);">删除</button>
                </div>
            </td>
        </tr>
    `;
}

// Preview file
let videoPlayer = null;
let previewMediaFiles = [];
let currentPreviewIndex = -1;
let previewLoadingMore = false;
let previewSessionId = 0;

function previewFile(file) {
    if (isAlbum(file)) {
        window.location.href = `/album/${file.uuid}`;
    } else if (isImage(file) || isVideo(file)) {
        showImagePreview(file);
    } else if (isAudio(file)) {
        showAudioPreview(file);
    }
}

// Close image preview
function closeImagePreview() {
    if (videoPlayer) {
        videoPlayer.pause();
    }
    document.getElementById('imagePreviewModal').classList.add('hidden');
}

// Backdrop click: only close for images. Videos have high replay cost,
// so require the explicit close button.
function handlePreviewBackdropClick() {
    const videoWrap = document.getElementById('imagePreviewVideoWrap');
    if (videoWrap && !videoWrap.classList.contains('hidden')) {
        return;
    }
    closeImagePreview();
}

// Audio preview
function showAudioPreview(file) {
    const player = document.getElementById('audioPreviewPlayer');
    document.getElementById('audioPreviewName').textContent = file.fileName;
    player.volume = 0.2;
    player.src = `/p/${file.uuid}`;
    document.getElementById('audioPreviewModal').classList.remove('hidden');
    player.play().catch(() => {});
}

function closeAudioPreview() {
    const player = document.getElementById('audioPreviewPlayer');
    player.pause();
    player.src = '';
    document.getElementById('audioPreviewModal').classList.add('hidden');
}

// Show image preview and locate it in the preview list
function showImagePreview(file) {
    previewSessionId++;
    previewLoadingMore = false;
    currentPreviewIndex = previewMediaFiles.findIndex(f => f.uuid === file.uuid);
    updateImagePreview(file);
    document.getElementById('imagePreviewModal').classList.remove('hidden');
    updatePreviewNavButtons();
}

function openPreviewDetail() {
    if (currentPreviewIndex >= 0 && previewMediaFiles[currentPreviewIndex]) {
        const uuid = previewMediaFiles[currentPreviewIndex].uuid;
        showFileDetail(uuid);
    }
}

// Update the displayed media (image or video) and caption
function updateImagePreview(file) {
    const img = document.getElementById('imagePreviewSrc');
    const videoWrap = document.getElementById('imagePreviewVideoWrap');
    const videoEl = document.getElementById('imagePreviewVideo');

    document.getElementById('imagePreviewName').textContent = file.fileName;

    if (isVideo(file)) {
        img.classList.add('hidden');
        videoWrap.classList.remove('hidden');

        const source = file.hlsAvailable
            ? `/hls/${file.uuid}/playlist.m3u8`
            : `/p/${file.uuid}`;
        const type = file.hlsAvailable ? 'application/x-mpegURL' : 'video/mp4';

        if (!videoPlayer) {
            videoPlayer = videojs(videoEl, {
                fluid: true,
                responsive: true
            });
        }
        videoPlayer.src({ src: source, type: type });
        videoPlayer.play();
    } else {
        if (videoPlayer) {
            videoPlayer.pause();
        }
        videoWrap.classList.add('hidden');
        img.classList.remove('hidden');
        img.src = `/p/${file.uuid}`;
    }

    const counter = document.getElementById('imagePreviewCounter');
    if (counter) {
        if (currentPreviewIndex >= 0 && previewMediaFiles.length > 0) {
            counter.textContent = `${currentPreviewIndex + 1} / ${previewMediaFiles.length}`;
        } else {
            counter.textContent = '';
        }
    }
}

// Navigate to previous image
function previewPreviousImage(e) {
    if (e) e.stopPropagation();
    if (previewLoadingMore) return;
    if (currentPreviewIndex > 0) {
        currentPreviewIndex--;
        updateImagePreview(previewMediaFiles[currentPreviewIndex]);
        updatePreviewNavButtons();
    }
}

// Navigate to next image (auto-loads more in grid view when reaching the end)
async function previewNextImage(e) {
    if (e) e.stopPropagation();
    if (previewLoadingMore) return;
    if (currentPreviewIndex < 0) return;

    // Not at the last media item — just advance
    if (currentPreviewIndex < previewMediaFiles.length - 1) {
        currentPreviewIndex++;
        updateImagePreview(previewMediaFiles[currentPreviewIndex]);
        updatePreviewNavButtons();
        return;
    }

    // At the last item — try to load more when the current view can fetch another page
    const canLoadMore = (currentView === 'grid' && gridHasMore) || (currentView === 'icons' && iconHasMore);
    if (!canLoadMore) {
        return;
    }

    const sessionId = previewSessionId;
    previewLoadingMore = true;
    setPreviewNavButtonsLoading(true);
    const previousLength = previewMediaFiles.length;
    if (currentView === 'icons') loadIconView(false);
    else loadGridView(false);
    await waitForGridLoadComplete();

    // Bail if the user closed/reopened the preview while loading
    if (sessionId !== previewSessionId) return;

    previewLoadingMore = false;

    // Advance if new media arrived
    if (previewMediaFiles.length > previousLength) {
        currentPreviewIndex++;
    }
    const file = previewMediaFiles[currentPreviewIndex];
    if (file) {
        updateImagePreview(file);
    }
    updatePreviewNavButtons();
}

// Wait until grid loading finishes (polls gridLoading flag)
function waitForGridLoadComplete() {
    return new Promise(resolve => {
        const pending = () => currentView === 'icons' ? iconLoading : gridLoading;
        if (!pending()) {
            resolve();
            return;
        }
        const interval = setInterval(() => {
            if (!pending()) {
                clearInterval(interval);
                resolve();
            }
        }, 100);
    });
}

// Show loading state on prev/next buttons while more images are fetched
function setPreviewNavButtonsLoading(loading) {
    const prevBtn = document.getElementById('prevImageBtn');
    const nextBtn = document.getElementById('nextImageBtn');
    const counter = document.getElementById('imagePreviewCounter');
    if (loading) {
        if (prevBtn) prevBtn.disabled = true;
        if (nextBtn) nextBtn.disabled = true;
        if (counter) counter.textContent = '加载更多...';
    }
}

// Enable/disable prev/next buttons based on current position
function updatePreviewNavButtons() {
    const prevBtn = document.getElementById('prevImageBtn');
    const nextBtn = document.getElementById('nextImageBtn');
    const detailBtn = document.getElementById('previewDetailBtn');

    if (currentView === 'list') {
        if (prevBtn) prevBtn.classList.add('hidden');
        if (nextBtn) nextBtn.classList.add('hidden');
        if (detailBtn) detailBtn.classList.add('hidden');
        return;
    }

    if (prevBtn) prevBtn.classList.remove('hidden');
    if (nextBtn) nextBtn.classList.remove('hidden');
    if (detailBtn) detailBtn.classList.remove('hidden');

    const hasList = previewMediaFiles.length > 0;
    const atStart = currentPreviewIndex <= 0;
    const atEnd = currentPreviewIndex >= previewMediaFiles.length - 1;
    if (prevBtn) {
        prevBtn.disabled = !hasList || atStart;
    }
    if (nextBtn) {
        const canLoadMore = (currentView === 'grid' && gridHasMore) || (currentView === 'icons' && iconHasMore);
        nextBtn.disabled = !hasList || (atEnd && !canLoadMore);
    }
}

// Toggle file selection
function toggleFileSelection(uuid, selected) {
    if (selected) {
        selectedFiles.add(uuid);
    } else {
        selectedFiles.delete(uuid);
    }
    
    // 更新行的背景色
    const row = document.querySelector(`.list-row[data-uuid="${uuid}"]`);
    if (row) {
        if (selected) {
            row.classList.add('bg-blue-50');
        } else {
            row.classList.remove('bg-blue-50');
        }
    }
    
    updateSelectedToolbar();
}

// Update selected toolbar
function selectionCount() {
    return selectedFiles.size + selectedFolders.size;
}

function updateSelectedToolbar() {
    const toolbar = document.getElementById('selectedToolbar');
    const countLabel = document.getElementById('selectedCount');
    syncIconSelection();
    
    if (selectionCount() > 0) {
        toolbar.classList.remove('hidden');
        countLabel.textContent = `已选择 ${selectionCount()} 项`;
    } else {
        toolbar.classList.add('hidden');
    }
    
    // Update checkboxes
    document.querySelectorAll('.file-checkbox, .list-checkbox').forEach(cb => {
        const element = cb.closest('[data-uuid]');
        if (element) {
            const uuid = element.dataset.uuid;
            cb.checked = selectedFiles.has(uuid);
        }
    });
    
    // 检查是否全选（仅列表视图）
    const selectAllCheckbox = document.getElementById('selectAll');
    if (selectAllCheckbox && currentView === 'list') {
        const listContainer = document.getElementById('fileListTableBody');
        const totalRows = listContainer.querySelectorAll('.list-row').length;
        selectAllCheckbox.checked = totalRows > 0 && selectedFiles.size === totalRows;
    }
}

// Clear selection
function clearSelection() {
    selectedFiles.clear();
    selectedFolders.clear();
    
    // 清除列表视图的选中状态
    const listContainer = document.getElementById('fileListTableBody');
    listContainer.querySelectorAll('.list-row').forEach(row => {
        const checkbox = row.querySelector('.list-checkbox');
        if (checkbox) checkbox.checked = false;
        row.classList.remove('bg-blue-50');
    });
    
    // 清除网格视图的选中状态
    const gridContainer = document.getElementById('gridView');
    gridContainer.querySelectorAll('.file-card').forEach(card => {
        const checkbox = card.querySelector('.file-checkbox');
        if (checkbox) checkbox.checked = false;
    });
    
    // 取消全选 checkbox
    const selectAllCheckbox = document.getElementById('selectAll');
    if (selectAllCheckbox) selectAllCheckbox.checked = false;
    
    updateSelectedToolbar();
}

// Handle select all
function handleSelectAll(e) {
    if (e.target.checked) {
        selectAllOnPage();
    } else {
        clearSelection();
    }
}

// Select all on current page
function selectAllOnPage() {
    if (currentView === 'list') {
        // 列表视图：选择所有行
        const listContainer = document.getElementById('fileListTableBody');
        listContainer.querySelectorAll('.list-row').forEach(row => {
            const uuid = row.dataset.uuid;
            selectedFiles.add(uuid);
            const checkbox = row.querySelector('.list-checkbox');
            if (checkbox) checkbox.checked = true;
            row.classList.add('bg-blue-50');
        });
    } else if (currentView === 'icons') {
        selectAllIcons();
        return;
    } else {
        // 网格视图：选择所有卡片
        const gridContainer = document.getElementById('gridView');
        gridContainer.querySelectorAll('.file-card').forEach(card => {
            const uuid = card.dataset.uuid;
            selectedFiles.add(uuid);
            const checkbox = card.querySelector('.file-checkbox');
            if (checkbox) checkbox.checked = true;
        });
    }
    updateSelectedToolbar();
}

// Switch view
function applyViewChrome(view, restorePanel = true) {
    const gridView = document.getElementById('gridView');
    const listView = document.getElementById('listView');
    const iconView = document.getElementById('iconView');
    const paginationRow = document.getElementById('paginationRow');
    if (gridView) gridView.classList.toggle('hidden', view !== 'grid');
    if (listView) listView.classList.toggle('hidden', view !== 'list');
    if (iconView) iconView.classList.toggle('hidden', view !== 'icons');
    if (view !== 'icons') iconThumbnailObserver?.disconnect();
    if (paginationRow) paginationRow.classList.toggle('hidden', view !== 'list');
    document.querySelectorAll('.view-toggle-btn').forEach(button => {
        button.setAttribute('aria-pressed', String(button.dataset.view === view));
    });
    const listModeActions = document.getElementById('listModeActions');
    if (listModeActions) listModeActions.classList.toggle('view-hidden', view !== 'list');
    if (view === 'list') {
        if (restorePanel) restoreLocalPanel();
        return;
    }
    const localFolderPanel = document.getElementById('localFolderPanel');
    const mainView = document.getElementById('mainView');
    if (localFolderPanel) localFolderPanel.classList.add('no-transition');
    if (mainView) mainView.classList.add('no-transition');
    if (localFolderPanel) localFolderPanel.classList.remove('active');
    if (mainView) mainView.classList.remove('local-panel-active');
    if (mainView) mainView.offsetHeight;
    if (localFolderPanel) localFolderPanel.classList.remove('no-transition');
    if (mainView) mainView.classList.remove('no-transition');
}

function switchView(view) {
    if (view !== 'list' && view !== 'grid' && view !== 'icons') return;
    currentView = view;
    localStorage.setItem('tfs-view', view);
    clearSelection();
    applyViewChrome(view);
    loadFileList();
}

// Change page (for list view only)
function changePage(delta) {
    gotoPage(currentPage + delta);
}

// Jump to an absolute page number
function gotoPage(page) {
    if (page >= 1 && page <= totalPages && page !== currentPage) {
        currentPage = page;
        clearSelection();
        loadListView();
    }
}

// Change page size; resets to first page
function changePageSize(newSize) {
    const next = parseInt(newSize, 10);
    if (!isNaN(next) && next > 0 && next !== pageSize) {
        pageSize = next;
        currentPage = 1;
        clearSelection();
        loadListView();
    }
}

// Jump to the page entered in the input
function jumpToPage() {
    const input = document.getElementById('jumpPageInput');
    if (!input) return;
    const page = parseInt(input.value, 10);
    if (!isNaN(page) && page >= 1 && page <= totalPages) {
        gotoPage(page);
        input.value = '';
    }
}

// Build the list of page numbers/ellipses to render
function getPageList(current, total) {
    if (total <= 7) {
        return Array.from({ length: total }, (_, i) => i + 1);
    }
    const pages = [1];
    const start = Math.max(2, current - 1);
    const end = Math.min(total - 1, current + 1);
    if (start > 2) pages.push('...');
    for (let i = start; i <= end; i++) pages.push(i);
    if (end < total - 1) pages.push('...');
    pages.push(total);
    return pages;
}

// Render numbered page buttons
function renderPageNumbers() {
    const container = document.getElementById('pageNumbers');
    if (!container) return;
    container.innerHTML = '';
    getPageList(currentPage, totalPages).forEach(p => {
        if (p === '...') {
            const ellipsis = document.createElement('span');
            ellipsis.className = 'pagination-ellipsis';
            ellipsis.textContent = '…';
            container.appendChild(ellipsis);
        } else {
            const btn = document.createElement('button');
            btn.className = 'pagination-btn' + (p === currentPage ? ' pagination-btn-active' : '');
            btn.textContent = p;
            if (p === currentPage) {
                btn.setAttribute('aria-current', 'page');
            } else {
                btn.addEventListener('click', () => gotoPage(p));
            }
            container.appendChild(btn);
        }
    });
}

// Update pagination
function updatePagination(data) {
    if (!data) return;
    const total = data.total || 0;
    const effectivePageSize = data.pageSize || pageSize;
    totalPages = Math.ceil(total / effectivePageSize) || 1;

    const totalRecordsEl = document.getElementById('totalRecords');
    const prevPageBtn = document.getElementById('prevPage');
    const nextPageBtn = document.getElementById('nextPage');
    const pageSizeSelect = document.getElementById('pageSizeSelect');

    if (totalRecordsEl) totalRecordsEl.textContent = total;
    if (prevPageBtn) prevPageBtn.disabled = currentPage <= 1;
    if (nextPageBtn) nextPageBtn.disabled = currentPage >= totalPages;
    if (pageSizeSelect) pageSizeSelect.value = String(pageSize);

    renderPageNumbers();
    console.log('Pagination updated:', { currentPage, totalPages, total, pageSize });
}

// Update statistics
function updateStats(data) {
    let notice = document.getElementById('federationNotice');
    if (!notice) {
        notice = document.createElement('p'); notice.id = 'federationNotice'; notice.setAttribute('role', 'status');
        notice.className = 'hidden px-4 py-2 text-sm'; notice.style.color = 'var(--color-warning)';
        document.getElementById('mainContent')?.insertBefore(notice, document.getElementById('mainContent')?.querySelector('main'));
    }
    notice.textContent = data?.partial ? '部分节点不可达，当前仅显示可达节点的结果；本机资源可正常使用。' : '';
    notice.classList.toggle('hidden', !data?.partial);
    if (data && data.rows) {
        const total = data.total || data.rows.length;
        
        // 安全地更新统计元素（如果存在的话）
        const totalFilesEl = document.getElementById('totalFiles');
        const imageFilesEl = document.getElementById('imageFiles');
        const videoFilesEl = document.getElementById('videoFiles');
        const publicFilesEl = document.getElementById('publicFiles');
        
        if (totalFilesEl) {
            totalFilesEl.textContent = total.toLocaleString();
        }
        
        // Count by type (simplified - in real scenario, you'd get this from backend)
        if (imageFilesEl) {
            const imageCount = data.rows.filter(f => isImage(f)).length;
            imageFilesEl.textContent = imageCount.toLocaleString();
        }
        
        if (videoFilesEl) {
            const videoCount = data.rows.filter(f => isVideo(f)).length;
            videoFilesEl.textContent = videoCount.toLocaleString();
        }
        
        if (publicFilesEl) {
            const publicCount = data.rows.filter(f => f.isPublicAccess).length;
            publicFilesEl.textContent = publicCount.toLocaleString();
        }
    }
}

// File upload
function handleFileUpload(e) {
    const uploadFolder = currentFolder;
    const files = e.target.files;
    if (files.length === 0) return;
    
    const modal = document.getElementById('uploadProgressModal');
    const progressBar = document.getElementById('uploadProgressBar');
    const uploadFileName = document.getElementById('uploadFileName');
    const uploadFileCount = document.getElementById('uploadFileCount');
    const uploadPercent = document.getElementById('uploadPercent');
    const uploadTransferred = document.getElementById('uploadTransferred');
    const uploadSpeed = document.getElementById('uploadSpeed');
    const uploadTotalSize = document.getElementById('uploadTotalSize');
    
    modal.classList.remove('hidden');
    
    let uploaded = 0;
    const totalFiles = files.length;
    let totalBytes = 0;
    let totalTransferred = 0;
    
    Array.from(files).forEach(file => {
        totalBytes += file.size;
    });
    
    uploadTotalSize.textContent = formatFileSize(totalBytes);
    uploadFileCount.textContent = `1 / ${totalFiles}`;
    
    const uploadFile = (file, index) => {
        return new Promise((resolve, reject) => {
            uploadFileName.textContent = file.name;
            uploadFileName.title = file.name;
            uploadFileCount.textContent = `${index + 1} / ${totalFiles}`;
            
            const formData = new FormData();
            formData.append('file', file);
            if (uploadFolder) formData.append('folder', uploadFolder);
            
            const xhr = new XMLHttpRequest();
            
            let startTime = Date.now();
            let lastLoaded = 0;
            let lastTime = startTime;
            
            xhr.upload.addEventListener('progress', (event) => {
                if (event.lengthComputable) {
                    const currentTime = Date.now();
                    const timeDiff = (currentTime - lastTime) / 1000;
                    const loadedDiff = event.loaded - lastLoaded;
                    
                    let speed = 0;
                    if (timeDiff > 0) {
                        speed = loadedDiff / timeDiff;
                    }
                    
                    const overallTransferred = totalTransferred + event.loaded;
                    const overallPercent = Math.round((overallTransferred / totalBytes) * 100);
                    
                    progressBar.style.width = `${overallPercent}%`;
                    uploadPercent.textContent = `${overallPercent}%`;
                    uploadTransferred.textContent = formatFileSize(overallTransferred);
                    
                    if (speed > 0) {
                        uploadSpeed.textContent = formatSpeed(speed);
                    }
                    
                    lastLoaded = event.loaded;
                    lastTime = currentTime;
                }
            });
            
            xhr.addEventListener('load', () => {
                if (xhr.status >= 200 && xhr.status < 300) {
                    totalTransferred += file.size;
                    uploaded++;
                    resolve();
                } else {
                    reject(new Error(`Upload failed with status ${xhr.status}`));
                }
            });
            
            xhr.addEventListener('error', () => {
                reject(new Error('Upload failed'));
            });
            
            xhr.addEventListener('abort', () => {
                reject(new Error('Upload aborted'));
            });
            
            xhr.open(uploadFolder ? 'POST' : 'PUT', uploadFolder ? '/api/folders/upload' : '/f');
            xhr.send(formData);
        });
    };
    
    const uploadAllFiles = async () => {
        try {
            for (let i = 0; i < files.length; i++) {
                await uploadFile(files[i], i);
            }
            
            progressBar.style.width = '100%';
            uploadPercent.textContent = '100%';
            uploadTransferred.textContent = formatFileSize(totalBytes);
            uploadSpeed.textContent = '完成';
            
            setTimeout(() => {
                modal.classList.add('hidden');
                progressBar.style.width = '0%';
                uploadPercent.textContent = '0%';
                uploadTransferred.textContent = '0 B';
                uploadSpeed.textContent = '-- MB/s';
                showToast('上传成功！', 'success');
                loadFileList();
                document.getElementById('uploadFileBtn').value = '';
            }, 800);
        } catch (error) {
            console.error('Upload error:', error);
            showToast('上传失败: ' + error.message, 'error');
            modal.classList.add('hidden');
        }
    };
    
    uploadAllFiles();
}

function handleComicUpload(e) {
    const files = e.target.files;
    if (files.length === 0) return;

    const modal = document.getElementById('uploadProgressModal');
    const progressBar = document.getElementById('uploadProgressBar');
    const uploadFileName = document.getElementById('uploadFileName');
    const uploadFileCount = document.getElementById('uploadFileCount');
    const uploadPercent = document.getElementById('uploadPercent');
    const uploadTransferred = document.getElementById('uploadTransferred');
    const uploadSpeed = document.getElementById('uploadSpeed');
    const uploadTotalSize = document.getElementById('uploadTotalSize');

    modal.classList.remove('hidden');

    let totalBytes = 0;
    Array.from(files).forEach(file => { totalBytes += file.size; });
    uploadTotalSize.textContent = formatFileSize(totalBytes);
    uploadFileCount.textContent = `1 / ${files.length}`;

    const file = files[0];
    uploadFileName.textContent = file.name;
    uploadFileName.title = file.name;

    const formData = new FormData();
    formData.append('file', file);

    const xhr = new XMLHttpRequest();
    const startTime = Date.now();
    let lastLoaded = 0;
    let lastTime = startTime;

    xhr.upload.addEventListener('progress', (event) => {
        if (event.lengthComputable) {
            const currentTime = Date.now();
            const timeDiff = (currentTime - lastTime) / 1000;
            const loadedDiff = event.loaded - lastLoaded;
            let speed = 0;
            if (timeDiff > 0) speed = loadedDiff / timeDiff;
            const overallPercent = Math.round((event.loaded / totalBytes) * 100);
            progressBar.style.width = `${overallPercent}%`;
            uploadPercent.textContent = `${overallPercent}%`;
            uploadTransferred.textContent = formatFileSize(event.loaded);
            if (speed > 0) uploadSpeed.textContent = formatSpeed(speed);
            lastLoaded = event.loaded;
            lastTime = currentTime;
        }
    });

    xhr.addEventListener('load', () => {
        if (xhr.status >= 200 && xhr.status < 300) {
            progressBar.style.width = '100%';
            uploadPercent.textContent = '100%';
            uploadTransferred.textContent = formatFileSize(totalBytes);
            uploadSpeed.textContent = '完成';
            setTimeout(() => {
                modal.classList.add('hidden');
                progressBar.style.width = '0%';
                uploadPercent.textContent = '0%';
                uploadTransferred.textContent = '0 B';
                uploadSpeed.textContent = '-- MB/s';
                showToast('上传成功！', 'success');
                loadFileList();
                document.getElementById('comicUploadBtn').value = '';
            }, 800);
        } else {
            modal.classList.add('hidden');
            showToast('上传失败: ' + (xhr.status), 'error');
        }
    });

    xhr.addEventListener('error', () => {
        modal.classList.add('hidden');
        showToast('上传失败: 网络错误', 'error');
    });

    xhr.open('PUT', '/comic');
    xhr.send(formData);
}

function formatSpeed(bytesPerSecond) {
    if (bytesPerSecond === 0) return '0 B/s';
    const k = 1024;
    const sizes = ['B/s', 'KB/s', 'MB/s', 'GB/s'];
    const i = Math.floor(Math.log(bytesPerSecond) / Math.log(k));
    const speed = bytesPerSecond / Math.pow(k, i);
    return speed.toFixed(2) + ' ' + sizes[i];
}

// Close upload modal
function closeUploadModal() {
    document.getElementById('uploadProgressModal').classList.add('hidden');
}

// Delete functions
let deleteFolderTarget = null;

function showDeleteConfirm() {
    if (selectionCount() === 0) {
        showToast('请先选择要删除的文件', 'warning');
        return;
    }
    deleteFolderTarget = null;
    const folderNote = selectedFolders.size > 0 ? '文件夹会连同其中的内容一起删除。' : '此操作不可恢复。';
    document.getElementById('deleteConfirmMessage').textContent = `确定要删除选中的 ${selectionCount()} 项吗？${folderNote}`;
    document.getElementById('deleteConfirmModal').classList.remove('hidden');
}

function showFolderDeleteConfirm(folder) {
    if (folder.managed === false) return;
    deleteFolderTarget = folder;
    document.getElementById('deleteConfirmMessage').textContent = `确定要删除文件夹「${folder.fileName}」及其内部的所有文件和子文件夹吗？此操作不可恢复。`;
    document.getElementById('deleteConfirmModal').classList.remove('hidden');
}

function closeDeleteModal() {
    document.getElementById('deleteConfirmModal').classList.add('hidden');
    deleteFolderTarget = null;
}

async function confirmDelete() {
    const uuids = Array.from(selectedFiles);
    const folder = deleteFolderTarget;
    const folders = folder ? [] : Array.from(selectedFolders);
    if (!folder && uuids.length === 0 && folders.length === 0) return;
    const submit = document.getElementById('confirmDeleteBtn');
    submit.disabled = true;
    try {
        if (folder) {
            const response = await fetch('/api/folders', {
                method: 'DELETE',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({folder: folder.folderPath})
            });
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
        } else if (uuids.length > 0) {
            const response = await fetch('/removeFileBatch', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(uuids)
            });
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
        }
        for (const path of folders) {
            const response = await fetch('/api/folders', {
                method: 'DELETE',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({folder: path})
            });
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
        }
        closeDeleteModal();
        clearSelection();
        showToast('删除成功', 'success');
        loadFileList();
    } catch (error) {
        console.error('Delete error:', error);
        showToast('删除失败', 'error');
    } finally {
        submit.disabled = false;
    }
}

// Batch download
function batchDownload() {
    if (selectedFiles.size === 0) {
        showToast('请先选择要下载的文件', 'warning');
        return;
    }
    
    const uuids = Array.from(selectedFiles);
    
    fetch('/downloadFileBatch', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(uuids)
    })
    .then(response => response.json())
    .then(data => {
        const iframe = document.createElement('iframe');
        iframe.style.display = 'none';
        document.body.appendChild(iframe);
        iframe.src = `/g/b/${data.uuid}`;
        showToast('正在准备下载...', 'info');
    })
    .catch(error => {
        console.error('Batch download error:', error);
        showToast('批量下载失败', 'error');
    });
}

// HLS conversion
function convertToHLS() {
    const selected = Array.from(selectedFiles);
    if (selected.length !== 1) {
        showToast('请选择一个视频文件进行转码', 'warning');
        return;
    }
    
    convertSingleToHLS(selected[0]);
}

function convertSingleToHLS(uuid) {
    fetch(`/hls/convert?uuid=${uuid}`, { method: 'POST' })
    .then(response => response.text())
    .then(data => {
        showToast('HLS 转码已启动', 'success');
        loadFileList();
    })
    .catch(error => {
        console.error('HLS conversion error:', error);
        showToast('转码失败', 'error');
    });
}

// Publish to gallery
function publishToGallery() {
    if (selectedFiles.size === 0) {
        showToast('请先选择要发布的文件', 'warning');
        return;
    }
    
    Array.from(selectedFiles).forEach(uuid => {
        publishSingleToGallery(uuid);
    });
}

function publishSingleToGallery(uuid) {
    fetch(`/publicAccess/${uuid}`, {
        method: 'POST'
    })
    .then(() => {
        showToast('发布成功', 'success');
        loadFileList();
    })
    .catch(error => {
        console.error('Publish error:', error);
        showToast('发布失败', 'error');
    });
}

// Download single file
function downloadFile(file) {
    const link = document.createElement('a');
    link.href = `/f/${file.uuid}`;
    link.download = file.fileName;
    link.target = '_blank';
    link.click();
}

// Toggle show all files (including copies)
function toggleShowAll() {
    showAllFiles = !showAllFiles;
    localStorage.setItem('tfs-show-all', showAllFiles);
    const btn = document.getElementById('showAllBtn');

    if (showAllFiles) {
        btn.style.backgroundColor = 'var(--color-accent-muted)';
        btn.style.color = 'var(--color-accent-primary)';
        searchQuery = 'meta:all';
    } else {
        btn.style.backgroundColor = 'var(--color-bg-tertiary)';
        btn.style.color = 'var(--color-text-secondary)';
        searchQuery = '';
    }

    document.getElementById('searchInput').value = searchQuery;
    currentPage = 1;
    gridPage = 0; // 重置网格页码
    
    loadFileList();
}

// Refresh file list
function refreshFileList() {
    loadFileList();
    showToast('已刷新', 'success');
}

// ======================== File Detail Modal ========================
let fileDetailSession = 0;

/**
 * 显示文件详情弹窗，异步加载详情数据
 */
function showFileDetail(uuid) {
    const session = ++fileDetailSession;
    const modal = document.getElementById('fileDetailModal');
    const loading = document.getElementById('fileDetailLoading');
    const content = document.getElementById('fileDetailContent');
    const mediaSection = document.getElementById('detailMediaSection');
    ClashFiles.reset();
    document.getElementById('fileDetailError').classList.add('hidden');
    content.classList.remove('hidden');

    // 显示弹窗，展示加载层
    modal.classList.remove('hidden');
    loading.classList.remove('hidden');
    content.style.opacity = '0.4';
    mediaSection.classList.add('hidden');
    document.getElementById('detailHlsSection').classList.add('hidden');
    document.getElementById('detailMetadataSection').classList.add('hidden');
    document.getElementById('detailAiSection').classList.add('hidden');

    // 异步请求文件详情
    fetch(`/api/file/${uuid}/details`)
        .then(res => {
            if (!res.ok) throw new Error('文件不存在');
            return res.json();
        })
        .then(data => {
            if (session !== fileDetailSession) return;
            renderFileDetail(data);
        })
        .catch(err => {
            if (session !== fileDetailSession) return;
            const error = document.getElementById('fileDetailError');
            error.textContent = `加载失败: ${err.message}`;
            error.classList.remove('hidden');
            content.classList.add('hidden');
            showToast('文件详情加载失败', 'error');
        })
        .finally(() => {
            if (session !== fileDetailSession) return;
            loading.classList.add('hidden');
            content.style.opacity = '1';
        });
}

/**
 * 渲染文件详情内容
 */
function renderFileDetail(data) {
    ClashFiles.load(data);
    // 文件名（含 title 用于截断时 tooltip）
    const nameEl = document.getElementById('detailFileName');
    nameEl.textContent = data.fileName || '-';
    nameEl.title = data.fileName || '';
    document.getElementById('detailFileSize').textContent = formatFileSize(data.size || 0);
    document.getElementById('detailUuid').textContent = data.uuid || '-';
    document.getElementById('detailPath').textContent = data.path || '-';

    // 状态标识
    const badges = document.getElementById('detailStatusBadges');
    badges.innerHTML = '';
    if (data.copyOf) {
        badges.appendChild(createBadge('副本', 'var(--color-accent-muted)', 'var(--color-accent-primary)'));
    }
    if (data.hlsAvailable) {
        badges.appendChild(createBadge('HLS', 'rgba(16, 185, 129, 0.1)', 'var(--color-success)'));
    }
    if (data.albumAvailable) {
        badges.appendChild(createBadge('图集', 'rgba(6, 182, 212, 0.1)', '#06b6d4'));
    }
    if (data.isPublicAccess) {
        badges.appendChild(createBadge('公开', 'var(--color-bg-tertiary)', 'var(--color-text-secondary)'));
    }

    // 标签
    const detailTags = document.getElementById('detailTags');
    detailTags.innerHTML = '';
    if (data.tags && data.tags.length > 0) {
        data.tags.forEach(tag => {
            const label = tag.namespace && tag.namespace !== 'manual' ? `${tag.namespace}:${tag.name}` : tag.name;
            const span = document.createElement('span');
            span.className = 'tag-badge';
            span.title = label;
            const textSpan = document.createElement('span');
            textSpan.className = 'tag-badge-text';
            textSpan.textContent = label;
            span.appendChild(textSpan);
            detailTags.appendChild(span);
        });
    } else {
        detailTags.innerHTML = '<span class="text-sm theme-page-text-muted">无标签</span>';
    }

    // 媒体信息
    const mediaInfo = data.mediaInfo;
    if (mediaInfo) {
        const mediaSection = document.getElementById('detailMediaSection');
        mediaSection.classList.remove('hidden');

        // 封面图
        const coverWrap = document.getElementById('detailCoverWrap');
        const coverImg = document.getElementById('detailCoverImg');
        const cover = mediaInfo.cover;
        if (cover) {
            coverImg.src = `/api/file/${data.uuid}/cover`;
            coverWrap.classList.remove('hidden');
        } else {
            coverImg.src = '';
            coverWrap.classList.add('hidden');
        }

        // 容器格式
        const formatInfo = document.getElementById('detailFormatInfo');
        if (mediaInfo.formatName) {
            formatInfo.classList.remove('hidden');
            document.getElementById('detailFormatName').textContent = mediaInfo.formatLongName || mediaInfo.formatName;
            document.getElementById('detailFormatBitrate').textContent = mediaInfo.bitRate ? formatBitrate(mediaInfo.bitRate) : '-';
            document.getElementById('detailDuration').textContent = mediaInfo.duration ? formatDuration(mediaInfo.duration) : '-';
        } else {
            formatInfo.classList.add('hidden');
        }

        // 视频流
        const videoInfo = document.getElementById('detailVideoInfo');
        const video = mediaInfo.video;
        if (video) {
            videoInfo.classList.remove('hidden');
            document.getElementById('detailVideoCodec').textContent = video.codecLong || video.codec || '-';
            document.getElementById('detailVideoResolution').textContent = (video.width && video.height) ? `${video.width} × ${video.height}` : '-';
            document.getElementById('detailVideoFps').textContent = video.frameRate ? `${video.frameRate.toFixed(2)} fps` : '-';
            document.getElementById('detailVideoBitrate').textContent = video.bitRate ? formatBitrate(video.bitRate) : '-';
            document.getElementById('detailVideoPixFmt').textContent = video.pixFmt || '-';
        } else {
            videoInfo.classList.add('hidden');
        }

        // 音频流
        const audioInfo = document.getElementById('detailAudioInfo');
        const audio = mediaInfo.audio;
        if (audio) {
            audioInfo.classList.remove('hidden');
            document.getElementById('detailAudioCodec').textContent = audio.codecLong || audio.codec || '-';
            document.getElementById('detailAudioSampleRate').textContent = audio.sampleRate ? `${(audio.sampleRate / 1000).toFixed(1)} kHz` : '-';
            document.getElementById('detailAudioChannels').textContent = audio.channels ? formatChannels(audio.channels) : '-';
            document.getElementById('detailAudioBitrate').textContent = audio.bitRate ? formatBitrate(audio.bitRate) : '-';
        } else {
            audioInfo.classList.add('hidden');
        }

        // 元数据标签
        const metadata = mediaInfo.metadata;
        const metadataSection = document.getElementById('detailMetadataSection');
        const metadataGrid = document.getElementById('detailMetadataGrid');
        if (metadata) {
            const fields = [
                { key: 'title', label: '标题' },
                { key: 'artist', label: '艺术家' },
                { key: 'album', label: '专辑' },
                { key: 'albumArtist', label: '专辑艺术家' },
                { key: 'track', label: '音轨' },
                { key: 'date', label: '年代' },
                { key: 'genre', label: '风格' },
                { key: 'composer', label: '作曲' },
                { key: 'comment', label: '备注', maxLen: 60 }
            ];
            const items = fields.filter(f => metadata[f.key]).map(f => {
                let value = metadata[f.key];
                let titleAttr = '';
                if (f.maxLen && value.length > f.maxLen) {
                    titleAttr = ` title="${BootFSSecurity.html(value)}"`;
                    value = value.substring(0, f.maxLen) + '…';
                }
                return `<div class="flex justify-between"${titleAttr}><span class="theme-page-text-muted">${f.label}</span><span class="theme-page-text-primary font-medium text-right" style="max-width: 10rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">${BootFSSecurity.html(value)}</span></div>`;
            });
            if (items.length > 0) {
                metadataGrid.innerHTML = items.join('');
                metadataSection.classList.remove('hidden');
            } else {
                metadataSection.classList.add('hidden');
            }
        } else {
            metadataSection.classList.add('hidden');
        }
    }

    // HLS 媒体规格
    const hlsSection = document.getElementById('detailHlsSection');
    if (data.hlsAvailable) {
        fetch(`/hls/${data.uuid}/segments/info`)
            .then(res => res.json())
            .then(hlsInfo => {
                document.getElementById('detailHlsSegments').textContent = hlsInfo.segmentCount + ' 段';
                document.getElementById('detailHlsSize').textContent = formatFileSize(hlsInfo.totalSize || 0);
                document.getElementById('detailHlsStatus').textContent = hlsInfo.isFinalized ? '已完结' : '未完结';
                document.getElementById('detailHlsPlaylist').textContent = `/hls/${data.uuid}/playlist.m3u8`;
                hlsSection.classList.remove('hidden');
            })
            .catch(() => {
                hlsSection.classList.add('hidden');
            });
    } else {
        hlsSection.classList.add('hidden');
    }

    // AI 生成元数据 — 有 ai 标签时才读取
    const aiSection = document.getElementById('detailAiSection');
    const hasAiTag = data.tags && data.tags.some(t => {
        // do not change t.name because it`s the tags content, except you know what you are doing
        const src = (t.name || '').toLowerCase();
        const ns = (t.namespace || '').toLowerCase();
        return src === 'ai' || ns.startsWith('ai');
    });
    if (hasAiTag) {
        fetch(`/api/file/${data.uuid}/ai-metadata`)
            .then(res => res.json())
            .then(ai => {
                if (!ai || Object.keys(ai).length === 0) {
                    aiSection.classList.add('hidden');
                    return;
                }
                renderAiField('detailAiPrompt', 'detailAiPromptText', ai.positivePrompt);
                renderAiField('detailAiNegPrompt', 'detailAiNegPromptText', ai.negativePrompt);
                renderAiField('detailAiModel', null, ai.model);
                renderAiField('detailAiSampler', null, ai.sampler);
                bindCopyOnClick('detailAiPromptText');
                bindCopyOnClick('detailAiNegPromptText');

                const paramsGrid = document.getElementById('detailAiParams');
                const params = [
                    { label: '步数', value: ai.steps },
                    { label: 'CFG', value: ai.cfgScale },
                    { label: '种子', value: ai.seed, mono: true },
                    { label: '尺寸', value: ai.size }
                ];
                paramsGrid.innerHTML = params
                    .filter(p => p.value)
                    .map(p => `<div class="flex justify-between"><span class="theme-page-text-muted">${p.label}</span><span class="theme-page-text-primary font-medium text-right ${p.mono ? 'font-mono' : ''}">${BootFSSecurity.html(p.value)}</span></div>`)
                    .join('');
                aiSection.classList.remove('hidden');
            })
            .catch(() => {
                aiSection.classList.add('hidden');
            });
    } else {
        aiSection.classList.add('hidden');
    }
}

function renderAiField(containerId, textId, value) {
    const container = document.getElementById(containerId);
    if (!value) {
        container.classList.add('hidden');
        return;
    }
    container.classList.remove('hidden');
    if (textId) {
        document.getElementById(textId).textContent = value;
    } else {
        const valueEl = container.lastElementChild;
        valueEl.textContent = value;
        valueEl.title = value;
    }
}

function bindCopyOnClick(elementId) {
    const el = document.getElementById(elementId);
    if (!el) return;
    el.style.cursor = 'pointer';
    el.title = '点击复制';
    el.onclick = () => {
        const text = el.textContent;
        if (!text) return;
        navigator.clipboard.writeText(text)
            .then(() => showToast('已复制到剪切板', 'success'))
            .catch(() => {
                const ta = document.createElement('textarea');
                ta.value = text;
                ta.style.position = 'fixed';
                ta.style.opacity = '0';
                document.body.appendChild(ta);
                ta.select();
                try {
                    document.execCommand('copy');
                    showToast('已复制到剪切板', 'success');
                } catch {
                    showToast('复制失败', 'error');
                }
                document.body.removeChild(ta);
            });
    };
}

function createBadge(text, bgColor, textColor) {
    const span = document.createElement('span');
    span.className = 'px-2 py-1 text-xs rounded-md';
    span.style.backgroundColor = bgColor;
    span.style.color = textColor;
    span.textContent = text;
    return span;
}

function formatBitrate(bitRate) {
    if (!bitRate) return '-';
    if (bitRate >= 1000000) return (bitRate / 1000000).toFixed(2) + ' Mbps';
    if (bitRate >= 1000) return (bitRate / 1000).toFixed(0) + ' Kbps';
    return bitRate + ' bps';
}

function formatDuration(seconds) {
    if (!seconds) return '-';
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = Math.floor(seconds % 60);
    if (h > 0) {
        return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
    }
    return `${m}:${String(s).padStart(2, '0')}`;
}

function formatChannels(channels) {
    switch (channels) {
        case 1: return '单声道 (Mono)';
        case 2: return '立体声 (Stereo)';
        case 6: return '5.1 环绕声';
        case 8: return '7.1 环绕声';
        default: return channels + ' 声道';
    }
}

/**
 * 关闭文件详情弹窗
 */
function closeFileDetailModal(event) {
    // 如果点击了弹窗外部背景，关闭
    if (event && event.target !== document.getElementById('fileDetailModal')) return;
    ++fileDetailSession;
    ClashFiles.reset();
    document.getElementById('fileDetailModal').classList.add('hidden');
}

// Toast notification
function showToast(message, type = 'info') {
    const toast = document.getElementById('toast');
    const toastMessage = document.getElementById('toastMessage');
    const toastIcon = document.getElementById('toastIcon');
    
    const icons = {
        success: '<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7"></path>',
        error: '<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path>',
        warning: '<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"></path>',
        info: '<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path>'
    };
    
    const colors = {
        success: 'bg-green-500 text-white',
        error: 'bg-red-500 text-white',
        warning: 'bg-yellow-500 text-white',
        info: 'bg-blue-500 text-white'
    };
    
    toastMessage.textContent = message;
    toastIcon.innerHTML = icons[type];
    toast.className = `fixed bottom-4 right-4 px-4 py-2.5 theme-rounded-xl shadow-lg z-50 ${colors[type]}`;
    toast.classList.remove('hidden');
    
    setTimeout(() => {
        toast.classList.add('translate-y-2', 'opacity-0');
        setTimeout(() => {
            toast.classList.add('hidden');
            toast.classList.remove('translate-y-2', 'opacity-0');
        }, 300);
    }, 3000);
}

// ===== Tag management =====
function showTagInput(row, uuid, folder = null) {
    // Remove any existing in-flight tag input
    const existingInput = row.querySelector('.tag-input-inline');
    if (existingInput) return;

    const addBtn = row.querySelector('.tag-add-btn');
    const wrapper = row.querySelector('.tag-col .flex');

    // Hide add button, show inline input
    addBtn.style.display = 'none';

    const inputContainer = document.createElement('span');
    inputContainer.className = 'tag-input-inline';
    inputContainer.innerHTML = `
        <input type="text" class="tag-input-field" placeholder="标签名" maxlength="30" autofocus>
    `;
    wrapper.insertBefore(inputContainer, addBtn);

    const input = inputContainer.querySelector('input');
    input.focus();

    const cleanup = () => {
        inputContainer.remove();
        addBtn.style.display = '';
    };

    input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            const tagName = input.value.trim();
            if (tagName) {
                confirmAddTag(uuid, tagName, folder);
            }
            cleanup();
        } else if (e.key === 'Escape') {
            cleanup();
        }
    });

    input.addEventListener('blur', () => {
        // slight delay to let Enter/Escape handle first
        setTimeout(() => {
            if (inputContainer.parentNode) {
                cleanup();
            }
        }, 150);
    });
}

async function confirmAddTag(uuid, tagName, folder = null) {
    if (folder?.managed === false) return;
    const tags = [{name: tagName, namespace: 'manual', source: 'manual', confidence: 1.0}];
    try {
        const response = await fetch(folder ? '/api/folders/tags' : `/api/file/${uuid}/tags`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(folder ? {folder: folder.folderPath, tags} : tags)
        });
        if (response.ok) {
            showToast('标签已添加', 'success');
            loadFileList();
        } else {
            showToast('添加标签失败', 'error');
        }
    } catch (error) {
        console.error('Add tag error:', error);
        showToast('添加标签失败', 'error');
    }
}

async function removeTag(uuid, tagIndexFormat, folder = null) {
    if (folder?.managed === false) return;
    try {
        const response = await fetch(folder ? '/api/folders/tags' : `/api/file/${uuid}/tags`, {
            method: 'DELETE',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(folder ? {folder: folder.folderPath, tags: [tagIndexFormat]} : [tagIndexFormat])
        });
        if (response.ok) {
            showToast('标签已删除', 'success');
            loadFileList();
        } else {
            showToast('删除标签失败', 'error');
        }
    } catch (error) {
        console.error('Remove tag error:', error);
        showToast('删除标签失败', 'error');
    }
}

// ======================== 重命名 ========================
let renameTargetUuid = null;
let renameTargetFolder = null;

function showRenameModal(uuid, oldName) {
    renameTargetUuid = uuid;
    renameTargetFolder = null;
    document.getElementById('renamePrompt').textContent = '请输入新的文件名：';
    const input = document.getElementById('renameInput');
    input.value = oldName;
    document.getElementById('renameModal').classList.remove('hidden');
    input.focus();
    const dot = oldName.lastIndexOf('.');
    if (dot > 0) {
        input.setSelectionRange(0, dot);
    } else {
        input.select();
    }
}

function showFolderRenameModal(folder) {
    if (folder.managed === false) return;
    showRenameModal(folder.uuid, folder.fileName);
    renameTargetFolder = folder;
    document.getElementById('renamePrompt').textContent = '请输入新的文件夹名称，内部文件和子文件夹会一并保留：';
    document.getElementById('renameInput').select();
}

function closeRenameModal() {
    document.getElementById('renameModal').classList.add('hidden');
    renameTargetUuid = null;
    renameTargetFolder = null;
}

async function confirmRename() {
    if (!renameTargetUuid && !renameTargetFolder) return;
    const newFileName = document.getElementById('renameInput').value.trim();
    if (!newFileName) {
        showToast(renameTargetFolder ? '文件夹名称不能为空' : '文件名不能为空', 'warning');
        return;
    }
    if (renameTargetFolder && (/[\/\\\x00-\x1f\x7f]/.test(newFileName) || newFileName === '.' || newFileName === '..')) {
        showToast('请输入有效的文件夹名称', 'warning');
        return;
    }
    const uuid = renameTargetUuid;
    const folder = renameTargetFolder;
    const submit = document.getElementById('confirmRenameBtn');
    submit.disabled = true;
    try {
        const response = await fetch(folder ? '/api/folders/rename' : `/api/file/${uuid}/rename`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(folder ? {folder: folder.folderPath, name: newFileName} : {fileName: newFileName})
        });
        if (response.ok) {
            showToast('重命名成功', 'success');
            closeRenameModal();
            loadFileList();
        } else {
            const msg = await response.text();
            showToast('重命名失败: ' + msg, 'error');
        }
    } catch (error) {
        console.error('Rename error:', error);
        showToast('重命名失败', 'error');
    } finally {
        submit.disabled = false;
    }
}

// Utility functions
function escapeFileName(value) {
    return value.replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character]));
}

function isImage(file) {
    if (!file || file.folder || !file.fileName) return false;
    const imageExtensions = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg', 'ico', 'tiff', 'tif'];
    const fileName = file.fileName.toLowerCase();
    return imageExtensions.some(ext => fileName.endsWith('.' + ext) || fileName.endsWith(ext));
}

function isVideo(file) {
    if (!file || file.folder || !file.fileName) return false;
    const videoExtensions = ['mp4', 'webm', 'avi', 'mov', 'mkv', 'flv', 'wmv', 'm4v'];
    const fileName = file.fileName.toLowerCase();
    return videoExtensions.some(ext => fileName.endsWith('.' + ext) || fileName.endsWith(ext));
}

function isAlbum(file) {
    return file && file.albumAvailable === '1';
}

// 返回缩略图 URL：优先使用 thumbUrl（集群下指向上传节点），否则回退到本地 /thumb/{uuid}
function getThumbUrl(file) {
    if (file && file.thumbUrl) return file.thumbUrl;
    return '/thumb/' + file.uuid;
}

// 判断是否有缩略图可用：thumbUrl 存在或本地 thumbAvailable === '1'
function hasThumb(file) {
    return !!(file && (file.thumbUrl || file.thumbAvailable === '1'));
}

function isAudio(file) {
    if (!file || file.folder || !file.fileName) return false;
    const audioExtensions = ['mp3', 'wav', 'flac', 'aac', 'ogg', 'wma', 'm4a', 'opus'];
    const fileName = file.fileName.toLowerCase();
    return audioExtensions.some(ext => fileName.endsWith('.' + ext) || fileName.endsWith(ext));
}

async function fetchAlbumPoster(uuid) {
    try {
        const response = await fetch(`/api/album/${uuid}`);
        if (response.ok) {
            const data = await response.json();
            return data.poster || null;
        }
    } catch (e) {
        console.error('Failed to fetch album poster:', e);
    }
    return null;
}

// Apply or remove NSFW blur in both grid and list views
function applyNsfwBlurToAll() {
    document.querySelectorAll('.waterfall-card, .list-row, .icon-item').forEach(applyNsfwBlurToItem);
}

// Check the tag labels shared by grid cards and list rows
function itemHasNsfwTag(item) {
    const tags = (item.dataset.tags || '').toLowerCase().split('\n');
    if (tags.some(text => text.trim() === 'nsfw' || text.startsWith('nsfw:') || text.endsWith(':nsfw'))) return true;
    const badges = item.querySelectorAll('.waterfall-tag-badge, .tag-badge-text');
    for (const badge of badges) {
        const text = (badge.textContent || '').toLowerCase().trim();
        if (text === 'nsfw' || text.startsWith('nsfw:') || text.endsWith(':nsfw')) {
            return true;
        }
    }
    return false;
}

// Synchronize blur after an item is rendered or the toggle changes
function applyNsfwBlurToItem(item) {
    item.classList.toggle('nsfw-blur', nsfwBlurActive && itemHasNsfwTag(item));
}

function formatFileSize(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i];
}

function formatDateTime(ts) {
    const d = new Date(ts);
    const pad = n => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function debounce(func, wait) {
    let timeout;
    return function executedFunction(...args) {
        const later = () => {
            clearTimeout(timeout);
            func.apply(this, args);
        };
        clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
}

// Drag and drop
function initializeDragAndDrop() {
    const dropZone = document.body;
    const overlay = document.getElementById('listDropOverlay');

    ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
        dropZone.addEventListener(eventName, preventDefaults, false);
    });

    function preventDefaults(e) {
        e.preventDefault();
        e.stopPropagation();
    }

    dropZone.addEventListener('dragenter', () => {
        dropZone.classList.add('drag-active');
        if (overlay) overlay.classList.remove('hidden');
    }, false);

    dropZone.addEventListener('dragover', (e) => {
        if (draggingFileHandle) {
            e.dataTransfer.dropEffect = 'copy';
        }
    }, false);

    dropZone.addEventListener('dragleave', (e) => {
        if (e.relatedTarget === null) {
            dropZone.classList.remove('drag-active');
            if (overlay) overlay.classList.add('hidden');
        }
    }, false);

    dropZone.addEventListener('drop', async (e) => {
        dropZone.classList.remove('drag-active');
        if (overlay) overlay.classList.add('hidden');
        let files = e.dataTransfer.files;
        // 右栏拖拽：files 为空时从 draggingFileHandle 异步获取 File
        if (files.length === 0 && draggingFileHandle) {
            try {
                const file = await draggingFileHandle.getFile();
                files = [file];
            } catch (err) {
                showToast('读取文件失败：' + err.message, 'error');
            }
            draggingFileHandle = null;
        }
        if (files.length > 0) {
            handleFileUpload({ target: { files } });
        }
    }, false);
}

// ===== IndexedDB helpers：持久化 FileSystemDirectoryHandle =====
const DIR_DB_NAME = 'tfs-local-dir';
const DIR_STORE_NAME = 'handles';
const DIR_KEY = 'root';
const BOOKMARK_STORE = 'bookmarks';
const RECENT_STORE = 'recent';

function openDirDB() {
    return new Promise((resolve, reject) => {
        const req = indexedDB.open(DIR_DB_NAME, 2);
        req.onupgradeneeded = () => {
            const db = req.result;
            if (!db.objectStoreNames.contains(DIR_STORE_NAME)) {
                db.createObjectStore(DIR_STORE_NAME);
            }
            if (!db.objectStoreNames.contains(BOOKMARK_STORE)) {
                db.createObjectStore(BOOKMARK_STORE, { keyPath: 'id', autoIncrement: true });
            }
            if (!db.objectStoreNames.contains(RECENT_STORE)) {
                db.createObjectStore(RECENT_STORE, { keyPath: 'id', autoIncrement: true });
            }
        };
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error);
    });
}

async function saveDirHandle(handle) {
    const db = await openDirDB();
    return new Promise((resolve, reject) => {
        const tx = db.transaction(DIR_STORE_NAME, 'readwrite');
        tx.objectStore(DIR_STORE_NAME).put(handle, DIR_KEY);
        tx.oncomplete = () => { db.close(); resolve(); };
        tx.onerror = () => { db.close(); reject(tx.error); };
    });
}

async function loadDirHandle() {
    const db = await openDirDB();
    return new Promise((resolve, reject) => {
        const tx = db.transaction(DIR_STORE_NAME, 'readonly');
        const req = tx.objectStore(DIR_STORE_NAME).get(DIR_KEY);
        req.onsuccess = () => { db.close(); resolve(req.result || null); };
        req.onerror = () => { db.close(); reject(req.error); };
    });
}

// ===== 书签 & 最近打开 =====
async function saveBookmark(handle, name) {
    const db = await openDirDB();
    return new Promise((resolve, reject) => {
        const tx = db.transaction(BOOKMARK_STORE, 'readwrite');
        tx.objectStore(BOOKMARK_STORE).add({ handle, name, savedAt: Date.now() });
        tx.oncomplete = () => { db.close(); resolve(); };
        tx.onerror = () => { db.close(); reject(tx.error); };
    });
}

async function loadBookmarks() {
    const db = await openDirDB();
    return new Promise((resolve, reject) => {
        const tx = db.transaction(BOOKMARK_STORE, 'readonly');
        const req = tx.objectStore(BOOKMARK_STORE).getAll();
        req.onsuccess = () => { db.close(); resolve(req.result || []); };
        req.onerror = () => { db.close(); reject(req.error); };
    });
}

async function deleteBookmark(id) {
    const db = await openDirDB();
    return new Promise((resolve, reject) => {
        const tx = db.transaction(BOOKMARK_STORE, 'readwrite');
        tx.objectStore(BOOKMARK_STORE).delete(id);
        tx.oncomplete = () => { db.close(); resolve(); };
        tx.onerror = () => { db.close(); reject(tx.error); };
    });
}

async function addRecent(handle, name) {
    const db = await openDirDB();
    return new Promise((resolve, reject) => {
        const tx = db.transaction(RECENT_STORE, 'readwrite');
        const store = tx.objectStore(RECENT_STORE);
        const getAllReq = store.getAll();
        getAllReq.onsuccess = () => {
            const all = getAllReq.result || [];
            all.forEach(r => { if (r.name === name) store.delete(r.id); });
            store.add({ handle, name, openedAt: Date.now() });
            const remaining = all.filter(r => r.name !== name).sort((a, b) => b.openedAt - a.openedAt);
            if (remaining.length >= 5) {
                remaining.slice(4).forEach(r => store.delete(r.id));
            }
        };
        tx.oncomplete = () => { db.close(); resolve(); };
        tx.onerror = () => { db.close(); reject(tx.error); };
    });
}

async function loadRecents() {
    const db = await openDirDB();
    return new Promise((resolve, reject) => {
        const tx = db.transaction(RECENT_STORE, 'readonly');
        const req = tx.objectStore(RECENT_STORE).getAll();
        req.onsuccess = () => {
            db.close();
            resolve((req.result || []).sort((a, b) => b.openedAt - a.openedAt).slice(0, 5));
        };
        req.onerror = () => { db.close(); reject(req.error); };
    });
}

async function clearRecents() {
    const db = await openDirDB();
    return new Promise((resolve, reject) => {
        const tx = db.transaction(RECENT_STORE, 'readwrite');
        tx.objectStore(RECENT_STORE).clear();
        tx.oncomplete = () => { db.close(); resolve(); };
        tx.onerror = () => { db.close(); reject(tx.error); };
    });
}

// ===== Local folder panel：打开本地文件夹、渲染表格列表、拖拽上传 =====
function initializeLocalFolderPanel() {
    const openBtn = document.getElementById('openLocalFolderBtn');
    const panel = document.getElementById('localFolderPanel');
    const closeBtn = document.getElementById('localFolderClose');
    const changeBtn = document.getElementById('localFolderChange');
    const backBtn = document.getElementById('localFolderBack');
    const bookmarkBtn = document.getElementById('localFolderBookmark');
    const bookmarksBtn = document.getElementById('localFolderBookmarksBtn');
    const bookmarksDropdown = document.getElementById('bookmarksDropdown');
    const previewToggleBtn = document.getElementById('localFolderPreviewToggle');

    if (!openBtn) return;

    // 不支持 File System Access API 时隐藏入口
    if (!window.showDirectoryPicker) {
        openBtn.classList.add('hidden');
        return;
    }

    openBtn.addEventListener('click', () => {
        const panel = document.getElementById('localFolderPanel');
        if (panel && panel.classList.contains('active')) {
            closeLocalFolderPanel();
        } else {
            openLocalFolder();
        }
    });
    if (closeBtn) closeBtn.addEventListener('click', closeLocalFolderPanel);
    if (changeBtn) changeBtn.addEventListener('click', changeLocalFolder);
    if (backBtn) backBtn.addEventListener('click', backLocalDir);
    if (bookmarkBtn) {
        bookmarkBtn.disabled = true;
        bookmarkBtn.addEventListener('click', toggleCurrentBookmark);
    }
    if (previewToggleBtn) {
        previewToggleBtn.classList.toggle('active', localPreviewEnabled);
        previewToggleBtn.title = localPreviewEnabled ? '关闭悬停预览' : '开启悬停预览';
        previewToggleBtn.addEventListener('click', () => {
            localPreviewEnabled = !localPreviewEnabled;
            localStorage.setItem('tfs-local-preview-enabled', String(localPreviewEnabled));
            previewToggleBtn.classList.toggle('active', localPreviewEnabled);
            previewToggleBtn.title = localPreviewEnabled ? '关闭悬停预览' : '开启悬停预览';
            if (!localPreviewEnabled) hideLocalImagePreview();
        });
    }
    if (bookmarksBtn) {
        bookmarksBtn.addEventListener('click', async (e) => {
            e.stopPropagation();
            const wasHidden = bookmarksDropdown.classList.contains('hidden');
            await renderBookmarksDropdown();
            bookmarksDropdown.classList.toggle('hidden', !wasHidden);
        });
        document.addEventListener('click', (e) => {
            if (bookmarksDropdown && !bookmarksDropdown.contains(e.target) && !bookmarksBtn.contains(e.target)) {
                bookmarksDropdown.classList.add('hidden');
            }
        });
    }

    // 阻止右栏内部 drop 冒泡到 body，防止拖回右栏误触发上传
    if (panel) {
        panel.addEventListener('dragover', (e) => e.preventDefault());
        panel.addEventListener('drop', (e) => e.stopPropagation());
    }
}

async function openLocalFolder() {
    try {
        // 优先使用已持久化的目录句柄
        if (!storedDirHandle) {
            storedDirHandle = await loadDirHandle();
        }
        if (storedDirHandle) {
            // 检查权限，已授权直接渲染；未授权则在用户手势中请求
            let perm = await storedDirHandle.queryPermission({ mode: 'read' });
            if (perm !== 'granted') {
                perm = await storedDirHandle.requestPermission({ mode: 'read' });
            }
            if (perm === 'granted') {
                dirStack = [storedDirHandle];
                await renderLocalDir();
                showLocalFolderPanel();
                return;
            }
            // 权限被拒，回退到 picker 重新选择
        }
        // 无持久化句柄或权限被拒，走 picker
        const handle = await window.showDirectoryPicker();
        storedDirHandle = handle;
        await saveDirHandle(handle);
        await addRecent(handle, handle.name);
        dirStack = [handle];
        await renderLocalDir();
        showLocalFolderPanel();
    } catch (e) {
        if (e.name !== 'AbortError') {
            showToast('打开文件夹失败：' + e.message, 'error');
        }
    }
}

async function changeLocalFolder() {
    try {
        const handle = await window.showDirectoryPicker();
        storedDirHandle = handle;
        await saveDirHandle(handle);
        await addRecent(handle, handle.name);
        dirStack = [handle];
        await renderLocalDir();
    } catch (e) {
        if (e.name !== 'AbortError') {
            showToast('更换文件夹失败：' + e.message, 'error');
        }
    }
}

function showLocalFolderPanel(skipAnimation) {
    const panel = document.getElementById('localFolderPanel');
    const mainView = document.getElementById('mainView');
    const openBtn = document.getElementById('openLocalFolderBtn');
    if (skipAnimation) {
        if (panel) panel.classList.add('no-transition');
        if (mainView) mainView.classList.add('no-transition');
    }
    if (panel) panel.classList.add('active');
    if (mainView) mainView.classList.add('local-panel-active');
    if (openBtn) openBtn.classList.add('active');
    localStorage.setItem('tfs-local-panel-open', 'true');
    if (skipAnimation) {
        if (panel) panel.offsetHeight; // 强制 reflush，确保无动画样式已应用
        if (panel) panel.classList.remove('no-transition');
        if (mainView) mainView.classList.remove('no-transition');
    }
}

function closeLocalFolderPanel() {
    const panel = document.getElementById('localFolderPanel');
    const mainView = document.getElementById('mainView');
    const openBtn = document.getElementById('openLocalFolderBtn');
    if (panel) panel.classList.remove('active');
    if (mainView) mainView.classList.remove('local-panel-active');
    if (openBtn) openBtn.classList.remove('active');
    localStorage.setItem('tfs-local-panel-open', 'false');
}

// 保存/移除当前目录书签
async function toggleCurrentBookmark() {
    const current = dirStack[dirStack.length - 1];
    if (!current) {
        showToast('请先打开一个文件夹', 'error');
        return;
    }
    try {
        const existing = await findCurrentBookmark();
        if (existing) {
            await deleteBookmark(existing.id);
            showToast('已移除书签', 'info');
        } else {
            const name = dirStack.map(h => h.name).join(' / ');
            await saveBookmark(current, name);
            showToast('已保存书签', 'success');
        }
        await updateBookmarkBtnState();
    } catch (e) {
        showToast('书签操作失败：' + e.message, 'error');
    }
}

// 查找当前目录是否已保存为书签
async function findCurrentBookmark() {
    const current = dirStack[dirStack.length - 1];
    if (!current) return null;
    const bookmarks = await loadBookmarks();
    for (const bm of bookmarks) {
        if (bm.handle && await current.isSameEntry(bm.handle)) {
            return bm;
        }
    }
    return null;
}

// 更新书签按钮状态
async function updateBookmarkBtnState() {
    const bookmarkBtn = document.getElementById('localFolderBookmark');
    if (!bookmarkBtn) return;
    const current = dirStack[dirStack.length - 1];
    if (!current) {
        bookmarkBtn.disabled = true;
        bookmarkBtn.classList.remove('active');
        delete bookmarkBtn.dataset.bookmarkId;
        return;
    }
    bookmarkBtn.disabled = false;
    const bm = await findCurrentBookmark();
    if (bm) {
        bookmarkBtn.classList.add('active');
        bookmarkBtn.title = '移除书签';
        bookmarkBtn.dataset.bookmarkId = bm.id;
    } else {
        bookmarkBtn.classList.remove('active');
        bookmarkBtn.title = '保存书签';
        delete bookmarkBtn.dataset.bookmarkId;
    }
}

// 通过 handle 打开目录（书签/最近打开复用）
async function openFromHandle(handle) {
    try {
        let perm = await handle.queryPermission({ mode: 'read' });
        if (perm !== 'granted') {
            perm = await handle.requestPermission({ mode: 'read' });
        }
        if (perm !== 'granted') return;

        // 尝试从当前栈顶查找到目标 handle 的路径，保留父子目录链
        const current = dirStack[dirStack.length - 1];
        if (current) {
            const path = await findPathToHandle(current, handle);
            if (path) {
                dirStack.push(...path);
                storedDirHandle = handle;
                await saveDirHandle(handle);
                await renderLocalDir();
                showLocalFolderPanel();
                return;
            }
        }

        // 无父子关系，重置栈
        storedDirHandle = handle;
        await saveDirHandle(handle);
        dirStack = [handle];
        await renderLocalDir();
        showLocalFolderPanel();
    } catch (e) {
        showToast('打开失败：' + e.message, 'error');
    }
}

// 从 startHandle 查找到 targetHandle 的子目录链（不含 startHandle，含 targetHandle）
async function findPathToHandle(startHandle, targetHandle, maxDepth = 5) {
    if (await startHandle.isSameEntry(targetHandle)) return [];
    if (maxDepth <= 0) return null;

    for await (const [, childHandle] of startHandle.entries()) {
        if (childHandle.kind !== 'directory') continue;
        if (await childHandle.isSameEntry(targetHandle)) {
            return [childHandle];
        }
        const subPath = await findPathToHandle(childHandle, targetHandle, maxDepth - 1);
        if (subPath) {
            return [childHandle, ...subPath];
        }
    }
    return null;
}

// 渲染书签 & 最近打开下拉菜单
async function renderBookmarksDropdown() {
    const dropdown = document.getElementById('bookmarksDropdown');
    if (!dropdown) return;

    const [bookmarks, recents] = await Promise.all([loadBookmarks(), loadRecents()]);

    let html = '';

    if (bookmarks.length > 0) {
        html += `<div class="px-3 py-2 border-b" style="border-color: var(--color-border-primary);"><p class="text-xs font-medium uppercase tracking-wider" style="color: var(--color-text-tertiary);">书签</p></div>`;
        html += '<div class="py-1">';
        bookmarks.forEach(bm => {
            html += `
                <div class="bookmark-item flex items-center justify-between px-3 py-2 text-sm cursor-pointer transition-colors" data-bookmark-id="${bm.id}" style="color: var(--color-text-secondary);" onmouseover="this.style.backgroundColor='var(--color-bg-tertiary)'" onmouseout="this.style.backgroundColor=''">
                    <span class="truncate flex-1">${BootFSSecurity.html(bm.name)}</span>
                    <button class="bookmark-delete-btn ml-2 flex-shrink-0 p-0.5 rounded" data-bookmark-id="${bm.id}" style="color: var(--color-text-muted);" title="删除书签">
                        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path></svg>
                    </button>
                </div>
            `;
        });
        html += '</div>';
    }

    if (recents.length > 0) {
        html += `<div class="px-3 py-2 border-t ${bookmarks.length > 0 ? 'border-b' : 'border-b'}" style="border-color: var(--color-border-primary);"><p class="text-xs font-medium uppercase tracking-wider" style="color: var(--color-text-tertiary);">最近打开</p></div>`;
        html += '<div class="py-1">';
        recents.forEach(r => {
            html += `
                <div class="recent-item flex items-center px-3 py-2 text-sm cursor-pointer transition-colors" data-recent-id="${r.id}" style="color: var(--color-text-secondary);" onmouseover="this.style.backgroundColor='var(--color-bg-tertiary)'" onmouseout="this.style.backgroundColor=''">
                    <span class="truncate">${BootFSSecurity.html(r.name)}</span>
                </div>
            `;
        });
        html += '</div>';
        html += `<div class="border-t" style="border-color: var(--color-border-primary);"><button id="clearRecentBtn" class="w-full text-left px-3 py-2 text-sm transition-colors" style="color: var(--color-danger);" onmouseover="this.style.backgroundColor='var(--color-bg-tertiary)'" onmouseout="this.style.backgroundColor=''">清除最近打开</button></div>`;
    }

    if (!html) {
        html = '<div class="px-3 py-6 text-center text-sm" style="color: var(--color-text-tertiary);">暂无书签或历史记录</div>';
    }

    dropdown.innerHTML = html;

    // 绑定书签项点击
    dropdown.querySelectorAll('.bookmark-item').forEach(item => {
        item.addEventListener('click', async (e) => {
            if (e.target.closest('.bookmark-delete-btn')) return;
            const id = parseInt(item.dataset.bookmarkId);
            const bms = await loadBookmarks();
            const bm = bms.find(b => b.id === id);
            if (bm) {
                await openFromHandle(bm.handle);
                dropdown.classList.add('hidden');
            }
        });
    });

    // 绑定书签删除
    dropdown.querySelectorAll('.bookmark-delete-btn').forEach(btn => {
        btn.addEventListener('click', async (e) => {
            e.stopPropagation();
            const id = parseInt(btn.dataset.bookmarkId);
            await deleteBookmark(id);
            await renderBookmarksDropdown();
        });
    });

    // 绑定最近打开点击
    dropdown.querySelectorAll('.recent-item').forEach(item => {
        item.addEventListener('click', async () => {
            const id = parseInt(item.dataset.recentId);
            const recs = await loadRecents();
            const r = recs.find(x => x.id === id);
            if (r) {
                await openFromHandle(r.handle);
                dropdown.classList.add('hidden');
            }
        });
    });

    // 绑定清除最近打开
    const clearBtn = document.getElementById('clearRecentBtn');
    if (clearBtn) {
        clearBtn.addEventListener('click', async () => {
            await clearRecents();
            await renderBookmarksDropdown();
        });
    }
}

// 恢复右栏打开状态：从 IndexedDB 读取句柄 + queryPermission 检查权限
// queryPermission 不需要用户手势，若权限仍为 granted 则直接恢复；否则等用户点击按钮时 requestPermission
async function restoreLocalPanel() {
    if (localStorage.getItem('tfs-local-panel-open') !== 'true') return;
    if (currentView !== 'list') return;
    const panel = document.getElementById('localFolderPanel');
    if (panel && panel.classList.contains('active')) return;

    if (!storedDirHandle) {
        storedDirHandle = await loadDirHandle();
    }
    if (!storedDirHandle) return;

    try {
        const perm = await storedDirHandle.queryPermission({ mode: 'read' });
        if (perm === 'granted') {
            dirStack = [storedDirHandle];
            await renderLocalDir();
            if (currentView === 'list') showLocalFolderPanel(true);
        }
    } catch (_) {}
}

// 本地文件图片 hover 预览
let localPreviewTimer = null;
let localPreviewUrl = null;
let localPreviewEnabled = localStorage.getItem('tfs-local-preview-enabled') !== 'false';

function showLocalImagePreview(row, url) {
    let previewEl = document.getElementById('localImagePreview');
    if (!previewEl) {
        previewEl = document.createElement('div');
        previewEl.id = 'localImagePreview';
        previewEl.className = 'fixed z-50 pointer-events-none';
        previewEl.style.display = 'none';
        const img = document.createElement('img');
        img.className = 'rounded-lg shadow-xl';
        img.style.cssText = 'max-width: 240px; max-height: 240px; border: 1px solid var(--color-border-primary);';
        previewEl.appendChild(img);
        document.body.appendChild(previewEl);
    }
    const img = previewEl.querySelector('img');
    img.onload = () => {
        const rect = row.getBoundingClientRect();
        const previewH = img.offsetHeight || 240;
        const previewW = img.offsetWidth || 240;
        let top = rect.bottom + 8;
        if (top + previewH > window.innerHeight) {
            top = rect.top - previewH - 8;
            if (top < 0) top = 8;
        }
        let left = rect.left + 8;
        if (left + previewW > window.innerWidth) {
            left = window.innerWidth - previewW - 8;
        }
        previewEl.style.left = left + 'px';
        previewEl.style.top = top + 'px';
        previewEl.style.display = 'block';
    };
    img.src = url;
}

function hideLocalImagePreview() {
    const previewEl = document.getElementById('localImagePreview');
    if (previewEl) previewEl.style.display = 'none';
    if (localPreviewUrl) {
        URL.revokeObjectURL(localPreviewUrl);
        localPreviewUrl = null;
    }
}

async function renderLocalDir() {
    const listEl = document.getElementById('localFileList');
    const emptyHint = document.getElementById('localEmptyHint');
    const pathEl = document.getElementById('localFolderPath');
    const backBtn = document.getElementById('localFolderBack');
    if (!listEl) return;

    const current = dirStack[dirStack.length - 1];
    const entries = [];
    for await (const [name, handle] of current.entries()) {
        entries.push({ name, handle, kind: handle.kind });
    }
    // Pre-fetch file metadata (size + lastModified) in parallel for sorting and display
    await Promise.all(entries.map(async (entry) => {
        if (entry.kind === 'file') {
            try {
                const file = await entry.handle.getFile();
                entry.lastModified = file.lastModified;
                entry.size = file.size;
            } catch (_) {
                entry.lastModified = 0;
                entry.size = 0;
            }
        }
    }));
    entries.sort((a, b) => {
        if (a.kind !== b.kind) return a.kind === 'directory' ? -1 : 1;
        if (a.kind === 'file') {
            // Files: newest first by modification time
            return (b.lastModified || 0) - (a.lastModified || 0);
        }
        // Directories: by name
        return a.name.localeCompare(b.name);
    });

    pathEl.textContent = '../ ' + (dirStack.map(h => h.name).join(' / ') || '本地文件夹');
    backBtn.disabled = dirStack.length <= 1;

    if (entries.length === 0) {
        listEl.innerHTML = '';
        emptyHint.classList.remove('hidden');
        return;
    }
    emptyHint.classList.add('hidden');

    const dirIcon = '<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z"></path></svg>';
    const fileIcon = '<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z"></path></svg>';

    listEl.innerHTML = entries.map(({ kind }, index) => {
        const isDir = kind === 'directory';
        const dataKind = isDir ? 'dir' : 'file';
        return `
            <tr class="local-file-row" data-kind="${dataKind}" data-index="${index}" style="background-color: var(--color-bg-secondary); border-color: var(--color-border-primary);">
                <td class="px-6 py-4">
                    <div class="flex items-center">
                        <div class="flex-shrink-0 h-10 w-10 rounded-lg flex items-center justify-center local-file-icon" style="background-color: var(--color-bg-tertiary);">
                            ${isDir ? dirIcon : fileIcon}
                        </div>
                        <div class="ml-4 min-w-0">
                            <div class="text-sm font-medium truncate" style="color: var(--color-text-primary);"></div>
                        </div>
                    </div>
                </td>
                <td class="px-6 py-4 whitespace-nowrap text-sm local-file-size" style="color: var(--color-text-secondary);">—</td>
            </tr>
        `;
    }).join('');

    // 填充文件名、绑定事件、异步获取文件大小
    listEl.querySelectorAll('.local-file-row').forEach(row => {
        const entry = entries[parseInt(row.dataset.index)];
        if (!entry) return;
        row.querySelector('.text-sm.font-medium').textContent = entry.name;
        if (entry.kind === 'directory') {
            row.addEventListener('click', () => enterLocalDir(entry.handle));
            // 异步统计子项数量作为"大小"列展示
            (async () => {
                try {
                    let count = 0;
                    for await (const _ of entry.handle.entries()) count++;
                    const sizeCell = row.querySelector('.local-file-size');
                    if (sizeCell) sizeCell.textContent = `${count} 项`;
                } catch (_) {}
            })();
        } else {
            row.setAttribute('draggable', 'true');
            row.addEventListener('dragstart', (e) => {
                draggingFileHandle = entry.handle;
                row.classList.add('dragging');
                e.dataTransfer.effectAllowed = 'copy';
                e.dataTransfer.setData('text/plain', entry.name);
            });
            row.addEventListener('dragend', () => {
                row.classList.remove('dragging');
                draggingFileHandle = null;
            });
            // 文件大小已在排序前预取
            const sizeCell = row.querySelector('.local-file-size');
            if (sizeCell) sizeCell.textContent = formatFileSize(entry.size || 0);

            // 图片 hover 预览
            const lowerName = entry.name.toLowerCase();
            const imageExtensions = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg', 'ico', 'tiff', 'tif'];
            if (imageExtensions.some(ext => lowerName.endsWith('.' + ext))) {
                let hoverActive = false;
                row.addEventListener('mouseenter', () => {
                    if (!localPreviewEnabled) return;
                    hoverActive = true;
                    localPreviewTimer = setTimeout(async () => {
                        if (!hoverActive) return;
                        try {
                            const file = await entry.handle.getFile();
                            if (!hoverActive) return;
                            if (localPreviewUrl) URL.revokeObjectURL(localPreviewUrl);
                            localPreviewUrl = URL.createObjectURL(file);
                            showLocalImagePreview(row, localPreviewUrl);
                        } catch (_) {}
                    }, 300);
                });
                row.addEventListener('mouseleave', () => {
                    hoverActive = false;
                    if (localPreviewTimer) {
                        clearTimeout(localPreviewTimer);
                        localPreviewTimer = null;
                    }
                    hideLocalImagePreview();
                });
            }
        }
    });

    updateBookmarkBtnState();
}

function enterLocalDir(handle) {
    dirStack.push(handle);
    renderLocalDir();
}

function backLocalDir() {
    if (dirStack.length > 1) {
        dirStack.pop();
        renderLocalDir();
    }
}

// Show loading overlay
function showLoadingOverlay() {
    const overlay = document.getElementById('loadingOverlay');
    if (overlay) {
        overlay.classList.remove('hidden');
    }
}

// Hide loading overlay
function hideLoadingOverlay() {
    const overlay = document.getElementById('loadingOverlay');
    if (overlay) {
        overlay.classList.add('hidden');
    }
}

// TS Upload Functions
let selectedTsFiles = [];
let currentVirtualFileUuid = null;
let tsUploadMode = 'create'; // 'create' or 'edit'
let editingFileObject = null;
let currentSegments = [];
let selectedSegmentIndex = null;
let draggedSegmentIndex = null;

function showTsUploadModal() {
    if (selectedFiles.size > 1) {
        showToast('仅限单选，请只选择一个文件', 'warning');
        return;
    }
    
    selectedTsFiles = [];
    currentVirtualFileUuid = null;
    editingFileObject = null;
    tsUploadMode = 'create';
    currentSegments = [];
    selectedSegmentIndex = null;
    
    if (selectedFiles.size === 1) {
        const selectedUuid = Array.from(selectedFiles)[0];
        fetch(`/file/${selectedUuid}`)
            .then(response => {
                if (!response.ok) {
                    throw new Error('File not found');
                }
                return response.json();
            })
            .then(file => {
                if (file && file.hlsAvailable === '1') {
                    tsUploadMode = 'edit';
                    editingFileObject = file;
                    currentVirtualFileUuid = file.uuid;
                    openTsUploadModalUI();
                    loadSegmentList();
                } else {
                    showToast('该内容仅 HLS 可用，请选择已启用 HLS 的文件', 'warning');
                }
            })
            .catch(() => {
                openTsUploadModalUI();
            });
    } else {
        openTsUploadModalUI();
    }
}

function openTsUploadModalUI() {
    document.getElementById('tsFileInput').value = '';
    document.getElementById('tsFileList').innerHTML = '';
    document.getElementById('tsFileCount').textContent = '已选择 0 个文件';
    document.getElementById('tsUploadProgress').classList.add('hidden');
    document.getElementById('tsUploadProgressBar').style.width = '0%';
    document.getElementById('tsUploadPercent').textContent = '0%';
    document.getElementById('tsUploadStatus').textContent = '准备上传...';
    
    const modalTitle = document.getElementById('tsUploadModalTitle');
    const fileNameInput = document.getElementById('tsVideoFileName');
    const startBtn = document.getElementById('startTsUploadBtn');
    const infoDiv = document.getElementById('tsEditInfo');
    const segmentActions = document.getElementById('tsSegmentActions');
    
    if (tsUploadMode === 'edit' && editingFileObject) {
        modalTitle.textContent = '编辑 HLS 切片';
        fileNameInput.value = editingFileObject.fileName;
        fileNameInput.disabled = true;
        startBtn.textContent = '追加切片';
        startBtn.classList.remove('theme-btn-primary');
        startBtn.classList.add('theme-btn-success');
        
        if (infoDiv) {
            infoDiv.innerHTML = `
                <div class="flex items-center space-x-2 mb-2">
                    <span class="px-2 py-1 text-xs rounded-md" style="background-color: rgba(16, 185, 129, 0.1); color: var(--color-success);">编辑模式</span>
                    <span class="text-xs theme-page-text-muted">UUID: ${editingFileObject.uuid}</span>
                </div>
                <p class="text-xs theme-page-text-secondary">新上传的切片将追加到现有播放列表末尾，右侧可拖拽调整顺序</p>
            `;
            infoDiv.classList.remove('hidden');
        }
        
        if (segmentActions) {
            segmentActions.classList.remove('hidden');
        }
    } else {
        modalTitle.textContent = 'TS 切片上传';
        fileNameInput.value = '';
        fileNameInput.disabled = false;
        startBtn.textContent = '开始上传';
        startBtn.classList.remove('theme-btn-success');
        startBtn.classList.add('theme-btn-primary');
        
        if (infoDiv) {
            infoDiv.innerHTML = `
                <div class="flex items-center space-x-2 mb-2">
                    <span class="px-2 py-1 text-xs rounded-md" style="background-color: rgba(245, 158, 11, 0.1); color: var(--color-warning);">新建模式</span>
                </div>
                <p class="text-xs theme-page-text-secondary">将创建新的虚拟文件并上传切片</p>
            `;
            infoDiv.classList.remove('hidden');
        }
        
        if (segmentActions) {
            segmentActions.classList.add('hidden');
        }
        
        renderSegmentList([]);
    }
    
    document.getElementById('tsUploadModal').classList.remove('hidden');
    
    document.getElementById('tsFileInput').addEventListener('change', handleTsFileSelect);
    document.getElementById('startTsUploadBtn').addEventListener('click', startTsUpload);
    document.getElementById('tsSaveOrderBtn').addEventListener('click', saveSegmentOrder);
    document.getElementById('tsDeleteSegmentBtn').addEventListener('click', deleteSelectedSegment);
}

function loadSegmentList() {
    if (!currentVirtualFileUuid) return;
    
    fetch(`/hls/${currentVirtualFileUuid}/segments/list`)
        .then(response => response.json())
        .then(data => {
            currentSegments = data.segments || [];
            renderSegmentList(currentSegments);
        })
        .catch(error => {
            console.error('Failed to load segment list:', error);
            renderSegmentList([]);
        });
}

function renderSegmentList(segments) {
    const listContainer = document.getElementById('tsSegmentList');
    const countSpan = document.getElementById('tsSegmentCount');
    
    if (countSpan) {
        countSpan.textContent = `共 ${segments.length} 个切片`;
    }
    
    if (!segments || segments.length === 0) {
        listContainer.innerHTML = `
            <div class="text-center py-8 theme-page-text-muted text-sm">
                <svg class="mx-auto h-8 w-8 mb-2 opacity-50" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 4v16M17 4v16M3 8h4m10 0h4M3 12h18M3 16h4m10 0h4M4 20h16a1 1 0 001-1V5a1 1 0 00-1-1H4a1 1 0 00-1 1v14a1 1 0 001 1z"></path>
                </svg>
                <p>暂无切片</p>
                <p class="text-xs mt-1">${tsUploadMode === 'edit' ? '上传切片后显示' : '选择 HLS 文件后显示'}</p>
            </div>
        `;
        return;
    }
    
    listContainer.innerHTML = segments.map((segment, index) => `
        <div class="segment-item flex items-center gap-2 p-2 theme-rounded-lg cursor-move transition-all"
             style="background-color: var(--color-bg-secondary); ${selectedSegmentIndex === index ? 'box-shadow: inset 0 0 0 2px var(--color-accent-primary);' : ''}"
             draggable="true"
             data-index="${index}"
             data-filename="${BootFSSecurity.html(segment.fileName)}">
            <div class="flex-shrink-0 w-6 h-6 flex items-center justify-center theme-rounded text-xs font-bold"
                 style="background-color: var(--color-accent-muted); color: var(--color-accent-primary);">
                ${index + 1}
            </div>
            <div class="flex-1 min-w-0">
                <div class="text-xs font-medium truncate theme-page-text-primary" title="${BootFSSecurity.html(segment.fileName)}">${BootFSSecurity.html(segment.fileName)}</div>
                <div class="text-xs theme-page-text-muted">${formatFileSize(segment.size)} · ${segment.duration.toFixed(1)}s</div>
            </div>
            <div class="flex-shrink-0 cursor-grab theme-page-text-muted hover:theme-page-text-primary">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 8h16M4 16h16"></path>
                </svg>
            </div>
        </div>
    `).join('');
    
    listContainer.querySelectorAll('.segment-item').forEach(item => {
        item.addEventListener('click', (e) => {
            if (!e.target.closest('.cursor-grab')) {
                selectSegment(parseInt(item.dataset.index));
            }
        });
        
        item.addEventListener('dragstart', handleDragStart);
        item.addEventListener('dragover', handleDragOver);
        item.addEventListener('drop', handleDrop);
        item.addEventListener('dragend', handleDragEnd);
        item.addEventListener('dragenter', handleDragEnter);
        item.addEventListener('dragleave', handleDragLeave);
    });
}

function selectSegment(index) {
    selectedSegmentIndex = index;
    renderSegmentList(currentSegments);
    updateDeleteButton();
}

function updateDeleteButton() {
    const deleteBtn = document.getElementById('tsDeleteSegmentBtn');
    if (deleteBtn) {
        if (selectedSegmentIndex !== null) {
            deleteBtn.disabled = false;
            deleteBtn.classList.remove('opacity-50', 'cursor-not-allowed');
        } else {
            deleteBtn.disabled = true;
            deleteBtn.classList.add('opacity-50', 'cursor-not-allowed');
        }
    }
}

function handleDragStart(e) {
    draggedSegmentIndex = parseInt(e.target.dataset.index);
    e.target.style.opacity = '0.5';
    e.dataTransfer.effectAllowed = 'move';
}

function handleDragOver(e) {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
}

function handleDragEnter(e) {
    e.preventDefault();
    const item = e.target.closest('.segment-item');
    if (item) {
        item.style.borderTop = '2px solid var(--color-accent-primary)';
    }
}

function handleDragLeave(e) {
    const item = e.target.closest('.segment-item');
    if (item) {
        item.style.borderTop = '';
    }
}

function handleDrop(e) {
    e.preventDefault();
    const item = e.target.closest('.segment-item');
    if (item) {
        item.style.borderTop = '';
    }
    
    const targetIndex = parseInt(item?.dataset.index);
    
    if (draggedSegmentIndex !== null && targetIndex !== null && draggedSegmentIndex !== targetIndex) {
        const draggedItem = currentSegments[draggedSegmentIndex];
        currentSegments.splice(draggedSegmentIndex, 1);
        currentSegments.splice(targetIndex, 0, draggedItem);
        
        currentSegments = currentSegments.map((seg, idx) => ({ ...seg, index: idx }));
        
        renderSegmentList(currentSegments);
        showToast('切片顺序已更改，请点击"保存排序"生效', 'info');
    }
    
    draggedSegmentIndex = null;
}

function handleDragEnd(e) {
    e.target.style.opacity = '';
    document.querySelectorAll('.segment-item').forEach(item => {
        item.style.borderTop = '';
    });
}

async function saveSegmentOrder() {
    if (!currentVirtualFileUuid || currentSegments.length === 0) {
        showToast('没有切片需要保存', 'warning');
        return;
    }
    
    const segmentOrder = currentSegments.map(s => s.fileName);
    
    try {
        const response = await fetch(`/hls/${currentVirtualFileUuid}/segments/reorder`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(segmentOrder)
        });
        
        if (!response.ok) {
            throw new Error('保存失败');
        }
        
        showToast('切片顺序已保存', 'success');
        loadSegmentList();
    } catch (error) {
        console.error('Save order error:', error);
        showToast('保存失败: ' + error.message, 'error');
    }
}

async function deleteSelectedSegment() {
    if (selectedSegmentIndex === null || !currentSegments[selectedSegmentIndex]) {
        showToast('请先选择要删除的切片', 'warning');
        return;
    }
    
    const segment = currentSegments[selectedSegmentIndex];
    
    if (!confirm(`确定要删除切片 "${segment.fileName}" 吗？`)) {
        return;
    }
    
    try {
        const response = await fetch(`/hls/${currentVirtualFileUuid}/segments/${encodeURIComponent(segment.fileName)}`, {
            method: 'DELETE'
        });
        
        if (!response.ok) {
            throw new Error('删除失败');
        }
        
        showToast('切片已删除', 'success');
        selectedSegmentIndex = null;
        loadSegmentList();
    } catch (error) {
        console.error('Delete segment error:', error);
        showToast('删除失败: ' + error.message, 'error');
    }
}

function closeTsUploadModal() {
    document.getElementById('tsUploadModal').classList.add('hidden');
}

function handleTsFileSelect(e) {
    const files = Array.from(e.target.files);
    selectedTsFiles = files.filter(f => f.name.toLowerCase().endsWith('.ts'));
    
    const fileList = document.getElementById('tsFileList');
    fileList.innerHTML = '';
    
    selectedTsFiles.forEach((file, index) => {
        const item = document.createElement('div');
        item.className = 'flex items-center justify-between px-2 py-1 theme-rounded text-xs';
        item.style.backgroundColor = 'var(--color-bg-secondary)';
        item.innerHTML = `
            <span class="truncate theme-page-text-primary" title="${BootFSSecurity.html(file.name)}">${BootFSSecurity.html(file.name)}</span>
            <span class="theme-page-text-muted ml-2">${formatFileSize(file.size)}</span>
        `;
        fileList.appendChild(item);
    });
    
    document.getElementById('tsFileCount').textContent = `已选择 ${selectedTsFiles.length} 个文件`;
}

async function startTsUpload() {
    const fileName = document.getElementById('tsVideoFileName').value.trim() || 'virtual_video.mp4';
    
    if (selectedTsFiles.length === 0) {
        showToast('请先选择 TS 切片文件', 'warning');
        return;
    }
    
    const startBtn = document.getElementById('startTsUploadBtn');
    startBtn.disabled = true;
    startBtn.textContent = '上传中...';
    
    document.getElementById('tsUploadProgress').classList.remove('hidden');
    
    const totalBytes = selectedTsFiles.reduce((sum, f) => sum + f.size, 0);
    let totalTransferred = 0;
    let lastTime = Date.now();
    let lastLoaded = 0;
    
    const uploadSegment = (file, sequence) => {
        return new Promise((resolve, reject) => {
            const formData = new FormData();
            formData.append('segment', file);
            formData.append('sequence', sequence);
            formData.append('duration', '10.0');
            
            const xhr = new XMLHttpRequest();
            
            xhr.upload.addEventListener('progress', (event) => {
                if (event.lengthComputable) {
                    const currentTime = Date.now();
                    const timeDiff = (currentTime - lastTime) / 1000;
                    const loadedDiff = event.loaded - lastLoaded;
                    
                    let speed = 0;
                    if (timeDiff > 0) {
                        speed = loadedDiff / timeDiff;
                    }
                    
                    const overallTransferred = totalTransferred + event.loaded;
                    const overallPercent = Math.round((overallTransferred / totalBytes) * 100);
                    
                    updateTsProgressWithStats(overallPercent, overallTransferred, speed, `正在上传: ${file.name}`);
                    
                    lastLoaded = event.loaded;
                    lastTime = currentTime;
                }
            });
            
            xhr.addEventListener('load', () => {
                if (xhr.status >= 200 && xhr.status < 300) {
                    totalTransferred += file.size;
                    lastLoaded = 0;
                    resolve();
                } else {
                    try {
                        const errorData = JSON.parse(xhr.responseText);
                        reject(new Error(errorData.error || `上传切片 ${file.name} 失败`));
                    } catch (e) {
                        reject(new Error(`上传切片 ${file.name} 失败，状态码: ${xhr.status}`));
                    }
                }
            });
            
            xhr.addEventListener('error', () => {
                reject(new Error(`上传切片 ${file.name} 网络错误`));
            });
            
            xhr.open('POST', `/hls/${currentVirtualFileUuid}/segments`);
            xhr.send(formData);
        });
    };
    
    try {
        let startSequence = 0;
        
        if (tsUploadMode === 'edit' && currentVirtualFileUuid) {
            updateTsProgress(0, '正在获取现有切片信息...');
            
            const segmentInfoResponse = await fetch(`/hls/${currentVirtualFileUuid}/segments/info`);
            if (segmentInfoResponse.ok) {
                const segmentInfo = await segmentInfoResponse.json();
                startSequence = segmentInfo.segmentCount || 0;
            }
            
            updateTsProgress(5, `编辑模式: 从第 ${startSequence} 个切片开始追加`);
        } else {
            updateTsProgress(0, '正在创建虚拟文件...');
            
            const createResponse = await fetch('/virtual-file', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ fileName: fileName, fileType: 'video/mp4' })
            });
            
            if (!createResponse.ok) {
                throw new Error('创建虚拟文件失败');
            }
            
            const createData = await createResponse.json();
            currentVirtualFileUuid = createData.uuid;
            
            updateTsProgress(5, `虚拟文件已创建: ${currentVirtualFileUuid}`);
        }
        
        const sortedFiles = selectedTsFiles.sort((a, b) => {
            const numA = parseInt(a.name.match(/(\d+)/)?.[1] || '0');
            const numB = parseInt(b.name.match(/(\d+)/)?.[1] || '0');
            return numA - numB;
        });
        
        for (let i = 0; i < sortedFiles.length; i++) {
            const file = sortedFiles[i];
            const sequence = startSequence + i;
            
            await uploadSegment(file, sequence);
        }
        
        updateTsProgress(95, '正在完成播放列表...');
        
        const finalizeResponse = await fetch(`/hls/${currentVirtualFileUuid}/finalize`, {
            method: 'POST'
        });
        
        if (!finalizeResponse.ok) {
            throw new Error('完成播放列表失败');
        }
        
        updateTsProgress(100, '上传完成!');
        document.getElementById('tsUploadSpeed').textContent = '-- MB/s';
        
        if (tsUploadMode === 'edit') {
            loadSegmentList();
        }
        
        setTimeout(() => {
            showToast(`TS 切片上传成功！`, 'success');
            loadFileList();
        }, 1000);
        
    } catch (error) {
        console.error('TS upload error:', error);
        showToast('上传失败: ' + error.message, 'error');
        updateTsProgress(0, '上传失败: ' + error.message);
    } finally {
        startBtn.disabled = false;
        if (tsUploadMode === 'edit') {
            startBtn.textContent = '追加切片';
        } else {
            startBtn.textContent = '开始上传';
        }
    }
}

function updateTsProgress(percent, status) {
    document.getElementById('tsUploadProgressBar').style.width = `${percent}%`;
    document.getElementById('tsUploadPercent').textContent = `${percent}%`;
    document.getElementById('tsUploadStatus').textContent = status;
}

function updateTsProgressWithStats(percent, transferred, speed, status) {
    document.getElementById('tsUploadProgressBar').style.width = `${percent}%`;
    document.getElementById('tsUploadPercent').textContent = `${percent}%`;
    document.getElementById('tsUploadTransferred').textContent = formatFileSize(transferred);
    document.getElementById('tsUploadSpeed').textContent = speed > 0 ? formatSpeed(speed) : '-- MB/s';
    document.getElementById('tsUploadStatus').textContent = status;
}

// 点击视频卡片时动态加载视频
document.addEventListener('click', async (e) => {
    const videoContainer = e.target.closest('.video-card-container');
    if (!videoContainer || videoContainer.dataset.videoLoaded === 'true') return;
    
    // 阻止事件冒泡，避免触发卡片预览
    e.stopPropagation();
    
    const uuid = videoContainer.dataset.videoUuid;
    if (!uuid) return;
    
    // 标记为已加载，防止重复
    videoContainer.dataset.videoLoaded = 'true';
    
    // 隐藏缩略图和播放图标
    const thumbnail = videoContainer.querySelector('.video-thumbnail');
    const placeholder = videoContainer.querySelector('.video-placeholder');
    const playIcon = videoContainer.querySelector('.video-play-icon');
    
    if (thumbnail) thumbnail.style.display = 'none';
    if (placeholder) placeholder.style.display = 'none';
    if (playIcon) playIcon.style.display = 'none';
    
    // 创建并插入视频元素
    const video = document.createElement('video');
    video.src = `/p/${uuid}`;
    video.className = 'w-full h-full object-cover';
    video.muted = true;
    video.preload = 'metadata';
    video.onerror = () => {
        // 视频加载失败，显示占位符
        video.style.display = 'none';
        if (placeholder) placeholder.style.display = 'flex';
    };
    
    videoContainer.appendChild(video);
    
    // 视频加载完成后自动播放
    video.onloadeddata = () => {
        video.play().catch(() => {});
    };
    
    // 鼠标悬停时播放，移出时暂停
    videoContainer.addEventListener('mouseenter', () => {
        if (video.src) video.play().catch(() => {});
    });
    videoContainer.addEventListener('mouseleave', () => {
        video.pause();
        video.currentTime = 0;
    });
});

// Icon view: aligned small icons, marquee selection, and drag into a folder.
let iconPage = 0;
let iconHasMore = true;
let iconLoading = false;
let iconRecords = new Map();
let iconAnchorKey = null;
let iconPointer = null;
let iconMoveLock = false;
let iconThumbnailObserver;
const ICON_PAGE_SIZE = 200;

function initializeIconBrowser() {
    const view = document.getElementById('iconView');
    if (!view) return;
    view.addEventListener('dblclick', event => {
        const item = event.target.closest('.icon-item');
        if (item) openIconItem(item);
    });
    view.addEventListener('click', event => {
        const item = event.target.closest('.icon-item');
        if (item && (event.detail === 0 || event.pointerType === 'touch')) openIconItem(item);
    });
    view.addEventListener('keydown', event => {
        const item = event.target.closest('.icon-item');
        if (item && event.key === ' ') {
            event.preventDefault();
            if (!event.repeat) toggleIcon(item);
        }
    });
    view.addEventListener('pointerdown', onIconPointerDown);
    view.addEventListener('pointermove', onIconPointerMove);
    view.addEventListener('pointerup', onIconPointerUp);
    view.addEventListener('pointercancel', onIconPointerUp);
    const loadMore = document.getElementById('iconLoadMoreButton');
    if (loadMore) loadMore.addEventListener('click', () => loadIconView(false));
    const main = document.getElementById('mainView');
    if (main) {
        if ('IntersectionObserver' in window) {
            iconThumbnailObserver = new IntersectionObserver((entries, observer) => {
                for (const {target, isIntersecting} of entries) {
                    if (!isIntersecting) continue;
                    observer.unobserve(target);
                    target.src = target.dataset.src;
                    delete target.dataset.src;
                }
            }, {root: main, rootMargin: '320px 0px'});
        }
        main.addEventListener('scroll', () => {
            if (currentView !== 'icons' || iconLoading || !iconHasMore) return;
            if (main.scrollTop + main.clientHeight >= main.scrollHeight - 280) loadIconView(false);
        });
    }
}

function loadIconView(reset = false) {
    if (iconLoading && !reset) return;
    const requestVersion = reset ? ++folderRequestVersion : folderRequestVersion;
    const grid = document.getElementById('iconGrid');
    const empty = document.getElementById('iconEmpty');
    const loadMore = document.getElementById('iconLoadMore');
    const loadMoreButton = document.getElementById('iconLoadMoreButton');
    if (reset) {
        iconThumbnailObserver?.disconnect();
        iconPage = 0;
        iconHasMore = true;
        iconLoading = false;
        iconRecords = new Map();
        iconAnchorKey = null;
        previewMediaFiles = [];
        renderFolderBreadcrumbs();
        if (grid) grid.replaceChildren();
        if (empty) empty.classList.add('hidden');
        if (loadMore) loadMore.classList.add('hidden');
    }
    if (!iconHasMore || !grid) {
        iconLoading = false;
        return;
    }
    iconLoading = true;
    if (loadMoreButton) loadMoreButton.disabled = true;
    const offset = iconPage * ICON_PAGE_SIZE;
    const params = new URLSearchParams({
        pageNumber: offset,
        pageSize: ICON_PAGE_SIZE,
        search: searchQuery
    });
    fetch(fileListUrl(params))
        .then(response => {
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            return response.json().then(normalizeFolderRows);
        })
        .then(data => {
            if (requestVersion !== folderRequestVersion) return;
            const rows = data && Array.isArray(data.rows) ? data.rows : [];
            const fragment = document.createDocumentFragment();
            rows.forEach(file => {
                const item = createIconItem(file);
                iconRecords.set(item.dataset.key, file);
                if (isImage(file) || isVideo(file)) previewMediaFiles.push(file);
                applyNsfwBlurToItem(item);
                const cell = document.createElement('div');
                cell.className = 'icon-cell';
                cell.appendChild(item);
                fragment.appendChild(cell);
            });
            grid.appendChild(fragment);
            syncIconSelection();
            iconPage++;
            iconHasMore = iconPage * ICON_PAGE_SIZE < (data.total || 0) && rows.length > 0;
            if (empty) {
                empty.textContent = searchQuery ? '没有匹配的文件' : '此文件夹是空的';
                empty.classList.toggle('hidden', grid.childElementCount > 0);
            }
            if (loadMore) loadMore.classList.toggle('hidden', !iconHasMore);
            updateStats(data);
        })
        .catch(error => {
            if (requestVersion !== folderRequestVersion) return;
            console.error('Error loading icons:', error);
            showToast('加载文件失败', 'error');
            iconHasMore = false;
            if (loadMore) loadMore.classList.add('hidden');
        })
        .finally(() => {
            if (requestVersion !== folderRequestVersion) return;
            iconLoading = false;
            if (loadMoreButton) loadMoreButton.disabled = false;
        });
}

function createIconItem(file) {
    const item = document.createElement('button');
    item.type = 'button';
    const folder = !!file.folder;
    item.className = 'icon-item';
    item.dataset.kind = folder ? 'folder' : 'file';
    item.dataset.key = folder ? 'folder:' + file.folderPath : 'file:' + (file.uuid || file.objectKey || file.fileName);
    if (folder) item.dataset.folderPath = file.folderPath || '';
    else item.dataset.objectKey = file.objectKey || '';
    if (file.uuid) item.dataset.uuid = file.uuid;
    const movable = folder ? file.managed !== false : !file.originEndpoint && !!file.objectKey;
    item.dataset.managed = movable ? 'true' : 'false';
    item.dataset.tags = (file.tags || []).map(tag => {
        return tag.namespace && tag.namespace !== 'manual' ? `${tag.namespace}:${tag.name}` : tag.name;
    }).join('\n');
    item.title = file.fileName || '';

    const glyph = document.createElement('span');
    glyph.className = 'icon-glyph';
    const kind = isImage(file) || isAlbum(file) ? 'image' : isVideo(file) ? 'video' : isAudio(file) ? 'audio' : 'file';
    const thumbnail = (kind === 'image' || kind === 'video') && hasThumb(file)
        ? BootFSSecurity.url(getThumbUrl(file)) : '';
    if (folder) {
        glyph.appendChild(iconFolderGlyph());
    } else if (thumbnail) {
        const placeholder = iconFileGlyph(kind);
        const image = document.createElement('img');
        image.alt = '';
        image.draggable = false;
        image.decoding = 'async';
        image.className = 'icon-thumbnail-pending';
        image.addEventListener('load', () => {
            placeholder.remove();
            image.classList.remove('icon-thumbnail-pending');
            if (kind === 'video') glyph.appendChild(iconPlayBadge());
        }, {once: true});
        image.addEventListener('error', () => image.remove(), {once: true});
        glyph.append(placeholder, image);
        if (iconThumbnailObserver) {
            image.dataset.src = thumbnail;
            iconThumbnailObserver.observe(image);
        } else {
            image.loading = 'lazy';
            image.src = thumbnail;
        }
    } else {
        glyph.appendChild(iconFileGlyph(kind));
    }
    const name = document.createElement('span');
    name.className = 'icon-name';
    name.textContent = file.fileName || (folder ? '文件夹' : '未命名');
    item.append(glyph, name);
    return item;
}

function iconFolderGlyph() {
    const holder = document.createElement('div');
    holder.innerHTML = '<svg class="icon-folder-svg" viewBox="0 0 80 64" aria-hidden="true">'
        + '<path fill="#7CC4F8" d="M8 18c0-3.3 2.7-6 6-6h16.2c1.2 0 2.3.5 3.1 1.3L37 17h29c3.3 0 6 2.7 6 6v2H8v-7z"></path>'
        + '<path fill="#1E88E5" d="M4 30c0-2.2 1.8-4 4-4h64c2.2 0 4 1.8 4 4v22c0 3.3-2.7 6-6 6H10c-3.3 0-6-2.7-6-6V30z"></path>'
        + '<path fill="#4DA3F0" d="M4 30c0-2.2 1.8-4 4-4h64c2.2 0 4 1.8 4 4v7H4v-7z"></path></svg>';
    return holder.firstElementChild;
}

function iconFileGlyph(kind) {
    const paths = {
        image: '<rect x="4" y="4" width="16" height="16" rx="2"></rect><circle cx="8.5" cy="8.5" r="1.5"></circle><path d="M21 15l-5-5L5 21"></path>',
        video: '<rect x="3" y="5" width="12" height="14" rx="2"></rect><path d="M15 10l6-3v10l-6-3z"></path>',
        audio: '<path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle>',
        file: '<path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"></path><path d="M14 2v6h6"></path>'
    };
    const holder = document.createElement('div');
    holder.innerHTML = `<svg class="icon-file-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[kind] || paths.file}</svg>`;
    return holder.firstElementChild;
}

function iconPlayBadge() {
    const badge = document.createElement('span');
    badge.className = 'icon-play-badge';
    badge.innerHTML = '<svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M8 5v14l11-7z"></path></svg>';
    return badge;
}

function iconItems() {
    return Array.from(document.querySelectorAll('#iconGrid .icon-item'));
}

function addIconToSelection(item) {
    if (!item) return;
    if (item.dataset.kind === 'folder') selectedFolders.add(item.dataset.folderPath);
    else if (item.dataset.uuid) selectedFiles.add(item.dataset.uuid);
}

function removeIconFromSelection(item) {
    if (!item) return;
    if (item.dataset.kind === 'folder') selectedFolders.delete(item.dataset.folderPath);
    else selectedFiles.delete(item.dataset.uuid);
}

function selectOnlyIcon(item) {
    selectedFiles.clear();
    selectedFolders.clear();
    addIconToSelection(item);
    iconAnchorKey = item.dataset.key;
    updateSelectedToolbar();
}

function toggleIcon(item) {
    if (item.classList.contains('selected')) removeIconFromSelection(item);
    else addIconToSelection(item);
    iconAnchorKey = item.dataset.key;
    updateSelectedToolbar();
}

function selectIconRange(item) {
    const items = iconItems();
    let anchor = items.findIndex(entry => entry.dataset.key === iconAnchorKey);
    if (anchor < 0) anchor = 0;
    const current = items.indexOf(item);
    if (current < 0) return;
    const start = Math.min(anchor, current);
    const end = Math.max(anchor, current);
    selectedFiles.clear();
    selectedFolders.clear();
    for (let index = start; index <= end; index++) addIconToSelection(items[index]);
    updateSelectedToolbar();
}

function selectAllIcons() {
    selectedFiles.clear();
    selectedFolders.clear();
    iconItems().forEach(addIconToSelection);
    updateSelectedToolbar();
}

function syncIconSelection() {
    iconItems().forEach(item => {
        const selected = item.dataset.kind === 'folder'
            ? selectedFolders.has(item.dataset.folderPath)
            : selectedFiles.has(item.dataset.uuid);
        item.classList.toggle('selected', selected);
        item.setAttribute('aria-pressed', String(selected));
    });
}

function openIconItem(item) {
    const file = iconRecords.get(item.dataset.key);
    if (!file) return;
    if (file.folder) openFolder(file.folderPath);
    else if (isImage(file) || isVideo(file) || isAudio(file) || isAlbum(file)) previewFile(file);
    else if (file.uuid) showFileDetail(file.uuid);
}

function onIconPointerDown(event) {
    if (event.button !== 0 || event.pointerType === 'touch') return;
    const view = document.getElementById('iconView');
    const item = event.target.closest('.icon-item');
    if (!item && event.target.closest('button, a, input, textarea')) return;
    if (item) {
        item.focus({preventScroll: true});
        const additive = event.metaKey || event.ctrlKey;
        const wasSelected = item.classList.contains('selected');
        if (event.shiftKey) selectIconRange(item);
        else if (additive) toggleIcon(item);
        else if (!wasSelected) selectOnlyIcon(item);
        iconPointer = {
            mode: 'maybe-drag',
            x: event.clientX,
            y: event.clientY,
            collapseOnClick: !event.shiftKey && !additive && wasSelected,
            item
        };
    } else {
        iconPointer = {
            mode: 'maybe-marquee',
            x: event.clientX,
            y: event.clientY,
            additive: event.metaKey || event.ctrlKey,
            baseFiles: new Set(selectedFiles),
            baseFolders: new Set(selectedFolders)
        };
    }
    (item || view).setPointerCapture(event.pointerId);
    event.preventDefault();
}

function onIconPointerMove(event) {
    if (!iconPointer) return;
    if (!iconPointer.moved && Math.hypot(event.clientX - iconPointer.x, event.clientY - iconPointer.y) < 4) return;
    iconPointer.moved = true;
    autoScrollIconView(event.clientY);
    if (iconPointer.mode === 'maybe-drag' || iconPointer.mode === 'drag') {
        iconPointer.mode = 'drag';
        if (!iconPointer.visual) {
            iconItems().forEach(item => {
                if (item.classList.contains('selected')) item.classList.add('is-dragging');
            });
            iconPointer.visual = true;
        }
        updateIconDrag(event.clientX, event.clientY);
        return;
    }
    iconPointer.mode = 'marquee';
    placeIconMarquee(iconPointer.x, iconPointer.y, event.clientX, event.clientY);
    applyMarqueeSelection(clientSelectionRect(iconPointer.x, iconPointer.y, event.clientX, event.clientY), iconPointer);
}

function onIconPointerUp(event) {
    if (!iconPointer) return;
    const state = iconPointer;
    iconPointer = null;
    const marquee = document.getElementById('iconMarquee');
    if (marquee) marquee.classList.add('hidden');
    const target = state.mode === 'drag' && event.type === 'pointerup'
        ? dropTargetAt(event.clientX, event.clientY) : null;
    clearIconDragVisuals();
    if (event.type === 'pointercancel') return;
    if (state.mode === 'drag') {
        if (target && canDropInto(target.destination, target.managed)) moveSelectionTo(target.destination);
        else if (target && !target.managed) showToast('请在来源节点移动这些项目', 'warning');
        return;
    }
    if (!state.moved && state.mode === 'maybe-marquee' && !state.additive) clearSelection();
    if (!state.moved && state.collapseOnClick && state.item) selectOnlyIcon(state.item);
}

function autoScrollIconView(clientY) {
    const main = document.getElementById('mainView');
    if (!main) return;
    const rect = main.getBoundingClientRect();
    if (clientY < rect.top + 56) main.scrollTop -= 18;
    else if (clientY > rect.bottom - 56) main.scrollTop += 18;
}

function clientSelectionRect(x1, y1, x2, y2) {
    return {
        left: Math.min(x1, x2),
        top: Math.min(y1, y2),
        right: Math.max(x1, x2),
        bottom: Math.max(y1, y2)
    };
}

function placeIconMarquee(x1, y1, x2, y2) {
    const view = document.getElementById('iconView');
    const marquee = document.getElementById('iconMarquee');
    if (!view || !marquee) return;
    const bounds = view.getBoundingClientRect();
    const rect = clientSelectionRect(x1, y1, x2, y2);
    marquee.style.left = (rect.left - bounds.left) + 'px';
    marquee.style.top = (rect.top - bounds.top) + 'px';
    marquee.style.width = (rect.right - rect.left) + 'px';
    marquee.style.height = (rect.bottom - rect.top) + 'px';
    marquee.classList.remove('hidden');
}

function applyMarqueeSelection(rect, state) {
    selectedFiles.clear();
    selectedFolders.clear();
    if (state.additive) {
        state.baseFiles.forEach(uuid => selectedFiles.add(uuid));
        state.baseFolders.forEach(path => selectedFolders.add(path));
    }
    iconItems().forEach(item => {
        const hit = Array.from(item.querySelectorAll('.icon-glyph > :not(.icon-thumbnail-pending), .icon-name')).some(part => {
            const bounds = part.getBoundingClientRect();
            return rect.left < bounds.right && rect.right > bounds.left && rect.top < bounds.bottom && rect.bottom > bounds.top;
        });
        if (hit) addIconToSelection(item);
    });
    updateSelectedToolbar();
}

function updateIconDrag(x, y) {
    let badge = document.getElementById('iconDragBadge');
    if (!badge) {
        badge = document.createElement('div');
        badge.id = 'iconDragBadge';
        document.body.appendChild(badge);
    }
    badge.textContent = selectionCount() + ' 项';
    badge.style.left = (x + 14) + 'px';
    badge.style.top = (y + 16) + 'px';
    document.querySelectorAll('.drop-target').forEach(element => element.classList.remove('drop-target'));
    const target = dropTargetAt(x, y);
    if (target && canDropInto(target.destination, target.managed)) target.element.classList.add('drop-target');
}

function dropTargetAt(x, y) {
    const elements = document.elementsFromPoint ? document.elementsFromPoint(x, y) : [document.elementFromPoint(x, y)];
    for (const element of elements) {
        if (!element || !element.closest) continue;
        const item = element.closest('.icon-cell')?.firstElementChild;
        if (item && item.dataset.kind === 'folder' && !item.classList.contains('is-dragging')) {
            return {destination: item.dataset.folderPath || '', element: item, managed: item.dataset.managed !== 'false'};
        }
        const crumb = element.closest('#folderBreadcrumbs button');
        if (crumb) return {destination: crumb.dataset.folderPath || '', element: crumb, managed: true};
    }
    return null;
}

function canDropInto(destination, managed) {
    if (!managed || selectionCount() === 0) return false;
    if (destination === currentFolder) return false;
    for (const path of selectedFolders) {
        if (destination === path || destination.startsWith(path)) return false;
    }
    return true;
}

function clearIconDragVisuals() {
    document.querySelectorAll('.is-dragging, .drop-target').forEach(element => {
        element.classList.remove('is-dragging', 'drop-target');
    });
    const badge = document.getElementById('iconDragBadge');
    if (badge) badge.remove();
}

async function moveSelectionTo(destination) {
    if (iconMoveLock) return;
    const files = [];
    const folders = [];
    iconItems().forEach(item => {
        if (!item.classList.contains('selected') || item.dataset.managed === 'false') return;
        if (item.dataset.kind === 'folder') folders.push(item.dataset.folderPath);
        else if (item.dataset.objectKey) files.push(item.dataset.objectKey);
    });
    if (files.length + folders.length === 0) {
        showToast('这些项目不能移动', 'warning');
        return;
    }
    iconMoveLock = true;
    try {
        const response = await fetch('/api/folders/move', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({destination, files, folders})
        });
        if (!response.ok) {
            showToast(await moveErrorMessage(response), 'error');
            return;
        }
        const result = await response.json().catch(() => ({}));
        clearSelection();
        showToast(result.moved > 0 ? `已移动 ${result.moved} 项` : '已在此文件夹中', result.moved > 0 ? 'success' : 'info');
        loadFileList();
    } catch (error) {
        console.error('Move error:', error);
        showToast('移动失败', 'error');
    } finally {
        iconMoveLock = false;
    }
}

async function moveErrorMessage(response) {
    let text = '';
    try { text = await response.text(); } catch (error) { text = ''; }
    if (response.status === 409) {
        if (/itself/i.test(text)) return '不能把文件夹移到它自己或子文件夹里';
        if (/same name|already exists/i.test(text)) return '目标里已有同名文件或文件夹';
        if (/source node/i.test(text)) return '请在来源节点移动这些项目';
        if (/acknowledgement/i.test(text)) return '还有上传未完成，请稍后再移动';
        return '无法移动，请刷新后重试';
    }
    if (response.status === 404) return '要移动的文件或文件夹已经不存在';
    if (response.status === 400) return '不能移动这些项目';
    return '移动失败';
}
