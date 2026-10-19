import urllib.request, json, sys, re, socket
def get(u, h=None, n=4000):
    try:
        r = urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent":"RadioCast/2.7", **(h or {})}), timeout=10)
        return r.status, dict(r.headers), r.read(n)
    except Exception as e:
        return None, {}, str(e).encode()
def icy(u):
    try:
        r = urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent":"RadioCast/2.7","Icy-MetaData":"1"}), timeout=10)
        mi = r.headers.get("icy-metaint"); print("  url:", r.geturl(), "metaint:", mi, "icy-name:", r.headers.get("icy-name"))
        if not mi: return
        mi=int(mi)
        for _ in range(3):
            r.read(mi); l=r.read(1)[0]*16
            if l: print("  META:", r.read(l)); return
        print("  no meta in 3 blocks")
    except Exception as e: print("  ERR", e)
streams = {
 "nova":"https://novazz.ice.infomaniak.ch/novazz-128.mp3",
 "fip":"https://icecast.radiofrance.fr/fip-midfi.mp3",
 "mouv":"https://icecast.radiofrance.fr/mouv-midfi.mp3",
 "inter":"https://icecast.radiofrance.fr/franceinter-midfi.mp3",
 "p3a":"https://sverigesradio.se/topsy/direkt/164-hi-mp3",
 "p3b":"https://live1.sr.se/p3-aac-192",
 "p3c":"https://sverigesradio.se/topsy/direkt/srapi/164.mp3",
}
for k,u in streams.items():
    print("== ICY", k, u); icy(u)
for u in ["https://icecast.radiofrance.fr/status-json.xsl","https://novazz.ice.infomaniak.ch/status-json.xsl"]:
    s,h,b = get(u, n=1500); print("== STATUS", u, s, b[:600])
apis = [
 "https://api.sr.se/api/v2/playlists/rightnow?channelid=164&format=json",
 "https://www.radiofrance.fr/fip/api/live?webradio=fip",
 "https://www.radiofrance.fr/mouv/api/live?webradio=mouv",
 "https://www.radiofrance.fr/franceinter/api/live?webradio=franceinter",
 "https://www.radiofrance.fr/franceinter/api/live",
 "https://www.radiofrance.fr/mouv/api/live",
 "https://www.radiofrance.fr/fip/api/live",
 "https://www.nova.fr/radios-data/www.nova.fr/all.json",
 "https://www.nova.fr/wp-json/radios/v1/onair",
]
for u in apis:
    s,h,b = get(u, n=6000); print("== API", u, s, h.get("Content-Type")); print(b[:3000].decode("utf-8","replace"))
s,h,b = get("https://de1.api.radio-browser.info/json/stations/search?name=P3&countrycode=SE&limit=8", n=20000)
try:
    for x in json.loads(b): print("RB", x["name"], x["url_resolved"])
except Exception as e: print("RB err", e, b[:200])
