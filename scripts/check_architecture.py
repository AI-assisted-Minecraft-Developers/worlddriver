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
API_PACKAGES = {'contract': 'schema', 'schema': 'schema', 'methods': 'methods', 'registry': 'registry',
                'call': 'call', 'thread': 'thread', 'action': 'action', 'game': 'game'}
RUNTIME = {'protocol', 'schema', 'methods', 'registry', 'call', 'thread', 'action'}
MAY_USE = {'schema': {'protocol', 'schema'},
           'methods': {'protocol', 'schema', 'methods', 'registry', 'call'},
           'registry': RUNTIME, 'call': RUNTIME, 'thread': RUNTIME, 'action': RUNTIME}
TRANSPORTS = ('rpc', 'mcp', 'script')
# Classes not yet in their layer's subpackage, by the layer they belong to. Shrinks as they move.
UNMOVED = {'api/' + name: layer for layer, names in {
    'registry': 'Api ApiBatch ApiErrors ApiHandler ApiHandlers ApiMethod ApiRegistry DriverApi',
    'call': 'ApiTimers CallContext CancellationToken RunContext WaitPermits',
    'thread': 'GameThreads ServerThreadGuard ServerThreadHop',
    'game': 'EventWire Positions UnloadedAreaException WorldCell'}.items() for name in names.split()}
UNMOVED['model/DriverEvent'] = 'game'
# Layer violations the move has not reached yet. A listed one that no longer occurs fails the
# check as well, so the list can only shrink.
PENDING = {
    ('api/DriverApi.java', 'net.minecraft'): 'host hooks move to api/game/DriverHost',
    ('api/DriverApi.java', 'game DriverEvent'): 'host hooks move to api/game/DriverHost',
    ('api/DriverApi.java', 'game WorldCell'): 'host hooks move to api/game/DriverHost',
    ('mcp/McpServer.java', 'game DriverEvent'): 'event listeners take EventContracts.Event',
    ('rpc/EventNotifications.java', 'game DriverEvent'): 'event listeners take EventContracts.Event',
    ('rpc/EventNotifications.java', 'game EventWire'): 'event listeners take EventContracts.Event',
    ('rpc/RpcServer.java', 'game DriverEvent'): 'event listeners take EventContracts.Event',
}


def code(path):
    return JAVA_TOKENS.sub(lambda m: m.group() if m.group().startswith(('"', "'")) else ' ',
                           path.read_text(encoding='utf-8'))


def layer_of(name):
    """The layer of a class given as its path under worlddriver/ without .java, or None outside api."""
    if name in UNMOVED:
        return UNMOVED[name]
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
        used = {(p.replace('.', '/') + c) for p, c in DRIVER_REFERENCE.findall(text)}
        package = name.rsplit('/', 1)[0]
        used |= {c for c in classes if c.rsplit('/', 1)[0] == package and c in UNMOVED
                 and re.search(r'\b' + c.rsplit('/', 1)[1] + r'\b', text)}
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
        if relative.parts[:2] in [('bot', 'process'), ('bot', 'scheduler'), ('bot', 'movement'), ('bot', 'pathfinder')]:
            banned += ['net.magicterra.worlddriver.integration.', 'net.magicterra.worlddriver.mcp.',
                       'net.magicterra.worlddriver.rpc.', 'net.magicterra.worlddriver.api.']
            if re.search(r'WorldDriverCommon\s*\.\s*api\s*\(', text):
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
