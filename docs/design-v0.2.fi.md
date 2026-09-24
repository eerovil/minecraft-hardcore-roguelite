# Minecraft Hardcore Roguelite – suunnitteludokumentti v0.2

## 1. Perusidea

Modi muuttaa Minecraft Hardcoren rogueliteksi.

- Yksi maailma = yksi run.
- Pelaajalla on yksi elämä per run.
- Kuolema päättää runin lopullisesti.
- Kuoleman jälkeen pelaaja pääsee kauppaan.
- Kaupasta ostetut unlockit ja upgradet ovat pysyviä tuleville runeille.
- Uusi run alkaa uudessa maailmassa.
- Kaikki kaupan vaihtoehdot ovat lähtökohtaisesti näkyvissä ja ostettavissa heti, jos valuuttaa riittää.
- Pelaajaa ei pakoteta tiettyyn upgrade-polkuun.

Tavoitteena on, että ensimmäiset runit ovat selvästi vanilla Hardcorea vaikeampia, mutta pitkän progression jälkeen pelaaja on selvästi vanilla Hardcore -hahmoa vahvempi.

---

## 2. Balance-tavoite

Progressio jakautuu kahteen osaan:

### Vanilla-tason palauttaminen

Pelaaja aloittaa maailmassa, josta puuttuu useita tavallisia Minecraftin ominaisuuksia.

Tavoite on, että noin **viisi kohtuullisen onnistunutta runia** riittää suunnilleen palauttamaan vanilla-tason, jos pelaaja käyttää valuuttansa siihen.

Pelaajan ei kuitenkaan tarvitse ostaa kaikkia vanilla-unlockeja. Hän voi jättää esimerkiksi kuparin tai jonkin eläinlajin kokonaan avaamatta ja käyttää valuutan johonkin muuhun.

### Vanilla+

Vanillan yli menevät upgradet ovat pitkäaikaista endgame-progressionia.

- Ne ovat noin **10–50 kertaa kalliimpia** kuin tavalliset vanilla-tasoa palauttavat unlockit.
- Niitä voi ostaa heti alusta lähtien, jos pelaaja onnistuu säästämään tarpeeksi valuuttaa.
- Hinnat eivät vaihdu progression aikana.
- Täysin maxatulla buildilla Ender Dragonin pitäisi olla suhteellisen helppo.
- Max-build ei silti ole god mode: vakavat virheet voivat edelleen tappaa.

---

## 3. Unlockien tärkein suunnittelusääntö

Hyvän unlockin pitää olla pelaajalle **selkeästi havaittava**.

Jos jokin ominaisuus puuttuu maailmasta, pelaajan pitäisi huomata se nopeasti eikä vasta pitkän pelaamisen jälkeen.

Huono tilanne:

> Pelaaja pelaa 30 minuuttia ja huomaa vasta sitten, ettei jokin harvinainen rakenne tai kasvi voi spawnata.

Tämä tuntuu yllätykseltä ja ärsyttää.

Hyvä tilanne:

> Pelaaja avaa inventoryn ja näkee heti, että armor-slotit ovat lukossa.

Tai:

> Pelaaja aloittaa runin ja huomaa heti, ettei maailmassa ole puita eikä malmeja.

### Hyvä unlock

- selkeä
- binäärinen: on / ei ole
- näkyvä tai nopeasti havaittava
- muuttaa runin strategiaa
- ei perustu piilotettuun prosenttimuutokseen

### Vältetään

- mining speedin keinotekoinen hidastaminen
- ruoan täyttävyyden pienentäminen
- aseiden tai armorien epämääräinen heikentäminen
- harvinaisten rakenteiden poistaminen vain unlockin vuoksi
- harvinaisten kasvien poistaminen, jos pelaaja ei edes huomaa niiden puuttumista
- reseptikirjan tai crafting-reseptien keinotekoinen blokkaaminen
- Netherin tai Endin portaalien blokkaaminen sen jälkeen, kun pelaaja on jo rakentanut portaalin

Vaikeuden pitäisi tuntua **reilulta ja ymmärrettävältä**, ei hitaalta tai ärsyttävältä.

---

## 4. Lähtömaailmasta poistettavia asioita

### Puut (ei enää poisteta)

Puut olivat alun perin lukittu unlock, mutta ensimmäinen oikea testipelaus ilman cheat-komentoja
osoitti, että puuttomasta maailmasta ei päässyt alkuun lainkaan: ei puuta, ei työpöytää, ei
työkaluja eikä tapaa ansaita ensimmäistä valuuttaa (#60).

Puut generoidaan siksi normaalisti jo ensimmäisestä runista alkaen, eikä niitä myydä kaupassa.

Kun run alkaa rajatun world borderin sisällä, borderin sisällä on aina vähintään yksi
`#minecraft:logs`-blokki:

1. Jos worldgen jätti borderin sisään puuta, maailmaan ei kosketa.
2. Muuten spawn ja border siirretään lähimmän metsäisen biomin puiden viereen, enintään 512 blokin
   päähän. Maailma pysyy täysin vanillana.
3. Jos sellaista ei ole (esim. superflat), spawnin viereen kasvatetaan yksi tammi.

Seediä ei koskaan vaihdeta: nimetty seed pysyy samana.

### Malmit

Malmit voidaan unlockata erikseen.

Esimerkiksi:

- hiili
- rauta
- kupari
- kulta
- redstone
- lapis lazuli
- timantti

Unlockit eivät muodosta pakollista ketjua.

Esimerkiksi pelaaja voi:

- ostaa raudan ennen kuparia
- jättää kuparin kokonaan ostamatta
- säästää valuuttaa suoraan tärkeämmäksi kokemaansa unlockiin

### Eläimet

Eläinlajit voidaan unlockata yksitellen.

Esimerkiksi:

- lehmä
- sika
- lammas
- kana
- hevonen
- susi

Lähtömaailmassa eläimiä ei välttämättä ole lainkaan.

Jokainen ostettu eläin-unlock tuo kyseisen eläimen tulevien runien world generationiin/spawniin.

### Kylät

Kylät voivat puuttua kokonaan alusta.

Village-unlock tuo kylät mukaan tulevien maailmojen generointiin.

Tämä on riittävän merkittävä ja ymmärrettävä muutos, että se sopii unlockiksi.

---

## 5. Inventory-slotit unlockeina

Inventoryn käyttöliittymä näyttää heti, mitkä slotit ovat lukossa.

### Armor-slotit

Armor-slotit voivat olla alussa lukittuina.

Pelaaja voi edelleen craftata armorin, mutta sitä ei voi pukea ennen slotin avaamista.

Mahdollisia toteutuksia:

- kaikki neljä armor-slottia yhdellä unlockilla
- tai slotit erikseen, jos se osoittautuu kiinnostavammaksi

UI:n pitää näyttää lukitus erittäin selvästi.

### Offhand

Offhand-slot voi olla alussa lukittu.

Tällöin esimerkiksi kilven voi craftata normaalisti, mutta sitä ei voi käyttää ennen offhand-unlockia.

Tämä on parempi ratkaisu kuin kilpireseptin poistaminen, koska:

- pelaajan vanillatieto säilyy hyödyllisenä
- lukitus näkyy inventoryssa
- pelaaja ymmärtää heti, mikä puuttuu

---

## 6. World Border

World border on yksi tärkeimmistä world-progression järjestelmistä.

Borderissa on vain muutama suuri taso, ei kymmeniä pieniä portaita.

Alustava rakenne:

1. **Tiny**
2. **Medium**
3. **Large**
4. **Infinite**

Aiempi alustava kokoasteikko:

- Tiny: noin 128×128 tai mahdollisesti vielä pienempi
- Medium: noin 512×512
- Large: noin 2048×2048
- Infinite: käytännössä normaali rajaton maailma

Tarkat koot ratkaistaan playtestillä.

Border-unlockit ovat toisistaan seuraavia tasoja, mutta muun kaupan ei tarvitse seurata mitään lineaarista puuta.

---

## 7. Vaikeus alussa

Ensimmäisten runien pitää olla erittäin vaikeita, mutta ei ärsyttäviä.

Selvästi sopiva vaikeuselementti:

- viholliset tekevät enemmän damagea

Muita vaikeuksia haetaan ensisijaisesti siitä, että maailmasta puuttuu hyödyllisiä vanilla-asioita, eikä siitä että normaaleista toiminnoista tehdään hitaampia.

Esimerkiksi:

- pieni world border
- ei puita
- ei malmeja
- ei eläimiä
- ei armor-slotien käyttöä
- ei offhandia
- ei kyliä

Tämä tekee runeista keskenään erilaisia sitä mukaa kun pelaaja rakentaa maailmaa takaisin.

---

## 8. Natural regeneration

Natural regeneration ei ole asia, joka pitäisi palauttaa matkalla vanilla-tasoon.

Ajatus on, että regeneration kuuluu ennemmin **vanilla+ / erittäin kalliisiin upgradeihin**.

Tämän tarkka toteutus ja tasot päätetään myöhemmin.

---

## 9. Vanilla+ – permanentit status-efektit

Positiivisia Minecraftin status-efektejä voidaan myydä kaupassa pysyvinä upgradena.

Ajatus:

> Pelaaja ostaa efektin tai sen tason, ja efekti on aktiivinen automaattisesti jokaisessa tulevassa runissa.

Mahdollisia esimerkkejä:

- Speed
- Haste
- Strength
- Resistance
- Jump Boost
- Regeneration
- Fire Resistance
- Water Breathing
- Night Vision
- Luck

Kaikkia efektejä ei tarvitse välttämättä käyttää, jos jokin rikkoo peliä tai tekee siitä tylsän.

### Speed

Speed toimii hyvänä esimerkkinä asteittaisesta vanilla+ upgradesta.

Alustava tavoite:

- +10 %
- +20 %
- +30 %
- +40 %
- +50 % max

Maksimissaan pelaaja voisi siis liikkua noin 50 % normaalia nopeammin.

Vanilla+ status-upgradet ovat selvästi kalliimpia kuin tavalliset unlockit.

---

## 10. Hunger / ruokailu vanilla+

Vanilla+ puolella voidaan helpottaa hunger-järjestelmää.

Ajatus on vielä avoin, mutta mahdollisia vaihtoehtoja:

- hunger alkaa jokaisen runin alussa täynnä
- saturation alkaa tavallista korkeampana
- hunger kuluu hitaammin
- erittäin kallis endgame-upgrade voisi tehdä ruokahuollosta lähes merkityksettömän

Tavoite ei ole muuttaa early gamea tällä tavalla, vaan tarjota pitkän progression jälkeen selkeä mukavuus- ja power-upgrade.

---

## 11. Spawn chest / starttivarusteet

Vanilla+ kaupassa on oma **starttivaruste-järjestelmä**.

Kun uusi run alkaa, spawnin lähelle ilmestyy arkku.

Arkku sisältää kaikki ne starttivarusteet, jotka pelaaja on pysyvästi ostanut kaupasta.

Mahdollisia ostettavia asioita:

- ruoka
- puu
- työkalut
- aseet
- armor
- nuolet
- muut tavalliset vanilla-itemit

### Enchantatut starttivarusteet

Kalliimmissa upgradessa voidaan ostaa valmiiksi enchantattuja tavaroita.

Esimerkiksi:

- Efficiency I iron pickaxe
- Efficiency III diamond pickaxe
- Efficiency V diamond/netherite pickaxe
- Sharpness-miekkoja
- Fortune/Silk Touch -työkaluja
- enchantattua armoria

Tarkka sisältö ja hinnoittelu suunnitellaan myöhemmin.

Start chest toimii pysyvänä kokoelmana:

> Mitä enemmän startti-itemeitä pelaaja on ostanut, sitä paremmin varustettuna seuraava run alkaa.

---

## 12. Kaupan rakenne

Kaupan ei tarvitse olla yksi lineaarinen skill tree.

Pelaaja saa itse päättää, millaisen progression hän rakentaa.

Mahdollisia kategorioita:

### World
- yksittäiset malmit
- eläinlajit
- kylät
- world border

### Player / Vanilla Restoration
- armor-slotit
- offhand
- muut selkeät vanilla-toiminnot, joita löydetään myöhemmin

### Vanilla+
- permanentit status-efektit
- movement speed
- regeneration
- hunger-helpotukset
- muut vanilla-rajan ylittävät ominaisuudet

### Starting Gear
- ruoka
- työkalut
- aseet
- armor
- enchantatut tavarat

---

## 13. Economy

Yksi tärkeimmistä balance-tavoitteista:

> Pelaajan pitäisi voida saavuttaa suunnilleen vanilla-taso noin viidessä kohtuullisessa runissa, jos hän käyttää valuuttansa siihen.

Vanilla+ progression pitää kestää paljon pidempään.

Yleinen hintasääntö:

- vanilla-tasoa palauttava unlock: normaali hinta
- vanilla+ upgrade: noin **10–50× kalliimpi**

Kaikki hinnat ovat kiinteitä.

Kauppa ei vaihda hintoja sen mukaan, missä vaiheessa pelaaja on.

---

## 14. Endgame-tavoite

Ender Dragonin ei tarvitse olla voitettavissa vasta max-buildilla.

Tavoite:

- taitava pelaaja voi päästä pitkälle jo ennen kaikkia vanilla-unlockeja
- vanilla-tason ympärillä Dragon vastaa suunnilleen tavallista Hardcore-haastetta
- vanilla+ build tekee taistelusta asteittain helpomman
- täysin maxatulla buildilla Ender Dragon pitäisi pystyä voittamaan suhteellisen helposti

Max-buildin pitää tuntua palkitsevan voimakkaalta pitkän progression jälkeen.

---

## 15. Suunnittelufilosofia tiivistettynä

Modin ydinkysymys ei ole:

> “Miten tehdään Minecraftista hitaampi?”

Vaan:

> “Miltä Minecraft tuntuu, kun maailma on aluksi vajaa ja pelaaja ostaa sen ominaisuudet vähitellen takaisin – ja lopulta ylittää vanillan rajat?”

Hyvät upgradet muuttavat selkeästi sitä, **mitä maailmassa on** tai **mitä pelaaja voi tehdä**.

Vanilla+ puolella ne saavat muuttua myös selkeäksi power fantasyksi.
