#!/usr/bin/env python3
"""Enforce production dependency boundaries. Pass --jars after assembling loader jars."""
import argparse
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA_TOKENS = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*.*?\*/|//[^\n]*', re.S)
DRIVER_REFERENCE = re.compile(r'net\.magicterra\.worlddriver\.((?:[a-z]\w*\.)*)([A-Z]\w*)')

# The api layers (docs/dev/api-layers.md): the layers each may use, and which subpackage holds which.
API_PACKAGES = {'schema': 'schema', 'methods': 'methods', 'registry': 'registry',
                'call': 'call', 'thread': 'thread', 'action': 'action', 'game': 'game'}
RUNTIME = {'protocol', 'schema', 'methods', 'registry', 'call', 'thread', 'action'}
MAY_USE = {'schema': {'protocol', 'schema'},
           'methods': {'protocol', 'schema', 'methods', 'registry', 'call'},
           'registry': RUNTIME, 'call': RUNTIME, 'thread': RUNTIME, 'action': RUNTIME}
TRANSPORTS = ('rpc', 'mcp', 'script')
# Layer violations the move has not reached yet. A listed one that no longer occurs fails the
# check as well, so the list can only shrink.
PENDING = {}

# The layers below the api (docs/dev/bot-layering.md). The bot stack is ordered: each layer may
# use the ones after it, then game and config. Only the facade (bot's own package) speaks the api.
BOT_ORDER = ['bot', 'bot/arbitration', 'bot/behavior', 'bot/control', 'bot/planning', 'bot/rules',
             'bot/world']
GAME = ('game', 'game/observe', 'game/body', 'game/edit', 'game/ui')
# Stack violations not fixed yet, one "file<TAB>what" per line, under the same shrink-only rule.
STACK_PENDING = ROOT / 'scripts/architecture-pending.txt'


def code(path):
    return JAVA_TOKENS.sub(lambda m: m.group() if m.group().startswith(('"', "'")) else ' ',
                           path.read_text(encoding='utf-8'))


def layer_of(name):
    """The layer of a class given as its path under worlddriver/ without .java, or None outside api."""
    parts = name.split('/')
    if parts[0] == 'protocol':
        return 'protocol'
    if parts[0] != 'api':
        return None
    if len(parts) == 2:
        return 'unclassified'
    return API_PACKAGES.get(parts[1], 'unclassified')


def layer_violations(source):
    """Each (file, what) where a class reaches past its layer, as found in the source tree."""
    classes = {p.relative_to(source).with_suffix('').as_posix() for p in source.rglob('*.java')}
    found = set()
    for path in source.rglob('*.java'):
        relative = path.relative_to(source)
        name = relative.with_suffix('').as_posix()
        text = re.sub(r"(?m)^package [^;]+;", "", code(path))
        layer = layer_of(name)
        area = relative.parts[0]
        if layer is None and area not in TRANSPORTS:
            continue
        if layer == 'unclassified':
            found.add((relative.as_posix(), 'no layer'))
            continue
        # A package is a layer, so a class's unimported same-package neighbours never cross one.
        used = {(p.replace('.', '/') + c) for p, c in DRIVER_REFERENCE.findall(text)}
        if layer not in (None, 'game') and 'net.minecraft.' in text:
            found.add((relative.as_posix(), 'net.minecraft'))
        for target in used & classes:
            target_layer = layer_of(target)
            if target_layer is None or target == name:
                continue
            if (layer in MAY_USE and target_layer not in MAY_USE[layer]) \
                    or (area in TRANSPORTS and target_layer == 'game'):
                found.add((relative.as_posix(), f'{target_layer} {target.rsplit("/", 1)[1]}'))
    return found


def stack_unit(name):
    """The stack layer of a class given as its path under worlddriver/ without .java."""
    parts = name.split('/')
    if parts[0] in ('bot', 'game'):
        return parts[0] if len(parts) == 2 else parts[0] + '/' + parts[1]
    return parts[0] if len(parts) > 1 else '(root)'


def stack_allows(src, dst):
    """Whether a class in layer src may use one in layer dst."""
    if src == dst or dst in ('protocol', 'mixin'):
        return True
    if dst == 'config':
        return True
    if src == 'config':
        return False
    if dst == 'debug':
        # Debug tooling plugs in at the facade and the wiring; nothing it records may depend on it.
        return src in ('(root)', 'bot', 'integration')
    if src in BOT_ORDER:
        if dst in BOT_ORDER:
            return BOT_ORDER.index(dst) > BOT_ORDER.index(src)
        return dst in GAME or (src == 'bot' and dst == 'api')
    if src in GAME:
        return dst in ('game', 'game/observe')
    if src == 'application':
        return dst in GAME or dst in ('api', 'bot')
    # Everything else reaches the bot only through its facade.
    return not (dst in BOT_ORDER and dst != 'bot') or src == 'debug'


def stack_violations(source):
    """Each (file, what) where a class outside the api reaches past its layer."""
    classes = {p.relative_to(source).with_suffix('').as_posix() for p in source.rglob('*.java')}
    found = set()
    for path in source.rglob('*.java'):
        relative = path.relative_to(source)
        name = relative.with_suffix('').as_posix()
        src = stack_unit(name)
        if src == 'api':
            continue
        text = re.sub(r"(?m)^package [^;]+;", "", code(path))
        if (src in BOT_ORDER or src == 'config') and 'net.minecraft.client.' in text:
            found.add((relative.as_posix(), 'net.minecraft.client'))
        used = {(p.replace('.', '/') + c) for p, c in DRIVER_REFERENCE.findall(text)}
        for target in used & classes:
            dst = stack_unit(target)
            if target != name and not stack_allows(src, dst):
                found.add((relative.as_posix(), f'{dst} {target.rsplit("/", 1)[-1]}'))
    return found


def read_pending():
    if not STACK_PENDING.exists():
        return set()
    return {tuple(line.split('\t')) for line in STACK_PENDING.read_text(encoding='utf-8').splitlines()
            if line and not line.startswith('#')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jars', action='store_true')
    args = parser.parse_args()
    errors = []
    for module in ('fabric', 'neoforge'):
        for path in (ROOT / module / 'src/main/java').rglob('*.java'):
            if 'net.magicterra.stagewright.' in code(path):
                errors.append(f'{path.relative_to(ROOT)}: production dependency on StageWright')
    source = ROOT / 'common/src/main/java/net/magicterra/worlddriver'
    for path in source.rglob('*.java'):
        relative = path.relative_to(source)
        text = code(path)
        text = re.sub(r"(?m)^package [^;]+;", "", text)
        area = relative.parts[0]
        banned = []
        if area == 'protocol':
            banned = ['net.minecraft.', 'net.fabricmc.', 'net.neoforged.', 'io.netty.',
                      'dev.latvian.', 'net.magicterra.worlddriver.']
            # The protocol can refer to itself.
            text = text.replace('net.magicterra.worlddriver.protocol.', '')
        elif area == 'api':
            banned = ['net.magicterra.worlddriver.' + p + '.'
                      for p in ('rpc', 'mcp', 'application', 'bot', 'client', 'script', 'integration')]
        elif area in ('rpc', 'mcp'):
            banned = ['net.magicterra.worlddriver.application.']
        if relative.parts[:2] in [('bot', 'behavior'), ('bot', 'arbitration'), ('bot', 'control'),
                                  ('bot', 'planning'), ('bot', 'rules')]:
            banned += ['net.magicterra.worlddriver.integration.', 'net.magicterra.worlddriver.mcp.',
                       'net.magicterra.worlddriver.rpc.', 'net.magicterra.worlddriver.api.']
            if re.search(r'WorldDriverCommon\s*\.\s*(registry|host)\s*\(', text):
                errors.append(f'{relative}: global driver API lookup in core behaviour')
        banned += ['net.magicterra.stagewright.']
        for dependency in banned:
            if dependency in text:
                errors.append(f'{relative}: forbidden dependency {dependency}')
    violations = layer_violations(source)
    for file, what in sorted(violations - PENDING.keys()):
        errors.append(f'{file}: layer violation, uses {what}')
    for file, what in sorted(PENDING.keys() - violations):
        errors.append(f'{file}: listed as pending "{what}" but no longer does it; drop it from PENDING')
    stack = stack_violations(source)
    pending = read_pending()
    for file, what in sorted(stack - pending):
        errors.append(f'{file}: layer violation, uses {what}')
    for file, what in sorted(pending - stack):
        errors.append(f'{file}: listed as pending "{what}" but no longer does it; '
                      f'drop it from {STACK_PENDING.name}')
    if args.jars:
        for module in ('common', 'fabric', 'neoforge'):
            jars = list((ROOT / module / 'build/libs').glob('*.jar'))
            jars = [p for p in jars if not any(tag in p.name for tag in ('-sources', '-dev-shadow'))]
            if not jars:
                errors.append(f'{module}: no assembled jars')
            for jar in jars:
                with zipfile.ZipFile(jar) as archive:
                    for name in archive.namelist():
                        if name.startswith(('net/magicterra/stagewright/',
                                            'net/magicterra/worlddriver/testcontent/',
                                            'net/magicterra/worlddriver/bot/stagewright/')):
                            errors.append(f'{jar.name}: shipped test framework or fixture {name}')
    if errors:
        for error in errors:
            print('architecture: ' + error)
        return 1
    print('architecture boundaries OK' + (' (sources and jars)' if args.jars else ' (sources)'))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
