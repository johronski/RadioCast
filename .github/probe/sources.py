# Probe 1 nov: hur kan låtkällor mappas automatiskt? SR:s kanallista vs radio-browser.
import urllib.request, json, re
def get(u):
    r = urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": "RadioCast-probe"}), timeout=20)
    return json.loads(r.read())
src = json.load(open("meta-sources.json"))["sources"]
def known(url):
    for p in src:
        m = re.search(p["match"], url or "")
        if not m: continue
        k = m.group(1) if m.groups() else ""
        if "map" in p and k not in p["map"]: continue
        return p["id"]
    return None

print("== SR kanaler (api.sr.se/api/v2/channels)")
ch = get("https://api.sr.se/api/v2/channels?format=json&pagination=false")["channels"]
print("antal", len(ch))
for c in ch[:60]:
    lu = (c.get("liveaudio") or {}).get("url")
    print(c["id"], c["name"], "|", c.get("channeltype"), "|", lu, "| siteurl", c.get("siteurl"))
srids = {c["id"] for c in ch}

print("\n== radio-browser: SR-kanaler (countrycode SE)")
rb = get("https://de1.api.radio-browser.info/json/stations/search?countrycode=SE&limit=2000&hidebroken=true&order=clickcount&reverse=true")
print("SE-kanaler totalt", len(rb))
sr = [s for s in rb if re.search(r"sr\.se|sverigesradio", (s["url"] + s["url_resolved"] + s["homepage"]).lower())]
print("ser ut som SR:", len(sr))
hit = 0
for s in sr:
    k = known(s["url"]) or known(s["url_resolved"])
    hit += bool(k)
    ids = set(re.findall(r"(\d{2,4})", s["url"] + " " + s["url_resolved"] + " " + s["homepage"]))
    print(("OK " if k else "-- ") + s["name"], "|", s["url"], "|", s["url_resolved"], "|", s["homepage"], "| id i SR-lista:", sorted(int(i) for i in ids if int(i) in srids))
print("träffas av dagens mönster:", hit, "av", len(sr))

print("\n== radio-browser: topp 300 globalt, andel med låtkälla")
top = get("https://de1.api.radio-browser.info/json/stations/topclick/300")
n = sum(1 for s in top if known(s["url"]) or known(s["url_resolved"]))
print(n, "av", len(top))
hosts = {}
for s in top:
    h = re.sub(r"^https?://([^/:]+).*", r"\1", s["url_resolved"]).split(".")
    h = ".".join(h[-2:]); hosts[h] = hosts.get(h, 0) + 1
print("vanligaste värdar:", sorted(hosts.items(), key=lambda x: -x[1])[:25])

print("\n== Radio France: finns kanalförteckning?")
for u in ["https://api.radiofrance.fr/livemeta/pull", "https://www.radiofrance.fr/api/v2.1/stations"]:
    try: d = get(u); print(u, "OK", str(d)[:400])
    except Exception as e: print(u, "ERR", e)
