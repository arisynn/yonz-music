"""Inspect APK/nested Chaquopy ELF PT_LOAD alignment without executing any bundled binary."""
import argparse, io, json, pathlib, struct, zipfile, hashlib
p=argparse.ArgumentParser();p.add_argument('apk');p.add_argument('--output');args=p.parse_args()
files=[]
def scan(archive,prefix=''):
 with zipfile.ZipFile(archive) as z:
  for item in z.infolist():
   if item.is_dir():continue
   if item.filename.endswith(('.so','.imy')):
    data=z.read(item)
    if data.startswith(b'\x7fELF'):
     assert data[4]==2 and data[5]==1, 'Only reviewed 64-bit little-endian ELF is expected'
     offset=struct.unpack_from('<Q',data,32)[0];size,count=struct.unpack_from('<HH',data,54)
     alignments=[]
     for n in range(count):
      header=offset+n*size
      if struct.unpack_from('<I',data,header)[0]==1:alignments.append(struct.unpack_from('<Q',data,header+48)[0])
     files.append({'path':prefix+item.filename,'loadAlignments':alignments,'compatible16KiB':bool(alignments) and min(alignments)>=16384})
    elif item.filename.endswith('.imy') and data.startswith(b'PK'):
     scan(io.BytesIO(data),prefix+item.filename+'!/')
apk=pathlib.Path(args.apk);scan(apk)
report={'apk':apk.name,'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'elfCount':len(files),'all16KiB':all(x['compatible16KiB'] for x in files),'files':files}
text=json.dumps(report,indent=2)
if args.output:pathlib.Path(args.output).write_text(text+'\n')
print(json.dumps({k:v for k,v in report.items() if k!='files'}))
for item in files:
 if not item['compatible16KiB']:print('UNALIGNED',item['path'],item['loadAlignments'])
raise SystemExit(0 if report['all16KiB'] else 1)
