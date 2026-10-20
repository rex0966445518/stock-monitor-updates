"""Check DEX class definitions, rather than references, before publishing."""
import struct
import sys
import zipfile


def defined_classes(data):
    def u32(offset):
        return struct.unpack_from('<I', data, offset)[0]
    strings_size, strings_offset = u32(56), u32(60)
    strings = []
    for index in range(strings_size):
        offset = u32(strings_offset + index * 4)
        while data[offset] & 0x80:
            offset += 1
        offset += 1  # Skip ULEB128 UTF-16 length.
        strings.append(data[offset:data.index(b'\0', offset)].decode('utf-8', errors='replace'))
    types_offset = u32(68)
    for index in range(u32(96)):
        class_index = u32(u32(100) + index * 32)
        yield strings[u32(types_offset + class_index * 4)]


with zipfile.ZipFile(sys.argv[1]) as apk:
    assert apk.testzip() is None, 'Corrupt APK'
    classes = {name for path in apk.namelist() if path.endswith('.dex')
               for name in defined_classes(apk.read(path))}
required = {
    'Landroidx/core/content/ContextCompat;',
    'Landroidx/core/app/ActivityCompat;',
    'Lcom/journeyapps/barcodescanner/CaptureActivity;',
    'Lcom/journeyapps/barcodescanner/CaptureManager;',
    'Lcom/google/zxing/MultiFormatReader;',
    'Lcom/rex/mitakecompanion/MainActivity;',
}
missing = required - classes
assert not missing, f'Missing packaged scanner classes: {sorted(missing)}'
print(f'APK scanner dependency check passed ({len(classes)} defined classes)')
