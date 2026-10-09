from pathlib import Path
import hashlib,json,subprocess,sys
snapshot=Path(__file__).resolve().parent
root=Path(sys.argv[1]).resolve()
manifest=json.loads((snapshot/'manifest.json').read_text())
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=root).decode().strip()==manifest['baselineHead']
for record in manifest['files']:
    file=root/record['path']
    assert hashlib.sha256(file.read_bytes()).hexdigest()==record['sha256'],record['path']
for name,expected in manifest['protectedProductionSha256'].items():
    assert hashlib.sha256((root/name).read_bytes()).hexdigest()==expected,name
for name,record in manifest['artifacts'].items():
    assert hashlib.sha256((snapshot/name).read_bytes()).hexdigest()==record['sha256'],name
print('Exact baseline HEAD,26 overlay files, protected production and snapshot artifact hashes verified; no builds run.')
