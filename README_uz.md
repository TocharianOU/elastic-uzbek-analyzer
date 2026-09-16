# Elasticsearch uchun oʻzbek tili analizatori

[![English](https://img.shields.io/badge/Language-English-blue)](README.md)
[![Yuklab olishlar](https://img.shields.io/github/downloads/TocharianOU/elastic-uzbek-analyzer/total)](https://github.com/TocharianOU/elastic-uzbek-analyzer/releases)
[![Litsenziya](https://img.shields.io/badge/litsenziya-Apache%202.0-blue)](LICENSE)

Elasticsearch uchun oʻzbek matnini tahlil qilish. Toʻrt xil yozuv bitta qidiruv
kalitiga keltiriladi, qoʻshimchali soʻzlar esa oʻzagiga qaytariladi. Shuning
uchun `qopqogi` deb yozgan xaridor `қопқоғи` deb yozilgan eʼlonni ham topadi.

## Muammo

Oʻzbek tili bir vaqtda toʻrt xil yoziladi va mahsulot katalogida ularning
hammasi uchraydi:

| Yozuv | "qopqogʻi" |
|---|---|
| Lotin, 1995 | `qopqogʻi` |
| Lotin, 2026 islohoti | `qopqoği` |
| Kirill | `қопқоғи` |
| Arab (`uzs`) | `قوپقوغی` |

Faqat apostrofning oʻzi oʻnlab koʻrinishda keladi yoki umuman yozilmaydi —
`qopqogʻi`, `qopqog'i`, `qopqog’i`, `` qopqog`i ``, `qopqogi`. Bularning
barchasi bitta soʻz.

Elasticsearch 34 ta til analizatori bilan keladi va ulardan faqat turkchasi
turkiy til. Shuning uchun bugun har bir yozilish faqat oʻzini topadi. 46 000
oʻzbek oʻzagidan iborat lugʻatda apostrof **toʻrt xil kodda uchraydi, toʻgʻrisi
esa bironta ham emas**.

## Oʻrnatish

Bitta buyruq. Elasticsearch versiyangizga mos keladiganini tanlang, soʻng
tugunni qayta ishga tushiring.

```bash
# Elasticsearch 8.x
bin/elasticsearch-plugin install https://github.com/TocharianOU/elastic-uzbek-analyzer/releases/latest/download/uzbek-analyzer-plugin-es8.zip
```

```bash
# Elasticsearch 9.x
bin/elasticsearch-plugin install https://github.com/TocharianOU/elastic-uzbek-analyzer/releases/latest/download/uzbek-analyzer-plugin-es9.zip
```

Bu havolalar doim eng soʻnggi relizga olib boradi, shuning uchun ularda
yangilab turiladigan versiya raqami yoʻq. Nazorat summalari har bir fayl yonida
eʼlon qilinadi; aniq versiyalar
[Releases](https://github.com/TocharianOU/elastic-uzbek-analyzer/releases)
sahifasida.

Docker uchun xuddi shu buyruq konteyner ichida bajariladi:

```bash
docker exec -it <konteyner> bin/elasticsearch-plugin install --batch \
  https://github.com/TocharianOU/elastic-uzbek-analyzer/releases/latest/download/uzbek-analyzer-plugin-es8.zip
docker restart <konteyner>
```

Oʻrnatilganini tekshirish:

```bash
curl localhost:9200/_cat/plugins?v
```

## Foydalanish

```
GET _analyze
{ "analyzer": "uzbek", "text": "Телефон учун қопқоғи" }
-> telefon  ucun  qopqoq

GET _analyze
{ "analyzer": "uzbek", "text": "Telefon uchun qopqogʻi" }
-> telefon  ucun  qopqoq
```

| Nomi | Turi | Vazifasi |
|---|---|---|
| `uzbek` | analizator | toʻliq zanjir, faqat oʻzak |
| `uzbek_split` | analizator | toʻliq zanjir, oʻzak va qoʻshimchalar |
| `uzbek_normalize` | char filter | har qanday yozuvni ichki shaklga keltiradi |
| `uzbek_tokenizer` | tokenizator | apostrof harf hisoblanadi, model kodlari buzilmaydi |
| `uzbek_morph` | token filter | himoyalaydi, soʻng oʻzakka qaytaradi; oldidan `uzbek_normalize` kerak |
| `uzbek_morph_split` | token filter | himoyalaydi, oʻzak va qoʻshimchalarni beradi |

### Tavsiya etilgan mapping

Uch maydon, chunki mahsulot nomini ortiqcha qisqartirish morfologik shaklni
topmaslikdan koʻra qimmatroq. Toʻliqlik tahlil qilingan maydondan, aniqlik
qolgan ikkitasidan keladi.

```json
{
  "settings": { "analysis": { "analyzer": {
    "uz_lemma": { "type": "custom", "char_filter": ["uzbek_normalize"],
                  "tokenizer": "uzbek_tokenizer", "filter": ["uzbek_morph"] },
    "uz_exact": { "type": "custom", "char_filter": ["uzbek_normalize"],
                  "tokenizer": "uzbek_tokenizer" }
  } } },
  "mappings": { "properties": { "title": {
    "type": "text", "analyzer": "uz_lemma",
    "fields": {
      "exact": { "type": "text",    "analyzer": "uz_exact" },
      "raw":   { "type": "keyword", "ignore_above": 256 }
    }
  } } }
}
```

Uchalasidan ham qidiring va aniq mos kelgani yuqoriroq chiqsin:

```json
{ "query": { "bool": { "should": [
  { "match": { "title":       { "query": "qopqogi" } } },
  { "match": { "title.exact": { "query": "qopqogi", "boost": 3 } } },
  { "term":  { "title.raw":   { "value": "qopqogi", "boost": 10 } } }
] } } }
```

Analizator qidiruv paytida ham ishlashi shart. Yozuvlar oʻrtasidagi moslik
indeks va soʻrov bir xil funksiyadan oʻtgani uchun ishlaydi.

## Qanday ishlaydi

```
L0  aniqlash     qaysi yozuv; oʻzbekcha kirill yoki ruscha
L1  normallash   bitta ichki shaklga keltirish, offset xaritasini saqlash
L2  ajratish     apostrof — harf; model kodlari butun qoladi
L3  himoya       morfologiya tegmasligi kerak boʻlgan soʻzlar
L4  morfologiya  shaklni lugʻatdan izlash, topilmasa qoʻshimchani ajratish
L5  mapping      yuqoridagi uch maydonli sxema
```

Ichki shakl — 2026-yil sentyabrida tasdiqlangan lotin alifbosi: `oʻ gʻ sh ch`
oʻrniga `ö ğ ş ç`. Har bir tovushga bitta kod toʻgʻri keladi, shuning uchun
keyingi qatlamlar qoʻsh harflar chegarasini kuzatmasdan ishlaydi.

Har bir qatlam alohida sinaladi. Raqamlar, har bir qaror sababi va sinab
koʻrilib rad etilgan yechimlar [docs/layer-status.md](docs/layer-status.md)
faylida.

## Oʻlchangan natijalar

45 ta test. Har bir oʻlchov qaysi manbada olingani koʻrsatilgan.

| | |
|---|---|
| Normallashtirish | soniyasiga ~400 ming token, bitta oqimda |
| Yozuvlar oʻrtasida | soʻzning har bir yozilishi bitta kalitga keladi |
| Kalit toʻqnashuvi | 42 869 oʻzakning 0,97% i (barcha diakritikani olib tashlashda 1,50%) |
| 3-qatlam ortiqcha himoyasi | 42 869 dan 368 tasi (0,86%), hammasi oʻrinli |
| 4-qatlam, faqat qoidalar | 37 762 shakldan 63,2% |
| 4-qatlam ortiqcha ajratishi | 65 727 oʻzakdan 292 tasi (0,44%) |
| Xotirada | ~15,6 MB, bir marta yuklanadi |

## Sinab koʻrish

[demo/](demo/) papkasi Elasticsearch va Kibanani koʻtaradi hamda turli yozuvda
yozilgan oʻn bitta eʼlonni indekslaydi. Bitta soʻrovni bir indeksda ham shu
plagin orqali, ham oddiy `standard` analizator orqali ishga tushiradi.

```
=== query: qopqogi

  standard analyzer  -- bugungi holat
    [3] Telefon uchun qopqogi himoya plyonkasi bilan
    -> 1 hit(s)

  uzbek analyzer     -- shu plagin
    [1] Samsung Galaxy A54 uchun silikon qopqogʻi qora
    [2] Xiaomi Redmi Note 12 qopqog'i shaffof
    [3] Telefon uchun qopqogi himoya plyonkasi bilan
    [4] Телефон учун қопқоғи кўк рангли
    [11] تېلېفون اوچون قوپقوغی
    -> 5 hit(s)
```

Sinov soʻrovlari va ularning kutilgan natijalari —
[demo/queries.md](demo/queries.md).

## Hozircha qila olmaydigan ishlar

Keyin bilib qolmaslik uchun shu yerda aytiladi.

- **Brend va oʻzlashma soʻzlar roʻyxati — dastlabki**, haqiqiy katalogdan emas,
  bozor haqidagi umumiy bilimdan tuzilgan. Haqiqiy eʼlon maʼlumotlari bilan
  almashtirish kerak boʻlgan birinchi narsa.
- **Hech bir oʻlchov haqiqiy katalog matnida olinmagan.** Yuqoridagi raqamlar
  lugʻatlardan. Ular analizator til jihatidan toʻgʻri ekanini koʻrsatadi, lekin
  mahsulot nomlarida qanday ishlashini emas.
- **Arab yozuvi tarjima qilinadi, ochib berilmaydi.** Bu yozuvda qisqa unlilar
  koʻpincha yozilmaydi, shuning uchun `قوپقوغی` lotin va kirill shakllari bilan
  bitta kalitga keladi, ammo `دولت` `dolt` boʻlib chiqadi — soʻz esa `davlat`.
- **Soʻz yasovchi qoʻshimchalar qamrab olinmagan.** Roʻyxat faqat shakl
  yasovchi, shuning uchun `oʻqi` va `oʻqituvchi` qoida bilan bogʻlanmaydi.
- **Manba maʼlumotlarida ayrim qoʻshimchali shakllar lemma sifatida turibdi**,
  shuning uchun `shahar` va `shahri` alohida qoladi. Buni tuzatish uchun
  qoidalar emas, tekshirilgan lemma roʻyxati kerak.

## Hissa qoʻshish

Eng foydali ikki narsa:

1. **Haqiqiy mahsulot nomlari.** Bir necha ming eʼlon nomi brend va oʻzlashma
   soʻzlar roʻyxatini haqiqatan sozlash uchun yetarli.
2. **Shu tarjimani tuzatish.** Bu matn ona tili soʻzlovchisi tomonidan
   tekshirilmagan. Notoʻgʻri yoki gʻaliz joylarini koʻrsangiz, iltimos, issue
   oching yoki pull request yuboring.

## Litsenziya

Apache 2.0. Qaysi til resurslaridan foydalanilgani va ularning litsenziyalari —
[NOTICE.txt](NOTICE.txt).
