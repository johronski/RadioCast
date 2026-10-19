import urllib.request, json, time
def get(u):
    try:
        r = urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent":"Mozilla/5.0 RadioCast"}), timeout=10)
        return json.loads(r.read())
    except Exception as e:
        return str(e)
now=time.time()
for sid in [7,6,1,4,5,64,65,66,69,70,71,74,77,78,95]:
    d=get("https://api.radiofrance.fr/livemeta/pull/%d"%sid)
    if isinstance(d,str): print(sid,"ERR",d); continue
    print("== ", sid, "keys", list(d.keys()), "levels", json.dumps(d.get("levels"))[:300])
    for k,s in d["steps"].items():
        if s["start"]<=now<=s["end"]:
            print("  CUR", {x:s.get(x) for x in ["title","authors","performers","titreAlbum","embedType","depth","visual","cover","titleSlug","anneeEditionMusique","label","stationId"]})
    one=next(iter(d["steps"].values())); print("  fields:", sorted(one.keys()))
