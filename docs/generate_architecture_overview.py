#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generate the "Architecture Overview" PNG for A2UI README (zh_CN).

Style matches ``docs/a2ui_and_llm.png``: flat design, rounded rectangles,
deep-blue headers on light-blue bodies.

Usage::

    python3 docs/generate_architecture_overview.py

Requires ``matplotlib``.
"""

import os
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import matplotlib.font_manager as fm
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch


# --------------------------------------------------------------------------- #
# Fonts
# --------------------------------------------------------------------------- #
def get_cjk_font():
    candidates = [
        '/System/Library/Fonts/PingFang.ttc',
        '/System/Library/Fonts/STHeiti Light.ttc',
        '/System/Library/Fonts/Hiragino Sans GB.ttc',
        '/Library/Fonts/Arial Unicode.ttf',
    ]
    for path in candidates:
        if os.path.exists(path):
            return fm.FontProperties(fname=path)
    for f in fm.fontManager.ttflist:
        if 'PingFang' in f.name or 'Heiti' in f.name or 'Noto Sans CJK' in f.name:
            return fm.FontProperties(fname=f.fname)
    return fm.FontProperties()


CJK = get_cjk_font()


# --------------------------------------------------------------------------- #
# Palette (aligned with a2ui_and_llm.png)
# --------------------------------------------------------------------------- #
COLORS = {
    'bg':            '#FFFFFF',
    'header_blue':   '#4A86E8',   # deep-blue header strip
    'header_edge':   '#3A6FCC',
    'body_blue':     '#EAF1FB',   # light-blue body
    'body_edge':     '#B9CFEF',
    'inner_blue':    '#FFFFFF',   # inner white rounded boxes
    'inner_edge':    '#B9CFEF',
    'accent_dark':   '#1F3A68',   # Kuikly render engine (dark navy)
    'accent_dark_e': '#14284A',
    'text_white':    '#FFFFFF',
    'text_dark':     '#1F3A68',
    'text_sub':      '#5E7CA8',
    'arrow':         '#4A86E8',
    'title':         '#1F3A68',
}


# --------------------------------------------------------------------------- #
# Primitives
# --------------------------------------------------------------------------- #
def rounded(ax, x, y, w, h, fc, ec, lw=1.2, pad=0.08, alpha=1.0, zorder=1):
    box = FancyBboxPatch(
        (x, y), w, h,
        boxstyle=f"round,pad={pad},rounding_size=0.18",
        facecolor=fc, edgecolor=ec, linewidth=lw, alpha=alpha, zorder=zorder,
    )
    ax.add_patch(box)
    return box


def text(ax, x, y, s, size=10, color='#1F3A68', weight='normal',
         ha='center', va='center', cjk=True, zorder=5):
    kwargs = dict(fontsize=size, color=color, fontweight=weight,
                  ha=ha, va=va, zorder=zorder)
    if cjk:
        kwargs['fontproperties'] = CJK
    else:
        kwargs['fontfamily'] = 'sans-serif'
    ax.text(x, y, s, **kwargs)


def arrow(ax, x1, y1, x2, y2, color=None, lw=1.6, zorder=3):
    color = color or COLORS['arrow']
    a = FancyArrowPatch(
        (x1, y1), (x2, y2),
        arrowstyle='-|>', mutation_scale=14,
        color=color, lw=lw, zorder=zorder,
        shrinkA=0, shrinkB=0,
    )
    ax.add_patch(a)


def section(ax, x, y, w, h, title_cn, subtitle=None):
    """Draw a section: deep-blue header strip + light-blue body."""
    header_h = 0.55
    # body
    rounded(ax, x, y, w, h, COLORS['body_blue'], COLORS['body_edge'],
            lw=1.2, pad=0.0, zorder=1)
    # header
    rounded(ax, x, y + h - header_h, w, header_h,
            COLORS['header_blue'], COLORS['header_edge'],
            lw=1.0, pad=0.0, zorder=2)
    text(ax, x + w / 2, y + h - header_h / 2, title_cn,
         size=11, color=COLORS['text_white'], weight='bold', zorder=6)
    if subtitle:
        text(ax, x + w / 2, y + h - header_h - 0.25, subtitle,
             size=8.5, color=COLORS['text_sub'], zorder=6)


def inner_card(ax, x, y, w, h, title, desc=None,
               fc=None, ec=None, title_color=None):
    fc = fc or COLORS['inner_blue']
    ec = ec or COLORS['inner_edge']
    title_color = title_color or COLORS['text_dark']
    rounded(ax, x, y, w, h, fc, ec, lw=1.0, pad=0.0, zorder=3)
    if desc:
        text(ax, x + w / 2, y + h * 0.65, title,
             size=9.5, color=title_color, weight='bold', zorder=6)
        text(ax, x + w / 2, y + h * 0.3, desc,
             size=8, color=COLORS['text_sub'], zorder=6)
    else:
        text(ax, x + w / 2, y + h / 2, title,
             size=9.5, color=title_color, weight='bold', zorder=6)


# --------------------------------------------------------------------------- #
# Main figure
# --------------------------------------------------------------------------- #
def create_diagram(output_path):
    fig, ax = plt.subplots(figsize=(13, 15), dpi=180)
    ax.set_xlim(0, 20)
    ax.set_ylim(0, 23)
    ax.axis('off')
    fig.patch.set_facecolor(COLORS['bg'])

    # ---- Title -----------------------------------------------------------
    text(ax, 10, 22.2, 'Kuikly A2UI 架构概览',
         size=20, color=COLORS['title'], weight='bold', zorder=10)
    text(ax, 10, 21.55,
         'JSON 协议 → Kotlin 跨平台核心 → Kuikly 渲染 → 原生三端',
         size=11, color=COLORS['text_sub'], zorder=10)

    # ---- Layer 1: Server / AI Model -------------------------------------
    L1_x, L1_y, L1_w, L1_h = 2.5, 18.5, 15, 1.9
    section(ax, L1_x, L1_y, L1_w, L1_h, 'Server / AI Model')
    text(ax, L1_x + L1_w / 2, L1_y + 0.55, '生成 JSON 消息流',
         size=11, color=COLORS['text_dark'])

    # Arrow 1 (JSON Stream)
    arrow(ax, 10, L1_y, 10, 17.5, lw=2.0)
    text(ax, 10.7, 18.0, 'JSON Stream',
         size=9, color=COLORS['arrow'], weight='bold', ha='left', cjk=False)

    # ---- Layer 2: A2UI Core (Kotlin Multiplatform) ----------------------
    L2_x, L2_y, L2_w, L2_h = 1.0, 7.3, 18.0, 10.2
    section(ax, L2_x, L2_y, L2_w, L2_h,
            'A2UI Core  (Kotlin Multiplatform)')

    # -- Row A: three pipeline cards + arrows
    row_a_y = 15.0
    row_a_h = 1.5
    card_w = 4.6
    gap = 0.7
    row_a_total = 3 * card_w + 2 * gap
    row_a_x0 = L2_x + (L2_w - row_a_total) / 2

    cards_a = [
        ('StreamParser', '流式解析'),
        ('DSLBridge',    '消息路由'),
        ('ProtocolAdapter', '协议适配 / 映射'),
    ]
    centers_a = []
    for i, (t_en, t_cn) in enumerate(cards_a):
        cx = row_a_x0 + i * (card_w + gap)
        inner_card(ax, cx, row_a_y, card_w, row_a_h, t_en, t_cn)
        centers_a.append((cx, cx + card_w, row_a_y + row_a_h / 2))

    # arrows between row-A cards
    for i in range(len(centers_a) - 1):
        x_from = centers_a[i][1]
        x_to = centers_a[i + 1][0]
        y_mid = centers_a[i][2]
        arrow(ax, x_from + 0.02, y_mid, x_to - 0.02, y_mid, lw=1.6)

    # -- Down arrow into A2UIApplier
    applier_y = 12.9
    applier_h = 1.4
    arrow(ax, 10, row_a_y, 10, applier_y + applier_h, lw=1.6)

    # -- A2UIApplier card
    appl_x = L2_x + 1.2
    appl_w = L2_w - 2.4
    rounded(ax, appl_x, applier_y, appl_w, applier_h,
            COLORS['inner_blue'], COLORS['inner_edge'], lw=1.0, pad=0.0, zorder=3)
    text(ax, appl_x + appl_w / 2, applier_y + applier_h * 0.68,
         'A2UIApplier', size=11, color=COLORS['text_dark'], weight='bold')
    text(ax, appl_x + appl_w / 2, applier_y + applier_h * 0.32,
         '组件树操作 ·  createComponent / updateComponent / removeComponent',
         size=9, color=COLORS['text_sub'])

    # -- Down arrow into Pager card
    pager_y = 10.8
    pager_h = 1.7
    arrow(ax, 10, applier_y, 10, pager_y + pager_h, lw=1.6)

    # -- A2UIPager / Flex Tree card
    pg_x = L2_x + 1.2
    pg_w = L2_w - 2.4
    rounded(ax, pg_x, pager_y, pg_w, pager_h,
            COLORS['inner_blue'], COLORS['inner_edge'], lw=1.0, pad=0.0, zorder=3)
    text(ax, pg_x + pg_w / 2, pager_y + pager_h * 0.75,
         'A2UIPager  或  嵌入宿主 Kuikly Pager 的 Flex 树',
         size=10.5, color=COLORS['text_dark'], weight='bold')
    # nested DSL tree chip
    chip_x = pg_x + 1.2
    chip_w = pg_w - 2.4
    chip_y = pager_y + 0.25
    chip_h = 0.65
    rounded(ax, chip_x, chip_y, chip_w, chip_h,
            '#DCE8F7', '#8FB3E2', lw=0.9, pad=0.0, zorder=4)
    text(ax, chip_x + chip_w / 2, chip_y + chip_h / 2,
         'Kuikly DSL Component Tree',
         size=10, color=COLORS['text_dark'], weight='bold', cjk=False)

    # -- Down arrow into Kuikly Core card
    core_y = 8.1
    core_h = 2.2
    arrow(ax, 10, pager_y, 10, core_y + core_h, lw=1.6)

    # -- Kuikly Core card (dark navy)
    co_x = L2_x + 1.2
    co_w = L2_w - 2.4
    rounded(ax, co_x, core_y, co_w, core_h,
            COLORS['accent_dark'], COLORS['accent_dark_e'],
            lw=1.0, pad=0.0, zorder=3)
    text(ax, co_x + co_w / 2, core_y + core_h - 0.5,
         'Kuikly Core',
         size=12, color=COLORS['text_white'], weight='bold', cjk=False)
    text(ax, co_x + co_w / 2, core_y + core_h - 0.95,
         '自动布局 + 渲染',
         size=10, color='#C7DAF5')
    # bullets
    bullets = [
        '•  FlexNode 布局计算',
        '•  RenderView 创建',
        '•  文本测量',
        '•  组件生命周期管理',
    ]
    col_x = [co_x + co_w * 0.18, co_x + co_w * 0.56]
    for i, b in enumerate(bullets):
        cx = col_x[i % 2]
        cy = core_y + 0.75 - (i // 2) * 0.45
        text(ax, cx, cy, b, size=9.2, color='#E6EEFA', ha='left')

    # ---- Arrow 2 (NativeBridge) ------------------------------------------
    arrow(ax, 10, L2_y, 10, 6.4, lw=2.0)
    text(ax, 10.7, 6.85, 'NativeBridge',
         size=9.5, color=COLORS['arrow'], weight='bold', ha='left', cjk=False)

    # ---- Layer 3: Platform Render Layer ---------------------------------
    L3_x, L3_y, L3_w, L3_h = 1.0, 2.0, 18.0, 4.4
    section(ax, L3_x, L3_y, L3_w, L3_h, 'Platform Render Layer  (原生渲染层)')

    # three platform cards
    plats = [
        ('Android',   '#E8F5EA', '#9BCFA5', '#1B7A3A',
         'A2UISurfaceView  /  A2UISurface { }'),
        ('iOS',       '#EAF1FB', '#8FB3E2', '#1F3A68',
         'A2UISurfaceView  /  A2UISurface { }'),
        ('HarmonyOS', '#FCEBEB', '#E5A5A5', '#9B2222',
         'A2UISurfaceView  /  A2UISurface { }'),
    ]
    p_card_w = 5.4
    p_gap = 0.5
    p_total = 3 * p_card_w + 2 * p_gap
    p_x0 = L3_x + (L3_w - p_total) / 2
    p_y = L3_y + 0.9
    p_h = 2.0
    for i, (name, fc, ec, tc, sub) in enumerate(plats):
        px = p_x0 + i * (p_card_w + p_gap)
        rounded(ax, px, p_y, p_card_w, p_h, fc, ec, lw=1.1, pad=0.0, zorder=3)
        text(ax, px + p_card_w / 2, p_y + p_h * 0.68,
             name, size=13, color=tc, weight='bold', cjk=False)
        text(ax, px + p_card_w / 2, p_y + p_h * 0.3,
             sub, size=8.5, color=COLORS['text_sub'], cjk=False)

    # ---- Footer tag -----------------------------------------------------
    text(ax, 10, 1.2,
         '一份 JSON 协议  →  一套 Kuikly DSL 组件树  →  三端一致原生渲染',
         size=10, color=COLORS['text_sub'], weight='bold')

    # save
    plt.subplots_adjust(left=0.02, right=0.98, top=0.98, bottom=0.02)
    plt.savefig(output_path, dpi=180, bbox_inches='tight',
                pad_inches=0.25, facecolor='white', edgecolor='none')
    plt.close()
    print(f"[OK] saved: {output_path}")


if __name__ == '__main__':
    here = os.path.dirname(os.path.abspath(__file__))
    out = os.path.join(here, 'a2ui_architecture_overview.png')
    create_diagram(out)
