"""Query OSV for the exact resolved inventory; absence of advisories is not a security guarantee."""
import argparse,datetime,json,pathlib,urllib.request
p=argparse.ArgumentParser();p.add_argument('inventory');p.add_argument('--output',required=True);a=p.parse_args()
rows=json.loads(pathlib.Path(a.inventory).read_text())
packages=[{'package':{'ecosystem':'Maven','name':x['group']+':'+x['artifact']},'version':x['version']} for x in rows]
packages += [{'package':{'ecosystem':'PyPI','name':name},'version':version} for name,version in [('yt-dlp','2026.10.21'),('yt-dlp-ejs','0.8.0'),('mutagen','1.48.1'),('certifi','2026.10.19')]]
report={'checkedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'service':'https://api.osv.dev/v1/querybatch','scope':'Exact resolved Maven runtime + installed PyPI packages; native libraries separately inventoried','queries':packages}
try:
 request=urllib.request.Request(report['service'],data=json.dumps({'queries':packages}).encode(),headers={'Content-Type':'application/json','User-Agent':'Yono-Music-Dependency-Audit'})
 with urllib.request.urlopen(request,timeout=60) as response:data=json.load(response)
 report['results']=data['results'];report['completed']=True
 findings=[{'package':p,'advisories':r.get('vulns',[])} for p,r in zip(packages,data['results']) if r.get('vulns')]
 report['findings']=findings;print('OSV packages:',len(packages),'with advisory matches:',len(findings))
 for row in findings:print(row['package'],[x['id'] for x in row['advisories']])
except Exception as error:
 report.update(completed=False,error=type(error).__name__+': '+str(error));print('OSV lookup incomplete:',report['error'])
pathlib.Path(a.output).write_text(json.dumps(report,indent=2)+'\n')
