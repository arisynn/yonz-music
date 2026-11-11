"""Public, source-supplied artwork variants; never derive or invent image URLs."""
from urllib.parse import urlsplit
HOSTS = {"i.ytimg.com", "img.youtube.com", "lh3.googleusercontent.com", "lh4.googleusercontent.com", "yt3.googleusercontent.com", "yt3.ggpht.com"}

def candidates(images):
    if isinstance(images, dict):
        images=images.get("thumbnails") or images.get("thumbnail") or []
    result={}
    for row in images if isinstance(images,list) else []:
        try:
            url=row.get("url","")
            if url.startswith("//"):url="https:"+url
            parsed=urlsplit(url)
            if (len(url)>4096 or parsed.scheme!="https" or parsed.hostname not in HOSTS or parsed.username
                    or parsed.password or parsed.port not in (None,443) or parsed.fragment):continue
            width=max(0,min(8192,int(row.get("width") or 0)))
            height=max(0,min(8192,int(row.get("height") or 0)))
            result[url]={"url":url,"width":width,"height":height}
        except (ValueError,TypeError,AttributeError):continue
    return sorted(result.values(),key=lambda r:min(r["width"],r["height"]),reverse=True)[:6]

def choose(images,target=512):
    rows=candidates(images)
    if not rows:return ""
    return min(rows,key=lambda r:(min(r["width"],r["height"])<target,
        not (r["width"] and r["height"] and max(r["width"],r["height"])/min(r["width"],r["height"])<=1.25),
        abs(max(r["width"],r["height"])-target) if not min(r["width"],r["height"]) else 0,
        r["width"]*r["height"] if min(r["width"],r["height"])>=target else -r["width"]*r["height"]))["url"]
