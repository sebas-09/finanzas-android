"""Auditoría estática acotada; no sustituye revisión bancaria/OEM física."""
import argparse
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--dependency-report', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
logging = re.compile(r'android\.util\.Log|\bLog\.(?:v|d|i|w|e|wtf)\(|\bprintln\(|printStackTrace\(|System\.out')
findings = []
files = []
technical_dump = 'writer.println(capture.technicalStatus())'
for area in ('app', 'core', 'feature'):
    for source in (root/area).rglob('*'):
        if source.suffix not in ('.kt', '.java') or '/src/main/' not in source.as_posix():
            continue
        files.append(source)
        for line, value in enumerate(source.read_text(encoding='utf-8').splitlines(), 1):
            if logging.search(value):
                if source.name == 'BankNotificationListenerService.kt' and value.strip() == technical_dump:
                    continue  # dump protegido del servicio: solo estado, causas técnicas y contadores.
                findings.append(f'{source.relative_to(root)}:{line}')
assert not findings, 'Revisar salida potencial de datos: ' + ', '.join(findings)
domains = {'root', 'file', 'database', 'sharedpref', 'external', 'device_root',
           'device_file', 'device_database', 'device_sharedpref'}
backup = ET.parse(root/'app/src/main/res/xml/backup_rules.xml').getroot()
assert {n.get('domain') for n in backup.findall('exclude') if n.get('path') == '.'} >= domains
rules = ET.parse(root/'app/src/main/res/xml/data_extraction_rules.xml').getroot()
for name in ('cloud-backup', 'device-transfer'):
    assert {n.get('domain') for n in rules.find(name).findall('exclude') if n.get('path') == '.'} >= domains
raw = args.dependency_report.read_bytes()
report = raw.decode('utf-16') if raw.startswith((b'\xff\xfe', b'\xfe\xff')) else raw.decode('utf-8-sig')
assert 'releaseRuntimeClasspath' in report
vendors = ('com.google.firebase', 'com.amplitude', 'com.mixpanel', 'io.sentry', 'com.segment',
           'com.facebook.android', 'com.appsflyer', 'com.adjust.sdk')
assert not any(vendor in report for vendor in vendors), 'SDK externo requiere revisión de privacidad'
result = {'productionSourceFilesReviewed': len(files), 'unreviewedLoggingSites': findings,
          'technicalServiceDump': 'state/gate/failure class/counters only; no amounts or notification text',
          'backupAndDeviceTransferExcluded': True, 'knownAnalyticsVendorsAbsent': True,
          'scope': 'Static source/dependency review. Manifest checked separately; not a physical log capture.'}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
print('PASS: production logging, backup/transfer exclusions and release dependency report reviewed')
