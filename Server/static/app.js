/**
 * ❤️ 心率监测器 - 前端逻辑
 * WebSocket 实时通信 + Chart.js 图表 + UI 状态管理
 */

// ═══════════════════════════════════════════════════════
// 全局状态
// ═══════════════════════════════════════════════════════
let ws = null;
let chart = null;
let historyChart = null;
let currentTab = 'live';
let isConnected = false;
let isScanning = false;
let chartData = [];
const MAX_CHART_POINTS = 60;

// DOM 元素缓存
const DOM = {
    hrValue: () => document.getElementById('hrValue'),
    hrLabel: () => document.getElementById('hrLabel'),
    heartSvg: () => document.getElementById('heartSvg'),
    pulseRing: () => document.getElementById('pulseRing'),
    heartContainer: () => document.getElementById('heartContainer'),
    connectionBadge: () => document.getElementById('connectionBadge'),
    statMin: () => document.getElementById('statMin'),
    statMax: () => document.getElementById('statMax'),
    statAvg: () => document.getElementById('statAvg'),
    statCount: () => document.getElementById('statCount'),
    toastContainer: () => document.getElementById('toastContainer'),
    chartTimeLabel: () => document.getElementById('chartTimeLabel'),
    hrZones: () => document.getElementById('hrZones'),
    manualIpAddr: () => document.getElementById('manualIpAddr'),
};


// ═══════════════════════════════════════════════════════
// WebSocket 连接
// ═══════════════════════════════════════════════════════

function connectWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    let wsUrl = `${protocol}//${window.location.host}/ws`;

    // 如果当前网页 URL 带有 token，则传递给 WebSocket 连接以通过认证
    const urlParams = new URLSearchParams(window.location.search);
    const token = urlParams.get('token');
    if (token) {
        wsUrl += `?token=${encodeURIComponent(token)}`;
    }

    ws = new WebSocket(wsUrl);

    ws.onopen = () => {
        console.log('WebSocket 已连接');
    };

    ws.onmessage = (event) => {
        try {
            const data = JSON.parse(event.data);
            handleMessage(data);
        } catch (e) {
            console.error('消息解析错误:', e);
        }
    };

    ws.onclose = () => {
        console.log('WebSocket 已断开，3秒后重连...');
        setTimeout(connectWebSocket, 3000);
    };

    ws.onerror = (err) => {
        console.error('WebSocket 错误:', err);
    };
}


// ═══════════════════════════════════════════════════════
// 消息处理
// ═══════════════════════════════════════════════════════

function handleMessage(data) {
    switch (data.type) {
        case 'init':
            handleInit(data);
            break;
        case 'status':
            handleStatus(data);
            break;
        case 'heartrate':
            handleHeartRate(data);
            break;
        case 'error':
            handleError(data);
            break;
    }
}

function handleInit(data) {
    // 异步初始化手机连接地址 (支持外网域名与 Token 自动附带)
    initPhoneConnectionUrl();

    if (data.connected) {
        setConnectionState('connected', data.device_name);
    }

    // 恢复历史数据
    if (data.history && data.history.length > 0) {
        data.history.forEach(h => {
            addChartPoint(h.hr, h.time);
        });
        const last = data.history[data.history.length - 1];
        updateHRDisplay(last.hr);
    }
}

function handleStatus(data) {
    switch (data.status) {
        case 'connecting':
            setConnectionState('connecting', data.message);
            showToast(data.message, 'info');
            break;
        case 'connected':
            setConnectionState('connected', data.device_name);
            showToast(`✅ ${data.message}`, 'success');
            // 清空图表数据，准备接收新数据
            chartData = [];
            updateChart();
            break;
        case 'disconnected':
            setConnectionState('disconnected');
            showToast(data.message, 'info');
            resetHRDisplay();
            break;
    }
}

function handleHeartRate(data) {
    updateHRDisplay(data.hr);
    addChartPoint(data.hr, data.time);
    updateChart();
    updateStats(data.stats);
    updateZones(data.hr);
    checkHeartRateAlert(data.hr);
    if (data.hrv) {
        updateHRVDisplay(data.hrv);
    }
}

function handleError(data) {
    showToast(data.message, 'error', 8000);
}


// ═══════════════════════════════════════════════════════
// UI 更新函数
// ═══════════════════════════════════════════════════════

function setConnectionState(state, label = '') {
    const badge = DOM.connectionBadge();

    badge.className = 'connection-badge';
    
    switch (state) {
        case 'connected':
            badge.classList.add('connected');
            badge.querySelector('.badge-text').textContent = `已连接: ${label}`;
            isConnected = true;
            break;
        case 'connecting':
            badge.classList.add('connecting');
            badge.querySelector('.badge-text').textContent = '连接中...';
            break;
        case 'disconnected':
        default:
            badge.querySelector('.badge-text').textContent = '未连接';
            isConnected = false;
            break;
    }
}

function updateHRDisplay(hr) {
    const hrEl = DOM.hrValue();
    const labelEl = DOM.hrLabel();
    const heartSvg = DOM.heartSvg();
    const pulseRing = DOM.pulseRing();

    hrEl.textContent = hr;
    hrEl.classList.remove('no-data');

    // 心率描述
    let desc = '';
    if (hr < 60) desc = '心率偏低';
    else if (hr < 90) desc = '正常静息心率';
    else if (hr < 120) desc = '轻度活动';
    else if (hr < 150) desc = '中高强度运动';
    else desc = '高强度运动';
    labelEl.textContent = desc;

    // 根据心率设置跳动速度
    const beatDuration = (60 / hr).toFixed(3);
    heartSvg.style.setProperty('--beat-duration', `${beatDuration}s`);
    pulseRing.style.setProperty('--beat-duration', `${beatDuration}s`);
    heartSvg.classList.add('beating');
    pulseRing.classList.add('active');
}

function resetHRDisplay() {
    const hrEl = DOM.hrValue();
    const labelEl = DOM.hrLabel();
    const heartSvg = DOM.heartSvg();
    const pulseRing = DOM.pulseRing();

    hrEl.textContent = '--';
    hrEl.classList.add('no-data');
    labelEl.textContent = '等待连接...';
    heartSvg.classList.remove('beating');
    pulseRing.classList.remove('active');
    
    stopAlertWarning();

    // 重置统计
    DOM.statMin().textContent = '--';
    DOM.statMax().textContent = '--';
    DOM.statAvg().textContent = '--';
    DOM.statCount().textContent = '0';

    // 重置区间
    document.querySelectorAll('.zone').forEach(z => z.classList.remove('active'));

    // 重置 HRV 评估显示
    updateHRVDisplay({
        hrv_rmssd: 0,
        stress_index: 0,
        fatigue_level: 0,
        fatigue_desc: '等待数据...',
        is_real_hrv: false,
        is_ready: false
    });
}

function updateHRVDisplay(hrv) {
    if (!hrv) return;

    const rmssdValEl = document.getElementById('hrvRmssdValue');
    const rmssdDescEl = document.getElementById('hrvRmssdDesc');
    const stressValEl = document.getElementById('hrvStressValue');
    const stressProgEl = document.getElementById('hrvStressProgress');
    const fatigueValEl = document.getElementById('hrvFatigueValue');
    const fatigueProgEl = document.getElementById('hrvFatigueProgress');
    const sourceBadgeEl = document.getElementById('hrvSourceBadge');

    const hasRmssd = hrv.hrv_rmssd !== undefined && hrv.hrv_rmssd !== null;
    const hasStress = hrv.stress_index !== undefined && hrv.stress_index !== null;
    const hasFatigue = hrv.fatigue_level !== undefined && hrv.fatigue_level !== null;

    if (rmssdValEl) {
        rmssdValEl.textContent = (hasRmssd && hrv.is_ready) ? hrv.hrv_rmssd : '--';
    }
    
    if (rmssdDescEl) {
        if (hrv.is_ready && hasRmssd) {
            let desc = '';
            if (hrv.hrv_rmssd < 25) desc = '自主神经活性较低 (疲劳/紧张)';
            else if (hrv.hrv_rmssd < 50) desc = '自主神经平衡适中';
            else desc = '自主神经活性优异 (恢复良好)';
            rmssdDescEl.textContent = desc;
        } else {
            rmssdDescEl.textContent = '需至少5次连续跳动分析';
        }
    }

    if (stressValEl) {
        stressValEl.textContent = (hasStress && hrv.is_ready) ? hrv.stress_index : '--';
    }
    if (stressProgEl) {
        stressProgEl.style.width = (hasStress && hrv.is_ready) ? `${hrv.stress_index}%` : '0%';
    }

    if (fatigueValEl) {
        fatigueValEl.textContent = (hasFatigue && hrv.is_ready) ? hrv.fatigue_level : '--';
    }
    if (fatigueProgEl) {
        fatigueProgEl.style.width = (hasFatigue && hrv.is_ready) ? `${hrv.fatigue_level}%` : '0%';
    }

    if (sourceBadgeEl) {
        sourceBadgeEl.className = 'hrv-source-badge';
        if (hrv.is_ready) {
            if (hrv.is_real_hrv) {
                sourceBadgeEl.classList.add('badge-real');
                sourceBadgeEl.textContent = '⌚ 硬件级真实数据';
            } else {
                sourceBadgeEl.classList.add('badge-simulated');
                sourceBadgeEl.textContent = '🌐 网络模拟估算';
            }
        } else {
            sourceBadgeEl.textContent = '等待分析...';
        }
    }
}

function updateStats(stats) {
    if (!stats) return;
    DOM.statMin().textContent = stats.min;
    DOM.statMax().textContent = stats.max;
    DOM.statAvg().textContent = stats.avg;
    DOM.statCount().textContent = stats.count;
}

function updateZones(hr) {
    const zones = document.querySelectorAll('.zone');
    zones.forEach(z => z.classList.remove('active'));

    let activeZone;
    if (hr < 60) activeZone = 'rest';
    else if (hr < 90) activeZone = 'light';
    else if (hr < 120) activeZone = 'moderate';
    else if (hr < 150) activeZone = 'vigorous';
    else activeZone = 'peak';

    const el = document.querySelector(`.zone-${activeZone}`);
    if (el) el.classList.add('active');
}


// ═══════════════════════════════════════════════════════
// Chart.js 图表
// ═══════════════════════════════════════════════════════

function initChart() {
    const canvas = document.getElementById('hrChart');
    if (!canvas) return;

    const ctx = canvas.getContext('2d');

    // 创建渐变填充
    const gradient = ctx.createLinearGradient(0, 0, 0, 200);
    gradient.addColorStop(0, 'rgba(255, 45, 85, 0.25)');
    gradient.addColorStop(0.5, 'rgba(255, 45, 85, 0.08)');
    gradient.addColorStop(1, 'rgba(255, 45, 85, 0)');

    chart = new Chart(ctx, {
        type: 'line',
        data: {
            labels: [],
            datasets: [{
                label: '心率 (BPM)',
                data: [],
                borderColor: '#ff2d55',
                backgroundColor: gradient,
                fill: true,
                tension: 0.35,
                borderWidth: 2.5,
                pointRadius: 0,
                pointHoverRadius: 5,
                pointHoverBackgroundColor: '#ff2d55',
                pointHoverBorderColor: '#fff',
                pointHoverBorderWidth: 2,
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: {
                duration: 250,
                easing: 'easeOutQuart',
            },
            plugins: {
                legend: { display: false },
                tooltip: {
                    enabled: true,
                    backgroundColor: 'rgba(10, 10, 30, 0.9)',
                    titleColor: '#8888aa',
                    bodyColor: '#ff6b8a',
                    bodyFont: { size: 14, weight: '700' },
                    padding: 12,
                    cornerRadius: 10,
                    displayColors: false,
                    callbacks: {
                        title: (items) => items[0]?.label || '',
                        label: (item) => `${item.parsed.y} BPM`,
                    }
                }
            },
            scales: {
                x: {
                    display: true,
                    grid: {
                        color: 'rgba(255, 255, 255, 0.03)',
                        drawBorder: false,
                    },
                    ticks: {
                        color: '#44446a',
                        font: { size: 10 },
                        maxTicksLimit: 8,
                        maxRotation: 0,
                    },
                    border: { display: false },
                },
                y: {
                    display: true,
                    suggestedMin: 50,
                    suggestedMax: 150,
                    grid: {
                        color: 'rgba(255, 255, 255, 0.03)',
                        drawBorder: false,
                    },
                    ticks: {
                        color: '#44446a',
                        font: { size: 10 },
                        stepSize: 20,
                        padding: 8,
                    },
                    border: { display: false },
                }
            },
            interaction: {
                intersect: false,
                mode: 'index',
            },
        }
    });
}

function addChartPoint(hr, timestamp) {
    const time = new Date(timestamp * 1000);
    const label = time.toLocaleTimeString('zh-CN', {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
    });

    chartData.push({ label, hr });

    if (chartData.length > MAX_CHART_POINTS) {
        chartData = chartData.slice(-MAX_CHART_POINTS);
    }
}

function updateChart() {
    if (!chart) return;

    chart.data.labels = chartData.map(d => d.label);
    chart.data.datasets[0].data = chartData.map(d => d.hr);
    chart.update('none'); // 无动画更新以保持流畅

    const timeLabel = DOM.chartTimeLabel();
    if (chartData.length > 1) {
        timeLabel.textContent = `${chartData[0].label} - ${chartData[chartData.length - 1].label}`;
    }
}


// ═══════════════════════════════════════════════════════
// 用户交互
// ═══════════════════════════════════════════════════════

function startScan() {
    if (isScanning) return;
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ action: 'scan' }));
    } else {
        showToast('正在重新连接服务器...', 'error');
    }
}

function connectDevice(address, name) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
            action: 'connect',
            address: address,
            name: name,
        }));
    }
}

function doDisconnect() {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ action: 'disconnect' }));
    }
}


// ═══════════════════════════════════════════════════════
// Toast 通知
// ═══════════════════════════════════════════════════════

function showToast(message, type = 'info', duration = 4000) {
    const container = DOM.toastContainer();
    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    toast.textContent = message;
    container.appendChild(toast);

    setTimeout(() => {
        toast.classList.add('hiding');
        setTimeout(() => toast.remove(), 300);
    }, duration);
}


// ═══════════════════════════════════════════════════════
// 工具函数
// ═══════════════════════════════════════════════════════

function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}


// ═══════════════════════════════════════════════════════
// 主题切换 (亮黑模式)
// ═══════════════════════════════════════════════════════

function toggleTheme() {
    const isLight = document.body.classList.toggle('light-theme');
    localStorage.setItem('theme', isLight ? 'light' : 'dark');
    updateChartTheme(isLight);
}

function updateChartTheme(isLight) {
    const gridColor = isLight ? 'rgba(0, 0, 0, 0.04)' : 'rgba(255, 255, 255, 0.03)';
    const tickColor = isLight ? '#8888aa' : '#44446a';
    
    if (chart) {
        chart.options.scales.x.grid.color = gridColor;
        chart.options.scales.y.grid.color = gridColor;
        chart.options.scales.x.ticks.color = tickColor;
        chart.options.scales.y.ticks.color = tickColor;
        chart.update();
    }
    
    if (historyChart) {
        historyChart.options.scales.x.grid.color = gridColor;
        historyChart.options.scales.y.grid.color = gridColor;
        historyChart.options.scales.x.ticks.color = tickColor;
        historyChart.options.scales.y.ticks.color = tickColor;
        historyChart.update();
    }
}


// ═══════════════════════════════════════════════════════
// 初始化
// ═══════════════════════════════════════════════════════

document.addEventListener('DOMContentLoaded', () => {
    // 检查是否为 OBS 推流模式
    const urlParams = new URLSearchParams(window.location.search);
    const isObsMode = urlParams.get('obs') === 'true';
    if (isObsMode) {
        document.body.classList.add('obs-mode');
    }

    const savedTheme = localStorage.getItem('theme');
    const isLight = savedTheme === 'light';
    if (isLight) {
        document.body.classList.add('light-theme');
    }

    // OBS 模式下不需要初始化 Chart，节省性能
    if (!isObsMode) {
        initChart();
        updateChartTheme(isLight);
    }
    
    connectWebSocket();
    
    // 初始化安全警报设置
    loadAlertSettings();
    
    const enableToggle = document.getElementById('alertEnableToggle');
    if (enableToggle) {
        enableToggle.addEventListener('change', (e) => {
            alertState.isEnabled = e.target.checked;
            saveAlertSettings();
            if (!alertState.isEnabled) {
                stopAlertWarning();
            }
        });
    }
    
    const soundToggle = document.getElementById('alertSoundToggle');
    if (soundToggle) {
        soundToggle.addEventListener('change', (e) => {
            alertState.isSoundEnabled = e.target.checked;
            saveAlertSettings();
        });
    }
    
    const flashToggle = document.getElementById('alertFlashToggle');
    if (flashToggle) {
        flashToggle.addEventListener('change', (e) => {
            alertState.isFlashEnabled = e.target.checked;
            saveAlertSettings();
            if (!alertState.isFlashEnabled) {
                document.body.classList.remove('alert-flashing');
            }
        });
    }
    
    // 初始化 OBS 复制链接
    initObsUrl();
    
    // 初始化手机手动连接地址
    initPhoneConnectionUrl();
    
    // 初始化预警推送切换监听器
    initPushToggleListeners();
});

function initPushToggleListeners() {
    const pushplusToggle = document.getElementById('pushplusEnableToggle');
    const tgToggle = document.getElementById('tgEnableToggle');
    const dingtalkToggle = document.getElementById('dingtalkEnableToggle');
    const feishuToggle = document.getElementById('feishuEnableToggle');
    
    if (pushplusToggle) {
        pushplusToggle.addEventListener('change', (e) => {
            document.getElementById('pushplusTokenRow').style.display = e.target.checked ? 'flex' : 'none';
        });
    }
    if (tgToggle) {
        tgToggle.addEventListener('change', (e) => {
            document.getElementById('tgConfigRow').style.display = e.target.checked ? 'flex' : 'none';
        });
    }
    if (dingtalkToggle) {
        dingtalkToggle.addEventListener('change', (e) => {
            document.getElementById('dingtalkTokenRow').style.display = e.target.checked ? 'flex' : 'none';
        });
    }
    if (feishuToggle) {
        feishuToggle.addEventListener('change', (e) => {
            document.getElementById('feishuTokenRow').style.display = e.target.checked ? 'flex' : 'none';
        });
    }
}

// ═══════════════════════════════════════════════════════
// 选项卡切换与历史查询
// ═══════════════════════════════════════════════════════

function switchTab(tab) {
    currentTab = tab;
    
    // 切换按钮激活状态
    document.getElementById('tabBtnLive').classList.toggle('active', tab === 'live');
    document.getElementById('tabBtnHistory').classList.toggle('active', tab === 'history');
    document.getElementById('tabBtnNotifications').classList.toggle('active', tab === 'notifications');
    document.getElementById('tabBtnSettings').classList.toggle('active', tab === 'settings');
    
    // 切换面板显示状态
    document.getElementById('liveTabContent').style.display = tab === 'live' ? 'block' : 'none';
    document.getElementById('historyTabContent').style.display = tab === 'history' ? 'block' : 'none';
    document.getElementById('notificationsTabContent').style.display = tab === 'notifications' ? 'block' : 'none';
    document.getElementById('settingsTabContent').style.display = tab === 'settings' ? 'block' : 'none';
    
    // 自动填充日期为今天
    if (tab === 'history') {
        const dateInput = document.getElementById('historyDate');
        if (!dateInput.value) {
            const today = new Date().toISOString().split('T')[0];
            dateInput.value = today;
        }
    }

    // 进入设置面板时，自动加载最新的服务端配置
    if (tab === 'settings' || tab === 'notifications') {
        fetchServerConfig();
    }
}

async function queryHistory() {
    const dateInput = document.getElementById('historyDate');
    const dateStr = dateInput.value;
    if (!dateStr) {
        showToast('请先选择日期', 'warning');
        return;
    }
    
    // 获取当天的本地起止时间戳
    const localStart = new Date(dateStr + 'T00:00:00');
    const localEnd = new Date(dateStr + 'T23:59:59');
    const startTs = Math.floor(localStart.getTime() / 1000);
    const endTs = Math.floor(localEnd.getTime() / 1000);
    
    showToast('正在查询历史数据...', 'info');
    
    try {
        const response = await fetch(`/api/v1/heartrate/history?start=${startTs}&end=${endTs}`);
        const result = await response.json();
        
        if (response.ok && result.success) {
            displayHistoryData(result.records, dateStr);
        } else {
            showToast(result.message || '查询失败', 'error');
        }
    } catch (e) {
        console.error(e);
        showToast('网络请求失败', 'error');
    }
}

async function clearHistoryData() {
    if (!confirm('⚠️ 警告：您确定要清空所有的历史心率记录吗？此操作无法撤销！')) {
        return;
    }
    
    showToast('正在清空历史数据...', 'info');
    try {
        const response = await fetch('/api/v1/heartrate/history/clear', {
            method: 'POST'
        });
        const result = await response.json();
        
        if (response.ok && result.success) {
            showToast(result.message || '历史数据已清空', 'success');
            // 清空图表和指标状态显示
            const emptyState = document.getElementById('historyEmptyState');
            const chartCard = document.getElementById('historyChartCard');
            const statsGrid = document.getElementById('historyStatsGrid');
            if (emptyState && chartCard && statsGrid) {
                emptyState.style.display = 'flex';
                chartCard.style.display = 'none';
                statsGrid.style.display = 'none';
            }
            if (historyChart) {
                historyChart.destroy();
                historyChart = null;
            }
        } else {
            showToast(result.message || '清空失败', 'error');
        }
    } catch (e) {
        console.error(e);
        showToast('网络请求失败，清空操作未完成', 'error');
    }
}

function displayHistoryData(records, dateStr) {
    const emptyState = document.getElementById('historyEmptyState');
    const chartCard = document.getElementById('historyChartCard');
    const statsGrid = document.getElementById('historyStatsGrid');
    
    if (!records || records.length === 0) {
        emptyState.style.display = 'flex';
        chartCard.style.display = 'none';
        statsGrid.style.display = 'none';
        showToast('该日期暂无心率记录', 'info');
        return;
    }
    
    emptyState.style.display = 'none';
    chartCard.style.display = 'block';
    statsGrid.style.display = 'grid';
    
    // 计算统计指标
    const hrs = records.map(r => r.hr);
    const min = Math.min(...hrs);
    const max = Math.max(...hrs);
    const avg = (hrs.reduce((sum, val) => sum + val, 0) / hrs.length).toFixed(1);
    
    document.getElementById('historyMin').textContent = min;
    document.getElementById('historyMax').textContent = max;
    document.getElementById('historyAvg').textContent = avg;
    document.getElementById('historyCount').textContent = records.length;
    
    document.getElementById('historyChartTimeLabel').textContent = dateStr;
    
    // 渲染历史曲线
    renderHistoryChart(records);
}

function renderHistoryChart(records) {
    const canvas = document.getElementById('historyChart');
    if (!canvas) return;
    
    const ctx = canvas.getContext('2d');
    
    if (historyChart) {
        historyChart.destroy();
    }
    
    const gradient = ctx.createLinearGradient(0, 0, 0, 200);
    const isLight = document.body.classList.contains('light-theme');
    gradient.addColorStop(0, 'rgba(255, 45, 85, 0.25)');
    gradient.addColorStop(0.5, 'rgba(255, 45, 85, 0.08)');
    gradient.addColorStop(1, 'rgba(255, 45, 85, 0)');
    
    const gridColor = isLight ? 'rgba(0, 0, 0, 0.04)' : 'rgba(255, 255, 255, 0.03)';
    const tickColor = isLight ? '#8888aa' : '#44446a';
    
    const labels = records.map(r => {
        const d = new Date(r.time * 1000);
        return d.toLocaleTimeString('zh-CN', {
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit'
        });
    });
    const hrs = records.map(r => r.hr);
    
    historyChart = new Chart(ctx, {
        type: 'line',
        data: {
            labels: labels,
            datasets: [{
                label: '历史心率 (BPM)',
                data: hrs,
                borderColor: '#ff2d55',
                backgroundColor: gradient,
                fill: true,
                tension: 0.3,
                borderWidth: 2,
                pointRadius: records.length > 200 ? 0 : 2,
                pointHoverRadius: 5,
                pointHoverBackgroundColor: '#ff2d55',
                pointHoverBorderColor: '#fff',
                pointHoverBorderWidth: 2,
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { display: false },
                tooltip: {
                    enabled: true,
                    backgroundColor: 'rgba(10, 10, 30, 0.9)',
                    titleColor: '#8888aa',
                    bodyColor: '#ff6b8a',
                    bodyFont: { size: 14, weight: '700' },
                    padding: 12,
                    cornerRadius: 10,
                    displayColors: false,
                    callbacks: {
                        label: (item) => `${item.parsed.y} BPM`,
                    }
                }
            },
            scales: {
                x: {
                    display: true,
                    grid: {
                        color: gridColor,
                        drawBorder: false,
                    },
                    ticks: {
                        color: tickColor,
                        font: { size: 10 },
                        maxTicksLimit: 10,
                        maxRotation: 0,
                    },
                    border: { display: false },
                },
                y: {
                    display: true,
                    suggestedMin: 50,
                    suggestedMax: 150,
                    grid: {
                        color: gridColor,
                        drawBorder: false,
                    },
                    ticks: {
                        color: tickColor,
                        font: { size: 10 },
                        stepSize: 20,
                        padding: 8,
                    },
                    border: { display: false },
                }
            },
            interaction: {
                intersect: false,
                mode: 'index',
            },
        }
    });
}


// ═══════════════════════════════════════════════════════
// 🚨 心率超标声光警报系统
// ═══════════════════════════════════════════════════════

let alertState = {
    isEnabled: true,
    threshold: 150,
    isSoundEnabled: true,
    isFlashEnabled: true,
    audioInterval: null,
    isAlerting: false
};

function loadAlertSettings() {
    const saved = localStorage.getItem('alert_settings');
    if (saved) {
        try {
            alertState = JSON.parse(saved);
        } catch (e) {
            console.error('加载警报配置失败，使用默认配置', e);
        }
    }
    
    // 同步到 UI 元素
    const enableToggle = document.getElementById('alertEnableToggle');
    const thresholdSlider = document.getElementById('alertThreshold');
    const thresholdVal = document.getElementById('alertThresholdVal');
    const soundToggle = document.getElementById('alertSoundToggle');
    const flashToggle = document.getElementById('alertFlashToggle');
    
    if (enableToggle) enableToggle.checked = alertState.isEnabled;
    if (thresholdSlider) {
        thresholdSlider.value = alertState.threshold;
        if (thresholdVal) thresholdVal.textContent = alertState.threshold;
    }
    if (soundToggle) soundToggle.checked = alertState.isSoundEnabled;
    if (flashToggle) flashToggle.checked = alertState.isFlashEnabled;
}

function saveAlertSettings() {
    localStorage.setItem('alert_settings', JSON.stringify({
        isEnabled: alertState.isEnabled,
        threshold: alertState.threshold,
        isSoundEnabled: alertState.isSoundEnabled,
        isFlashEnabled: alertState.isFlashEnabled
    }));
}

function updateAlertThresholdLabel(val) {
    const thresholdVal = document.getElementById('alertThresholdVal');
    if (thresholdVal) thresholdVal.textContent = val;
    alertState.threshold = parseInt(val);
    saveAlertSettings();
}

let audioCtx = null;
function playAlertBeep() {
    try {
        if (!audioCtx) {
            audioCtx = new (window.AudioContext || window.webkitAudioContext)();
        }
        if (audioCtx.state === 'suspended') {
            audioCtx.resume();
        }
        
        const now = audioCtx.currentTime;
        
        // 播放连续两个嘀嘀声
        const playTone = (freq, startOffset, duration) => {
            const osc = audioCtx.createOscillator();
            const gainNode = audioCtx.createGain();
            osc.connect(gainNode);
            gainNode.connect(audioCtx.destination);
            
            osc.type = 'sine';
            osc.frequency.setValueAtTime(freq, now + startOffset);
            gainNode.gain.setValueAtTime(0.2, now + startOffset);
            gainNode.gain.exponentialRampToValueAtTime(0.01, now + startOffset + duration);
            
            osc.start(now + startOffset);
            osc.stop(now + startOffset + duration);
        };
        
        playTone(880, 0, 0.12);
        playTone(880, 0.18, 0.12);
    } catch (e) {
        console.error('音频警报失败:', e);
    }
}

function checkHeartRateAlert(hr) {
    if (!alertState.isEnabled) return;
    
    if (hr >= alertState.threshold) {
        startAlertWarning();
    } else {
        stopAlertWarning();
    }
}

function startAlertWarning() {
    if (alertState.isAlerting) return; // 已经在报警中
    alertState.isAlerting = true;
    
    // 1. 边缘红色闪烁
    if (alertState.isFlashEnabled) {
        document.body.classList.add('alert-flashing');
    }
    
    // 2. 播放警报嘀嘀声
    if (alertState.isSoundEnabled) {
        playAlertBeep();
        alertState.audioInterval = setInterval(() => {
            if (alertState.isSoundEnabled) {
                playAlertBeep();
            }
        }, 1500);
    }
}

function stopAlertWarning() {
    if (!alertState.isAlerting) return;
    alertState.isAlerting = false;
    
    if (alertState.audioInterval) {
        clearInterval(alertState.audioInterval);
        alertState.audioInterval = null;
    }
    
    document.body.classList.remove('alert-flashing');
}

function testAlertWarning() {
    if (alertState.isAlerting) {
        stopAlertWarning();
        showToast('测试结束', 'info');
        return;
    }
    
    showToast('🔴 警报效果测试启动 (5秒后自动停止)...', 'danger');
    
    alertState.isAlerting = true;
    if (alertState.isFlashEnabled) {
        document.body.classList.add('alert-flashing');
    }
    if (alertState.isSoundEnabled) {
        playAlertBeep();
        alertState.audioInterval = setInterval(() => {
            if (alertState.isSoundEnabled) {
                playAlertBeep();
            }
        }, 1200);
    }
    
    setTimeout(() => {
        stopAlertWarning();
    }, 5000);
}

// ─── OBS 模式辅助函数 ───────────────────────────────────

async function initObsUrl() {
    const obsUrlInput = document.getElementById('obsUrlInput');
    if (obsUrlInput) {
        try {
            // 获取最新配置以获取 token
            const response = await fetch('/api/v1/server/config');
            const result = await response.json();
            if (response.ok && result.success && result.api_token) {
                const url = `${window.location.protocol}//${window.location.host}/?obs=true&token=${result.api_token}`;
                obsUrlInput.value = url;
                return;
            }
        } catch (e) {
            console.error('获取 API Token 失败，将生成无 Token 推流链接:', e);
        }
        // 降级回退
        const url = `${window.location.protocol}//${window.location.host}/?obs=true`;
        obsUrlInput.value = url;
    }
}

function copyObsUrl() {
    const obsUrlInput = document.getElementById('obsUrlInput');
    if (obsUrlInput) {
        navigator.clipboard.writeText(obsUrlInput.value)
            .then(() => {
                showToast('📋 OBS 推流链接已复制到剪贴板', 'success');
            })
            .catch(() => {
                obsUrlInput.select();
                document.execCommand('copy');
                showToast('📋 OBS 推流链接已复制到剪贴板', 'success');
            });
    }
}

async function initPhoneConnectionUrl() {
    const manualIpEl = document.getElementById('manualIpAddr');
    if (!manualIpEl) return;

    let cloudMode = false;
    let apiToken = '';
    
    try {
        // 获取最新配置以获取 token 和运行模式
        const response = await fetch('/api/v1/server/config');
        const result = await response.json();
        if (response.ok && result.success) {
            cloudMode = result.cloud_mode || false;
            apiToken = result.api_token || '';
        }
    } catch (e) {
        console.error('获取服务端配置失败，将使用默认模式:', e);
    }

    // 检测当前是否是公网/外网环境 (如果不是 localhost 且不是局域网私有 IP，则自动按云端/公网模式展示)
    const hostname = window.location.hostname;
    const isLocal = ['localhost', '127.0.0.1', '[::1]'].includes(hostname) ||
                    /^(192\.168\.|10\.|172\.(1[6-9]|2[0-9]|3[0-1])\.)/.test(hostname);
    const showAsCloud = cloudMode || !isLocal;

    // 动态调整网页端“中转连接指南”的文案，淡化局域网色彩
    const gStep1Text = document.getElementById('guideStep1Text');
    const gStep3 = document.getElementById('guideStep3');
    const gStep4Text = document.getElementById('guideStep4Text');

    if (showAsCloud) {
        if (gStep1Text) {
            gStep1Text.innerHTML = '确保手机网络畅通，可直接访问公网服务器。若部署在外网或云端，需确保公网端口开放或安全通道畅通。';
        }
        if (gStep3) {
            gStep3.style.display = 'none'; // 云模式下屏蔽 UDP 局域网广播自动发现步骤
        }
        if (gStep4Text) {
            gStep4Text.innerHTML = '请在手机 App 中输入下方手动连接地址，并输入您的安全 Token（或直接点击下方复制完整地址）：';
        }
    } else {
        if (gStep1Text) {
            gStep1Text.innerHTML = '确保手机网络畅通。若是局域网本地运行，需确保手机和电脑在<strong>同一个 Wi-Fi</strong> 局域网下；若部署在外网，需确保公网可达。';
        }
        if (gStep3) {
            gStep3.style.display = 'flex'; // 局域网模式下显示自动发现步骤
        }
        if (gStep4Text) {
            gStep4Text.innerHTML = '若自动连接失败（如外网运行、组播拦截或跨网段），请在手机 App 中输入下方地址连接：';
        }
    }

    // 渲染连接 URL
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    if (apiToken) {
        manualIpEl.textContent = `${wsProtocol}//${window.location.host}/ws/phone?token=${apiToken}`;
    } else {
        manualIpEl.textContent = `${wsProtocol}//${window.location.host}/ws/phone`;
    }
}

// ─── 服务端配置管理 (修改密码、云模式切换、重新生成 API Token) ───

let loadedServerConfig = null;

async function fetchServerConfig() {
    try {
        const response = await fetch('/api/v1/server/config');
        const result = await response.json();
        if (response.ok && result.success) {
            loadedServerConfig = result;
            
            // 同步到页面组件
            const cloudModeToggle = document.getElementById('serverCloudModeToggle');
            const tokenInput = document.getElementById('serverApiTokenInput');
            const portInput = document.getElementById('serverPortInput');
            
            if (cloudModeToggle) cloudModeToggle.checked = result.cloud_mode;
            if (tokenInput) tokenInput.value = result.api_token;
            if (portInput) portInput.value = result.port;

            // 同步到推送设置组件
            const pushThreshold = document.getElementById('pushThresholdInput');
            const pushCooldown = document.getElementById('pushCooldownInput');
            const pushplusToggle = document.getElementById('pushplusEnableToggle');
            const pushplusToken = document.getElementById('pushplusTokenInput');
            const tgToggle = document.getElementById('tgEnableToggle');
            const tgToken = document.getElementById('tgTokenInput');
            const tgChatId = document.getElementById('tgChatIdInput');
            const dingtalkToggle = document.getElementById('dingtalkEnableToggle');
            const dingtalkToken = document.getElementById('dingtalkTokenInput');
            const feishuToggle = document.getElementById('feishuEnableToggle');
            const feishuToken = document.getElementById('feishuTokenInput');

            if (pushThreshold) pushThreshold.value = result.safety_threshold || 150;
            if (pushCooldown) pushCooldown.value = result.notifications?.cooldown_minutes || 5;

            if (pushplusToggle && result.notifications?.pushplus) {
                pushplusToggle.checked = result.notifications.pushplus.enabled;
                if (pushplusToken) pushplusToken.value = result.notifications.pushplus.token || '';
                document.getElementById('pushplusTokenRow').style.display = pushplusToggle.checked ? 'flex' : 'none';
            }
            if (tgToggle && result.notifications?.telegram) {
                tgToggle.checked = result.notifications.telegram.enabled;
                if (tgToken) tgToken.value = result.notifications.telegram.token || '';
                if (tgChatId) tgChatId.value = result.notifications.telegram.chat_id || '';
                document.getElementById('tgConfigRow').style.display = tgToggle.checked ? 'flex' : 'none';
            }
            if (dingtalkToggle && result.notifications?.dingtalk) {
                dingtalkToggle.checked = result.notifications.dingtalk.enabled;
                if (dingtalkToken) dingtalkToken.value = result.notifications.dingtalk.token || '';
                document.getElementById('dingtalkTokenRow').style.display = dingtalkToggle.checked ? 'flex' : 'none';
            }
            if (feishuToggle && result.notifications?.feishu) {
                feishuToggle.checked = result.notifications.feishu.enabled;
                if (feishuToken) feishuToken.value = result.notifications.feishu.token || '';
                document.getElementById('feishuTokenRow').style.display = feishuToggle.checked ? 'flex' : 'none';
            }

            // 同步心率阈值到主页警报配置
            const thresholdSlider = document.getElementById('alertThreshold');
            const thresholdVal = document.getElementById('alertThresholdVal');
            if (thresholdSlider && result.safety_threshold) {
                thresholdSlider.value = result.safety_threshold;
                if (thresholdVal) thresholdVal.textContent = result.safety_threshold;
                alertState.threshold = result.safety_threshold;
                saveAlertSettings();
            }
        } else {
            showToast('获取服务器配置失败: ' + (result.message || '未授权'), 'error');
        }
    } catch (e) {
        console.error('获取服务器配置失败:', e);
        showToast('获取服务器配置失败，请检查网络连接', 'error');
    }
}

async function saveServerSettings() {
    const cloudModeToggle = document.getElementById('serverCloudModeToggle');
    const portInput = document.getElementById('serverPortInput');
    const oldPasswordInput = document.getElementById('oldPasswordInput');
    const newPasswordInput = document.getElementById('newPasswordInput');
    const confirmPasswordInput = document.getElementById('confirmPasswordInput');
    
    const cloudMode = cloudModeToggle ? cloudModeToggle.checked : false;
    const port = portInput ? parseInt(portInput.value.trim()) : 8765;
    const oldPassword = oldPasswordInput ? oldPasswordInput.value.trim() : '';
    const newPassword = newPasswordInput ? newPasswordInput.value.trim() : '';
    const confirmPassword = confirmPasswordInput ? confirmPasswordInput.value.trim() : '';
    
    if (isNaN(port) || port < 1 || port > 65535) {
        showToast('端口范围必须在 1 到 65535 之间', 'warning');
        return;
    }
    
    const payload = {
        cloud_mode: cloudMode,
        port: port
    };
    
    // 检查密码修改
    if (newPassword) {
        if (!oldPassword) {
            showToast('修改密码必须输入当前密码', 'warning');
            return;
        }
        if (newPassword.length < 6) {
            showToast('新密码长度不能少于 6 位', 'warning');
            return;
        }
        if (newPassword !== confirmPassword) {
            showToast('新密码与确认密码不匹配', 'warning');
            return;
        }
        payload.old_password = oldPassword;
        payload.new_password = newPassword;
    }
    
    showToast('正在保存设置...', 'info');
    
    try {
        const response = await fetch('/api/v1/server/config', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(payload)
        });
        const result = await response.json();
        
        if (response.ok && result.success) {
            showToast('✅ 设置保存成功！如果修改了云端模式，请重启后端服务。', 'success', 5000);
            
            // 清空密码框
            if (oldPasswordInput) oldPasswordInput.value = '';
            if (newPasswordInput) newPasswordInput.value = '';
            if (confirmPasswordInput) confirmPasswordInput.value = '';
            
            // 更新当前状态
            fetchServerConfig();
        } else {
            showToast('保存设置失败: ' + (result.message || '未知错误'), 'error');
        }
    } catch (e) {
        console.error('保存设置失败:', e);
        showToast('保存设置失败，请检查网络连接', 'error');
    }
}

async function regenerateApiToken() {
    if (!confirm('警告：重新生成 API Token 后，之前使用该 Token 连接的手机 App 和 WPF 悬浮窗都会被拒绝访问。确定要重新生成吗？')) {
        return;
    }
    
    showToast('正在生成新 Token...', 'info');
    
    try {
        const response = await fetch('/api/v1/server/config', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ regenerate_token: true })
        });
        const result = await response.json();
        
        if (response.ok && result.success) {
            showToast('✅ 成功重新生成 API Token！请将新 Token 配置到各客户端。', 'success', 5000);
            const tokenInput = document.getElementById('serverApiTokenInput');
            if (tokenInput) tokenInput.value = result.api_token;
        } else {
            showToast('重新生成 Token 失败: ' + (result.message || '未知错误'), 'error');
        }
    } catch (e) {
        console.error('重新生成 Token 失败:', e);
        showToast('网络连接失败', 'error');
    }
}

function copyServerApiToken() {
    const tokenInput = document.getElementById('serverApiTokenInput');
    if (tokenInput && tokenInput.value) {
        navigator.clipboard.writeText(tokenInput.value)
            .then(() => {
                showToast('📋 API Token 已复制到剪贴板', 'success');
            })
            .catch(() => {
                tokenInput.select();
                document.execCommand('copy');
                showToast('📋 API Token 已复制到剪贴板', 'success');
            });
    }
}

function copyIpAddress() {
    const ipVal = document.getElementById('manualIpAddr').textContent;
    if (ipVal && ipVal !== '正在获取...') {
        navigator.clipboard.writeText(ipVal)
            .then(() => showToast('📋 连接地址已复制到剪贴板', 'success'))
            .catch(() => showToast('复制失败', 'error'));
    }
}

async function savePushSettings() {
    const pushThreshold = document.getElementById('pushThresholdInput');
    const pushCooldown = document.getElementById('pushCooldownInput');
    
    const pushplusToggle = document.getElementById('pushplusEnableToggle');
    const pushplusToken = document.getElementById('pushplusTokenInput');
    
    const tgToggle = document.getElementById('tgEnableToggle');
    const tgToken = document.getElementById('tgTokenInput');
    const tgChatId = document.getElementById('tgChatIdInput');
    
    const dingtalkToggle = document.getElementById('dingtalkEnableToggle');
    const dingtalkToken = document.getElementById('dingtalkTokenInput');
    
    const feishuToggle = document.getElementById('feishuEnableToggle');
    const feishuToken = document.getElementById('feishuTokenInput');
    
    const threshold = pushThreshold ? parseInt(pushThreshold.value.trim()) : 150;
    const cooldown = pushCooldown ? parseInt(pushCooldown.value.trim()) : 5;
    
    if (isNaN(threshold) || threshold < 40 || threshold > 220) {
        showToast('心率预警阈值必须在 40 到 220 BPM 之间', 'warning');
        return;
    }
    
    if (isNaN(cooldown) || cooldown < 1 || cooldown > 1440) {
        showToast('推送冷却时间必须在 1 到 1440 分钟之间', 'warning');
        return;
    }
    
    const payload = {
        safety_threshold: threshold,
        notifications: {
            pushplus: {
                enabled: pushplusToggle ? pushplusToggle.checked : false,
                token: pushplusToken ? pushplusToken.value.trim() : ''
            },
            telegram: {
                enabled: tgToggle ? tgToggle.checked : false,
                token: tgToken ? tgToken.value.trim() : '',
                chat_id: tgChatId ? tgChatId.value.trim() : ''
            },
            dingtalk: {
                enabled: dingtalkToggle ? dingtalkToggle.checked : false,
                token: dingtalkToken ? dingtalkToken.value.trim() : ''
            },
            feishu: {
                enabled: feishuToggle ? feishuToggle.checked : false,
                token: feishuToken ? feishuToken.value.trim() : ''
            },
            cooldown_minutes: cooldown
        }
    };
    
    showToast('正在保存推送设置...', 'info');
    
    try {
        const response = await fetch('/api/v1/server/config', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(payload)
        });
        const result = await response.json();
        
        if (response.ok && result.success) {
            showToast('✅ 推送设置保存成功！', 'success');
            
            // 同步回主页警报配置
            const thresholdSlider = document.getElementById('alertThreshold');
            const thresholdVal = document.getElementById('alertThresholdVal');
            if (thresholdSlider) {
                thresholdSlider.value = threshold;
                if (thresholdVal) thresholdVal.textContent = threshold;
                alertState.threshold = threshold;
                saveAlertSettings();
            }
            
            fetchServerConfig();
        } else {
            showToast('保存推送设置失败: ' + (result.message || '未知错误'), 'error');
        }
    } catch (e) {
        console.error('保存设置失败:', e);
        showToast('网络连接失败', 'error');
    }
}

async function testPushNotifications() {
    const pushplusToggle = document.getElementById('pushplusEnableToggle');
    const pushplusToken = document.getElementById('pushplusTokenInput');
    const tgToggle = document.getElementById('tgEnableToggle');
    const tgToken = document.getElementById('tgTokenInput');
    const tgChatId = document.getElementById('tgChatIdInput');
    const dingtalkToggle = document.getElementById('dingtalkEnableToggle');
    const dingtalkToken = document.getElementById('dingtalkTokenInput');
    const feishuToggle = document.getElementById('feishuEnableToggle');
    const feishuToken = document.getElementById('feishuTokenInput');
    
    const payload = {
        notifications: {
            pushplus: {
                enabled: pushplusToggle ? pushplusToggle.checked : false,
                token: pushplusToken ? pushplusToken.value.trim() : ''
            },
            telegram: {
                enabled: tgToggle ? tgToggle.checked : false,
                token: tgToken ? tgToken.value.trim() : '',
                chat_id: tgChatId ? tgChatId.value.trim() : ''
            },
            dingtalk: {
                enabled: dingtalkToggle ? dingtalkToggle.checked : false,
                token: dingtalkToken ? dingtalkToken.value.trim() : ''
            },
            feishu: {
                enabled: feishuToggle ? feishuToggle.checked : false,
                token: feishuToken ? feishuToken.value.trim() : ''
            }
        }
    };
    
    const notif = payload.notifications;
    if (!notif.pushplus.enabled && !notif.telegram.enabled && !notif.dingtalk.enabled && !notif.feishu.enabled) {
        showToast('请至少启用一个推送通道后再进行测试', 'warning');
        return;
    }
    
    showToast('正在发送测试消息...', 'info');
    
    try {
        const response = await fetch('/api/v1/server/test_push', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(payload)
        });
        const result = await response.json();
        if (response.ok && result.success) {
            showToast('✅ 测试消息发送请求成功，请检查目标客户端。', 'success', 5000);
        } else {
            showToast('发送测试消息失败: ' + (result.message || '未知错误'), 'error');
        }
    } catch (e) {
        console.error('发送测试消息失败:', e);
        showToast('网络连接失败', 'error');
    }
}

