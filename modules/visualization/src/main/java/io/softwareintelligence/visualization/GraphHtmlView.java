package io.softwareintelligence.visualization;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a graph as one self-contained HTML file: no server, no CDN, no framework, no fonts to
 * fetch. That is not minimalism for its own sake — the product promises to work air-gapped, and a
 * viewer that phones out to a script host would be the first thing in it that does not.
 *
 * <p>The layout is a seeded force simulation run to a fixed iteration count, so the same graph draws
 * the same picture every time. A viewer that rearranges itself on each open makes two screenshots
 * incomparable, which defeats the point of a reproducible model.
 *
 * <p>Confidence is drawn, not hidden: an edge below 0.95 is dashed and fades, and the threshold
 * slider lets a reader see exactly how much of a picture rests on inference rather than proof.
 */
public final class GraphHtmlView {
    /**
     * Above this, a force layout stops being readable and starts being a hairball - and, measured
     * on jackson-databind, 1200 nodes cost 12 seconds of layout before the page would paint. A
     * graph larger than this belongs in GraphML and a dedicated tool, which the CLI says on stderr.
     */
    public static final int DEFAULT_MAX_NODES = 500;

    private GraphHtmlView() { }

    public record View(String html, int renderedNodes, int totalNodes, int renderedEdges, int totalEdges) {
        public boolean truncated() { return renderedNodes < totalNodes; }
    }

    public static View render(CodeGraph graph, String title, int maxNodes) {
        List<GraphNode> nodes = graph.nodes();
        // Truncation keeps the highest-degree nodes, because those are what a reader came to see.
        Map<String, Integer> degree = new LinkedHashMap<>();
        for (GraphNode node : nodes) degree.put(node.id(), graph.incoming(node.id()).size() + graph.outgoing(node.id()).size());
        List<GraphNode> kept = nodes.stream()
                .sorted((left, right) -> {
                    int byDegree = Integer.compare(degree.get(right.id()), degree.get(left.id()));
                    return byDegree != 0 ? byDegree : left.id().compareTo(right.id());
                })
                .limit(Math.max(1, maxNodes))
                .sorted(java.util.Comparator.comparing(GraphNode::id))
                .toList();

        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < kept.size(); i++) index.put(kept.get(i).id(), i);
        List<GraphEdge> keptEdges = graph.edges().stream()
                .filter(edge -> index.containsKey(edge.from()) && index.containsKey(edge.to()))
                .toList();

        String data = data(kept, keptEdges, index);
        String html = TEMPLATE
                .replace("__TITLE__", escape(title))
                .replace("__SUMMARY__", escape(summary(kept.size(), nodes.size(), keptEdges.size(), graph.edges().size())))
                .replace("__DATA__", data);
        return new View(html, kept.size(), nodes.size(), keptEdges.size(), graph.edges().size());
    }

    public static View render(CodeGraph graph, String title) {
        return render(graph, title, DEFAULT_MAX_NODES);
    }

    private static String summary(int nodes, int totalNodes, int edges, int totalEdges) {
        if (nodes == totalNodes && edges == totalEdges) return nodes + " nodes, " + edges + " edges";
        return nodes + " of " + totalNodes + " nodes, " + edges + " of " + totalEdges
                + " edges (highest-degree kept; the full graph is in the JSON export)";
    }

    private static String data(List<GraphNode> nodes, List<GraphEdge> edges, Map<String, Integer> index) {
        StringBuilder json = new StringBuilder("{\"nodes\":[");
        for (int i = 0; i < nodes.size(); i++) {
            GraphNode node = nodes.get(i);
            if (i > 0) json.append(',');
            json.append("{\"id\":\"").append(Json.quote(node.id()))
                    .append("\",\"name\":\"").append(Json.quote(shortName(node)))
                    .append("\",\"kind\":\"").append(node.kind())
                    .append("\",\"file\":\"").append(Json.quote(node.provenance().file()))
                    .append("\",\"line\":").append(node.provenance().line())
                    .append(",\"resolver\":\"").append(Json.quote(node.provenance().resolver())).append("\"}");
        }
        json.append("],\"edges\":[");
        for (int i = 0; i < edges.size(); i++) {
            GraphEdge edge = edges.get(i);
            if (i > 0) json.append(',');
            json.append("{\"s\":").append(index.get(edge.from()))
                    .append(",\"t\":").append(index.get(edge.to()))
                    .append(",\"r\":\"").append(edge.kind())
                    .append("\",\"v\":\"").append(Json.quote(edge.provenance().resolver()))
                    .append("\",\"c\":").append(edge.provenance().confidence())
                    .append(",\"f\":\"").append(Json.quote(edge.provenance().file()))
                    .append("\",\"l\":").append(edge.provenance().line()).append('}');
        }
        return json.append("]}").toString();
    }

    /** Full ids are unreadable as labels; the tail is what identifies a symbol on screen. */
    private static String shortName(GraphNode node) {
        String id = node.id();
        if (node.kind() == EntityKind.ENDPOINT || node.kind() == EntityKind.DATABASE_TABLE
                || node.kind() == EntityKind.TOPIC || node.kind() == EntityKind.BUSINESS_CAPABILITY
                || node.kind() == EntityKind.MODULE) {
            return node.name();
        }
        int hash = id.indexOf('#');
        if (hash > 0) {
            String owner = id.substring(0, hash);
            return owner.substring(owner.lastIndexOf('.') + 1) + "." + id.substring(hash + 1);
        }
        int field = id.indexOf(".field:");
        if (field > 0) return id.substring(id.lastIndexOf('.', field - 1) + 1);
        String name = node.name();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final String TEMPLATE = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>__TITLE__</title>
            <style>
              :root { color-scheme: light dark; --bg:#ffffff; --fg:#1a1a1a; --muted:#6b7280; --line:#e5e7eb; --panel:#f9fafb; }
              @media (prefers-color-scheme: dark) {
                :root { --bg:#0f1115; --fg:#e6e6e6; --muted:#9aa0aa; --line:#262a33; --panel:#161922; }
              }
              * { box-sizing: border-box; }
              body { margin:0; font:13px/1.5 ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif;
                     background:var(--bg); color:var(--fg); overflow:hidden; }
              #app { display:flex; height:100vh; }
              #stage { flex:1; position:relative; }
              canvas { display:block; width:100%; height:100%; cursor:grab; }
              canvas.dragging { cursor:grabbing; }
              #side { width:340px; border-left:1px solid var(--line); background:var(--panel);
                      display:flex; flex-direction:column; overflow:hidden; }
              header { padding:12px 14px; border-bottom:1px solid var(--line); }
              h1 { font-size:14px; margin:0 0 2px; }
              .muted { color:var(--muted); font-size:12px; }
              .controls { padding:12px 14px; border-bottom:1px solid var(--line); display:grid; gap:10px; }
              input[type=search] { width:100%; padding:6px 8px; border:1px solid var(--line);
                      border-radius:6px; background:var(--bg); color:var(--fg); font:inherit; }
              label.row { display:flex; align-items:center; gap:8px; font-size:12px; }
              input[type=range] { flex:1; }
              #legend { padding:10px 14px; border-bottom:1px solid var(--line); display:flex;
                        flex-wrap:wrap; gap:6px; max-height:210px; overflow:auto; }
              .chip { display:flex; align-items:center; gap:5px; padding:3px 7px; border:1px solid var(--line);
                      border-radius:999px; font-size:11px; cursor:pointer; user-select:none; }
              .chip.off { opacity:.35; }
              .dot { width:9px; height:9px; border-radius:50%; }
              #details { padding:12px 14px; overflow:auto; flex:1; }
              #details h2 { font-size:13px; margin:0 0 6px; word-break:break-all; }
              .kv { display:grid; grid-template-columns:78px 1fr; gap:2px 8px; font-size:12px; margin-bottom:10px; }
              .kv span:first-child { color:var(--muted); }
              .edge { border-top:1px solid var(--line); padding:6px 0; font-size:12px; }
              .edge code { font-size:11px; word-break:break-all; }
              .badge { display:inline-block; padding:1px 6px; border-radius:4px; font-size:10px;
                       border:1px solid var(--line); margin-left:4px; }
              .proven { color:#15803d; } .inferred { color:#b45309; } .unresolved { color:#b91c1c; }
              footer { padding:8px 14px; border-top:1px solid var(--line); font-size:11px; color:var(--muted); }
            </style>
            </head>
            <body>
            <div id="app">
              <div id="stage"><canvas id="c"></canvas></div>
              <div id="side">
                <header>
                  <h1>__TITLE__</h1>
                  <div class="muted">__SUMMARY__</div>
                </header>
                <div class="controls">
                  <input type="search" id="q" placeholder="Search symbols, files, routes...">
                  <label class="row">min confidence <input type="range" id="conf" min="0" max="100" value="0"><span id="confv">0.00</span></label>
                  <label class="row"><input type="checkbox" id="labels" checked> show labels</label>
                </div>
                <div id="legend"></div>
                <div id="details"><div class="muted">Click a node to see its evidence. Drag to pan, scroll to zoom, drag a node to move it.</div></div>
                <footer>Dashed edges are inferred, not compiler-proven.</footer>
              </div>
            </div>
            <script>
            const DATA = __DATA__;
            const KIND_COLORS = {
              CONTROLLER:'#2563eb', SERVICE:'#7c3aed', REPOSITORY_COMPONENT:'#0891b2', ENTITY:'#059669',
              DATABASE_TABLE:'#047857', ENDPOINT:'#dc2626', TOPIC:'#ea580c', TRANSACTION:'#a16207',
              CONFIGURATION:'#4f46e5', SECURITY_GUARD:'#be123c', EXTERNAL_SERVICE:'#c026d3',
              CONFIGURATION_PROPERTY:'#0d9488', WORKFLOW:'#db2777', BUSINESS_CAPABILITY:'#9333ea',
              MODULE:'#475569', TYPE:'#64748b', INTERFACE:'#94a3b8', METHOD:'#6b7280', FIELD:'#9ca3af',
              PACKAGE:'#a3a3a3', FILE:'#d4d4d4', REPOSITORY:'#334155', EXTERNAL_SYMBOL:'#cbd5e1'
            };
            const color = k => KIND_COLORS[k] || '#94a3b8';

            // Deterministic PRNG: the same graph must produce the same picture on every open.
            function seeded(seed) {
              return function () {
                seed |= 0; seed = (seed + 0x6D2B79F5) | 0;
                let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
                t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
                return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
              };
            }

            const N = DATA.nodes.length;
            const rand = seeded(20260810);
            const px = new Float64Array(N), py = new Float64Array(N);
            const vx = new Float64Array(N), vy = new Float64Array(N);
            const deg = new Int32Array(N);
            for (const e of DATA.edges) { deg[e.s]++; deg[e.t]++; }
            for (let i = 0; i < N; i++) {
              const a = rand() * Math.PI * 2, r = 300 * Math.sqrt(rand());
              px[i] = Math.cos(a) * r; py[i] = Math.sin(a) * r;
            }

            // Fixed-iteration force layout. Cheap, deterministic, and good enough at this scale;
            // anything larger belongs in the JSON export and a real graph tool.
            (function layout() {
              // Repulsion is O(N^2) per iteration, so the iteration count comes down as N rises.
              // The product is held near a fixed budget: a big graph converges less, but it opens.
              const iterations = Math.max(60, Math.min(320, Math.floor(45000000 / Math.max(N * N, 1))));
              const k = Math.sqrt(360000 / Math.max(N, 1));
              for (let step = 0; step < iterations; step++) {
                const temperature = 1 - step / iterations;
                for (let i = 0; i < N; i++) { vx[i] = 0; vy[i] = 0; }
                for (let i = 0; i < N; i++) {
                  for (let j = i + 1; j < N; j++) {
                    let dx = px[i] - px[j], dy = py[i] - py[j];
                    let d2 = dx * dx + dy * dy;
                    if (d2 < 0.01) { dx = (rand() - 0.5) * 0.1; dy = (rand() - 0.5) * 0.1; d2 = 0.01; }
                    if (d2 > 640000) continue;
                    const f = (k * k) / d2;
                    vx[i] += dx * f; vy[i] += dy * f; vx[j] -= dx * f; vy[j] -= dy * f;
                  }
                }
                for (const e of DATA.edges) {
                  const dx = px[e.s] - px[e.t], dy = py[e.s] - py[e.t];
                  const d = Math.sqrt(dx * dx + dy * dy) || 0.01;
                  const f = (d * d) / k / d * 0.02;
                  vx[e.s] -= dx * f; vy[e.s] -= dy * f; vx[e.t] += dx * f; vy[e.t] += dy * f;
                }
                for (let i = 0; i < N; i++) {
                  vx[i] -= px[i] * 0.006; vy[i] -= py[i] * 0.006;
                  const speed = Math.sqrt(vx[i] * vx[i] + vy[i] * vy[i]) || 1;
                  const limit = Math.min(speed, 24 * temperature + 1) / speed;
                  px[i] += vx[i] * limit; py[i] += vy[i] * limit;
                }
              }
              // Normalize to a fixed extent. Without this the layout's absolute size depends on node
              // count, so a larger graph opens zoomed so far out that no label is legible.
              let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
              for (let i = 0; i < N; i++) {
                minX = Math.min(minX, px[i]); maxX = Math.max(maxX, px[i]);
                minY = Math.min(minY, py[i]); maxY = Math.max(maxY, py[i]);
              }
              const extent = Math.max(maxX - minX, maxY - minY, 1);
              const target = Math.min(1600, 220 + N * 11);
              const factor = target / extent;
              const cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
              for (let i = 0; i < N; i++) { px[i] = (px[i] - cx) * factor; py[i] = (py[i] - cy) * factor; }
            })();

            const canvas = document.getElementById('c');
            const ctx = canvas.getContext('2d');
            let scale = 1, offsetX = 0, offsetY = 0, selected = -1, hovered = -1;
            let minConfidence = 0, query = '', showLabels = true;
            const hiddenKinds = new Set();

            const kinds = [...new Set(DATA.nodes.map(n => n.kind))].sort();
            const legend = document.getElementById('legend');
            for (const kind of kinds) {
              const count = DATA.nodes.filter(n => n.kind === kind).length;
              const chip = document.createElement('div');
              chip.className = 'chip';
              chip.innerHTML = '<span class="dot" style="background:' + color(kind) + '"></span>' + kind + ' ' + count;
              chip.onclick = () => {
                if (hiddenKinds.has(kind)) { hiddenKinds.delete(kind); chip.classList.remove('off'); }
                else { hiddenKinds.add(kind); chip.classList.add('off'); }
                draw();
              };
              legend.appendChild(chip);
            }

            const visible = i => !hiddenKinds.has(DATA.nodes[i].kind);
            const matches = i => {
              if (!query) return false;
              const n = DATA.nodes[i];
              return (n.id + ' ' + n.name + ' ' + n.file).toLowerCase().includes(query);
            };
            const radius = i => Math.min(13, 4 + Math.sqrt(deg[i]) * 1.5);

            function resize() {
              const ratio = window.devicePixelRatio || 1;
              canvas.width = canvas.clientWidth * ratio;
              canvas.height = canvas.clientHeight * ratio;
              ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
              draw();
            }

            function fit() {
              let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
              for (let i = 0; i < N; i++) {
                minX = Math.min(minX, px[i]); maxX = Math.max(maxX, px[i]);
                minY = Math.min(minY, py[i]); maxY = Math.max(maxY, py[i]);
              }
              const w = canvas.clientWidth, h = canvas.clientHeight;
              scale = Math.min(w / (maxX - minX + 120), h / (maxY - minY + 120), 2.2) || 1;
              offsetX = w / 2 - ((minX + maxX) / 2) * scale;
              offsetY = h / 2 - ((minY + maxY) / 2) * scale;
            }

            function draw() {
              const w = canvas.clientWidth, h = canvas.clientHeight;
              ctx.clearRect(0, 0, w, h);
              const neighbours = new Set();
              if (selected >= 0) {
                neighbours.add(selected);
                for (const e of DATA.edges) {
                  if (e.s === selected) neighbours.add(e.t);
                  if (e.t === selected) neighbours.add(e.s);
                }
              }
              for (const e of DATA.edges) {
                if (e.c < minConfidence || !visible(e.s) || !visible(e.t)) continue;
                const focused = selected < 0 || e.s === selected || e.t === selected;
                ctx.globalAlpha = focused ? (e.c >= 0.95 ? 0.5 : 0.35) : 0.06;
                ctx.strokeStyle = e.c >= 0.95 ? '#94a3b8' : (e.c >= 0.7 ? '#f59e0b' : '#ef4444');
                ctx.lineWidth = focused && selected >= 0 ? 1.6 : 0.8;
                ctx.setLineDash(e.c >= 0.95 ? [] : [4, 3]);
                ctx.beginPath();
                ctx.moveTo(px[e.s] * scale + offsetX, py[e.s] * scale + offsetY);
                ctx.lineTo(px[e.t] * scale + offsetX, py[e.t] * scale + offsetY);
                ctx.stroke();
              }
              ctx.setLineDash([]); ctx.globalAlpha = 1;
              for (let i = 0; i < N; i++) {
                if (!visible(i)) continue;
                const x = px[i] * scale + offsetX, y = py[i] * scale + offsetY;
                const dim = selected >= 0 && !neighbours.has(i);
                ctx.globalAlpha = dim ? 0.12 : 1;
                ctx.beginPath();
                ctx.arc(x, y, radius(i), 0, Math.PI * 2);
                ctx.fillStyle = color(DATA.nodes[i].kind);
                ctx.fill();
                if (i === selected || i === hovered || matches(i)) {
                  ctx.lineWidth = 2.5;
                  ctx.strokeStyle = matches(i) && i !== selected ? '#f59e0b' : '#111827';
                  ctx.stroke();
                }
                if (showLabels && !dim && (scale > 0.75 || deg[i] > 6 || i === selected)) {
                  ctx.globalAlpha = dim ? 0.2 : 0.85;
                  ctx.fillStyle = getComputedStyle(document.body).color;
                  ctx.font = '11px ui-sans-serif, system-ui, sans-serif';
                  ctx.fillText(DATA.nodes[i].name, x + radius(i) + 3, y + 3);
                }
              }
              ctx.globalAlpha = 1;
            }

            function nodeAt(clientX, clientY) {
              const rect = canvas.getBoundingClientRect();
              const x = clientX - rect.left, y = clientY - rect.top;
              for (let i = N - 1; i >= 0; i--) {
                if (!visible(i)) continue;
                const dx = x - (px[i] * scale + offsetX), dy = y - (py[i] * scale + offsetY);
                if (dx * dx + dy * dy <= (radius(i) + 3) * (radius(i) + 3)) return i;
              }
              return -1;
            }

            function confidenceClass(c) { return c >= 0.95 ? 'proven' : (c >= 0.7 ? 'inferred' : 'unresolved'); }

            function select(i) {
              selected = i;
              const details = document.getElementById('details');
              if (i < 0) { details.innerHTML = '<div class="muted">Click a node to see its evidence.</div>'; draw(); return; }
              const n = DATA.nodes[i];
              let html = '<h2>' + n.name + '</h2><div class="kv">'
                + '<span>kind</span><span>' + n.kind + '</span>'
                + '<span>id</span><span><code>' + n.id + '</code></span>'
                + '<span>declared</span><span>' + (n.file ? n.file + ':' + n.line : 'no source location') + '</span>'
                + '<span>resolver</span><span>' + n.resolver + '</span></div>';
              const out = DATA.edges.filter(e => e.s === i), inc = DATA.edges.filter(e => e.t === i);
              const row = (e, other, arrow) => '<div class="edge">' + arrow + ' <strong>' + e.r + '</strong> '
                + DATA.nodes[other].name + '<span class="badge ' + confidenceClass(e.c) + '">' + e.v + ' ' + e.c.toFixed(2) + '</span>'
                + '<br><code>' + (e.f ? e.f + ':' + e.l : 'no location') + '</code></div>';
              html += '<div class="muted">' + out.length + ' outgoing</div>';
              out.slice(0, 40).forEach(e => html += row(e, e.t, '&rarr;'));
              html += '<div class="muted" style="margin-top:8px">' + inc.length + ' incoming</div>';
              inc.slice(0, 40).forEach(e => html += row(e, e.s, '&larr;'));
              details.innerHTML = html;
              draw();
            }

            let dragging = false, draggingNode = -1, lastX = 0, lastY = 0;
            canvas.addEventListener('mousedown', event => {
              lastX = event.clientX; lastY = event.clientY;
              draggingNode = nodeAt(event.clientX, event.clientY);
              dragging = true;
              canvas.classList.add('dragging');
            });
            window.addEventListener('mousemove', event => {
              if (!dragging) {
                const over = nodeAt(event.clientX, event.clientY);
                if (over !== hovered) { hovered = over; canvas.title = over >= 0 ? DATA.nodes[over].id : ''; draw(); }
                return;
              }
              const dx = event.clientX - lastX, dy = event.clientY - lastY;
              lastX = event.clientX; lastY = event.clientY;
              if (draggingNode >= 0) { px[draggingNode] += dx / scale; py[draggingNode] += dy / scale; }
              else { offsetX += dx; offsetY += dy; }
              draw();
            });
            window.addEventListener('mouseup', event => {
              if (dragging && draggingNode >= 0 && Math.abs(event.clientX - lastX) < 3) select(draggingNode);
              dragging = false; draggingNode = -1; canvas.classList.remove('dragging');
            });
            canvas.addEventListener('click', event => {
              const i = nodeAt(event.clientX, event.clientY);
              if (i >= 0 || selected >= 0) select(i);
            });
            canvas.addEventListener('wheel', event => {
              event.preventDefault();
              const rect = canvas.getBoundingClientRect();
              const x = event.clientX - rect.left, y = event.clientY - rect.top;
              const factor = event.deltaY < 0 ? 1.12 : 1 / 1.12;
              offsetX = x - (x - offsetX) * factor;
              offsetY = y - (y - offsetY) * factor;
              scale *= factor;
              draw();
            }, { passive: false });

            document.getElementById('q').addEventListener('input', event => {
              query = event.target.value.trim().toLowerCase();
              draw();
            });
            document.getElementById('conf').addEventListener('input', event => {
              minConfidence = event.target.value / 100;
              document.getElementById('confv').textContent = minConfidence.toFixed(2);
              draw();
            });
            document.getElementById('labels').addEventListener('change', event => {
              showLabels = event.target.checked; draw();
            });
            window.addEventListener('resize', resize);
            resize(); fit(); draw();
            </script>
            </body>
            </html>
            """;
}
