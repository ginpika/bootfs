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
let selectedFiles = new Set();
let currentView = 'list'; // 'grid' or 'list' - default to list
let searchQuery = '';
let showOnlyIndexed = false;

// Grid view (waterfall) specific state
let gridPage = 0;
let gridHasMore = true;
let gridLoading = false;
const GRID_PAGE_SIZE = 10;
const GRID_TARGET_COUNT = 10;
const WATERFALL_COLUMNS = 6;
let columnHeights = new Array(WATERFALL_COLUMNS).fill(0);
let gridTotal = 0;

// Initialize dashboard
document.addEventListener('DOMContentLoaded', function() {
    initTheme();
    initializeEventListeners();
    loadFileList();
    initializeDragAndDrop();
});

// Initialize event listeners
function initializeEventListeners() {
    // View toggle
    document.getElementById('gridViewBtn').addEventListener('click', () => switchView('grid'));
    document.getElementById('listViewBtn').addEventListener('click', () => switchView('list'));
    
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
        if (currentView === 'list') {
            loadFileList();
        } else {
            loadGridView(true);
        }
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
                <div class="file-name">${file.fileName}</div>
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
    document.getElementById('showOnlyIndexedBtn').addEventListener('click', toggleOnlyIndexed);
    document.getElementById('hlsConvertBtn').addEventListener('click', convertToHLS);
    document.getElementById('publishToGalleryBtn').addEventListener('click', publishToGallery);
    document.getElementById('batchDownloadBtn').addEventListener('click', batchDownload);
    document.getElementById('batchDeleteBtn').addEventListener('click', showDeleteConfirm);
    document.getElementById('tsUploadBtn').addEventListener('click', showTsUploadModal);
    
    // Upload
    document.getElementById('uploadFileBtn').addEventListener('change', handleFileUpload);
    
    // Pagination
    document.getElementById('prevPage').addEventListener('click', () => changePage(-1));
    document.getElementById('nextPage').addEventListener('click', () => changePage(1));
    
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
    
    // Cancel selection
    document.getElementById('cancelSelection').addEventListener('click', clearSelection);
    
    // Keyboard shortcuts
    document.addEventListener('keydown', function(e) {
        // Delete: Delete selected
        if (e.key === 'Delete' && selectedFiles.size > 0) {
            e.preventDefault();
            showDeleteConfirm();
        }
        // Escape: Clear selection
        if (e.key === 'Escape') {
            clearSelection();
            closeImagePreview();
            closeDeleteModal();
        }
    });
}

// Load file list
function loadFileList() {
    if (currentView === 'list') {
        loadListView();
    } else {
        loadGridView(true); // reset = true
    }
}

// Load list view (paginated)
function loadListView() {
    const startTime = Date.now();
    const minLoadingTime = 800; // 最小加载时间 0.8s
    
    // 显示加载动画
    showLoadingOverlay();
    
    const params = new URLSearchParams({
        pageNumber: (currentPage - 1) * 10,
        pageSize: 10,
        search: searchQuery
    });
    
    console.log('Loading list view with params:', params.toString());
    
    fetch(`/queryPageOffset?${params}`)
        .then(response => {
            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }
            return response.json();
        })
        .then(data => {
            console.log('Received data:', data);
            
            // 确保至少显示 0.8s 的加载动画
            const elapsed = Date.now() - startTime;
            const remainingTime = Math.max(0, minLoadingTime - elapsed);
            
            return new Promise(resolve => {
                setTimeout(() => {
                    resolve(data);
                }, remainingTime);
            });
        })
        .then(data => {
            // 隐藏加载动画
            hideLoadingOverlay();
            
            // 渲染内容
            if (data && data.rows) {
                renderFileList(data);
                updateStats(data);
                updatePagination(data);
            } else {
                console.error('Invalid data format:', data);
            }
        })
        .catch(error => {
            console.error('Error loading files:', error);
            hideLoadingOverlay();
            if (error.message && !error.message.includes('Cannot read properties')) {
                showToast('加载文件列表失败', 'error');
            }
        });
}

// Load grid view (waterfall with load more button)
function loadGridView(reset = false) {
    if (gridLoading) return;
    
    if (reset) {
        gridPage = 0;
        gridHasMore = true;
        gridLoading = false;
        gridTotal = 0;
        columnHeights = new Array(WATERFALL_COLUMNS).fill(0);
        
        const container = document.getElementById('waterfallContainer');
        if (container) {
            container.innerHTML = '';
            for (let i = 0; i < WATERFALL_COLUMNS; i++) {
                const column = document.createElement('div');
                column.className = 'flex-1 min-w-0 flex flex-col gap-4';
                column.id = `waterfall-column-${i}`;
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
    if (needCount <= 0 || !gridHasMore) {
        finishLoading();
        return;
    }
    
    const params = new URLSearchParams({
        pageNumber: gridPage * GRID_PAGE_SIZE,
        pageSize: Math.min(needCount, GRID_PAGE_SIZE),
        search: searchQuery
    });
    
    console.log(`Loading grid: need ${needCount} more, offset ${gridPage * GRID_PAGE_SIZE}, limit ${Math.min(needCount, GRID_PAGE_SIZE)}`);
    
    try {
        const response = await fetch(`/queryPageOffset?${params}`);
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        const data = await response.json();
        
        if (data && data.rows) {
            gridTotal = data.total || gridTotal;
            
            const mediaFiles = data.rows.filter(file => isImage(file) || isVideo(file) || isAlbum(file));
            
            if (mediaFiles.length > 0) {
                await appendToWaterfallAsync(mediaFiles);
            }
            
            gridPage++;
            
            const fetchedCount = data.rows.length;
            const remainingNeed = needCount - mediaFiles.length;
            
            gridHasMore = (gridPage * GRID_PAGE_SIZE) < gridTotal && fetchedCount > 0;
            
            if (remainingNeed > 0 && gridHasMore) {
                console.log(`Got ${mediaFiles.length} media files, need ${remainingNeed} more, loading next batch...`);
                await loadMediaFilesRecursively(remainingNeed);
            } else {
                finishLoading();
            }
            
            updateStats(data);
        } else {
            gridHasMore = false;
            finishLoading();
        }
    } catch (error) {
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

async function appendToWaterfallAsync(files) {
    const columns = [];
    for (let i = 0; i < WATERFALL_COLUMNS; i++) {
        const column = document.getElementById(`waterfall-column-${i}`);
        if (column) {
            columns.push(column);
        }
    }
    
    if (columns.length === 0) {
        console.error('Waterfall columns not found');
        return;
    }
    
    function getShortestColumnIndex() {
        let minHeight = columnHeights[0];
        let minIndex = 0;
        for (let i = 1; i < columnHeights.length; i++) {
            if (columnHeights[i] < minHeight) {
                minHeight = columnHeights[i];
                minIndex = i;
            }
        }
        return minIndex;
    }
    
    for (let index = 0; index < files.length; index++) {
        const file = files[index];
        await new Promise((resolve) => {
            const targetColumnIndex = getShortestColumnIndex();
            const targetColumn = columns[targetColumnIndex];
            
            const cardWrapper = document.createElement('div');
            cardWrapper.innerHTML = createWaterfallCard(file);
            const card = cardWrapper.firstElementChild;
            
            if (card && targetColumn) {
                card.addEventListener('click', () => {
                    previewFile(file);
                });
                
                const deleteBtn = card.querySelector('.waterfall-delete-btn');
                if (deleteBtn) {
                    deleteBtn.addEventListener('click', (e) => {
                        e.stopPropagation();
                        selectedFiles.clear();
                        selectedFiles.add(file.uuid);
                        showDeleteConfirm();
                    });
                }
                
                card.dataset.initialized = 'true';
                card.classList.add('fade-in-card');
                card.style.animationDelay = `${index * 50}ms`;
                
                targetColumn.appendChild(card);
                
                const albumCover = card.querySelector('.album-cover');
                if (albumCover && isAlbum(file)) {
                    fetchAlbumPoster(file.uuid).then(posterUrl => {
                        if (posterUrl) {
                            albumCover.innerHTML = `
                                <img src="${posterUrl}" 
                                     alt="${file.fileName}" 
                                     class="w-full h-full object-cover"
                                     loading="lazy"
                                     onerror="this.onerror=null;this.parentElement.innerHTML='<div class=\\'w-full h-full flex items-center justify-center\\' style=\\'background-color: var(--color-bg-tertiary);\\'><svg class=\\'w-16 h-16\\' style=\\'color: var(--color-text-muted);\\' fill=\\'none\\' stroke=\\'currentColor\\' viewBox=\\'0 0 24 24\\'><path stroke-linecap=\\'round\\' stroke-linejoin=\\'round\\' stroke-width=\\'2\\' d=\\'M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z\\'></path></svg></div>';">
                                <span class="absolute top-2 left-2 px-2 py-1 text-white text-xs theme-rounded-md font-medium" style="background-color: var(--color-accent-primary);">相册</span>
                            `;
                        }
                    });
                }
                
                const img = card.querySelector('img');
                const video = card.querySelector('video');
                
                const updateHeight = () => {
                    const cardHeight = card.offsetHeight;
                    columnHeights[targetColumnIndex] += cardHeight;
                    resolve();
                };
                
                if (img) {
                    if (img.complete) {
                        updateHeight();
                    } else {
                        img.onload = updateHeight;
                        img.onerror = updateHeight;
                    }
                } else if (video) {
                    if (video.readyState >= 1) {
                        updateHeight();
                    } else {
                        video.onloadedmetadata = updateHeight;
                        video.onerror = updateHeight;
                    }
                } else {
                    updateHeight();
                }
            } else {
                resolve();
            }
        });
    }
}

// Render file list
function renderFileList(data) {
    const gridContainer = document.getElementById('gridView');
    const listContainer = document.getElementById('fileListTableBody');
    
    if (!data || !data.rows || data.rows.length === 0) {
        gridContainer.innerHTML = `
            <div class="col-span-full text-center py-12">
                <svg class="mx-auto h-12 w-12 theme-page-text-muted" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"></path>
                </svg>
                <p class="mt-2 theme-page-text-secondary">暂无文件</p>
            </div>
        `;
        listContainer.innerHTML = `
            <tr>
                <td colspan="5" class="px-6 py-12 text-center theme-page-text-secondary">
                    暂无文件
                </td>
            </tr>
        `;
        return;
    }
    
    // Render list view
    listContainer.innerHTML = data.rows.map(file => createListRow(file)).join('');
    
    // Add event listeners to list rows
    listContainer.querySelectorAll('.list-row').forEach(row => {
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
    });
}

// Create waterfall card HTML
function createWaterfallCard(file) {
    const isImageFile = isImage(file);
    const isVideoFile = isVideo(file);
    const isAlbumFile = isAlbum(file);
    
    return `
        <div class="waterfall-card theme-card theme-rounded-xl overflow-hidden hover:shadow-lg transition-all cursor-pointer group" data-uuid="${file.uuid}" data-type="${isAlbumFile ? 'album' : (isImageFile ? 'image' : 'video')}">
            <div class="relative w-full">
                ${isAlbumFile ? `
                    <div class="relative w-full aspect-[3/4] album-cover" style="background-color: var(--color-bg-tertiary);" data-album-uuid="${file.uuid}">
                        <div class="w-full h-full flex items-center justify-center" style="background-color: var(--color-bg-hover);">
                            <svg class="w-16 h-16" style="color: var(--color-text-muted);" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z"></path>
                            </svg>
                        </div>
                        <span class="absolute top-2 left-2 px-2 py-1 text-white text-xs rounded-md font-medium" style="background-color: var(--color-accent-primary);">相册</span>
                    </div>
                ` : isImageFile ? `
                    <img src="/p/${file.uuid}" 
                         alt="${file.fileName}" 
                         class="w-full h-auto object-cover"
                         loading="lazy"
                         onerror="this.onerror=null;this.src='data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%2248%22 height=%2248%22 viewBox=%220 0 24 24%22 fill=%22none%22 stroke=%22%2394a3b8%22 stroke-width=%222%22 stroke-linecap=%22round%22 stroke-linejoin=%22round%22%3E%3Crect x=%223%22 y=%223%22 width=%2218%22 height=%2218%22 rx=%222%22 ry=%222%22/%3E%3Ccircle cx=%228.5%22 cy=%228.5%22 r=%221.5%22/%3E%3Cpolyline points=%2221 15 16 10 5 21%22/%3E%3C/svg%3E';">
                ` : isVideoFile ? `
                    <div class="relative w-full aspect-video" style="background-color: var(--color-bg-tertiary);">
                        <video src="/p/${file.uuid}" 
                               class="w-full h-full object-cover"
                               muted
                               preload="metadata"
                               onloadeddata="this.currentTime=0.1;"
                               onerror="this.style.display='none';this.nextElementSibling.style.display='flex';">
                        </video>
                        <div class="hidden w-full h-full items-center justify-center" style="background-color: var(--color-bg-tertiary);">
                            <svg class="w-16 h-16 theme-page-text-muted" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z"></path>
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                            </svg>
                        </div>
                        <div class="absolute inset-0 flex items-center justify-center pointer-events-none">
                            <div class="w-12 h-12 theme-rounded-full flex items-center justify-center shadow-lg" style="background-color: var(--color-bg-secondary);">
                                <svg class="w-5 h-5" style="color: var(--color-text-primary);" fill="currentColor" viewBox="0 0 24 24">
                                    <path d="M8 5v14l11-7z"></path>
                                </svg>
                            </div>
                        </div>
                        ${file.hlsAvailable ? '<span class="absolute top-2 right-2 px-2 py-1 text-white text-xs theme-rounded-md font-medium" style="background-color: var(--color-success);">HLS</span>' : ''}
                    </div>
                ` : ''}
                
                <div class="absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity">
                    <button class="waterfall-delete-btn p-2 text-white rounded-full transition-colors shadow-lg" style="background-color: var(--color-danger);">
                        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"></path>
                        </svg>
                    </button>
                </div>
            </div>
            
            <div class="p-3">
                <p class="text-sm font-medium truncate" style="color: var(--color-text-primary);" title="${file.fileName}">${file.fileName}</p>
                <div class="flex items-center justify-between mt-1">
                    <span class="text-xs" style="color: var(--color-text-tertiary);">${isAlbumFile ? '相册' : (isImageFile ? '图片' : '视频')}</span>
                    ${file.isPublicAccess ? '<span class="text-xs" style="color: var(--color-success);">公开</span>' : ''}
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
                    <img src="/p/${file.uuid}" 
                         alt="${file.fileName}" 
                         class="w-full h-full object-cover zoom-image"
                         onerror="this.onerror=null;this.src='data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%2248%22 height=%2248%22 viewBox=%220 0 24 24%22 fill=%22none%22 stroke=%22%2394a3b8%22 stroke-width=%222%22 stroke-linecap=%22round%22 stroke-linejoin=%22round%22%3E%3Crect x=%223%22 y=%223%22 width=%2218%22 height=%2218%22 rx=%222%22 ry=%222%22/%3E%3Ccircle cx=%228.5%22 cy=%228.5%22 r=%221.5%22/%3E%3Cpolyline points=%2221 15 16 10 5 21%22/%3E%3C/svg%3E';">
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
                            <button class="preview-btn flex-1 px-2 py-1.5 theme-rounded-lg text-xs font-medium transition-colors flex items-center justify-center" style="background-color: var(--color-bg-secondary); color: var(--color-text-primary);">
                                <svg class="w-4 h-4 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"></path>
                                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"></path>
                                </svg>
                                预览
                            </button>
                        ` : ''}
                        <button class="download-btn flex-1 px-2 py-1.5 theme-rounded-lg text-xs font-medium transition-colors flex items-center justify-center" style="background-color: var(--color-bg-secondary); color: var(--color-text-primary);">
                            <svg class="w-4 h-4 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"></path>
                            </svg>
                            下载
                        </button>
                        <button class="delete-btn p-1.5 theme-rounded-lg transition-colors" style="background-color: var(--color-danger); color: white;">
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
                <div class="absolute top-2 right-2 flex flex-col space-y-1">
                    ${file.copyOf ? '<span class="badge px-2 py-1 text-white text-xs theme-rounded-md font-medium" style="background-color: var(--color-accent-primary);">副本</span>' : ''}
                    ${file.hlsAvailable ? '<span class="badge px-2 py-1 text-white text-xs theme-rounded-md font-medium" style="background-color: var(--color-success);">HLS</span>' : ''}
                    ${file.albumAvailable ? '<span class="badge px-2 py-1 text-white text-xs theme-rounded-md font-medium" style="background-color: #06b6d4;">相册</span>' : ''}
                    ${file.isPublicAccess ? '<span class="badge px-2 py-1 text-white text-xs theme-rounded-md font-medium" style="background-color: var(--color-bg-tertiary); color: var(--color-text-secondary);">画廊</span>' : ''}
                </div>
            </div>
            
            <!-- Info -->
            <div class="p-3">
                <h3 class="text-sm font-medium truncate theme-page-text-primary" title="${file.fileName}">${file.fileName}</h3>
                <p class="text-xs mt-1 truncate theme-page-text-muted">${formatFileSize(file.size || 0)}</p>
                ${!file.copyOf ? `
                    <div class="mt-2 flex space-x-1">
                        ${!file.hlsAvailable && isVideoFile ? `
                            <button class="hls-convert-btn flex-1 px-2 py-1 text-xs theme-rounded transition-colors" style="color: var(--color-success); background-color: rgba(16, 185, 129, 0.1);">
                                HLS 转码
                            </button>
                        ` : ''}
                        ${!file.isPublicAccess && isImageFile ? `
                            <button class="public-access-btn flex-1 px-2 py-1 text-xs theme-rounded transition-colors" style="color: #a855f7; background-color: rgba(168, 85, 247, 0.1);">
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
    const isSelected = selectedFiles.has(file.uuid);
    const isImageFile = isImage(file);
    const isVideoFile = isVideo(file);
    
    return `
        <tr class="list-row ${isSelected ? 'selected' : ''}" style="background-color: ${isSelected ? 'var(--color-accent-muted)' : 'var(--color-bg-secondary)'}; border-color: var(--color-border-primary);" data-uuid="${file.uuid}">
            <td class="w-12 px-4 py-4 whitespace-nowrap">
                <input type="checkbox" class="list-checkbox w-4 h-4 rounded theme-input" ${isSelected ? 'checked' : ''}>
            </td>
            <td class="px-6 py-4">
                <div class="flex items-center">
                    <div class="flex-shrink-0 h-10 w-10">
                        ${isImageFile ? `
                            <img class="h-10 w-10 theme-rounded-lg object-cover" 
                                 src="/p/${file.uuid}" 
                                 alt=""
                                 onerror="this.onerror=null;this.src='data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%2240%22 height=%2240%22 viewBox=%220 0 24 22%22 fill=%22none%22 stroke=%22%2394a3b8%22 stroke-width=%222%22 stroke-linecap=%22round%22 stroke-linejoin=%22round%22%3E%3Crect x=%223%22 y=%223%22 width=%2218%22 height=%2218%22 rx=%222%22 ry=%222%22/%3E%3Ccircle cx=%228.5%22 cy=%228.5%22 r=%221.5%22/%3E%3Cpolyline points=%2221 15 16 10 5 21%22/%3E%3C/svg%3E';">
                        ` : isVideoFile ? `
                            <div class="h-10 w-10 theme-rounded-lg flex items-center justify-center" style="background-color: var(--color-bg-tertiary);">
                                <svg class="w-5 h-5 theme-page-text-muted" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z"></path>
                                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                                </svg>
                            </div>
                        ` : `
                            <div class="h-10 w-10 theme-rounded-lg flex items-center justify-center" style="background-color: var(--color-bg-tertiary);">
                                <svg class="w-5 h-5" style="color: var(--color-text-muted);" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"></path>
                                </svg>
                            </div>
                        `}
                    </div>
                    <div class="ml-4">
                        <div class="text-sm font-medium" style="color: var(--color-text-primary);">${file.fileName}</div>
                        <div class="text-xs" style="color: var(--color-text-muted);">${file.uuid}</div>
                    </div>
                </div>
            </td>
            <td class="px-6 py-4 whitespace-nowrap">
                <div class="flex space-x-1">
                    ${file.copyOf ? '<span class="px-2 py-1 text-xs rounded-md" style="background-color: var(--color-accent-muted); color: var(--color-accent-primary);">副本</span>' : ''}
                    ${file.hlsAvailable ? '<span class="px-2 py-1 text-xs rounded-md" style="background-color: rgba(16, 185, 129, 0.1); color: var(--color-success);">HLS</span>' : ''}
                    ${file.albumAvailable ? '<span class="px-2 py-1 text-xs rounded-md" style="background-color: rgba(6, 182, 212, 0.1); color: #06b6d4;">相册</span>' : ''}
                    ${file.isPublicAccess ? '<span class="px-2 py-1 text-xs rounded-md" style="background-color: var(--color-bg-tertiary); color: var(--color-text-secondary);">画廊</span>' : ''}
                </div>
            </td>
            <td class="px-6 py-4 whitespace-nowrap text-sm" style="color: var(--color-text-secondary);">
                ${formatFileSize(file.size || 0)}
            </td>
            <td class="px-6 py-4 whitespace-nowrap text-sm font-medium">
                <div class="flex space-x-2">
                    ${isImageFile ? `
                        <button onclick="previewFileByUuid('${file.uuid}')" style="color: var(--color-accent-primary);">预览</button>
                    ` : ''}
                    <a href="/f/${file.uuid}" download style="color: var(--color-success);">下载</a>
                    <button class="list-delete-btn" style="color: var(--color-danger);">删除</button>
                </div>
            </td>
        </tr>
    `;
}

// Preview file
let videoPlayer = null;

function previewFile(file) {
    if (isAlbum(file)) {
        window.location.href = `/album/${file.uuid}`;
    } else if (isImage(file)) {
        document.getElementById('imagePreviewSrc').src = `/p/${file.uuid}`;
        document.getElementById('imagePreviewName').textContent = file.fileName;
        document.getElementById('imagePreviewModal').classList.remove('hidden');
    } else if (isVideo(file)) {
        const videoElement = document.getElementById('videoPreviewSrc');
        const source = file.hlsAvailable 
            ? `/hls/${file.uuid}/playlist.m3u8` 
            : `/p/${file.uuid}`;
        const type = file.hlsAvailable ? 'application/x-mpegURL' : 'video/mp4';
        
        // 初始化 Video.js 播放器
        if (!videoPlayer) {
            videoPlayer = videojs(videoElement, {
                fluid: true,
                responsive: true
            });
        }
        
        // 设置视频源
        videoPlayer.src({
            src: source,
            type: type
        });
        
        document.getElementById('videoPreviewName').textContent = file.fileName;
        document.getElementById('videoPreviewModal').classList.remove('hidden');
        videoPlayer.play();
    }
}

// Close image preview
function closeImagePreview() {
    document.getElementById('imagePreviewModal').classList.add('hidden');
}

// Close video preview
function closeVideoPreview() {
    if (videoPlayer) {
        videoPlayer.pause();
        videoPlayer.src('');
    }
    document.getElementById('videoPreviewModal').classList.add('hidden');
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
function updateSelectedToolbar() {
    const toolbar = document.getElementById('selectedToolbar');
    const countLabel = document.getElementById('selectedCount');
    
    if (selectedFiles.size > 0) {
        toolbar.classList.remove('hidden');
        countLabel.textContent = `已选择 ${selectedFiles.size} 项`;
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
function switchView(view) {
    currentView = view;
    const gridView = document.getElementById('gridView');
    const listView = document.getElementById('listView');
    const gridViewBtn = document.getElementById('gridViewBtn');
    const listViewBtn = document.getElementById('listViewBtn');
    const paginationRow = document.getElementById('paginationRow');
    const batchDownloadBtn = document.getElementById('batchDownloadBtn');
    const batchDeleteBtn = document.getElementById('batchDeleteBtn');
    
    selectedFiles.clear();
    const selectedToolbar = document.getElementById('selectedToolbar');
    if (selectedToolbar) {
        selectedToolbar.classList.add('hidden');
    }
    
    if (view === 'grid') {
        gridView.classList.remove('hidden');
        listView.classList.add('hidden');
        gridViewBtn.classList.add('active');
        listViewBtn.classList.remove('active');
        if (paginationRow) {
            paginationRow.classList.add('hidden');
        }
        if (batchDownloadBtn) {
            batchDownloadBtn.classList.add('hidden');
        }
        if (batchDeleteBtn) {
            batchDeleteBtn.classList.add('hidden');
        }
        loadGridView(true);
    } else {
        gridView.classList.add('hidden');
        listView.classList.remove('hidden');
        listViewBtn.classList.add('active');
        gridViewBtn.classList.remove('active');
        if (paginationRow) {
            paginationRow.classList.remove('hidden');
        }
        if (batchDownloadBtn) {
            batchDownloadBtn.classList.remove('hidden');
        }
        if (batchDeleteBtn) {
            batchDeleteBtn.classList.remove('hidden');
        }
        loadFileList();
    }
}

// Change page (for list view only)
function changePage(delta) {
    const newPage = currentPage + delta;
    if (newPage >= 1 && newPage <= totalPages) {
        currentPage = newPage;
        loadListView(); // 直接调用列表视图加载，不使用 loadFileList()
    }
}

// Update pagination
function updatePagination(data) {
    if (data) {
        const total = data.total || 0;
        const pageSize = data.pageSize || 10;
        totalPages = Math.ceil(total / pageSize) || 1;
        
        const currentPageEl = document.getElementById('currentPage');
        const totalPagesEl = document.getElementById('totalPages');
        const prevPageBtn = document.getElementById('prevPage');
        const nextPageBtn = document.getElementById('nextPage');
        
        if (currentPageEl) currentPageEl.textContent = currentPage;
        if (totalPagesEl) totalPagesEl.textContent = totalPages;
        if (prevPageBtn) prevPageBtn.disabled = currentPage <= 1;
        if (nextPageBtn) nextPageBtn.disabled = currentPage >= totalPages;
        
        console.log('Pagination updated:', { currentPage, totalPages, total, pageSize });
    }
}

// Update statistics
function updateStats(data) {
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
            
            xhr.open('PUT', '/f');
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
function showDeleteConfirm() {
    if (selectedFiles.size === 0) {
        showToast('请先选择要删除的文件', 'warning');
        return;
    }
    document.getElementById('deleteConfirmModal').classList.remove('hidden');
}

function closeDeleteModal() {
    document.getElementById('deleteConfirmModal').classList.add('hidden');
}

function confirmDelete() {
    const uuids = Array.from(selectedFiles);
    
    fetch('/removeFileBatch', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(uuids)
    })
    .then(response => {
        closeDeleteModal();
        clearSelection();
        showToast('删除成功', 'success');
        loadFileList();
    })
    .catch(error => {
        console.error('Delete error:', error);
        showToast('删除失败', 'error');
        closeDeleteModal();
    });
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
    fetch(`/hls/convert?uuid=${uuid}`)
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

// Toggle only indexed
function toggleOnlyIndexed() {
    showOnlyIndexed = !showOnlyIndexed;
    const btn = document.getElementById('showOnlyIndexedBtn');
    
    if (showOnlyIndexed) {
        btn.style.backgroundColor = 'var(--color-accent-muted)';
        btn.style.color = 'var(--color-accent-primary)';
        searchQuery = 'meta:index';
    } else {
        btn.style.backgroundColor = 'var(--color-bg-tertiary)';
        btn.style.color = 'var(--color-text-secondary)';
        searchQuery = '';
    }
    
    document.getElementById('searchInput').value = searchQuery;
    currentPage = 1;
    gridPage = 0; // 重置网格页码
    
    // 根据当前视图调用对应的加载函数
    if (currentView === 'list') {
        loadListView();
    } else {
        loadGridView(true);
    }
}

// Refresh file list
function refreshFileList() {
    // 根据当前视图调用对应的加载函数
    if (currentView === 'list') {
        loadListView();
    } else {
        loadGridView(true);
    }
    showToast('已刷新', 'success');
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
    toast.className = `fixed bottom-4 right-4 px-6 py-3 rounded-lg shadow-lg transform transition-all duration-300 z-50 ${colors[type]}`;
    toast.classList.remove('hidden');
    
    setTimeout(() => {
        toast.classList.add('translate-y-2', 'opacity-0');
        setTimeout(() => {
            toast.classList.add('hidden');
            toast.classList.remove('translate-y-2', 'opacity-0');
        }, 300);
    }, 3000);
}

// Utility functions
function isImage(file) {
    if (!file || !file.fileName) return false;
    const imageExtensions = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg', 'ico', 'tiff', 'tif'];
    const fileName = file.fileName.toLowerCase();
    return imageExtensions.some(ext => fileName.endsWith('.' + ext) || fileName.endsWith(ext));
}

function isVideo(file) {
    if (!file || !file.fileName) return false;
    const videoExtensions = ['mp4', 'webm', 'avi', 'mov', 'mkv', 'flv', 'wmv', 'm4v'];
    const fileName = file.fileName.toLowerCase();
    return videoExtensions.some(ext => fileName.endsWith('.' + ext) || fileName.endsWith(ext));
}

function isAlbum(file) {
    return file && file.albumAvailable === '1';
}

async function fetchAlbumPoster(uuid) {
    try {
        const response = await fetch(`${MEILISEARCH_URL}/indexes/full-text/documents/${uuid}`, {
            headers: {
                'Authorization': `Bearer ${MEILISEARCH_TOKEN}`
            }
        });
        if (response.ok) {
            const data = await response.json();
            return data.poster || null;
        }
    } catch (e) {
        console.error('Failed to fetch album poster:', e);
    }
    return null;
}

function formatFileSize(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i];
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
    
    ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
        dropZone.addEventListener(eventName, preventDefaults, false);
    });
    
    function preventDefaults(e) {
        e.preventDefault();
        e.stopPropagation();
    }
    
    dropZone.addEventListener('dragenter', () => {
        dropZone.classList.add('drag-active');
    }, false);
    
    dropZone.addEventListener('dragleave', (e) => {
        if (e.relatedTarget === null) {
            dropZone.classList.remove('drag-active');
        }
    }, false);
    
    dropZone.addEventListener('drop', (e) => {
        dropZone.classList.remove('drag-active');
        const files = e.dataTransfer.files;
        if (files.length > 0) {
            document.getElementById('uploadFileBtn').files = files;
            handleFileUpload({ target: { files } });
        }
    }, false);
}

// Preview file by UUID (for list view)
function previewFileByUuid(uuid) {
    fetch(`/queryPageOffset?pageNumber=0&pageSize=100`)
    .then(response => response.json())
    .then(data => {
        const file = data.rows.find(f => f.uuid === uuid);
        if (file) {
            previewFile(file);
        }
    })
    .catch(error => {
        console.error('Error:', error);
    });
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
        startBtn.style.backgroundColor = 'var(--color-success)';
        
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
        startBtn.style.backgroundColor = 'var(--color-warning)';
        
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
             data-filename="${segment.fileName}">
            <div class="flex-shrink-0 w-6 h-6 flex items-center justify-center theme-rounded text-xs font-bold"
                 style="background-color: var(--color-accent-muted); color: var(--color-accent-primary);">
                ${index + 1}
            </div>
            <div class="flex-1 min-w-0">
                <div class="text-xs font-medium truncate theme-page-text-primary" title="${segment.fileName}">${segment.fileName}</div>
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
            <span class="truncate theme-page-text-primary" title="${file.name}">${file.name}</span>
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
