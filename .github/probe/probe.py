import urllib.request, json, re
def get(u, n=200000):
    try:
        r = urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent":"Mozilla/5.0 RadioCast"}), timeout=10)
        return r.status, r.read(n).decode("utf-8","replace")
    except Exception as e:
        return None, str(e)
for u in ["https://www.radiofrance.fr/api/live/webradios/fip",
          "https://www.radiofrance.fr/api/v2.1/stations/fip/live",
          "https://www.radiofrance.fr/api/v2.1/stations/mouv/live",
          "https://www.radiofrance.fr/api/v2.1/stations/franceinter/live",
          "https://www.radiofrance.fr/api/v2.1/stations/fip/webradios/fip",
          "https://api.radiofrance.fr/livemeta/pull/7",
          "https://api.radiofrance.fr/livemeta/live/7/inter_player",
          "https://www.radiofrance.fr/fip/api/live/webradios/fip",
          "https://www.radiofrance.fr/mouv/api/live/webradios/mouv",
          ]:
    s,b = get(u, 3000); print("== API", u, s); print(b[:1500])
for page in ["https://www.radiofrance.fr/fip", "https://www.radiofrance.fr/mouv", "https://www.radiofrance.fr/franceinter"]:
    s,b = get(page)
    print("== PAGE", page, s, len(b))
    found = sorted(set(re.findall(r'["\'](/[a-z0-9_/-]*api[a-z0-9_/.?=&-]*)', b)))[:60]
    for f in found: print("  ", f)
    for m in sorted(set(re.findall(r'https?://[a-z0-9.-]*radiofrance[a-z0-9.-]*/[^"\'\s<>]{0,80}live[^"\'\s<>]{0,60}', b)))[:30]: print("  L", m)
