"""Verifica privacidad del manifest realmente fusionado por AGP (sin dependencias externas)."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parents[1]
variant = sys.argv[1] if len(sys.argv) > 1 else 'debug'
manifests = list((root / 'app/build/intermediates/merged_manifests' / variant).rglob('AndroidManifest.xml'))
if len(manifests) != 1:
    raise SystemExit('Ejecute :app:process' + variant.title() + 'MainManifest antes de verificar')
manifest = ET.parse(manifests[0]).getroot()
android = '{http://schemas.android.com/apk/res/android}'
permissions = {e.attrib.get(android + 'name') for e in manifest if e.tag.startswith('uses-permission')}
forbidden = {'android.permission.' + name for name in (
    'INTERNET', 'READ_SMS', 'RECEIVE_SMS', 'READ_CONTACTS', 'ACCESS_FINE_LOCATION',
    'ACCESS_COARSE_LOCATION', 'CAMERA', 'READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE',
    'MANAGE_EXTERNAL_STORAGE', 'QUERY_ALL_PACKAGES')}
assert not permissions & forbidden, 'Permisos no autorizados: ' + str(permissions & forbidden)
allowed = {'android.permission.' + name for name in (
    'POST_NOTIFICATIONS', 'WAKE_LOCK', 'ACCESS_NETWORK_STATE', 'RECEIVE_BOOT_COMPLETED')}
allowed.add('ec.finanzas.locales.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION')
assert permissions <= allowed, 'Permisos nuevos sin auditar: ' + str(permissions - allowed)
app = manifest.find('application')
assert app is not None
assert app.attrib.get(android + 'allowBackup') == 'false', 'Backup habilitado'
assert app.attrib.get(android + 'usesCleartextTraffic') == 'false'
assert 'dataExtractionRules' in ''.join(app.attrib)
if variant == 'release':
    assert app.attrib.get(android + 'debuggable', 'false') == 'false', 'Release depurable'
    expected_exports = {
        'ec.finanzas.locales.MainActivity': None,
        'ec.finanzas.capture.BankNotificationListenerService': 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE',
        'androidx.work.impl.background.systemjob.SystemJobService': 'android.permission.BIND_JOB_SERVICE',
        'androidx.work.impl.diagnostics.DiagnosticsReceiver': 'android.permission.DUMP',
        'androidx.profileinstaller.ProfileInstallReceiver': 'android.permission.DUMP',
    }
    for node in app:
        if node.attrib.get(android + 'exported') == 'true':
            name = node.attrib.get(android + 'name')
            assert name in expected_exports, 'Componente público no auditado: ' + str(name)
            assert node.attrib.get(android + 'permission') == expected_exports[name], 'Protección inesperada: ' + name
listeners = []
for service in app.findall('service'):
    actions = {a.attrib.get(android + 'name') for a in service.findall('intent-filter/action')}
    if 'android.service.notification.NotificationListenerService' in actions:
        assert service.attrib.get(android + 'permission') == 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE'
        assert service.attrib.get(android + 'name') == 'ec.finanzas.capture.BankNotificationListenerService'
        listeners.append(service)
assert len(listeners) == 1, 'Debe existir un solo listener protegido por el permiso del sistema'
assert 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE' not in permissions, 'Binding pertenece al servicio, no a uses-permission'
assert 'android.permission.POST_NOTIFICATIONS' in permissions, 'F4 declara avisos propios opt-in'
print('PASS: manifest ' + variant + ' sin INTERNET ni permisos de datos sensibles; avisos propios opt-in, listener protegido, backup desactivado.')

