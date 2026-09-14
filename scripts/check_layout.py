# -*- coding: utf-8 -*-
"""
布局 inflate 静态检查（防回归）

用途：找出会在 inflate 时抛
    java.lang.UnsupportedOperationException:
        You must supply a layout_width attribute.
的元素（被包装成 InflateException，表现为打开对应页面即闪退）。

运行：
    python scripts/check_layout.py

退出码：0 = 无问题；1 = 发现问题（可直接用于 CI / pre-commit）

为什么必须专门检查：
    本项目 styles.xml 的 UI 令牌把 layout_width / layout_height 定义在**基样式**上
    （XmButton / XmCard / XmTopBar / XmInput / XmRadio / XmSwitch …），
    变体样式靠 `parent=` 继承。若变体漏写 parent，而布局里该元素又没有显式声明
    layout 参数，运行时就会崩 —— 而 AAPT 不报错（两种写法语法都合法）。

历史案例：XmButtonPrimary / XmButtonSecondary / XmButtonDanger 曾漏写
    parent="XmButton"，导致 fragment_device_config 第 132 行的 <Button btn_save>
    让 MainActivity 启动即闪退（FragmentManagerImpl.dispatchActivityCreated
    → DeviceConfigFragment.onCreateView:35 → inflate 抛异常）。
"""
import os
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, os.pardir, 'src', 'app', 'src', 'main', 'res')
RES = os.path.normpath(RES)

AND = '{http://schemas.android.com/apk/res/android}'
LW = AND + 'layout_width'
LH = AND + 'layout_height'
ID = AND + 'id'
# styles.xml 的 <item name="..."> 是纯字符串形式，不带命名空间展开
LW_STR = 'android:layout_width'
LH_STR = 'android:layout_height'

# 这些标签不需要 LayoutParams
SKIP_TAGS = {'merge', 'requestFocus', 'data', 'variable', 'import'}


def load_styles():
    """收集所有 style：name -> (parent, {item name})"""
    styles = {}
    for root_dir, _dirs, files in os.walk(RES):
        for f in files:
            if not f.endswith('.xml'):
                continue
            try:
                r = ET.parse(os.path.join(root_dir, f)).getroot()
            except Exception:
                continue
            if r.tag != 'resources':
                continue
            for st in r.findall('style'):
                styles[st.get('name')] = (
                    st.get('parent'),
                    {it.get('name') for it in st.findall('item')},
                )
    return styles


def resolve(styles, name, attr, seen=None):
    """沿 parent 链查属性。True=有；False=确定没有；None=无法判定（平台样式/未知）"""
    if not name:
        return False
    if seen is None:
        seen = set()
    if name in seen:
        return None
    seen.add(name)
    if name not in styles:
        return None
    parent, attrs = styles[name]
    if attr in attrs:
        return True
    if parent:
        if parent.startswith('android:') or parent.startswith('@android:'):
            return None
        r = resolve(styles, parent.split('/')[-1], attr, seen)
        if r is True:
            return True
        return None if r is None else False
    return False


def main():
    if not os.path.isdir(RES):
        print('res 目录不存在: %s' % RES)
        return 2

    styles = load_styles()
    lay_dir = os.path.join(RES, 'layout')
    problems = []

    def walk(el, is_root, fname):
        if el.tag in SKIP_TAGS:
            return
        if not is_root:
            st = el.get('style')
            sn = st.split('/')[-1] if st else None
            bad = []
            if el.get(LW) is None and resolve(styles, sn, LW_STR) is not True:
                bad.append('layout_width')
            if el.get(LH) is None and resolve(styles, sn, LH_STR) is not True:
                bad.append('layout_height')
            if bad:
                problems.append((fname, el.tag, el.get(ID) or '-', st or '-', ','.join(bad)))
        for ch in el:
            walk(ch, False, fname)

    for f in sorted(os.listdir(lay_dir)):
        if not f.endswith('.xml'):
            continue
        try:
            root = ET.parse(os.path.join(lay_dir, f)).getroot()
        except Exception as e:
            problems.append((f, 'PARSE_ERROR', '-', '-', str(e)))
            continue
        walk(root, True, f)

    print('styles: %d   layouts: %d' % (len(styles), len(os.listdir(lay_dir))))
    if not problems:
        print('OK - 未发现缺少 layout_width/layout_height 的元素')
        return 0

    print('')
    print('发现 %d 处将在 inflate 时抛异常的元素：' % len(problems))
    for f, tag, eid, st, bad in problems:
        print('  %-30s %-13s %-26s %-24s MISSING: %s' % (f, tag, eid, st, bad))
    print('')
    print('修复建议：优先给变体样式补 parent（如 parent="XmButton"），'
          '或在布局中显式声明 android:layout_width/layout_height。')
    return 1


if __name__ == '__main__':
    # 重定向到文件时 Windows 默认按 locale 编码写出，会与 UTF-8 的中文就地错位
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass
    sys.exit(main())
