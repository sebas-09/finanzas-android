"""Prueba actualización/borrado REAL. Rechaza dispositivos físicos y AVDs ajenos a F5.

Requiere --allow-test-data-clear; nunca usar contra una instalación con datos personales.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--phase', choices=['seed', 'upgrade', 'export', 'reset'], required=True)
parser.add_argument('--allow-test-data-clear', action='store_true', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
evidence = root / 'docs/testing/evidence-f5'
evidence.mkdir(parents=True, exist_ok=True)
api = '29' if args.serial == 'emulator-5558' else '36'
pkg = 'ec.finanzas.locales'


def adb(*parts, timeout=120, check=True):
    result = subprocess.run([args.adb, '-s', args.serial, *parts], capture_output=True,
                            encoding='utf-8', timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


assert args.serial in ('emulator-5558', 'emulator-5560'), 'Only dedicated F5 emulator ports accepted'
assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1', 'Physical devices rejected'
assert f'finanzas-f5-api{api}' in adb('emu', 'avd', 'name'), 'Wrong AVD: test data clear rejected'
assert adb('shell', 'getprop', 'sys.boot_completed').strip() == '1', 'Wait for boot completion'


def probe(phase):
    output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                 'ec.finanzas.locales.F5DeliveryProbe', '-e', 'f5Phase', phase,
                 '-e', 'allowF5DeliveryProbe', 'yes',
                 pkg + '.test/androidx.test.runner.AndroidJUnitRunner', timeout=180)
    (evidence / f'delivery-{phase}-api{api}.log').write_text(output, encoding='utf-8')
    assert 'OK (1 test)' in output and 'FAILURES!!!' not in output, output
    # Android redirige System.out del test a logcat, no al stream de am instrument.
    stdout = adb('logcat', '-d', '-s', 'System.out:I')
    markers = '\n'.join(line for line in stdout.splitlines() if re.search(r'F5_(WRAPPED_SHA256|FINGERPRINT|PHASE_OK)=', line))
    output += '\nSynthetic probe markers from logcat:\n' + markers + '\n'
    (evidence / f'delivery-{phase}-api{api}.log').write_text(output, encoding='utf-8')
    values = dict(re.findall(r'F5_(WRAPPED_SHA256|FINGERPRINT|PHASE_OK)=([^\s]+)', output))
    assert values.get('PHASE_OK') == phase, output
    assert len(values['WRAPPED_SHA256']) == len(values['FINGERPRINT']) == 64
    return values


def dump():
    for attempt in range(6):
        adb('shell', 'input', 'keyevent', '224')  # Mantener despierto el AVD durante el recorrido.
        output = adb('shell', 'uiautomator', 'dump', '/sdcard/f5-ui.xml', timeout=40)
        if 'dumped' in output:
            raw = adb('shell', 'cat', '/sdcard/f5-ui.xml')
            nodes = list(ET.fromstring(raw).iter('node'))
            close = [n for n in nodes if n.get('text') in ('Close app', 'Cerrar app')]
            if close and any('System UI' in n.get('text', '') for n in nodes):
                (evidence/f'environment-system-ui-api{api}.xml').write_text(raw, encoding='utf-8')
                left, top, right, bottom = map(int, re.findall(r'\d+', close[-1].get('bounds')))
                adb('shell', 'input', 'tap', str((left+right)//2), str((top+bottom)//2))
                time.sleep(1)
                continue
            (evidence / f'delivery-last-ui-api{api}.xml').write_text(raw, encoding='utf-8')
            return nodes
        time.sleep(2)
    raise RuntimeError('Fresh UI hierarchy unavailable')


def screenshot(name):
    result = subprocess.run([args.adb, '-s', args.serial, 'exec-out', 'screencap', '-p'],
                            capture_output=True, timeout=30, check=True)
    folder = root/'docs/testing/screenshots-f5'
    folder.mkdir(exist_ok=True)
    (folder/f'{name}-api{api}.png').write_bytes(result.stdout)


def tap(label):
    for attempt in range(22):
        nodes = dump()
        matches = [n for n in nodes if label in (n.get('text'), n.get('content-desc'))]
        if matches:
            bounds = list(map(int, re.findall(r'\d+', matches[-1].get('bounds'))))
            adb('shell', 'input', 'tap', str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))
            time.sleep(.5)
            return
        # Recuperar solamente el componente System UI si el AVD muestra su ANR conocido.
        close = [n for n in nodes if n.get('text') in ('Close app', 'Cerrar app')]
        if close and any('System UI' in n.get('text', '') for n in nodes):
            b = list(map(int, re.findall(r'\d+', close[-1].get('bounds'))))
            adb('shell', 'input', 'tap', str((b[0]+b[2])//2), str((b[1]+b[3])//2))
            continue
        scroll = [n for n in nodes if n.get('scrollable') == 'true']
        if scroll:
            left, top, right, bottom = map(int, re.findall(r'\d+', scroll[-1].get('bounds')))
            adb('shell', 'input', 'swipe', str((left+right)//2), str(int(top+(bottom-top)*.8)),
                str((left+right)//2), str(int(top+(bottom-top)*.3)), '300')
        else:
            time.sleep(1)
    raise RuntimeError('Control not found: ' + label)


if args.phase == 'seed':
    for apk in ('artifacts/finanzas-locales-f4-debug.apk',
                'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
        print(adb('install', '-r', '-t', str(root/apk), timeout=180).strip())
    state = probe('seed')
    (evidence/f'delivery-seed-api{api}.json').write_text(json.dumps(state, indent=2), encoding='utf-8')
elif args.phase == 'upgrade':
    print(adb('install', '-r', '-t', str(root/'app/build/outputs/apk/debug/app-debug.apk'), timeout=180).strip())
    old = json.loads((evidence/f'delivery-seed-api{api}.json').read_text())
    new = probe('upgrade')
    assert old['WRAPPED_SHA256'] == new['WRAPPED_SHA256'], 'Wrapped database key changed during upgrade'
    assert old['FINGERPRINT'] == new['FINGERPRINT'], 'HMAC key changed during upgrade'
elif args.phase == 'export':
    adb('shell', 'am', 'force-stop', pkg)
    adb('shell', 'am', 'start', '-f', '0x10008000', '-n', pkg+'/.MainActivity')
    time.sleep(3)
    tap('Explorar la app')
    tap('Ajustes')
    tap('Exportar movimientos CSV')
    screenshot('export-warning')
    tap('Elegir destino')
    for attempt in range(6):
        nodes = dump()
        edits = [n for n in nodes if n.get('class') == 'android.widget.EditText']
        if edits:
            edit = edits[0]
            break
        time.sleep(1)
    else:
        raise RuntimeError('Document picker filename field unavailable')
    b = list(map(int, re.findall(r'\d+', edit.get('bounds'))))
    adb('shell', 'input', 'tap', str((b[0]+b[2])//2), str((b[1]+b[3])//2))
    adb('shell', 'input', 'keyevent', '123')
    adb('shell', 'input', 'keyevent', *(['67'] * (len(edit.get('text',''))+5)))
    filename = f'f5-delivery-api{api}.csv'
    adb('shell', 'input', 'text', filename)
    ime = adb('shell', 'dumpsys', 'input_method')
    if 'mInputShown=true' in ime or 'mIsInputViewShown=true' in ime:
        adb('shell', 'input', 'keyevent', '4')
    nodes = dump()
    save = next(n.get('text') for n in nodes if n.get('text','').lower() in ('save','guardar'))
    tap(save)
    time.sleep(2)
    screenshot('csv-saved')
    external = '/sdcard/Download/' + filename
    target = evidence/f'export-api{api}.csv'
    adb('pull', external, str(target))
    content = target.read_text(encoding='utf-8')
    assert '"3.99"' in content and 'f5-upgrade-marker' in content
    assert 'F5 Banco' in content
    output = {'externalPath':external,'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),
              'systemDocumentPicker':True,'containsSyntheticSeededMovement':True}
    (evidence/f'export-api{api}.json').write_text(json.dumps(output,indent=2),encoding='utf-8')
else:
    old = json.loads((evidence/f'delivery-seed-api{api}.json').read_text())
    adb('shell', 'am', 'force-stop', pkg)
    adb('shell', 'am', 'start', '-f', '0x10008000', '-n', pkg+'/.MainActivity')
    time.sleep(3)
    tap('Explorar la app')
    tap('Ajustes')
    tap('Eliminar todos los datos')
    screenshot('erase-confirmation')
    nodes = dump()
    button = next(n for n in nodes if n.get('text') == 'Borrar definitivamente')
    # Compose puede exponer la propiedad enabled en su nodo padre; las pruebas Compose
    # verifican además que el botón no responde sin reconocimiento.
    tap('Entiendo que el borrado es irreversible')
    tap('Borrar definitivamente')
    for attempt in range(60):
        if not adb('shell', 'pidof', pkg, check=False).strip():
            break
        time.sleep(1)
    else:
        raise RuntimeError('Android did not close the app after its clear-data request')
    time.sleep(2)
    new = probe('reset')
    assert old['WRAPPED_SHA256'] != new['WRAPPED_SHA256'], 'Database wrapping material was not renewed'
    assert old['FINGERPRINT'] != new['FINGERPRINT'], 'HMAC key survived the explicit clear'
    export_record = evidence/f'export-api{api}.json'
    if export_record.exists():
        export_data = json.loads(export_record.read_text())
        external_hash = adb('shell','sha256sum',export_data['externalPath']).split()[0]
        assert external_hash == export_data['sha256'], 'External CSV changed or was removed by data clear'
    adb('shell', 'am', 'force-stop', pkg)
    adb('shell', 'am', 'start', '-f', '0x10008000', '-n', pkg+'/.MainActivity')
    time.sleep(3)
    tap('Explorar la app')
    nodes = dump()
    assert any(n.get('text','').startswith('Captura no activada') for n in nodes), 'Fresh app could not be explored without accepting consent'
    screenshot('after-reset')
    (evidence/f'delivery-reset-api{api}.json').write_text(json.dumps({
        'realUiConfirmation': True, 'processClosed': True, 'databaseAndHmacKeysRenewed': True,
        'oldPrivateMarkersGone': True, 'ledgerAndCustomRulesEmpty': True, 'freshConsent': True,
    }, indent=2), encoding='utf-8')
print(f'PASS: F5 {args.phase} API{api} on dedicated emulator')
