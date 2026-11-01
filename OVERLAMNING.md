# Överlämning (uppdaterad 1 nov 2026)

Läs detta först. Fråga sedan vad uppgiften gäller och läs bara den del av `ANDRINGSFORSLAG.md` som berörs.

## Användaren och arbetssätt
- Svara alltid på **svenska**, kort och tydligt. Användaren sitter oftast i mobilen (Android, Chrome).
- Byggpausen slutade 31 okt, men **fråga fortfarande alltid "Är du säker?" innan något ändras** – även buggar och små ändringar (beslut 1 nov). Önskemål läggs i `ANDRINGSFORSLAG.md`.
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
- Grenen `probe`: `probe.yml` kör just nu SR-generatorn (visar diff). Provskript där: `.github/probe/sources.py`, `random5.py`, `icy.mjs` – byt körsteget i `probe.yml` för nya prov.
- Ändringar utanför `paths-ignore` i `build.yml` (t.ex. `build.yml` själv eller Java-kod) bygger en ny Android-release (`v1.0.N`). Undvik i onödan; hände 1 nov.

## Låtkällor för nya kanaler (1 nov)
- Förslag och probe-resultat i `ANDRINGSFORSLAG.md`, avsnittet "Låtkällor för nya kanaler".
- **Byggt 1 nov: SR-generatorn** `tools/build-sources.mjs`. Hämtar SR:s kanallista, följer varje kanals mp3-länk och AAC-spellista till kortkoden (`live1.sr.se/p4sth-…` → 701) och skriver om SR-posten i alla tre `meta-sources.json`. Alla 52 SR-kanaler täcks. P2 rättad: `p2` → 2562 (Klassiskt), `p2sm` → 163 (Språk och musik). En kod som flera kanaler leder till går till kanalen utan annan kod. Skydd: under 20 koder eller fel P1/P3 → inget skrivs.
- `.github/workflows/sr-sources.yml` kör generatorn måndagar (och manuellt) och öppnar PR från grenen `sr-sources` vid ändring. Kräver att "Allow GitHub Actions to create and approve pull requests" är på, annars blir jobbet rött med besked.
- Nästa steg: prova en P4-kanal i telefonen. Blir PR-steget i `sr-sources.yml` rött: slå på inställningen ovan (kunde inte kontrolleras härifrån).
- Ingen versionshöjning: `meta-sources.json` hämtas nätet först vid varje start (webb) resp. från GitHub (Android).
- Prov i Chromium 1 nov: webbappen får låt för bara 3 av 30 slumpade kanaler (CORS, http, HLS). Bred täckning kräver Android-skalet eller en mellanserver; inget beslut än.

## Låtlista utan att spela (Webb 4.17, 1 nov)
- Notikon till höger på raden för kanaler med låtkälla (`patternFor`). Öppnar låtvyn för den kanalen utan att spela: hämtar aktuell låt (`fromPattern`) och historik (`fetchHistory`), knappen "Spela X" startar kanalen. Kod: `peek`/`showSongSheet(station)`/`peekSongs` i `app.js`, `.row .songs` i `style.css`. Testfall i `smoke.mjs`.
- Bara provat offline här; riktiga källor ej provade i telefon än. Hämtade låtar hamnar i historiken (som backfill redan gör).

## Castning – läget och lärdomar
- `cast.js` skiljer på **ditt val** (pending) och **högtalarens besked**. Det **nyaste beskedet vinner**: Chromes fjärrspelare kan rapportera gammalt för gott (grupper!).
- Kanalbyte: frågar igen efter 8 s, ger upp och säger till efter 20 s.
- **Byt högtalare (Android)**: radion spelar vidare medan man väljer (användarens val 26 okt). Kostnad: Chromes automatiska återanslutning gör att listan ibland inte kommer första gången; appen säger då "Tryck Byt högtalare igen". Autojoin (ORIGIN_SCOPED) måste vara på – annars hittar appen inte högtalaren när den öppnas (prövat i 4.7, återställt i 4.8). Alternativ om det blir för jobbigt: tysta gamla högtalaren under valet (säkert byte).
- **Arbetssätt för castningsfel:** be om felsökningsloggen (⋯ → Om RadioCast → Kopiera felsökningslogg), gör sedan ett testfall i `web-tests/castsim.js`/`cast.mjs` som beter sig som verkligheten, rätta sist. Gissa inte; ändra inte flera saker samtidigt.
- Senaste version: **Webb 4.17** (1 nov).
- Volym (4.12): i telefonen (pekskärm) inget reglage – ljudet går alltid på full nivå och telefonens knappar styr; reglaget visas bara vid castning (där följer det telefonens knappar). Datorer behåller reglaget. Den som sänkt reglaget tonas upp en gång vid nästa start, med meddelande (`rc.volumeMoved`).
- Webb 4.13: reglaget slutade följa volymknapparna efter första dragningen (Chrome i telefonen skickar inte alltid pointerup → appen trodde fingret låg kvar). Släpper nu även vid pointercancel/lostpointercapture/change och senast 2 s efter sista rörelsen. Testfall i cast.mjs (`sim.buttons(nivå)` = volymknapparna).
- Webb 4.13–4.15: felsökning av att reglaget och volymknapparna kom ur takt efter en dragning vid castning. Orsak (bekräftad 27 okt): Chrome i telefonen har en egen volymnivå för knapparna som inte märker när webbsidan sätter volymen, så knapparna räknade från den gamla nivån och ljudet föll tvärt. Går inte att rätta från webben.
- **Webb 4.16 (31 okt, alternativ B):** i telefonen är reglaget vid castning bara en visare (`disabled`), knapparna styr ensamma. Datorer kan fortfarande dra. Loggraderna från 4.14–4.15 borttagna. Testfall i cast.mjs (`touch: true`). Ej provat i riktig telefon än.
- Tester: playwright finns förinstallerat men ESM hittar det inte – länka `ln -sfn "$(npm root -g)" node_modules` tillfälligt i repot.

## Uppspelning i telefonen – nätbyten (Webb 4.9)
- Felsökningsloggen skriver nu rader med `phone …`: start/restart, waiting, lost (orsak), network wifi -> cellular, online/offline, plays again after X s, gave up.
- Webb 4.10: loggar `app opened` (med datum), `stop pressed`, tillstånd i `phone network`-raden; knappen Rensa felsökningslogg i Om RadioCast.
- Webb 4.11: vakt – om uppspelningens tid stått still 4 s utan att webbläsaren sagt något loggas `phone frozen t=… ahead=… rs=… ns=…` och strömmen startas om. `stalled` och Stopp loggar också position/buffert. Provat lokalt med låtsasström som fastnar och med frusen tid; inte på riktig telefon.
- Användaren 27 okt: ljudet blev plötsligt tyst utan några händelser i loggen (ingen waiting/lost/nätbyte) → vakten.
- Öppen fråga 27 okt: vid wifi -> none -> wifi (samma sekund) kom ingen `restart` och inget `waiting`/`lost`, användaren tryckte inte Stopp men startade själv igen efter ~30 s. Orsak okänd – vänta på logg från 4.10.
- Strömmen startas om direkt när Chrome (Android) säger att nättypen bytts (`navigator.connection` change, bara när `type` ändras).
- Stopp räknas som förlorat efter 4 s om strömmen redan spelat (förut 8 s); vid start fortfarande 8 s.
- Otestat mot riktiga nätbyten. Nästa steg: be om loggen efter ett hack. Övervägt sedan: ny ström bredvid den gamla (byte utan tystnad), fördröjningsbuffert 15–20 s (MSE, kräver CORS), sömlöst bara i Android-skalet (13).
