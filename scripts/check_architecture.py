#!/usr/bin/env python3
"""Enforce production dependency boundaries. Pass --jars after assembling loader jars."""
import argparse
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA_TOKENS = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*.*?\*/|//[^\n]*', re.S)


def code(path):
    return JAVA_TOKENS.sub(lambda m: m.group() if m.group().startswith(('"', "'")) else ' ',
                           path.read_text(encoding='utf-8'))


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
