# Överlämning (26 okt 2026)

Läs detta först. Fråga sedan vad uppgiften gäller och läs bara den del av `ANDRINGSFORSLAG.md` som berörs.

## Användaren och arbetssätt
- Svara alltid på **svenska**, kort och tydligt. Användaren sitter oftast i mobilen (Android, Chrome).
- **Byggpaus till och med lör 31 okt.** Fråga alltid "Är du säker?" innan något ändras – även buggar och små ändringar. Önskemål läggs i `ANDRINGSFORSLAG.md`.
- "Ändra inget" / "gör inga ändringar" = bara analysera och föreslå.
- Var ärlig om osäkerhet; säg när något bara är testat mot låtsas-högtalare.
- Användaren vill hålla nere användningen: små, fokuserade ändringar; läs bara de filer som behövs.
- Fråga först vad uppgiften gäller, läs sedan. I `ANDRINGSFORSLAG.md`: läs bara den del uppgiften gäller (idélistorna är långa).
- Ny chatt först när chatten blivit lång (ca 15–20 meddelanden) eller ämnet lämnar RadioCast – inte efter varje liten uppgift.
- Uppdatera denna fil direkt när något nytt bestäms, inte först i slutet.

## Appen
- **Webbappen är huvudappen**: `docs/` (GitHub Pages, https://johronski.github.io/RadioCast/). ES-moduler i `docs/js/`:
  `app.js` (gränssnitt, uppspelning i telefonen, listor), `cast.js` (all Chromecast-logik), `sources.js` (låtinfo),
  `catalogue.js` (sök i radio-browser), `names.js`, `songtext.js`, `query.js`, `history.js`, `util.js`.
- **Android-appen** (`app/`) är fryst, bara buggrättningar.
- Låtkällor: `meta-sources.json` – tre identiska kopior (roten, `docs/`, `app/src/main/assets/`), testet kräver att de är lika.
- iPhone: ingen Chromecast (går inte från webbsidor); AirPlay-knappen öppnar Apples lista.

## Släppa en ny version
1. Höj versionen: `VERSION` i `docs/js/app.js` ("Webb X.Y"), `?v=X.Y` i `docs/index.html` och `docs/sw.js`, och `CACHE`-namnet i `docs/sw.js` (radiocast-web-NN).
2. Tester lokalt: `node --test web-tests/*.test.mjs`; klicktest `web-tests/smoke.mjs`; castning `web-tests/cast.mjs` (kräver playwright).
   Lokal server: `cd docs && python3 -m http.server 8765`.
3. Commit (`git -c user.name="RadioCast" -c user.email="radiocast@users.noreply.github.com" commit`) och push till `main`.
   GitHub Actions (`web.yml`) kör samma tester; Pages publicerar parallellt.
- Nätåtkomst från molnmiljön är begränsad: riktiga strömmar/API:er provas via grenen `probe` (workflow som skriver `probe-result.txt`).

## Castning – läget och lärdomar
- `cast.js` skiljer på **ditt val** (pending) och **högtalarens besked**. Det **nyaste beskedet vinner**: Chromes fjärrspelare kan rapportera gammalt för gott (grupper!).
- Kanalbyte: frågar igen efter 8 s, ger upp och säger till efter 20 s.
- **Byt högtalare (Android)**: radion spelar vidare medan man väljer (användarens val 26 okt). Kostnad: Chromes automatiska återanslutning gör att listan ibland inte kommer första gången; appen säger då "Tryck Byt högtalare igen". Autojoin (ORIGIN_SCOPED) måste vara på – annars hittar appen inte högtalaren när den öppnas (prövat i 4.7, återställt i 4.8). Alternativ om det blir för jobbigt: tysta gamla högtalaren under valet (säkert byte).
- **Arbetssätt för castningsfel:** be om felsökningsloggen (⋯ → Om RadioCast → Kopiera felsökningslogg), gör sedan ett testfall i `web-tests/castsim.js`/`cast.mjs` som beter sig som verkligheten, rätta sist. Gissa inte; ändra inte flera saker samtidigt.
- Senaste version: **Webb 4.11** (27 okt, byggd under pausen på användarens uttryckliga begäran).

## Uppspelning i telefonen – nätbyten (Webb 4.9)
- Felsökningsloggen skriver nu rader med `phone …`: start/restart, waiting, lost (orsak), network wifi -> cellular, online/offline, plays again after X s, gave up.
- Webb 4.10: loggar `app opened` (med datum), `stop pressed`, tillstånd i `phone network`-raden; knappen Rensa felsökningslogg i Om RadioCast.
- Webb 4.11: vakt – om uppspelningens tid stått still 4 s utan att webbläsaren sagt något loggas `phone frozen t=… ahead=… rs=… ns=…` och strömmen startas om. `stalled` och Stopp loggar också position/buffert. Provat lokalt med låtsasström som fastnar och med frusen tid; inte på riktig telefon.
- Användaren 27 okt: ljudet blev plötsligt tyst utan några händelser i loggen (ingen waiting/lost/nätbyte) → vakten.
- Öppen fråga 27 okt: vid wifi -> none -> wifi (samma sekund) kom ingen `restart` och inget `waiting`/`lost`, användaren tryckte inte Stopp men startade själv igen efter ~30 s. Orsak okänd – vänta på logg från 4.10.
- Strömmen startas om direkt när Chrome (Android) säger att nättypen bytts (`navigator.connection` change, bara när `type` ändras).
- Stopp räknas som förlorat efter 4 s om strömmen redan spelat (förut 8 s); vid start fortfarande 8 s.
- Otestat mot riktiga nätbyten. Nästa steg: be om loggen efter ett hack. Övervägt sedan: ny ström bredvid den gamla (byte utan tystnad), fördröjningsbuffert 15–20 s (MSE, kräver CORS), sömlöst bara i Android-skalet (13).
