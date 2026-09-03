#!/usr/bin/env python3
"""
Visualization Generator for Terrakube Registry Performance Benchmark.
Generates vector SVG time-series and comparison diagrams for latency, throughput,
JVM memory/GC, and container footprint.

All chart generators accept optional candidate data; when omitted only the baseline
series is rendered. JVM memory and container RSS charts use periodic time-series
snapshots collected during the run for rich over-time plots.
"""

import os
from typing import Dict, Any, List, Optional

# ── colour palette ────────────────────────────────────────────────────────────
_BG          = "#0d1117"
_PANEL       = "#161b22"
_GRID        = "#21262d"
_AXIS        = "#30363d"
_TEXT        = "#c9d1d9"
_MUTED       = "#8b949e"
_BLUE        = "#388bfd"
_BLUE_LIGHT  = "#58a6ff"
_GREEN       = "#2ea043"
_GREEN_LIGHT = "#3fb950"
_PURPLE      = "#8957e5"
_PURPLE_L    = "#bc8cff"
_ORANGE      = "#f0883e"
_ORANGE_L    = "#ffa657"
_RED         = "#f85149"

# ── helpers ───────────────────────────────────────────────────────────────────

def _svg_open(w: int, h: int) -> str:
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" '
        f'width="100%" height="{h}" '
        f'style="background:{_BG}; font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;">'
    )


def _title(cx: float, text: str) -> str:
    return f'<text x="{cx}" y="35" text-anchor="middle" fill="{_BLUE_LIGHT}" font-size="18" font-weight="bold">{text}</text>'


def _grid_h(x1: float, x2: float, y: float) -> str:
    return f'<line x1="{x1}" y1="{y}" x2="{x2}" y2="{y}" stroke="{_GRID}" stroke-dasharray="4"/>'


def _grid_v(x: float, y1: float, y2: float) -> str:
    return f'<line x1="{x}" y1="{y1}" x2="{x}" y2="{y2}" stroke="{_GRID}" stroke-dasharray="4"/>'


def _axis_h(x1: float, y: float, x2: float) -> str:
    return f'<line x1="{x1}" y1="{y}" x2="{x2}" y2="{y}" stroke="{_AXIS}" stroke-width="2"/>'


def _axis_v(x: float, y1: float, y2: float) -> str:
    return f'<line x1="{x}" y1="{y1}" x2="{x}" y2="{y2}" stroke="{_AXIS}" stroke-width="2"/>'


def _polyline(points: List, stroke: str, width: int = 3, dash: str = "") -> str:
    pts = " ".join(f"{x:.1f},{y:.1f}" for x, y in points)
    da = f' stroke-dasharray="{dash}"' if dash else ""
    return f'<polyline points="{pts}" fill="none" stroke="{stroke}" stroke-width="{width}"{da} stroke-linejoin="round"/>'


def _dot(cx: float, cy: float, r: int, fill: str) -> str:
    return f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="{r}" fill="{fill}"/>'


def _label(x: float, y: float, text: str, fill: str, size: int = 11,
           anchor: str = "middle", weight: str = "normal") -> str:
    return (
        f'<text x="{x:.1f}" y="{y:.1f}" text-anchor="{anchor}" '
        f'fill="{fill}" font-size="{size}" font-weight="{weight}">{text}</text>'
    )


def _rect_panel(x: float, y: float, w: float, h: float) -> str:
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="8" fill="{_PANEL}" stroke="{_AXIS}"/>'


def _badge(cx: float, cy: float, w: float, delta: float) -> str:
    color = _GREEN if delta <= 0 else _RED
    sign = "+" if delta > 0 else ""
    x = cx - w / 2
    return (
        f'<rect x="{x:.1f}" y="{cy:.1f}" width="{w:.1f}" height="18" rx="9" fill="{color}" fill-opacity="0.2"/>'
        f'<text x="{cx:.1f}" y="{cy + 13:.1f}" text-anchor="middle" fill="{color}" '
        f'font-size="11" font-weight="bold">{sign}{delta:.1f}%</text>'
    )


def _legend_line(x: float, y: float, stroke: str, dash: str, label: str) -> str:
    da = f' stroke-dasharray="{dash}"' if dash else ""
    return (
        f'<line x1="{x}" y1="{y}" x2="{x + 36}" y2="{y}" stroke="{stroke}" stroke-width="3"{da}/>'
        f'<text x="{x + 44}" y="{y + 4}" fill="{_MUTED}" font-size="12">{label}</text>'
    )


# ── main class ────────────────────────────────────────────────────────────────

class Visualizer:
    def __init__(self, output_dir: str):
        self.output_dir = output_dir
        os.makedirs(self.output_dir, exist_ok=True)

    # ── 1. Latency chart ─────────────────────────────────────────────────────

    def generate_latency_chart_svg(
        self,
        baseline_results: List[Any],
        candidate_results: Optional[List[Any]] = None,
    ) -> str:
        """
        Generates a grouped bar chart of p50/p90/p95/p99 latency per endpoint,
        with one stacked sub-panel per concurrency level.
        Candidate bars are added alongside baseline bars when provided.
        """
        svg_path = os.path.join(self.output_dir, "latency_comparison_p95_p99.svg")

        concurrencies = sorted(set(r.concurrency for r in baseline_results))
        if not concurrencies:
            concurrencies = [5, 10, 25]

        endpoints = list(dict.fromkeys(r.endpoint_name for r in baseline_results))
        has_candidate = bool(candidate_results)

        # Colour scheme: p50=blue, p90=purple, p95=orange, p99=red; candidate variant uses lighter shades
        _pct_colors = [_BLUE, _PURPLE, _ORANGE, _RED]
        _pct_labels = ["p50", "p90", "p95", "p99"]
        _pct_keys   = ["latency_p50_ms", "latency_p90_ms", "latency_p95_ms", "latency_p99_ms"]
        _cand_colors = [_BLUE_LIGHT, _PURPLE_L, _ORANGE_L, _GREEN_LIGHT]

        n_pct      = 4
        n_ep       = len(endpoints)
        n_series   = 2 if has_candidate else 1  # baseline + optional candidate

        # Layout constants
        cw            = 1100
        pl, pr        = 72, 24
        pt_header     = 54        # space for overall title
        panel_h       = 220       # height of each concurrency sub-panel
        panel_gap     = 32
        pb            = 28        # bottom padding
        ep_label_h    = 52        # space below bars for rotated endpoint labels
        bar_area_h    = panel_h - ep_label_h
        pw            = cw - pl - pr
        n_panels      = len(concurrencies)
        ch            = pt_header + n_panels * panel_h + (n_panels - 1) * panel_gap + pb

        # Width of one endpoint slot
        slot_w = pw / n_ep
        # Bars inside a slot: n_pct bars × n_series side-by-side, small gap between series
        series_gap   = 4
        total_bar_w  = slot_w * 0.82
        bar_w        = total_bar_w / (n_pct * n_series + (n_series - 1) * 0.5)

        svg = [_svg_open(cw, ch),
               _title(cw / 2, "Latency Percentiles (p50 / p90 / p95 / p99) per Endpoint")]

        b_lookup = {(r.endpoint_name, r.concurrency): r for r in baseline_results}
        c_lookup = {(r.endpoint_name, r.concurrency): r for r in (candidate_results or [])}

        for pi, conc in enumerate(concurrencies):
            py = pt_header + pi * (panel_h + panel_gap)

            # Gather max value for Y scale in this panel
            panel_vals = []
            for ep in endpoints:
                for key in _pct_keys:
                    br = b_lookup.get((ep, conc))
                    if br:
                        panel_vals.append(getattr(br, key, 0.0))
                    if has_candidate:
                        cr = c_lookup.get((ep, conc))
                        if cr:
                            panel_vals.append(getattr(cr, key, 0.0))
            max_v = max(max(panel_vals) * 1.18, 10.0) if panel_vals else 10.0

            svg.append(f'<g transform="translate({pl},{py:.1f})">')

            # Panel background
            svg.append(f'<rect x="0" y="0" width="{pw}" height="{panel_h}" rx="6" fill="{_PANEL}" stroke="{_AXIS}"/>')

            # Panel title
            svg.append(_label(pw / 2, 18, f"{conc} Workers", _BLUE_LIGHT, size=13, weight="bold"))

            # Y grid lines (4 lines)
            for gi in range(5):
                gv  = (max_v / 4) * gi
                gy  = bar_area_h - (gv / max_v) * (bar_area_h - 26) + 24
                svg.append(_grid_h(0, pw, gy))
                svg.append(_label(-4, gy + 4, f"{gv:.0f}ms", _MUTED, size=8, anchor="end"))

            # Axes
            svg.append(_axis_v(0, 24, panel_h - ep_label_h))
            svg.append(_axis_h(0, panel_h - ep_label_h, pw))

            # Draw bars per endpoint
            for ei, ep in enumerate(endpoints):
                slot_cx  = (ei + 0.5) * slot_w
                slot_x0  = slot_cx - total_bar_w / 2

                br = b_lookup.get((ep, conc))
                cr = c_lookup.get((ep, conc)) if has_candidate else None

                for si, (series_r, colors) in enumerate([
                    (br, _pct_colors),
                    *([(cr, _cand_colors)] if has_candidate else []),
                ]):
                    series_offset = si * (n_pct * bar_w + series_gap)
                    for ki, (key, col) in enumerate(zip(_pct_keys, colors)):
                        bx = slot_x0 + series_offset + ki * bar_w
                        val = getattr(series_r, key, 0.0) if series_r else 0.0
                        bh = (val / max_v) * (bar_area_h - 26) if max_v > 0 else 0
                        by = bar_area_h - bh + 24
                        svg.append(f'<rect x="{bx:.1f}" y="{by:.1f}" width="{bar_w:.1f}" height="{bh:.1f}" rx="2" fill="{col}" fill-opacity="0.88"/>')
                        if bh > 14:
                            svg.append(_label(bx + bar_w / 2, by - 2, f"{val:.0f}", col, size=7))

                # Rotated endpoint label below axis
                lx = slot_cx
                ly = panel_h - ep_label_h + 8
                short = ep.replace("_", " ")
                svg.append(
                    f'<text x="{lx:.1f}" y="{ly:.1f}" text-anchor="end" '
                    f'fill="{_TEXT}" font-size="9" '
                    f'transform="rotate(-38,{lx:.1f},{ly:.1f})">{short}</text>'
                )

            svg.append("</g>")

        # Legend
        ly   = ch - 14
        lgap = 56
        lx0  = pl
        series_labels = ["Baseline", "Candidate"] if has_candidate else ["Baseline"]
        for si, (sl, sc_set) in enumerate(zip(series_labels, [_pct_colors, _cand_colors])):
            base_x = lx0 + si * (n_pct * lgap + 60)
            svg.append(_label(base_x, ly, sl + ":", _MUTED, size=10, anchor="start"))
            for ki, (lbl, col) in enumerate(zip(_pct_labels, sc_set)):
                rx = base_x + 52 + ki * lgap
                svg.append(f'<rect x="{rx}" y="{ly - 9}" width="12" height="10" rx="2" fill="{col}"/>')
                svg.append(_label(rx + 16, ly, lbl, _TEXT, size=10, anchor="start"))

        svg.append("</svg>")
        with open(svg_path, "w", encoding="utf-8") as f:
            f.write("\n".join(svg))
        return svg_path

    # ── 2. Throughput chart ──────────────────────────────────────────────────

    def generate_throughput_chart_svg(
        self,
        baseline_results: List[Any],
        candidate_results: Optional[List[Any]] = None,
    ) -> str:
        """
        Generates RPS bar charts per endpoint, with one stacked sub-panel per
        concurrency level. Candidate bars are rendered alongside baseline bars
        when provided.
        """
        svg_path = os.path.join(self.output_dir, "throughput_vs_concurrency.svg")

        concurrencies = sorted(set(r.concurrency for r in baseline_results))
        if not concurrencies:
            concurrencies = [5, 10, 25]

        endpoints     = list(dict.fromkeys(r.endpoint_name for r in baseline_results))
        has_candidate = bool(candidate_results)

        # Layout constants
        cw            = 1100
        pl, pr        = 72, 24
        pt_header     = 54
        panel_h       = 220
        panel_gap     = 32
        pb            = 28
        ep_label_h    = 52
        bar_area_h    = panel_h - ep_label_h
        pw            = cw - pl - pr
        n_panels      = len(concurrencies)
        ch            = pt_header + n_panels * panel_h + (n_panels - 1) * panel_gap + pb

        slot_w        = pw / len(endpoints)
        n_series      = 2 if has_candidate else 1
        bar_w         = slot_w * 0.35
        series_gap    = slot_w * 0.08

        b_lookup = {(r.endpoint_name, r.concurrency): r.rps for r in baseline_results}
        c_lookup = {(r.endpoint_name, r.concurrency): r.rps for r in (candidate_results or [])}

        svg = [_svg_open(cw, ch),
               _title(cw / 2, "Throughput (RPS) per Endpoint across Concurrency Levels")]

        for pi, conc in enumerate(concurrencies):
            py = pt_header + pi * (panel_h + panel_gap)

            panel_vals = [b_lookup.get((ep, conc), 0.0) for ep in endpoints]
            if has_candidate:
                panel_vals += [c_lookup.get((ep, conc), 0.0) for ep in endpoints]
            max_v = max(max(panel_vals) * 1.18, 1.0) if panel_vals else 1.0

            svg.append(f'<g transform="translate({pl},{py:.1f})">')
            svg.append(f'<rect x="0" y="0" width="{pw}" height="{panel_h}" rx="6" fill="{_PANEL}" stroke="{_AXIS}"/>')
            svg.append(_label(pw / 2, 18, f"{conc} Workers", _BLUE_LIGHT, size=13, weight="bold"))

            # Y grid
            for gi in range(5):
                gv = (max_v / 4) * gi
                gy = bar_area_h - (gv / max_v) * (bar_area_h - 26) + 24
                svg.append(_grid_h(0, pw, gy))
                svg.append(_label(-4, gy + 4, f"{gv:.0f}", _MUTED, size=8, anchor="end"))

            svg.append(_axis_v(0, 24, panel_h - ep_label_h))
            svg.append(_axis_h(0, panel_h - ep_label_h, pw))

            for ei, ep in enumerate(endpoints):
                slot_cx   = (ei + 0.5) * slot_w
                total_bars_w = bar_w * n_series + (series_gap if has_candidate else 0)
                bx_base   = slot_cx - total_bars_w / 2

                # Baseline bar
                b_val = b_lookup.get((ep, conc), 0.0)
                bh    = (b_val / max_v) * (bar_area_h - 26) if max_v > 0 else 0
                by    = bar_area_h - bh + 24
                svg.append(f'<rect x="{bx_base:.1f}" y="{by:.1f}" width="{bar_w:.1f}" height="{bh:.1f}" rx="2" fill="{_BLUE}" fill-opacity="0.88"/>')
                if bh > 14:
                    svg.append(_label(bx_base + bar_w / 2, by - 3, f"{b_val:.1f}", _BLUE_LIGHT, size=8, weight="bold"))

                # Candidate bar (if present)
                if has_candidate:
                    c_val = c_lookup.get((ep, conc), 0.0)
                    ch_b  = (c_val / max_v) * (bar_area_h - 26) if max_v > 0 else 0
                    cy_b  = bar_area_h - ch_b + 24
                    cx_b  = bx_base + bar_w + series_gap
                    svg.append(f'<rect x="{cx_b:.1f}" y="{cy_b:.1f}" width="{bar_w:.1f}" height="{ch_b:.1f}" rx="2" fill="{_GREEN}" fill-opacity="0.88"/>')
                    if ch_b > 14:
                        svg.append(_label(cx_b + bar_w / 2, cy_b - 3, f"{c_val:.1f}", _GREEN_LIGHT, size=8, weight="bold"))

                # Rotated endpoint label
                lx = slot_cx
                ly = panel_h - ep_label_h + 8
                short = ep.replace("_", " ")
                svg.append(
                    f'<text x="{lx:.1f}" y="{ly:.1f}" text-anchor="end" '
                    f'fill="{_TEXT}" font-size="9" '
                    f'transform="rotate(-38,{lx:.1f},{ly:.1f})">{short}</text>'
                )

            svg.append("</g>")

        # Legend
        ly  = ch - 14
        svg.append(f'<rect x="{pl}" y="{ly - 9}" width="12" height="10" rx="2" fill="{_BLUE}"/>')
        svg.append(_label(pl + 16, ly, "Baseline RPS", _TEXT, size=10, anchor="start"))
        if has_candidate:
            svg.append(f'<rect x="{pl + 110}" y="{ly - 9}" width="12" height="10" rx="2" fill="{_GREEN}"/>')
            svg.append(_label(pl + 126, ly, "Candidate RPS", _TEXT, size=10, anchor="start"))

        svg.append("</svg>")
        with open(svg_path, "w", encoding="utf-8") as f:
            f.write("\n".join(svg))
        return svg_path

    # ── 2b. Throughput Bytes/sec chart ──────────────────────────────────────

    def generate_throughput_bytes_chart_svg(
        self,
        baseline_results: List[Any],
        candidate_results: Optional[List[Any]] = None,
    ) -> str:
        """
        Generates throughput (MB/s) bar charts per endpoint, with one stacked sub-panel
        per concurrency level. Candidate bars are rendered alongside baseline bars when provided.
        """
        svg_path = os.path.join(self.output_dir, "throughput_bytes_sec.svg")

        concurrencies = sorted(set(r.concurrency for r in baseline_results))
        if not concurrencies:
            concurrencies = [5, 10, 25]

        endpoints     = list(dict.fromkeys(r.endpoint_name for r in baseline_results))
        has_candidate = bool(candidate_results)

        # Layout constants
        cw            = 1100
        pl, pr        = 72, 24
        pt_header     = 54
        panel_h       = 220
        panel_gap     = 32
        pb            = 28
        ep_label_h    = 52
        bar_area_h    = panel_h - ep_label_h
        pw            = cw - pl - pr
        n_panels      = len(concurrencies)
        ch            = pt_header + n_panels * panel_h + (n_panels - 1) * panel_gap + pb

        slot_w        = pw / len(endpoints)
        n_series      = 2 if has_candidate else 1
        bar_w         = slot_w * 0.35
        series_gap    = slot_w * 0.08

        def get_mb_s(r_obj: Any) -> float:
            tb = getattr(r_obj, "throughput_bytes_sec", 0.0)
            if tb > 0:
                return round(tb / (1024 * 1024), 2)
            dur = getattr(r_obj, "duration_seconds", 0.0)
            tot_bytes = getattr(r_obj, "total_bytes_transferred", 0)
            return round((tot_bytes / dur) / (1024 * 1024), 2) if dur > 0 else 0.0

        b_lookup = {(r.endpoint_name, r.concurrency): get_mb_s(r) for r in baseline_results}
        c_lookup = {(r.endpoint_name, r.concurrency): get_mb_s(r) for r in (candidate_results or [])}

        svg = [_svg_open(cw, ch),
               _title(cw / 2, "Throughput (MB/s) per Endpoint across Concurrency Levels")]

        for pi, conc in enumerate(concurrencies):
            py = pt_header + pi * (panel_h + panel_gap)

            panel_vals = [b_lookup.get((ep, conc), 0.0) for ep in endpoints]
            if has_candidate:
                panel_vals += [c_lookup.get((ep, conc), 0.0) for ep in endpoints]
            max_v = max(max(panel_vals) * 1.18, 0.5) if panel_vals else 0.5

            svg.append(f'<g transform="translate({pl},{py:.1f})">')
            svg.append(f'<rect x="0" y="0" width="{pw}" height="{panel_h}" rx="6" fill="{_PANEL}" stroke="{_AXIS}"/>')
            svg.append(_label(pw / 2, 18, f"{conc} Workers", _BLUE_LIGHT, size=13, weight="bold"))

            # Y grid
            for gi in range(5):
                gv = (max_v / 4) * gi
                gy = bar_area_h - (gv / max_v) * (bar_area_h - 26) + 24
                svg.append(_grid_h(0, pw, gy))
                svg.append(_label(-4, gy + 4, f"{gv:.2f} MB/s", _MUTED, size=8, anchor="end"))

            svg.append(_axis_v(0, 24, panel_h - ep_label_h))
            svg.append(_axis_h(0, panel_h - ep_label_h, pw))

            for ei, ep in enumerate(endpoints):
                slot_cx   = (ei + 0.5) * slot_w
                total_bars_w = bar_w * n_series + (series_gap if has_candidate else 0)
                bx_base   = slot_cx - total_bars_w / 2

                # Baseline bar
                b_val = b_lookup.get((ep, conc), 0.0)
                bh    = (b_val / max_v) * (bar_area_h - 26) if max_v > 0 else 0
                by    = bar_area_h - bh + 24
                svg.append(f'<rect x="{bx_base:.1f}" y="{by:.1f}" width="{bar_w:.1f}" height="{bh:.1f}" rx="2" fill="{_BLUE}" fill-opacity="0.88"/>')
                if bh > 14:
                    svg.append(_label(bx_base + bar_w / 2, by - 3, f"{b_val:.2f}", _BLUE_LIGHT, size=8, weight="bold"))

                # Candidate bar (if present)
                if has_candidate:
                    c_val = c_lookup.get((ep, conc), 0.0)
                    ch_b  = (c_val / max_v) * (bar_area_h - 26) if max_v > 0 else 0
                    cy_b  = bar_area_h - ch_b + 24
                    cx_b  = bx_base + bar_w + series_gap
                    svg.append(f'<rect x="{cx_b:.1f}" y="{cy_b:.1f}" width="{bar_w:.1f}" height="{ch_b:.1f}" rx="2" fill="{_GREEN}" fill-opacity="0.88"/>')
                    if ch_b > 14:
                        svg.append(_label(cx_b + bar_w / 2, cy_b - 3, f"{c_val:.2f}", _GREEN_LIGHT, size=8, weight="bold"))

                # Rotated endpoint label
                lx = slot_cx
                ly = panel_h - ep_label_h + 8
                short = ep.replace("_", " ")
                svg.append(
                    f'<text x="{lx:.1f}" y="{ly:.1f}" text-anchor="end" '
                    f'fill="{_TEXT}" font-size="9" '
                    f'transform="rotate(-38,{lx:.1f},{ly:.1f})">{short}</text>'
                )

            svg.append("</g>")

        # Legend
        ly  = ch - 14
        svg.append(f'<rect x="{pl}" y="{ly - 9}" width="12" height="10" rx="2" fill="{_BLUE}"/>')
        svg.append(_label(pl + 16, ly, "Baseline MB/s", _TEXT, size=10, anchor="start"))
        if has_candidate:
            svg.append(f'<rect x="{pl + 120}" y="{ly - 9}" width="12" height="10" rx="2" fill="{_GREEN}"/>')
            svg.append(_label(pl + 136, ly, "Candidate MB/s", _TEXT, size=10, anchor="start"))

        svg.append("</svg>")
        with open(svg_path, "w", encoding="utf-8") as f:
            f.write("\n".join(svg))
        return svg_path


    # ── 3. JVM memory & GC time-series chart ─────────────────────────────────

    def generate_jvm_memory_chart_svg(
        self,
        b_telemetry: Dict[str, Any],
        c_telemetry: Optional[Dict[str, Any]] = None,
        b_timeseries: Optional[List[Dict[str, Any]]] = None,
        c_timeseries: Optional[List[Dict[str, Any]]] = None,
    ) -> str:
        """
        Generates a time-series line chart for JVM heap, committed memory, and GC pause.
        When timeseries data is available it is plotted as smooth curves over elapsed time.
        Candidate series omitted when not provided. Falls back to bar comparison when no
        timeseries data is available.
        """
        svg_path = os.path.join(self.output_dir, "jvm_memory_and_gc.svg")

        has_ts = bool(b_timeseries)
        has_candidate = c_telemetry is not None

        if has_ts:
            svg_path = self._jvm_timeseries_svg(svg_path, b_timeseries, c_timeseries if has_candidate else None)
        else:
            svg_path = self._jvm_bar_svg(svg_path, b_telemetry, c_telemetry if has_candidate else None)
        return svg_path

    def _jvm_timeseries_svg(
        self,
        svg_path: str,
        b_ts: List[Dict[str, Any]],
        c_ts: Optional[List[Dict[str, Any]]],
    ) -> str:
        """SVG time-series line chart — one sub-panel per metric."""

        metrics_cfg = [
            ("Heap Used (MB)",      "heap_used_mb",              1.0),
            ("Heap Committed (MB)", "heap_committed_mb",         1.0),
            ("GC Pause Total (ms)", "gc_duration_seconds_total", 1000.0),
            ("RSS Memory (MB)",     "container_memory_rss_mb",   1.0),
            ("JVM CPU Util (%)",    "cpu_recent_utilization_pct", 1.0),
        ]

        cw, ch = 920, 680
        pl, pr, pt_top, pb = 72, 30, 55, 50
        n_panels = len(metrics_cfg)
        gap = 14
        panel_h = (ch - pt_top - pb - gap * (n_panels - 1)) / n_panels
        pw = cw - pl - pr
        has_candidate = c_ts is not None and len(c_ts) > 0

        svg = [_svg_open(cw, ch), _title(cw / 2, "JVM Runtime &amp; GC Metrics — Over Time")]

        for pi, (title, key, scale) in enumerate(metrics_cfg):
            py = pt_top + pi * (panel_h + gap)

            b_vals = [s.get(key, 0.0) * scale for s in b_ts]
            b_times = [s.get("t", 0.0) for s in b_ts]
            c_vals = [s.get(key, 0.0) * scale for s in c_ts] if has_candidate else []
            c_times = [s.get("t", 0.0) for s in c_ts] if has_candidate else []

            all_vals = b_vals + c_vals
            max_v = max(max(all_vals) * 1.2, 1.0) if all_vals else 1.0
            all_t = b_times + c_times
            max_t = max(max(all_t), 1.0) if all_t else 1.0

            svg.append(f'<g transform="translate({pl},{py:.1f})">')
            svg.append(_rect_panel(0, 0, pw, panel_h))
            svg.append(_label(pw / 2, 18, title, _TEXT, size=12, weight="bold"))

            # Y grid (3 lines)
            for gi in range(4):
                gv = (max_v / 3) * gi
                gy = panel_h - (gv / max_v) * (panel_h - 26) - 8
                svg.append(_grid_h(0, pw, gy))
                svg.append(_label(-6, gy + 4, f"{gv:.0f}", _MUTED, size=9, anchor="end"))

            # Baseline series
            if b_vals and b_times:
                pts_b = [
                    (
                        (t / max_t) * pw,
                        panel_h - (v / max_v) * (panel_h - 26) - 8,
                    )
                    for t, v in zip(b_times, b_vals)
                ]
                svg.append(_polyline(pts_b, _BLUE, width=2))
                for x, y in pts_b:
                    svg.append(_dot(x, y, 3, _BLUE_LIGHT))

            # Candidate series
            if has_candidate and c_vals and c_times:
                pts_c = [
                    (
                        (t / max_t) * pw,
                        panel_h - (v / max_v) * (panel_h - 26) - 8,
                    )
                    for t, v in zip(c_times, c_vals)
                ]
                svg.append(_polyline(pts_c, _GREEN, width=2, dash="5,3"))
                for x, y in pts_c:
                    svg.append(_dot(x, y, 3, _GREEN_LIGHT))

            # X axis time labels at start / mid / end
            for frac, t_label in [(0.0, "0s"), (0.5, f"{max_t/2:.0f}s"), (1.0, f"{max_t:.0f}s")]:
                svg.append(_label(frac * pw, panel_h + 2, t_label, _MUTED, size=9))

            svg.append("</g>")

        # Legend
        ly = ch - 18
        svg.append(_legend_line(pl, ly, _BLUE, "", "Baseline"))
        if has_candidate:
            svg.append(_legend_line(pl + 180, ly, _GREEN, "5,3", "Candidate"))

        svg.append("</svg>")
        with open(svg_path, "w", encoding="utf-8") as f:
            f.write("\n".join(svg))
        return svg_path

    def _jvm_bar_svg(
        self,
        svg_path: str,
        b_telemetry: Dict[str, Any],
        c_telemetry: Optional[Dict[str, Any]],
    ) -> str:
        """Fallback bar chart when no timeseries data is available."""
        has_candidate = c_telemetry is not None
        cw, ch = 880, 420
        svg = [
            _svg_open(cw, ch),
            _title(cw / 2, "JVM Runtime &amp; Garbage Collection Footprint"),
        ]
        metrics = [
            ("Heap Used (MB)",       "heap_used_mb",              1.0),
            ("Heap Committed (MB)",  "heap_committed_mb",         1.0),
            ("GC Pause Total (ms)",  "gc_duration_seconds_total", 1000.0),
            ("Avg GC Pause (ms)",    "gc_avg_pause_ms",           1.0),
        ]
        panel_w = 175
        panel_h = 300
        start_y = 65
        for i, (title, key, scale) in enumerate(metrics):
            b_val = b_telemetry.get(key, 0.0) * scale
            c_val = c_telemetry.get(key, 0.0) * scale if has_candidate else 0.0
            px = 50 + i * 200
            svg.append(f'<g transform="translate({px},{start_y})">')
            svg.append(_rect_panel(0, 0, panel_w, panel_h))
            svg.append(_label(panel_w / 2, 28, title, _TEXT, size=13, weight="bold"))
            panel_max = max(b_val, c_val, 1.0) * 1.3
            bar_max_h = 170
            b_h = (b_val / panel_max) * bar_max_h
            svg.append(f'<rect x="25" y="{240 - b_h:.1f}" width="50" height="{b_h:.1f}" rx="4" fill="{_BLUE}"/>')
            svg.append(_label(50, 230 - b_h, f"{b_val:.1f}", _BLUE_LIGHT, size=12, weight="bold"))
            svg.append(_label(50, 260, "Baseline", _MUTED, size=11))
            if has_candidate:
                c_h = (c_val / panel_max) * bar_max_h
                svg.append(f'<rect x="100" y="{240 - c_h:.1f}" width="50" height="{c_h:.1f}" rx="4" fill="{_GREEN}"/>')
                svg.append(_label(125, 230 - c_h, f"{c_val:.1f}", _GREEN_LIGHT, size=12, weight="bold"))
                svg.append(_label(125, 260, "Candidate", _MUTED, size=11))
                delta = ((c_val - b_val) / b_val * 100) if b_val > 0 else 0.0
                svg.append(_badge(87, 275, 105, delta))
            svg.append("</g>")
        svg.append("</svg>")
        with open(svg_path, "w", encoding="utf-8") as f:
            f.write("\n".join(svg))
        return svg_path

    # ── 4. Container footprint chart ─────────────────────────────────────────

    def generate_footprint_chart_svg(
        self,
        b_size_mb: float,
        b_start_s: float,
        b_telemetry: Dict[str, Any],
        c_size_mb: Optional[float] = None,
        c_start_s: Optional[float] = None,
        c_telemetry: Optional[Dict[str, Any]] = None,
        b_timeseries: Optional[List[Dict[str, Any]]] = None,
        c_timeseries: Optional[List[Dict[str, Any]]] = None,
    ) -> str:
        """
        Generates container footprint chart.
        - Image size and cold startup: static bar panels.
        - RSS working-set memory: time-series line chart from periodic snapshots.
        Candidate panels/series omitted when not provided.
        """
        svg_path = os.path.join(self.output_dir, "container_cpu_and_startup.svg")
        has_candidate = c_size_mb is not None and c_start_s is not None
        has_ts = bool(b_timeseries)

        cw, ch = 920, 460
        pl, pr, pt, pb = 50, 30, 55, 55
        pw = cw - pl - pr
        ph = ch - pt - pb

        svg = [_svg_open(cw, ch), _title(cw / 2, "Container Footprint &amp; Startup Performance")]
        svg.append(f'<g transform="translate({pl},{pt})">')

        # ── static bars (left 40% of plot width) ──
        bar_section_w = pw * 0.38
        bar_panel_w = bar_section_w / (2 if has_candidate else 1) - 10
        panels_static = [
            ("Image Size on Disk", b_size_mb, c_size_mb if has_candidate else None, "MB"),
            ("Cold Startup to Ready", b_start_s, c_start_s if has_candidate else None, "s"),
        ]
        bp_w = 220  # each static panel width
        for pi, (title, b_val, c_val, unit) in enumerate(panels_static):
            px = pi * (bp_w + 20)
            panel_max = max(b_val, c_val or 0.0, 1.0) * 1.3
            bar_max_h = ph - 80
            svg.append(f'<g transform="translate({px},0)">')
            svg.append(_rect_panel(0, 0, bp_w, ph))
            svg.append(_label(bp_w / 2, 24, title, _TEXT, size=13, weight="bold"))
            b_h = (b_val / panel_max) * bar_max_h
            svg.append(f'<rect x="30" y="{ph - 60 - b_h:.1f}" width="65" height="{b_h:.1f}" rx="4" fill="{_BLUE}"/>')
            svg.append(_label(62, ph - 62 - b_h, f"{b_val:.1f} {unit}", _BLUE_LIGHT, size=12, weight="bold"))
            svg.append(_label(62, ph - 38, "Baseline", _MUTED, size=12))
            if has_candidate and c_val is not None:
                c_h = (c_val / panel_max) * bar_max_h
                svg.append(f'<rect x="125" y="{ph - 60 - c_h:.1f}" width="65" height="{c_h:.1f}" rx="4" fill="{_GREEN}"/>')
                svg.append(_label(158, ph - 62 - c_h, f"{c_val:.1f} {unit}", _GREEN_LIGHT, size=12, weight="bold"))
                svg.append(_label(158, ph - 38, "Candidate", _MUTED, size=12))
                delta = ((c_val - b_val) / b_val * 100) if b_val > 0 else 0.0
                svg.append(_badge(bp_w / 2, ph - 22, 110, delta))
            svg.append("</g>")

        # ── RSS time-series (right 55% of plot width) ──
        ts_x = (bp_w + 20) * len(panels_static) + 20
        ts_w = pw - ts_x
        svg.append(f'<g transform="translate({ts_x},0)">')
        svg.append(_rect_panel(0, 0, ts_w, ph))
        svg.append(_label(ts_w / 2, 24, "Container RSS Memory — Over Time", _TEXT, size=13, weight="bold"))

        if has_ts and b_timeseries:
            b_rss = [s.get("container_memory_rss_mb", 0.0) for s in b_timeseries]
            b_times = [s.get("t", 0.0) for s in b_timeseries]
            c_rss = [s.get("container_memory_rss_mb", 0.0) for s in c_timeseries] if c_timeseries else []
            c_times = [s.get("t", 0.0) for s in c_timeseries] if c_timeseries else []

            all_rss = b_rss + c_rss
            max_rss = max(max(all_rss) * 1.2, 1.0) if all_rss else 1.0
            all_t = b_times + c_times
            max_t = max(max(all_t), 1.0) if all_t else 1.0

            inner_h = ph - 44
            inner_w = ts_w - 20

            # Y grid
            for gi in range(4):
                gv = (max_rss / 3) * gi
                gy = inner_h - (gv / max_rss) * (inner_h - 10)
                svg.append(_grid_h(10, ts_w - 10, gy + 32))
                svg.append(_label(8, gy + 36, f"{gv:.0f}", _MUTED, size=9, anchor="end"))

            def to_xy(times, vals):
                return [
                    (
                        10 + (t / max_t) * (inner_w - 10),
                        32 + inner_h - (v / max_rss) * (inner_h - 10),
                    )
                    for t, v in zip(times, vals)
                ]

            pts_b = to_xy(b_times, b_rss)
            svg.append(_polyline(pts_b, _BLUE, width=2))
            for x, y in pts_b:
                svg.append(_dot(x, y, 3, _BLUE_LIGHT))

            if c_rss and c_times:
                pts_c = to_xy(c_times, c_rss)
                svg.append(_polyline(pts_c, _GREEN, width=2, dash="5,3"))
                for x, y in pts_c:
                    svg.append(_dot(x, y, 3, _GREEN_LIGHT))

            # X time labels
            for frac, lbl in [(0.0, "0s"), (0.5, f"{max_t/2:.0f}s"), (1.0, f"{max_t:.0f}s")]:
                svg.append(_label(10 + frac * (inner_w - 10), ph - 4, lbl, _MUTED, size=9))
        else:
            # Fallback: single bar for RSS
            b_rss_val = b_telemetry.get("container_memory_rss_mb", 0.0) if b_telemetry else 0.0
            c_rss_val = c_telemetry.get("container_memory_rss_mb", 0.0) if c_telemetry else 0.0
            panel_max = max(b_rss_val, c_rss_val, 1.0) * 1.3
            bar_max_h = ph - 80
            b_h = (b_rss_val / panel_max) * bar_max_h
            svg.append(f'<rect x="30" y="{ph - 60 - b_h:.1f}" width="65" height="{b_h:.1f}" rx="4" fill="{_BLUE}"/>')
            svg.append(_label(62, ph - 62 - b_h, f"{b_rss_val:.1f} MB", _BLUE_LIGHT, size=12, weight="bold"))
            svg.append(_label(62, ph - 38, "Baseline", _MUTED, size=12))
            if has_candidate:
                c_h = (c_rss_val / panel_max) * bar_max_h
                svg.append(f'<rect x="ts_w - 100" y="{ph - 60 - c_h:.1f}" width="65" height="{c_h:.1f}" rx="4" fill="{_GREEN}"/>')
                svg.append(_label(ts_w - 67, ph - 62 - c_h, f"{c_rss_val:.1f} MB", _GREEN_LIGHT, size=12, weight="bold"))

        svg.append("</g>")
        svg.append("</g>")

        # Legend
        ly = ch - 18
        svg.append(_legend_line(pl, ly, _BLUE, "", "Baseline"))
        if has_candidate:
            svg.append(_legend_line(pl + 180, ly, _GREEN, "5,3", "Candidate"))

        svg.append("</svg>")
        with open(svg_path, "w", encoding="utf-8") as f:
            f.write("\n".join(svg))
        return svg_path
