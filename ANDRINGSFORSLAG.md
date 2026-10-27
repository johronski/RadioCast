# Ändringsförslag

Byggpaus till och med lördag 31 oktober 2026. Nya önskemål samlas här i stället för att byggas direkt.
Regel (26 okt): under pausen frågas alltid "Är du säker?" innan något ändras – även buggrättningar och små ändringar.

Prioriterad lista (24 okt). Snarlika förslag är sammanslagna. Nytta = för nya användare (vänner) och dig, insats = ungefärlig storlek.

## Steg 1: Första intrycket — KLART i Webb 3.0 (24 okt)
Grundlista efter språk, högtalarknapp i sidhuvudet (sortering/redigering i ⋯-menyn), pil på spelarkortet, hemskärmsknapp under listan. Webb 3.1: välkomstrutan borttagen, Mouv' i startlistan.

## Först efter pausen: baskrav (Kano)
0. KLART i Webb 3.4: automatisk återanslutning (upp till ca 1 min, direkt när nätet är tillbaka). Ursprungligen: kontrollera och säkra automatisk återanslutning när nätet hackar (wifi ↔ mobildata, kort tapp i bilen eller på promenad). Måste fungera innan nya överraskningar.

## Stabilitet — KLART i Webb 4.4 (26 okt)
Castningen samlad i docs/js/cast.js med ett tillstånd (ditt val / högtalarens besked). 19 testfall med låtsas-Chromecast (web-tests/cast.mjs + castsim.js) körs vid varje ändring. Lägg till ett testfall för varje nytt castningsfel innan det rättas.

## Steg 2: Putsa (medel nytta, liten insats)
5. Dölj bithastighet i listan, utom där den skiljer två strömmar av samma kanal.
6. Volymreglaget syns bara vid castning. (Delvis klart i Webb 3.2: dolt på iPhone/iPad.)
7. ⋯-menyn: egna, tydliga ikoner per rad.
8. Tydligare hjälp när Chrome inte hittar några högtalare.
9. Låtvyn: mindre logotyp, låt och historik först.
9c. Volymkurva (25 okt): örat hör logaritmiskt, reglaget är rakt → svårt att fininställa låga nivåer. Låt volym = läge² (Chromecast, som kanske redan har egen kurva) resp. läge³ (telefonen); 50 % på reglaget ≈ 12,5 % signal. Räkna om sparad volym så inget hoppar vid uppdateringen. Provas med örat. Liten insats.
    - Plan (27 okt, ej byggd): bara `docs/js/app.js`, `cast.js` orörd, ca 10 rader. Hjälpfunktioner `curve(pos, n) = (pos/100)^n` och omvänd `100·nivå^(1/n)`; n = 3 i telefonen, n = 2 vid castning. Rad ~197 och ~287: skicka `curve(...)` i stället för `v/100`. Rad ~593: reglaget visar högtalarens volym via omvänd kurva (stämmer även om annan app ändrat). Spara läget under ny nyckel `volumePos`; saknas den räknas gamla `volume` om med `100·(gammal/100)^(1/3)`. Höj version.
    - Test: inget befintligt test rör volym (castsim har tom `setVolumeLevel`) → inga nya testfall. Kör `node --test web-tests/*.test.mjs` och `smoke.mjs`; `cast.mjs` kan hoppas över. Avgörande är örat: låga lägen i telefon och riktig högtalare. n = 2 vid castning är en bedömning, otestad mot riktig hårdvara.
9b. Högtalarvalet ner till spelaren (25 okt): volymraden blir "var och hur högt" – [högtalarikon] Kök ▾ till vänster, volymreglaget till höger (långt från stoppknappen, ingen ny rad). På iPhone bara högtalarvalet. Sidhuvudet behåller en liten ikon utan text (syns innan något spelat, ifylld vid castning). Skäl: tummen når, hör ihop med ljudet och volymen, känt mönster (Spotify Connect). Undvik: två likadana textknappar, ikon intill stopp (feltryck).

## Steg 3: Mer innehåll (medel nytta, medel insats)
10. Sök: fler genrer synliga (två rader/rutnät), populära kanaler i tomläget.
11. Riktiga kanallogotyper där radio-browser har dem.
12. Sovtimer som tonar ut i stället för att tystna tvärt.

## Steg 4: Större arbete (beslut behövs först)
13. Android-skal runt webbappen: sömlöst högtalarbyte utan avbrott, se alla högtalare. Insats: medel (webbkoden återanvänds; bara `cast.js` får en brygga till appen).
    - Förslag 26 okt: använd Android-appens **egna castklient** (CastClient/CastDiscovery) i stället för Googles bibliotek. Användaren upplevde inga problem med den i vardagen, den går förbi Chromes fjärrspelare (källan till dagens castningsproblem) och ger extra förmågor (se nedan). Bara provad med användarens egna högtalare. Skalet blir då ett tillägg för Android, webbappen förblir huvudappen.
    - Utrett 26 okt: inget hinder för Googles Cast-bibliotek (play-services-cast-framework) – inget Google-konto, standardmottagaren kräver ingen registrering/avgift (bara egna mottagare gör det; kontrollera villkoren), grupper stöds, ljudformatet spelas av mottagaren. Varför Android-appen har en egen castklient är inte dokumenterat; koden visar extra förmågor: status för alla högtalare före anslutning (t.ex. "Spotify", grupp), styra/stänga andra appar på högtalaren, anslutning via IP-adress, inget beroende av Play-tjänster. Kan återanvändas i skalet om de behövs.
    - Uppspelning i telefonen: ExoPlayer/Media3 (snabb start, http och HLS) – behövs troligen i skalet, eftersom en webbsida i en app spelar dåligt i bakgrunden och på låsskärmen.
14. "Dela kontroll"-länk i Android-appen så att webbappen kan ta över en cast som Android-appen startat. Låg prioritet så länge Android-appen är fryst; blir onödig om 13 byggs.
15. Chromecast från iPhone (25 okt) – stort och komplicerat, tveksam nytta just nu. Webbsidor kan aldrig casta på iPhone (Safari saknar stöd, Google stöder inte cast i Chrome på iOS); egen ljuddrivrutin går inte på iPhone. Vägar som fungerar:
    - A. Brygga hemma (AirConnect) på Raspberry Pi/NAS/Home Assistant: varje Chromecast syns som AirPlay-högtalare i appens AirPlay-knapp. Ingen kodändring. Grupper fungerar troligen inte; ca 1–2 s fördröjning. Bara hemma. Insats: liten.
    - B. Egen iPhone-app med Googles Cast-stöd (som Spotify/YouTube), ev. gemensamt projekt med Android-skalet (13). Apples utvecklarprogram 99 $/år, bygge på Mac (GitHub kan), App Store/TestFlight, användaren måste installera. Insats: stor.
    - C. Hemmaserver (t.ex. Home Assistant) som castar på uppdrag av webbappen; kräver https och inloggning. Bara hemma. Insats: medel.
    - D. Google-assistenten på iPhone ("spela P3 i köket") – inget att bygga, inte kopplat till RadioCast.

## Attraktiv kvalitet: nya idéer (Kano-genomgång 24 okt)
Favoriter i ordning: A, B, C.
- **A. "Vilken låt var det?"** Sök på ungefärlig tid och kanal; hämtar låtlistan från SR och Radio France, även för kanaler du inte lyssnade på. Stor nytta, liten–medel insats.
- **B. Vad spelas på alla kanaler just nu.** Aktuell låt under varje kanal i listan (bara när sidan syns). Stor överraskning, medel insats.
- **C. (KLART i Webb 3.8) Byt kanal med hörlurar och bilens ratt.** Nästa/föregående på låsskärm, hörlurar och i bilen bläddrar bland dina kanaler. Liten insats.
- **D. Prat-hoppet.** När kanalen pratar: erbjud en kanal i listan som spelar musik just då ("P3 pratar, FIP spelar … Byta?"). Frågar alltid, byter aldrig själv. Medel insats.
- **E. Spara låten.** Hjärta i låtvyn → "Mina låtar", öppna alla i Spotify eller dela listan. Liten insats.
- **F. Överraska mig.** Slumpa en okänd kanal i en genre du gillar, helst en som spelar musik just då. Liten insats.
- **G. Snålläge på mobildata.** Välj samma kanal i lägre kvalitet där den finns. Liten insats.
- **G2. Flytta till wifi direkt.** När telefonen byter från mobilnät till wifi (Chrome på Android säger till) flyttar appen strömmen själv, med bara ett kort hack, i stället för att vänta på att Android klipper mobildatan. Sparar surf. Liten insats.
- **H. Dela låten.** "Just nu på FIP: …" med länk där mottagaren kan lyssna direkt. Mycket liten insats.

## Inspiration från andra kategorier (24 okt, "tänk vilt")
Favoriter i ordning: I, J, K.
- **I. Låtprognos + låtlarm** (väder- och sportappar). Radio France publicerar kommande låtar: "Om 4 min på FIP: …". Bevaka artister/låtar från "Mina låtar" på alla kanaler (medan appen är öppen).
- **J. Scanna** (bilradion). Spelar 8 s av varje kanal tills du trycker Stopp.
- **K. RadioCast Wrapped** (Spotify Wrapped/Strava). Timmar per kanal, mest hörda artister, upptäckter – räknas lokalt ur historiken, lätt att dela.
- **L. Kanaldejting** (Tinder). 15 s av en okänd kanal, svep höger = spara, vänster = nästa.
- **M. Radioscener** (Philips Hue). "Morgon" = P1 i köket låg volym, "Middag" = FIP i vardagsrummet. Ett tryck.
- **N. Radiopasset** (Pokémon Go). Stämpel per land du lyssnat på, världskarta som fylls i.
- **O. Shazam utan mikrofon.** Visar vad alla kända kanaler spelar just nu, du pekar ut låten du hör på caféet.
- **P. Pausa radion** (TV-box). Fortsätt där du var efter ett samtal; spelar in i bakgrunden. Kontrollerat 25 okt: bara bakåt till när du började lyssna (30–60 min buffert i telefonen); SR:s och Radio Frances strömmar tillåter det tekniskt (CORS). Till programmets början går inte med öppna källor (Radio France HLS sparar bara ~28 s, SR har ingen öppen spolbar ström). Medel–stor insats, säkrast på Android.
- **Q. Programguide** (tablåer). SR:s tablå, påminnelse när ett program börjar.
- **R. Köksläge** (fotoram). Omslag och låt i helskärm på gammal platta/TV.
- **S. Språkbadet** (Duolingo). Daglig lyssning på ett språk du lär dig, med svit.
- **T. Röst-DJ.** Talsyntes presenterar mellan byten.
- **U. Säg det.** Röststyrning: "Spela FIP i köket".
- **V. Lyssna där vännen är / lokal radio utomlands.**
- **W. Låtstafett.** Dela en låt, vännen svarar med en från sin kanal, en gemensam lista växer.
- **X. Väderradio.** Föreslår kanal efter väder och tid; du bestämmer.

Obs: idéer som ska hända vid en viss tid när appen är stängd (påminnelser, väckning, larm i bakgrunden) kräver Android-skalet (punkt 13).

## Ännu vildare: radions grundidéer (24 okt)
Grundidéer: ett fönster mot någon annans nu · att lämna över valet till en människa · sällskap i bakgrunden · gemensam tid · ljud som formar ett rum.
Favoriter i ordning: Y1, Y2, Y3.
- **Y1. Kompassradion** (kompassen). Rikta telefonen åt ett håll och hör radio därifrån (telefonens kompass + kanalernas koordinater).
- **Y2. Solnedgångsradion** (astronomi). Spelar alltid en kanal där solen går ner just nu, följer skymningen runt jorden.
- **Y3. Lägereld** (gemensam tid). Bjud in en vän att lyssna samtidigt, t.ex. FIP fredag 18:00, via kalenderinbjudan med länk. Ingen server.
- **Y4. Radiotåget** (fönsterplatsen). Välj en rutt; var 20:e min byter appen till en kanal längre fram längs vägen, med karta.
- **Y5. Flyttfågelläget.** På vintern spelar kanaler där det är sommar.
- **Y6. Fiskelyckan.** Lägg ut ett drag (artist/låt/genre); appen bevakar alla kanaler den kan läsa och nappar när något passar spelas.
- **Y7. Låtminnen.** Välj ett foto och se vad som spelades på t.ex. P3 när det togs.
- **Y8. Kanalträdgården.** Kanaler du aldrig spelar tonas ner, nya förslag spirar.
- **Y9. DX-jakten** (amatörradio). Poäng för avstånd: "Längsta hittills: Nya Zeeland, 17 300 km."
- **Y10. Dygnsringning.** Kanalförslag i dygnets rytm, från din egen historik.
- **Y11. Radiodagboken.** En rad per dag skrivs av sig själv.
- **Y12. Vänners vänner.** Kanaler två steg bort via delade listor.
- **Y13. Antialgoritm-identitet.** "Här väljer människor, inte maskiner" – lyft fram att en programledare valde låten.
- **Y14. Jordens puls.** Hur många kanaler spelar samma låt just nu.
- **Y15. Tystnadens radio.** Bara instrumentalt, eller tystnar när någon pratar i rummet (mikrofon).

## Y1 i detalj: Kompassradion (24 okt)
**Känsla:** som en gammal kortvågsradio, fast man vrider jorden i stället för en ratt.

**Användning**
1. Öppna kompassläget (⋯-menyn eller egen knapp). Stor kompassros, du i mitten.
2. Vrid dig. Mellan kanalerna hörs svagt brus.
3. Håll stilla ca 1,5 s → låser på kanalen: kort vibration, bruset tonar bort, kanalen tonar in.
4. Skärmen visar stad och land, avstånd ("Dakar · 5 400 km"), lokal tid där ("21:40, mörkt"), kanalnamn och låt om den finns.
5. Avstånd med lutning: platt = närmast i riktningen, luta uppåt = längre bort (Göteborg → Lissabon → Brasilien).

**Magiska detaljer**
- Jorden är rund: rakt västerut från Stockholm hamnar man i Kanada, inte New York. Visa linjen på en liten jordglob.
- Hav i riktningen: bara brus och "Atlanten · nästa land om 4 800 km".
- Peka mot golvet: radio från andra sidan klotet (runt Nya Zeeland).
- Fyrläget: telefonen på bordet, appen vrider sig en grad i taget – världsresa på ca en timme.
- Mot en vän: välj en väns stad, kompassen pekar dit och spelar radio därifrån.
- Till högtalaren: telefonen är fjärrkontroll, låst kanal startar i vardagsrummet.

**Teknik**
- Din position stannar i telefonen; alternativt välj stad manuellt.
- radio-browser har koordinater: hämta ett par tusen populära, nyligen fungerande kanaler, sortera på riktning (storcirkel) och avstånd.
- Kompassen läses från webbsidan: Android direkt, iPhone efter knapptryck och tillåtelse.
- Utan kompass (dator): dra kompassrosen med finger/mus – fungerar för alla.
- Bruset skapas i appen; fyller också laddtiden när en ström startar.

**Risker**
- Kompassen kan visa 10–20° fel nära metall → breda sektorer (~10°), ibland kalibrering (rör telefonen i en åtta).
- Trasiga strömmar → hoppa tyst till nästa i samma riktning.
- 1–3 s starttid → döljs av bruset.

**Första versionen**
1. Kompassros att dra med fingret, kompassen som bonus där den finns.
2. Ett par tusen kanaler med koordinater, sektorer om 10°.
3. Brus mellan kanaler, låsning vid stillhet, stad/avstånd/lokal tid.
4. Avstånd med skjutreglage först, lutning senare.

Insats: medel (ungefär som sökfunktionen).

**Sömlösa byten i Kompassradion (25 okt)**
- *Varma kanaler:* medan du vrider spelar 2–3 kanaler tyst i förväg (den du pekar mot + grannarna). Vid låsning slås ljudet bara på – ingen starttid (live = redan "i nuet").
- *Förutse riktningen:* vridriktning och hastighet styr vilka som förladdas; kanaler bakom dig stängs.
- *Bruset som kuliss:* genereras i telefonen, fyller ut när en kanal inte hunnit bli varm.
- *Snabba kanaler först:* appen mäter starttid per kanal, minns den och väljer snabba i varje riktning; tröga/trasiga sorteras bort.
- *Data och batteri:* förladdning bara i kompassläget; 3 varma på wifi, 1 på mobildata (3 × 128 kbit/s ≈ 170 MB/h); övriga stängs vid låsning.
- *Begränsningar:* mjuk övertoning kräver att stationen tillåter ljudbearbetning (CORS), annars rent klipp utan tystnad (bruset mjukar upp). iPhone: volym kan inte ändras från webbsidor, bara av/på; om flera samtidiga strömmar tillåts måste testas. Chromecast kan inte förladda → vrid och lyssna i telefonen, "Skicka till högtalaren" när du hittat rätt (2–5 s en gång).
- *Resultat:* i telefonen i praktiken omedelbart när kanalen är varm, annars ett par sekunders brus.
