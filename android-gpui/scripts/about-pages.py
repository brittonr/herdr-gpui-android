#!/usr/bin/env python3
"""Generate Remote for Herdr's offline About, privacy, and licence pages.

Reads the committed Android dependency notice collection rather than walking
Cargo's dependency graph again. SPDX fallback texts are from
spdx/license-list-data commit 31846481de7ea17d92f2f46fad76abe58b2e9bb0.

    python3 scripts/about-pages.py [--out DIR] [--privacy PATH] [--check]
"""
import argparse
import hashlib
import html
import json
import pathlib
import re
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_OUT = ROOT / 'app/src/main/assets/about'
NOTICE_TEXT = ROOT.parent / 'android-remote/THIRD-PARTY-NOTICES.txt'
NOTICE_INVENTORY = ROOT.parent / 'android-remote/THIRD-PARTY-NOTICES.inventory.json'
SPDX_LICENSES = ROOT / 'scripts/licenses'
SPDX_REVISION = '31846481de7ea17d92f2f46fad76abe58b2e9bb0'
ANDROID = '{http://schemas.android.com/apk/res/android}'
COPYLEFT = re.compile(
    r'^(?:A?GPL|LGPL|SSPL|EUPL|OSL|CDDL|CPAL|CPL|EPL|MPL|MS-RL|RPL|RPSL|SISSL|QPL|Sleepycat)',
    re.IGNORECASE,
)
EXCEPTIONS = {'LLVM-exception'}
STYLE = '''
:root { color-scheme: light dark; font-family: sans-serif; line-height: 1.55;
  --background: #fff; --foreground: #202124; --muted: #5f6368; --link: #1459a6;
  --surface: #f1f3f4; }
@media (prefers-color-scheme: dark) {
  :root { --background: #17191c; --foreground: #e8eaed; --muted: #bdc1c6;
    --link: #8ab4f8; --surface: #24272b; }
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--background); color: var(--foreground); }
main { max-width: 52rem; margin: 0 auto; padding: 1.25rem 1rem 3rem; }
a { color: var(--link); overflow-wrap: anywhere; }
h1, h2, h3 { line-height: 1.25; }
.meta, .back, .file { color: var(--muted); font-size: .92rem; }
.links { padding-left: 1.4rem; }
article, section { margin: 1.5rem 0; }
.package-list { padding-left: 1.4rem; }
.package-list li { margin: .35rem 0; }
pre { margin: .6rem 0 1rem; padding: .85rem; background: var(--surface);
  border-radius: .45rem; white-space: pre-wrap; overflow-wrap: anywhere;
  font: .82rem/1.45 ui-monospace, monospace; }
@media (max-width: 30rem) { main { padding-inline: .8rem; } pre { font-size: .76rem; } }
'''


def app_settings():
    source = (ROOT / 'app/build.gradle').read_text(encoding='utf-8')

    def value(key, pattern):
        values = re.findall(r'^\s*' + re.escape(key) + r'\s+' + pattern + r'\s*$', source, re.MULTILINE)
        if len(values) != 1:
            raise SystemExit(f'Cannot read an unambiguous {key} from app/build.gradle')
        return values[0]

    return {
        'application_id': value('applicationId', r"['\"]([A-Za-z0-9_.]+)['\"]"),
        'version_code': value('versionCode', r'(\d+)'),
        'version_name': value('versionName', r"['\"]([^'\"]+)['\"]"),
    }


def inline(text):
    result, position = [], 0
    for match in re.finditer(r'\[([^\]]+)\]\(([^)\s]+)\)|\*\*([^*]+)\*\*', text):
        result.append(html.escape(text[position:match.start()]))
        if match.group(1) is not None:
            result.append(f'<a href="{html.escape(match.group(2), quote=True)}">{html.escape(match.group(1))}</a>')
        else:
            result.append(f'<strong>{html.escape(match.group(3))}</strong>')
        position = match.end()
    result.append(html.escape(text[position:]))
    rendered = ''.join(result)
    residue = re.sub(r'<[^>]+>', '', rendered).replace('&lt;', '').replace('&gt;', '')
    if re.search(r'[*_`<>\\[\\]]', residue):
        raise SystemExit(f'Unsupported Markdown in privacy policy line: {text!r}')
    return rendered


def markdown(source):
    blocks, paragraph, items = [], [], []

    def flush():
        if paragraph:
            blocks.append(f'<p>{inline(" ".join(paragraph))}</p>')
            paragraph.clear()
        if items:
            blocks.append('<ul>' + ''.join(f'<li>{inline(item)}</li>' for item in items) + '</ul>')
            items.clear()

    for line in source.splitlines():
        stripped = line.strip()
        if not stripped:
            flush()
        elif re.match(r'^(#{3,}|\d+\.\s|[*+]\s|>|```|\|)', stripped):
            raise SystemExit(f'Unsupported Markdown line: {line!r}')
        elif stripped.startswith('## '):
            flush()
            blocks.append(f'<h2>{inline(stripped[3:])}</h2>')
        elif stripped.startswith('# '):
            flush()
            blocks.append(f'<h1>{inline(stripped[2:])}</h1>')
        elif stripped.startswith('- '):
            if paragraph:
                flush()
            items.append(stripped[2:])
        elif items and line.startswith('  '):
            items[-1] += ' ' + stripped
        else:
            if items:
                flush()
            paragraph.append(stripped)
    flush()
    return '\n'.join(blocks)


def alternatives(expression):
    tokens = re.findall(r'\(|\)|[^\s()]+', expression.replace('/', ' OR '))
    position = 0

    def peek():
        return tokens[position] if position < len(tokens) else None

    def take():
        nonlocal position
        if position >= len(tokens):
            raise ValueError(f'unexpected end of SPDX expression: {expression!r}')
        position += 1
        return tokens[position - 1]

    def atom():
        token = take()
        if token == '(':
            result = disjunction()
            if take() != ')':
                raise ValueError(f'unbalanced parentheses in SPDX expression: {expression!r}')
            return result
        if token in ('AND', 'OR', 'WITH', ')'):
            raise ValueError(f'unexpected {token} in SPDX expression: {expression!r}')
        names = {token}
        if peek() == 'WITH':
            take()
            names.add(take())
        return [frozenset(names)]

    def conjunction():
        result = atom()
        while peek() == 'AND':
            take()
            right_side = atom()
            result = [left | right for left in result for right in right_side]
        return result

    def disjunction():
        result = conjunction()
        while peek() == 'OR':
            take()
            result.extend(conjunction())
        return result

    if not tokens:
        raise ValueError('empty SPDX expression')
    result = disjunction()
    if position != len(tokens):
        raise ValueError(f'trailing tokens in SPDX expression: {expression!r}')
    return result


def non_copyleft_alternatives(expression):
    return [
        alternative for alternative in alternatives(expression)
        if not any(COPYLEFT.match(name) for name in alternative)
    ]


def notice_texts():
    raw = NOTICE_TEXT.read_text(encoding='utf-8', errors='replace')
    starts = list(re.finditer(r'(?m)^PACKAGE ([^\s]+) ([^\s]+)\n(\{[^\n]*\})\n', raw))
    if not starts:
        raise SystemExit(f'No package blocks found in {NOTICE_TEXT}')
    found = {}
    for index, match in enumerate(starts):
        end = starts[index + 1].start() if index + 1 < len(starts) else len(raw)
        package = json.loads(match.group(3))
        body = raw[match.end():end]
        file_starts = list(re.finditer(r'(?m)^FILE (.+)\n', body))
        files = []
        for file_index, file_match in enumerate(file_starts):
            file_end = file_starts[file_index + 1].start() if file_index + 1 < len(file_starts) else len(body)
            text = body[file_match.end():file_end].rstrip('\n')
            files.append((file_match.group(1), text))
        found[(package['name'], package['version'])] = files
    return found


def version_key(version):
    return [int(part) if part.isdigit() else part for part in re.split(r'[.+-]', version)]


def source_link(package):
    source = package.get('source') or ''
    if source.startswith('registry+'):
        return f"https://crates.io/crates/{package['name']}/{package['version']}"
    if source.startswith('git+'):
        url, _, commit = source[len('git+'):].partition('#')
        return f'{url.split("?")[0]} (commit {commit})'
    return package.get('repository') or ''


def standard_texts(package, expression):
    texts = []
    ids = sorted({name for alternative in non_copyleft_alternatives(expression)
                  for name in alternative if name not in EXCEPTIONS})
    for license_id in ids:
        path = SPDX_LICENSES / f'{license_id}.txt'
        if not path.is_file():
            raise SystemExit(
                f"Missing standard SPDX text for {package['name']} {package['version']}: {license_id}; "
                f'fetch from spdx/license-list-data at {SPDX_REVISION} and commit under scripts/licenses/'
            )
        text = path.read_text(encoding='utf-8')
        if '<copyright holders>' in text:
            authors = package.get('authors') or [package['name']]
            holders = ', '.join(re.sub(r'\s*<[^>]*>', '', author) for author in authors)
            text = text.replace('<year> <copyright holders>', holders)
        texts.append((f'{license_id} (SPDX standard text)', text))
    return texts


def page(title, body, back=True):
    back_link = '<p class="back"><a href="index.html">\u2039 About Remote for Herdr</a></p>\n' if back else ''
    return f'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>
<link rel="stylesheet" href="style.css">
</head>
<body>
<main>
{back_link}{body}
</main>
</body>
</html>
'''


def index_page(settings):
    title = 'Remote for Herdr'
    privacy_url = 'https://github.com/brittonr/herdr-gpui-android/blob/main/android-gpui/PRIVACY.md'
    body = f'''<h1>{html.escape(title)}</h1>
<p class="meta">Version {html.escape(settings['version_name'])} ({html.escape(settings['version_code'])})</p>
<p>An unofficial third-party client for managing remote Herdr workspaces from Android.</p>
<ul class="links">
<li><a href="privacy.html">Privacy policy</a></li>
<li><a href="{html.escape(privacy_url, quote=True)}">Privacy policy online</a></li>
<li><a href="licenses.html">Open-source licences</a></li>
<li>Contact: <a href="mailto:herdr@robitzs.ch">herdr@robitzs.ch</a></li>
</ul>'''
    return page(title, body, back=False)


def privacy_page(path):
    body = markdown(path.read_text(encoding='utf-8'))
    privacy_url = 'https://github.com/brittonr/herdr-gpui-android/blob/main/android-gpui/PRIVACY.md'
    body += f'\n<p class="meta">This policy is also published at <a href="{privacy_url}">{privacy_url}</a>.</p>'
    return page('Remote for Herdr privacy policy', body)


def licences_page():
    if not NOTICE_TEXT.is_file() or not NOTICE_INVENTORY.is_file():
        raise SystemExit('Committed Android THIRD-PARTY-NOTICES.txt and .inventory.json are required')
    inventory = json.loads(NOTICE_INVENTORY.read_text(encoding='utf-8'))
    packages = sorted(inventory['packages'], key=lambda p: (p['name'], version_key(p['version'])))
    available = notice_texts()
    blocked = [f"{p['name']} {p['version']} ({p.get('license') or 'no licence'})"
               for p in packages if not p.get('license') or not non_copyleft_alternatives(p['license'])]
    if blocked:
        raise SystemExit('Packages with no non-copyleft licence alternative: ' + '; '.join(blocked))

    texts, text_ids = {}, {}

    def add_text(label, content):
        normalized = content.strip()
        digest = hashlib.sha256(normalized.encode('utf-8')).hexdigest()[:16]
        if digest not in texts:
            texts[digest] = (label, normalized)
        return digest

    asset_inputs = [
        ('Remote for Herdr project', ROOT.parent / 'LICENSE'),
        ('Android client NOTICE.txt', ROOT.parent / 'android/app/src/main/assets/NOTICE.txt'),
        ('Android client LICENSE.txt', ROOT.parent / 'android/app/src/main/assets/LICENSE.txt'),
        ('GPUI Mobile LICENSE', ROOT / 'app/src/main/assets/GPUI-MOBILE-LICENSE.txt'),
        ('GPUI Mobile NOTICE', ROOT / 'app/src/main/assets/GPUI-MOBILE-NOTICE.txt'),
        ('Noto fonts licence', ROOT / 'app/src/main/assets/NOTO-FONTS-LICENSE.txt'),
    ]
    groups = []
    for label, path in asset_inputs:
        if not path.is_file():
            raise SystemExit(f'Required app notice is missing: {path}')
        digest = add_text(label, path.read_text(encoding='utf-8', errors='replace'))
        groups.append((label, digest))

    package_rows = []
    for package in packages:
        expression = package['license']
        key = (package['name'], package['version'])
        shipped = available.get(key, [])
        if package.get('notice_files') and len(shipped) != len(package['notice_files']):
            raise SystemExit(f'Notice corpus/inventory mismatch for {package["name"]} {package["version"]}')
        if not shipped:
            shipped = standard_texts(package, expression)
        links = []
        for label, text in shipped:
            digest = add_text(label, text)
            links.append((label, digest))
        package_rows.append((package, links))

    contents = [
        '<h1>Open-source licences</h1>',
        '<p>Remote for Herdr is an unofficial third-party client. The following project, app and dependency notices apply to this app.</p>',
        '<h2>Project and Android app notices</h2>',
        '<ul class="package-list">',
    ]
    for label, digest in groups:
        contents.append(f'<li>{html.escape(label)}: <a href="#text-{digest}">licence or notice text</a></li>')
    contents.extend(['</ul>', f'<h2>Rust dependencies ({len(packages)})</h2>', '<ul class="package-list">'])
    for package, links in package_rows:
        name = f"{package['name']} {package['version']}"
        source = source_link(package)
        source_html = f' \u00b7 <a href="{html.escape(source.split(" ")[0], quote=True)}">source</a>' if source else ''
        refs = ', '.join(f'<a href="#text-{digest}">{html.escape(label)}</a>' for label, digest in links)
        contents.append(
            f'<li><strong>{html.escape(name)}</strong> <span class="meta">'
            f'{html.escape(package["license"])}{source_html}</span><br>{refs}</li>'
        )
    contents.extend(['</ul>', '<h2>Licence and notice texts</h2>'])
    for digest, (label, text) in sorted(texts.items(), key=lambda item: item[1][0].casefold()):
        contents.append(
            f'<section id="text-{digest}"><h3>{html.escape(label)}</h3>'
            f'<pre>{html.escape(text)}</pre></section>'
        )
    result = page('Open-source licences · Remote for Herdr', '\n'.join(contents))
    if len(result.encode('utf-8')) > 1_500_000:
        raise SystemExit(f'licenses.html is too large for the About WebView: {len(result.encode("utf-8"))} bytes')
    return result, {'packages': len(packages), 'distinct_texts': len(texts)}


def render(privacy):
    settings = app_settings()
    licenses, stats = licences_page()
    pages = {
        'index.html': index_page(settings),
        'privacy.html': privacy_page(privacy),
        'licenses.html': licenses,
        'style.css': STYLE.strip() + '\n',
    }
    return pages, stats


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=pathlib.Path, default=DEFAULT_OUT)
    parser.add_argument('--privacy', type=pathlib.Path, default=ROOT / 'PRIVACY.md')
    parser.add_argument('--check', action='store_true', help='fail if committed output is missing or stale')
    args = parser.parse_args()
    if not args.privacy.is_file():
        raise SystemExit(f'Privacy policy not found: {args.privacy}')
    pages, stats = render(args.privacy)
    if args.check:
        stale = [name for name, content in pages.items()
                 if not (args.out / name).is_file() or (args.out / name).read_text(encoding='utf-8') != content]
        if stale:
            raise SystemExit('Generated About pages are stale or missing: ' + ', '.join(stale))
        print(f"About pages current: {stats['packages']} packages, {stats['distinct_texts']} distinct texts, "
              f"licenses.html {(args.out / 'licenses.html').stat().st_size} bytes")
        return
    args.out.mkdir(parents=True, exist_ok=True)
    for name, content in pages.items():
        (args.out / name).write_text(content, encoding='utf-8', newline='\n')
    print(f"About pages: {stats['packages']} packages, {stats['distinct_texts']} distinct texts, "
          f"licenses.html {(args.out / 'licenses.html').stat().st_size} bytes")


if __name__ == '__main__':
    main()
