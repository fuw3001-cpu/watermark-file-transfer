"""Fetch pinned offline document resources. No user documents are sent anywhere."""
from pathlib import Path
import hashlib
import shutil
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.tools' / 'office'
ASSETS = ROOT / 'app/build/generated/documentAssets'
JNI = ROOT / 'app/build/generated/documentJni'
MODEL_COMMIT = '87416418657359cb625c412a48b6e1d6d41c29bd'

def fetch(url, path, expected=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists() or (expected and hashlib.sha256(path.read_bytes()).hexdigest() != expected):
        temporary = path.with_suffix(path.suffix + '.part')
        with urllib.request.urlopen(url, timeout=60) as source, temporary.open('wb') as out:
            shutil.copyfileobj(source, out)
        if expected and hashlib.sha256(temporary.read_bytes()).hexdigest() != expected:
            temporary.unlink()
            raise ValueError('Resource checksum mismatch: ' + path.name)
        temporary.replace(path)

def main():
    archive = CACHE / 'LibreOffice-arm64.tar.xz'
    fetch('https://github.com/vasuki-re/LibreOffice-Lite/releases/download/v2.0/LibreOffice-arm64.tar.xz', archive,
          '6665ad47db41d116248c40e6fc608a3425b9eb4a304f5ad5e5d5c80dbea61e30')
    old = b'/data/data/vasuki.istanpdf/files'
    base = b'/proc/self/fd/1023'
    assert (len(old) - len(base)) % 2 == 0
    alias = base + b'/.' * ((len(old) - len(base)) // 2)
    patches = 0
    with tarfile.open(archive) as tar:
        for entry in tar:
            if entry.isdir():
                continue
            if not entry.isfile():
                raise ValueError('Unexpected non-file resource: ' + entry.name)
            parts = Path(entry.name).parts
            if '..' in parts or Path(entry.name).is_absolute():
                raise ValueError('Unsafe resource path')
            if parts[0] == 'arm64-v8a':
                # FFmpeg's packaged NDK C++ shared runtime also serves the Office process.
                if parts[-1] == 'libc++_shared.so':
                    continue
                target = JNI / entry.name
            else:
                target = ASSETS / 'lo' / entry.name
            target.parent.mkdir(parents=True, exist_ok=True)
            data = tar.extractfile(entry).read()
            if target.suffix == '.so':
                patches += data.count(old)
                data = data.replace(old, alias)
            if not target.exists() or target.read_bytes() != data:
                target.write_bytes(data)
    model_hashes = {
        'eng': '7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2',
        'chi_sim': 'a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730',
    }
    for language in ('eng', 'chi_sim'):
        target = CACHE / (language + '.traineddata')
        fetch(f'https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/{MODEL_COMMIT}/{language}.traineddata', target, model_hashes[language])
        destination = ASSETS / 'ocr' / target.name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(target, destination)
    matting = ROOT / '.tools/matting/u2netp.onnx'
    fetch('https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx', matting,
          '309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8')
    model_destination = ASSETS / 'matting/u2netp.onnx'
    model_destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(matting, model_destination)
    for component in ('matting', 'scanning'):
        for license_file in (ROOT / 'third_party' / component).glob('*.txt'):
            target = ASSETS / 'licenses' / component / license_file.name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(license_file, target)
    print(f'Offline document resources ready; {patches} equal-length LibreOffice path relocations')

if __name__ == '__main__':
    main()
