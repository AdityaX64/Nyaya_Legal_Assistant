// Nyaya Legal Research Web Application Client

let currentActFilter = 'ALL';
let activeSources = [];
let currentConversationId = 'session-' + Math.random().toString(36).substring(2, 9);

document.addEventListener('DOMContentLoaded', () => {
    checkHealth();
    setInterval(checkHealth, 15000);
});

// HEALTH CHECK
async function checkHealth() {
    try {
        const res = await fetch('/api/health');
        if (!res.ok) throw new Error('Health check failed');
        const data = await res.json();
        
        const badge = document.getElementById('statusBadge');
        const dot = document.getElementById('statusDot');
        const text = document.getElementById('statusText');

        if (data.status === 'ok') {
            dot.className = 'h-2 w-2 rounded-full bg-emerald-500';
            text.textContent = `pgvector Active • ${data.chunks.toLocaleString()} chunks`;
        } else {
            dot.className = 'h-2 w-2 rounded-full bg-amber-500 animate-pulse';
            text.textContent = 'PostgreSQL Degraded';
        }
    } catch (e) {
        const dot = document.getElementById('statusDot');
        const text = document.getElementById('statusText');
        dot.className = 'h-2 w-2 rounded-full bg-rose-500';
        text.textContent = 'API Offline';
    }
}

// FILTER TOGGLE
function setActFilter(filter) {
    currentActFilter = filter;
    ['ALL', 'BNS', 'BNSS'].forEach(f => {
        const btn = document.getElementById('filter' + f);
        if (f === filter) {
            btn.className = 'px-2.5 py-1 rounded-md text-xs font-semibold bg-white text-slate-900 border border-slate-200/80 shadow-xs transition';
        } else {
            btn.className = 'px-2.5 py-1 rounded-md text-xs font-medium text-slate-500 hover:text-slate-900 transition';
        }
    });
}

function setQuery(text) {
    const input = document.getElementById('queryInput');
    input.value = text;
    input.focus();
}

function clearChat() {
    const container = document.getElementById('messagesContainer');
    const welcome = document.getElementById('welcomeState');
    container.innerHTML = '';
    currentConversationId = 'session-' + Math.random().toString(36).substring(2, 9);
    if (welcome) container.appendChild(welcome);
    closeInspector();
}

function handleKeyDown(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        document.getElementById('chatForm').dispatchEvent(new Event('submit'));
    }
}

// CHAT SUBMISSION
async function handleSend(e) {
    e.preventDefault();
    const input = document.getElementById('queryInput');
    const question = input.value.trim();
    if (!question) return;

    // Remove welcome state if present
    const welcome = document.getElementById('welcomeState');
    if (welcome) welcome.remove();

    input.value = '';
    const sendBtn = document.getElementById('sendBtn');
    sendBtn.disabled = true;

    // Append user message
    appendUserMessage(question);

    // Create assistant message container
    const assistantMsg = createAssistantMessagePlaceholder();

    try {
        // First fetch structured answer & sources via /api/chat
        const response = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                question: question,
                actFilter: currentActFilter,
                conversationId: currentConversationId
            })
        });

        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.detail || 'Failed to retrieve legal text.');
        }

        const data = await response.json();
        renderCompleteAnswer(assistantMsg, data.answer, data.sources, data.latencyMs);

    } catch (err) {
        renderError(assistantMsg, err.message);
    } finally {
        sendBtn.disabled = false;
        input.focus();
    }
}

function appendUserMessage(text) {
    const container = document.getElementById('messagesContainer');
    const div = document.createElement('div');
    div.className = 'flex justify-end';
    div.innerHTML = `
        <div class="max-w-2xl bg-gradient-to-br from-slate-900 to-slate-800 text-white px-5 py-3 rounded-2xl rounded-br-sm text-sm shadow-md leading-relaxed font-sans">
            ${escapeHtml(text)}
        </div>
    `;
    container.appendChild(div);
    container.scrollTop = container.scrollHeight;
}

function createAssistantMessagePlaceholder() {
    const container = document.getElementById('messagesContainer');
    const div = document.createElement('div');
    div.className = 'flex justify-start space-x-3.5 max-w-3xl';
    div.innerHTML = `
        <div class="h-8 w-8 rounded-xl bg-amber-50 border border-amber-200/90 flex items-center justify-center shrink-0 text-amber-700 mt-1 shadow-xs">
            <svg class="w-4 h-4 stroke-[2]" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M3 6l3 1m0 0l-3 9a5.002 5.002 0 006.001 0M6 7l3 9M6 7l6-2m6 2l3-1m-3 1l-3 9a5.002 5.002 0 006.001 0M18 7l3 9m-3-9l-6-2m0-2v2m0 16V5m0 16H9m3 0h3"></path></svg>
        </div>
        <div class="space-y-3.5 flex-1 bg-white border border-slate-200/90 p-5 rounded-2xl rounded-tl-sm shadow-xs">
            <div class="text-xs text-slate-500 flex items-center space-x-2.5">
                <span class="font-semibold text-slate-900 font-display tracking-wide">Nyaya Legal AI</span>
                <span class="inline-block h-1.5 w-1.5 rounded-full bg-amber-500 animate-ping"></span>
                <span class="text-[11px] text-amber-700 font-mono">Retrieving BNS/BNSS statutory provisions...</span>
            </div>
            <div class="text-sm text-slate-700 leading-relaxed prose-legal answer-body">
                <span class="inline-block animate-pulse text-slate-400">Synthesizing authoritative legal counsel from Gazette text...</span>
            </div>
        </div>
    `;
    container.appendChild(div);
    container.scrollTop = container.scrollHeight;
    return div;
}

function renderCompleteAnswer(wrapper, answerMarkdown, sources, latencyMs) {
    const body = wrapper.querySelector('.answer-body');
    
    // Parse markdown
    const formattedHtml = marked.parse(answerMarkdown);
    body.innerHTML = formattedHtml;

    // Enhance section mentions into clickable badges
    body.querySelectorAll('strong, p, li').forEach(el => {
        el.innerHTML = el.innerHTML.replace(/\[(BNS|BNSS)?\s*(?:Section|Sec\.?)\s*(\d+[A-Z]?)\]/gi, (match, act, sec) => {
            const actCode = act ? act.toUpperCase() : 'BNS';
            const isBnss = actCode === 'BNSS';
            const badgeClass = isBnss
                ? 'bg-sky-50 hover:bg-sky-100 text-sky-900 border-sky-200'
                : 'bg-amber-50 hover:bg-amber-100 text-amber-900 border-amber-200';
            return `<button type="button" onclick="inspectSectionByNumber('${actCode}', '${sec}')" class="inline-flex items-center px-2 py-0.5 rounded ${badgeClass} border text-xs font-mono font-medium mx-0.5 cursor-pointer transition shadow-xs" title="Click to inspect Gazette section">${match}</button>`;
        });
    });

    // Store sources for inspector
    activeSources = sources || [];

    // Render Source Cards & Latency
    if (sources && sources.length > 0) {
        const sourcesDiv = document.createElement('div');
        sourcesDiv.className = 'pt-3.5 border-t border-slate-100 mt-4 space-y-2.5';
        
        let cardsHtml = `
            <div class="flex items-center justify-between text-xs text-slate-500">
                <span class="font-semibold uppercase tracking-widest text-[10px] text-slate-500">Verified Legal Sources (${sources.length})</span>
                <span class="text-[11px] text-slate-500 font-mono font-medium">⚡ ${latencyMs}ms</span>
            </div>
            <div class="flex flex-wrap gap-2">
        `;

        sources.forEach((src, idx) => {
            const isBnss = src.actCode === 'BNSS';
            const badgeColor = isBnss 
                ? 'text-sky-950 border-sky-200 bg-sky-50/50 hover:border-sky-300 hover:bg-white' 
                : 'text-amber-950 border-amber-200 bg-amber-50/40 hover:border-amber-300 hover:bg-white';
            const tagBg = isBnss ? 'bg-sky-100 text-sky-900' : 'bg-amber-100 text-amber-900';
            cardsHtml += `
                <button type="button" onclick="openInspector(${idx})" class="text-left text-xs p-2 rounded-xl border ${badgeColor} transition flex items-center space-x-2 max-w-xs truncate shadow-xs hover:shadow-sm">
                    <span class="font-mono font-bold text-[11px] px-1.5 py-0.5 rounded ${tagBg}">${src.actCode} §${src.section || '?'}</span>
                    <span class="text-slate-800 truncate font-medium">${src.title || 'Section extract'}</span>
                    <span class="text-slate-500 text-[10px] font-mono">p.${src.pageStart}</span>
                </button>
            `;
        });

        cardsHtml += `</div>`;
        sourcesDiv.innerHTML = cardsHtml;
        wrapper.querySelector('.space-y-3.5').appendChild(sourcesDiv);
    }

    const container = document.getElementById('messagesContainer');
    container.scrollTop = container.scrollHeight;
}

function renderError(wrapper, errorMsg) {
    const body = wrapper.querySelector('.answer-body');
    body.innerHTML = `
        <div class="bg-rose-50 border border-rose-200 text-rose-800 p-3.5 rounded-xl text-xs space-y-1">
            <p class="font-semibold">Query Processing Failed</p>
            <p>${escapeHtml(errorMsg)}</p>
            <p class="text-[11px] text-slate-500 mt-1">If the database is unpopulated, use <strong>Manage Corpus ➔ One-Click Index</strong> above to index the BNS/BNSS PDFs.</p>
        </div>
    `;
}

// STATUTE INSPECTOR DRAWER
function openInspector(index) {
    if (!activeSources || !activeSources[index]) return;
    const src = activeSources[index];

    document.getElementById('drawerActCode').textContent = src.actCode;
    document.getElementById('drawerSectionHeading').textContent = `§${src.section} ${src.title || ''}`;
    document.getElementById('drawerActName').textContent = src.actCode === 'BNSS' ? 'Bharatiya Nagarik Suraksha Sanhita, 2023' : 'Bharatiya Nyaya Sanhita, 2023';
    document.getElementById('drawerSectionTitle').textContent = `Section ${src.section}: ${src.title || 'Statutory Provision'}`;
    document.getElementById('drawerPages').textContent = `Source File: ${src.source} (PDF page ${src.pageStart}${src.pageEnd > src.pageStart ? '-' + src.pageEnd : ''})`;
    document.getElementById('drawerText').textContent = src.excerpt;

    const drawer = document.getElementById('inspectorDrawer');
    drawer.classList.remove('hidden');
    drawer.classList.add('flex');
}

function inspectSectionByNumber(actCode, sectionNum) {
    const matchIndex = activeSources.findIndex(s => s.section === sectionNum && (!actCode || s.actCode === actCode));
    if (matchIndex >= 0) {
        openInspector(matchIndex);
    } else {
        // Create synthetic inspector view
        document.getElementById('drawerActCode').textContent = actCode;
        document.getElementById('drawerSectionHeading').textContent = `§${sectionNum}`;
        document.getElementById('drawerActName').textContent = actCode === 'BNSS' ? 'Bharatiya Nagarik Suraksha Sanhita, 2023' : 'Bharatiya Nyaya Sanhita, 2023';
        document.getElementById('drawerSectionTitle').textContent = `Section ${sectionNum}`;
        document.getElementById('drawerPages').textContent = `Referenced Section in Analysis`;
        document.getElementById('drawerText').textContent = `Section ${sectionNum} cited in statutory answer context.`;

        const drawer = document.getElementById('inspectorDrawer');
        drawer.classList.remove('hidden');
        drawer.classList.add('flex');
    }
}

function closeInspector() {
    const drawer = document.getElementById('inspectorDrawer');
    drawer.classList.add('hidden');
    drawer.classList.remove('flex');
}

// INGESTION & SAMPLE BOOTSTRAP
function openIngestModal() {
    document.getElementById('ingestModal').classList.remove('hidden');
}

function closeIngestModal() {
    document.getElementById('ingestModal').classList.add('hidden');
}

async function bootstrapSamples() {
    const btn = document.getElementById('bootstrapBtn');
    const status = document.getElementById('bootstrapStatus');
    btn.disabled = true;
    btn.textContent = 'Indexing PDFs...';
    status.classList.remove('hidden');
    status.className = 'text-xs text-sky-600 pt-1 flex items-center space-x-1.5';
    status.innerHTML = '<span class="animate-spin inline-block h-3 w-3 border-2 border-sky-600 border-t-transparent rounded-full"></span><span>Parsing BNS & BNSS PDFs into hierarchical section chunks...</span>';

    try {
        const res = await fetch('/api/ingest/samples', { method: 'POST' });
        if (!res.ok) {
            const err = await res.json();
            throw new Error(err.detail || 'Failed to ingest samples.');
        }
        const data = await res.json();
        status.className = 'text-xs text-emerald-700 pt-1 font-medium';
        status.textContent = `Successfully indexed ${data.chunksIndexed} statutory chunks across ${data.filenames.join(', ')}!`;
        checkHealth();
    } catch (err) {
        status.className = 'text-xs text-rose-700 pt-1 font-medium';
        status.textContent = `Error: ${err.message}`;
    } finally {
        btn.disabled = false;
        btn.textContent = 'One-Click Index';
    }
}

function updateFileCount() {
    const input = document.getElementById('pdfFileInput');
    const label = document.getElementById('fileCountLabel');
    if (input.files && input.files.length > 0) {
        label.textContent = `${input.files.length} PDF(s) selected: ` + Array.from(input.files).map(f => f.name).join(', ');
    } else {
        label.textContent = 'Click to select PDF files (max 45MB each)';
    }
}

async function handleUpload(e) {
    e.preventDefault();
    const input = document.getElementById('pdfFileInput');
    if (!input.files || input.files.length === 0) {
        alert('Please select at least one PDF file.');
        return;
    }

    const btn = document.getElementById('uploadBtn');
    btn.disabled = true;
    btn.textContent = 'Uploading & Chunking...';

    const formData = new FormData();
    for (let i = 0; i < input.files.length; i++) {
        formData.append('files', input.files[i]);
    }

    try {
        const res = await fetch('/api/ingest', { method: 'POST', body: formData });
        if (!res.ok) {
            const err = await res.json();
            throw new Error(err.detail || 'Upload failed');
        }
        const data = await res.json();
        alert(`Successfully indexed ${data.chunksIndexed} chunks!`);
        closeIngestModal();
        checkHealth();
    } catch (err) {
        alert('Ingest Error: ' + err.message);
    } finally {
        btn.disabled = false;
        btn.textContent = 'Upload & Parse Chunks';
    }
}

// BENCHMARK EVALS
function openEvalModal() {
    document.getElementById('evalModal').classList.remove('hidden');
    loadPreviousEvals();
}

function closeEvalModal() {
    document.getElementById('evalModal').classList.add('hidden');
}

async function loadPreviousEvals() {
    try {
        const res = await fetch('/api/evals/results');
        if (res.status === 200) {
            const data = await res.json();
            renderEvalReport(data);
        }
    } catch (ignored) {}
}

async function runBenchmarkEvals() {
    const btn = document.getElementById('runEvalBtn');
    const summary = document.getElementById('evalSummaryText');
    btn.disabled = true;
    btn.textContent = 'Running Benchmark...';
    summary.innerHTML = '<span class="text-sky-600 font-medium animate-pulse">Executing benchmark tests across BNS & BNSS questions...</span>';

    try {
        const res = await fetch('/api/evals/run', { method: 'POST' });
        if (!res.ok) {
            const err = await res.json();
            throw new Error(err.detail || 'Evaluation failed to execute.');
        }
        const report = await res.json();
        renderEvalReport(report);
    } catch (err) {
        summary.innerHTML = `<span class="text-rose-600 font-medium">Evaluation Error: ${escapeHtml(err.message)}</span>`;
    } finally {
        btn.disabled = false;
        btn.textContent = 'Run Evaluation Suite';
    }
}

function renderEvalReport(report) {
    const summary = document.getElementById('evalSummaryText');
    const badgeColor = report.passRate >= 80 ? 'text-emerald-700' : 'text-amber-700';
    summary.innerHTML = `
        <span class="font-bold text-slate-900">Scorecard:</span>
        <span class="${badgeColor} font-bold text-sm ml-2">${report.passRate.toFixed(1)}% Pass Rate</span>
        <span class="text-slate-600 ml-2">(${report.passedQuestions}/${report.totalQuestions} Passed)</span>
        <span class="text-slate-500 ml-2">Avg Latency: ${report.averageLatencyMs}ms</span>
    `;

    const tbody = document.getElementById('evalTableBody');
    tbody.innerHTML = '';

    report.results.forEach(item => {
        const tr = document.createElement('tr');
        tr.className = 'border-b border-slate-100 hover:bg-slate-50/70 transition';
        const statusBadge = item.passed 
            ? '<span class="px-2 py-0.5 rounded bg-emerald-50 text-emerald-800 border border-emerald-200 font-semibold text-[11px]">PASSED</span>'
            : '<span class="px-2 py-0.5 rounded bg-rose-50 text-rose-800 border border-rose-200 font-semibold text-[11px]">FAILED</span>';
        
        const expected = item.expectedSections && item.expectedSections.length > 0 
            ? item.expectedSections.map(s => `§${s}`).join(', ') 
            : '<span class="text-slate-400">Refusal check</span>';

        tr.innerHTML = `
            <td class="py-2.5 px-3 font-mono text-[11px] text-sky-700 font-medium">${escapeHtml(item.id)}</td>
            <td class="py-2.5 px-3 text-slate-800 max-w-xs truncate font-medium" title="${escapeHtml(item.question)}">${escapeHtml(item.question)}</td>
            <td class="py-2.5 px-3 font-mono text-[11px] text-slate-600">${expected}</td>
            <td class="py-2.5 px-3">${statusBadge}</td>
            <td class="py-2.5 px-3 text-right font-mono text-slate-500">${item.latencyMs}ms</td>
        `;
        tbody.appendChild(tr);
    });
}

function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/[&<>'"]/g, tag => ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        "'": '&#39;',
        '"': '&quot;'
    }[tag] || tag));
}
