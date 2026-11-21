# Geolocalização com PostGIS

Uma API de lugares e zonas de entrega sobre PostGIS: busca por raio, os k mais
próximos, o que aparece na janela do mapa, quais lugares uma zona cobre e quais
zonas cobrem um ponto. O projeto mostra o que costuma dar errado em consulta
geoespacial sem levantar erro nenhum: raio em graus em vez de metros,
latitude e longitude trocadas, SRID misturado, índice GiST que existe e não é
usado, polígono inválido aceito e área medida na projeção errada.

## Tecnologias

- **Java 21** e **Spring Boot 3.5**
- **PostgreSQL 16** com **PostGIS 3.5**
- **Hibernate Spatial** e **JTS** para mapear pontos e polígonos nas entidades
- **Spring Data JPA** para gravação e `NamedParameterJdbcTemplate` para as
  consultas espaciais, em SQL explícito
- **Flyway** para o schema
- **Testcontainers** com a imagem `postgis/postgis` nos testes
- **Gradle** (Kotlin DSL) com wrapper

## O modelo

```
place(id, name, category, location geometry(Point, 4326), created_at)
  GiST (location)                -- janela do mapa, polígono
  GiST ((location::geography))   -- raio e vizinho mais próximo em metros
zone(id, name, area geometry(Polygon, 4326), created_at)
  GiST (area)
  CHECK (ST_IsValid(area))
```

As coordenadas ficam em WGS 84 (SRID 4326) como `geometry`, e não como
`geography`: `geography` não tem `ST_Contains`, nem busca por envelope em
graus, nem `ST_Transform`. As distâncias em metros saem do cast para
`geography`, e esse cast tem índice próprio. O motivo está na seção de
índices.

## As armadilhas

### Graus não são metros

Sobre `geometry` em 4326, `ST_Distance`, `ST_DWithin` e `ST_Area` respondem em
graus. Dois pontos a 1 km de distância em São Paulo estão a `0.009` um do
outro, e um quadrado de 0,01° tem "área" `0.0001`. Pior que a unidade é a
forma: um grau de longitude encolhe com o cosseno da latitude, então um "raio"
de 0,01° é uma elipse. Em Oslo (60°N) ele alcança 1,1 km ao norte e só 556 m a
leste.

Toda medida da API é feita em `geography`, no esferoide WGS 84, em metros.

### Latitude e longitude

`ST_MakePoint(x, y)` é `(longitude, latitude)`. GeoJSON também é
`[longitude, latitude]`, e `Coordinate(x, y)` do JTS também. Trocar a ordem leva
São Paulo (-23,55; -46,63) para um ponto no Atlântico Sul a mais de 2 500 km, e
nenhuma validação de faixa percebe, porque -46,63 é uma latitude válida.

Por isso a API recebe `latitude` e `longitude` por nome, nunca um par, e um
único lugar no código (`Geometries`) monta pontos a partir desses nomes. O
GeoJSON das zonas segue a RFC 7946 e a mensagem de erro de posição fora da
faixa lembra a ordem.

### SRID

O `GeometryFactory` padrão do JTS cria geometrias com SRID 0, "desconhecido".
Uma coluna `geometry(Point, 4326)` **não** recusa SRID 0: o PostGIS o trata
como "use o SRID da coluna". Funciona por acaso quando as coordenadas são
longitude e latitude, e grava lixo quando não são: um ponto em metros UTM sem
SRID viraria uma "longitude" de 333 000. A coluna tem uma `CHECK` de faixa para
isso, e a aplicação usa uma fábrica única com SRID 4326. O que a coluna recusa
é um SRID conhecido e diferente (3857 numa coluna 4326), e operações entre
SRIDs diferentes falham com `mixed SRID`.

### Contido ou coberto

Um ponto exatamente na borda de um polígono não está *contido* nele
(`ST_Contains` responde falso), mas está *coberto* (`ST_Covers`). Para zona de
entrega, o cliente na divisa é atendido: a API usa `ST_Covers`. Buracos no
polígono (um parque no meio da zona) ficam de fora.

### Polígono inválido

A API recusa com 400 e o motivo, em vez de consertar:

- anel aberto (a primeira posição tem que repetir no fim);
- anel com menos de quatro posições;
- auto-interseção (a "gravata borboleta"), com o ponto onde ela acontece;
- polígono com mais de 180° de largura, que em `geometry` não atravessa o
  antimeridiano: dá a volta no mundo pelo outro lado.

`ST_MakeValid` transformaria a gravata em dois triângulos, uma área diferente
da que o usuário desenhou. A validação é feita com o JTS, para responder com o
motivo, e a `CHECK (ST_IsValid(area))` segura qualquer polígono que chegue à
tabela por outro caminho.

### Janela do mapa no antimeridiano

Uma janela sobre Fiji vai de 179°E a 179°W, e o cliente manda `west = 179`,
`east = -179`. `ST_MakeEnvelope(179, …, -179, …)` reordena os limites para -179
a 179: o mundo inteiro *menos* o que o usuário está vendo. Quando `west > east`
a consulta vira duas caixas, uma de cada lado.

## Índices

Cada consulta foi verificada no `EXPLAIN`, e os testes afirmam o nó e o nome do
índice:

| Consulta | Forma | Plano |
|---|---|---|
| raio | `ST_DWithin(location::geography, p, r)` | Bitmap Index Scan em `place_location_geog_gist` |
| k mais próximos | `ORDER BY location::geography <-> p LIMIT k` | Index Scan ordenado, sem Sort |
| janela do mapa | `location && ST_MakeEnvelope(...)` | índice de `location` |
| zona | `ST_Covers(z.area, p.location)` | Bitmap Index Scan em `place_location_gist` |

O que derruba o índice:

- **`ST_Distance(...) <= r` no WHERE.** Responde as mesmas linhas que
  `ST_DWithin`, mas é uma função comum: calcula a distância de todas as linhas.
  `ST_DWithin` expande o ponto numa caixa do tamanho do raio, pede ao índice os
  candidatos dentro dela e só então calcula a distância exata.
- **Cast no WHERE.** Com índice só em `location`, um predicado sobre
  `location::geography` é outra expressão, e o planejador não usa o índice. Por
  isso existe o índice de expressão sobre o cast. O teste derruba esse índice
  dentro de uma transação (DDL é transacional no PostgreSQL), lê o plano, vê o
  Seq Scan e desfaz com rollback.
- **Estatísticas velhas.** Depois de carga em massa a carga roda `ANALYZE`,
  para o planejador não estimar a seletividade com a tabela vazia.

O vizinho mais próximo usa `<->` em `geography`. Em `geometry` 4326 o mesmo
operador ordena por graus, e longe do equador a ordem sai errada: em Oslo, um
lugar 0,010° a leste (557 m) perde para um 0,006° ao norte (667 m). O `<->` em
`geography` mede numa esfera, e a distância devolvida é no esferoide; a ordem
só poderia divergir em empates de menos de 0,3%.

### A comparação sem índice

Os endpoints em `/api/admin/compare` rodam a **mesma consulta** com
`SET LOCAL enable_indexscan = off` e `enable_bitmapscan = off`, que valem até o
fim da transação e não vazam para o pool. Um detalhe torna isso traiçoeiro:
depois de cinco execuções o driver JDBC passa a usar *prepared statement* no
servidor, e o PostgreSQL guarda o plano por conexão. Um plano guardado com
índice continua sendo usado depois do `SET`, e a comparação mediria o índice
dos dois lados. A versão sem índice leva um comentário no SQL, o que a torna
outro statement, com cache próprio. O teste confere pelos contadores de
`pg_stat_xact_user_tables` que houve Seq Scan, e não pelo tempo.

## Área e projeção

O mesmo quadrado de 0,02° × 0,02°, medido de cinco jeitos, em três latitudes:

| Lugar | Graus² | Esferoide (referência) | Esfera | Web Mercator (3857) | UTM local |
|---|---|---|---|---|---|
| Equador | 0,0004 | 4 923 629 m² | -0,22% | +0,7% | +0,013% (32632) |
| São Paulo (23,6°S) | 0,0004 | 4 522 875 m² | -0,22% | +19,6% | -0,009% (32723) |
| Oslo (59,9°N) | 0,0004 | 2 492 693 m² | -0,22% | +296,7% | -0,057% (32632) |

Graus² dá o mesmo número para áreas reais que diferem quase 2×. Web Mercator,
a projeção dos mapas da web, estica os dois eixos por 1/cos(latitude): serve
para desenhar, nunca para medir. A zona UTM do próprio lugar (calculada a
partir do centroide) fica a menos de 0,06% do esferoide.

## API

| Método | Rota | Descrição |
|---|---|---|
| `POST` | `/api/places` | cria um lugar (`name`, `category`, `latitude`, `longitude`) |
| `GET` | `/api/places/{id}` | lê um lugar |
| `GET` | `/api/places/nearby` | `latitude`, `longitude`, `radius` (1 a 50 000 m), `category`, `limit` |
| `GET` | `/api/places/nearest` | `latitude`, `longitude`, `k` (1 a 100), `maxDistance` |
| `GET` | `/api/places/in-viewport` | `west`, `south`, `east`, `north`, `limit`; `west > east` cruza o antimeridiano |
| `POST` | `/api/zones` | cria uma zona (`name`, `area` em GeoJSON Polygon) |
| `GET` | `/api/zones/{id}` | zona em GeoJSON, com área e perímetro em metros |
| `GET` | `/api/zones/{id}/places` | lugares cobertos pela zona, com o total |
| `GET` | `/api/zones/{id}/area-comparison` | área em graus², esferoide, esfera, Web Mercator e UTM |
| `GET` | `/api/zones/covering` | zonas que cobrem `latitude`, `longitude` |
| `POST` | `/api/admin/seed?count=` | gera lugares aleatórios na Grande São Paulo, com semente fixa |
| `GET` | `/api/admin/compare/...` | `nearby`, `nearest` e `zones/{id}/places` com `strategy` (`INDEXED`, `NO_INDEX`, `DISTANCE_FILTER`) e `explain` |

## Como executar

```bash
export DOCKER_HOST=unix:///var/run/docker.sock   # se o contexto padrão não responder
docker compose up -d --wait
./gradlew bootRun
```

PostGIS em 5432 e a aplicação em 8080.

### Exemplos

```bash
# um lugar
curl -s -X POST localhost:8080/api/places -H 'Content-Type: application/json' \
  -d '{"name": "Masp", "category": "museum", "latitude": -23.5614, "longitude": -46.6559}'

# o que há a 1 km, mais perto primeiro, com a distância em metros
curl -s 'localhost:8080/api/places/nearby?latitude=-23.5613&longitude=-46.6565&radius=1000'

# os 5 mais próximos
curl -s 'localhost:8080/api/places/nearest?latitude=-23.5613&longitude=-46.6565&k=5'

# uma zona de entrega; posições em [longitude, latitude]
curl -s -X POST localhost:8080/api/zones -H 'Content-Type: application/json' -d '{
  "name": "Paulista",
  "area": {"type": "Polygon", "coordinates": [[
    [-46.67, -23.57], [-46.65, -23.57], [-46.65, -23.55], [-46.67, -23.55], [-46.67, -23.57]]]}}'

# entregam aqui?
curl -s 'localhost:8080/api/zones/covering?latitude=-23.5614&longitude=-46.6559'

# polígono que cruza a si mesmo: 400 com o motivo
curl -s -X POST localhost:8080/api/zones -H 'Content-Type: application/json' \
  -d '{"name": "Bow tie", "area": {"type": "Polygon", "coordinates": [[[0,0],[1,1],[1,0],[0,1],[0,0]]]}}'

# 200 mil lugares e o plano da busca por raio sem índice
curl -s -X POST 'localhost:8080/api/admin/seed?count=200000'
curl -s 'localhost:8080/api/admin/compare/nearby?latitude=-23.6&longitude=-46.63&radius=1000&strategy=NO_INDEX&explain=true'
```

## Testes

48 testes de integração contra PostGIS real, num único container para toda a
suíte.

- **UnitsAndAxesIT**: caracterização das armadilhas. Distância e área em graus,
  o raio em graus que vira elipse em Oslo, eixos trocados que passam na
  validação, SRID 0 aceito pela coluna, ponto em metros barrado só pela `CHECK`
  de faixa, SRID diferente recusado, `mixed SRID`, `ST_Contains` contra
  `ST_Covers` na borda.
- **PlaceQueriesIT**: raio em metros também perto do polo, filtro por
  categoria, k mais próximos com distância máxima, a ordem errada do `<->` em
  graus contra a certa em metros, janela com truncamento e janela sobre o
  antimeridiano (e a caixa ingênua que devolve o lado oposto do mundo).
- **ZonesIT**: área e perímetro em metros, borda incluída e buraco excluído,
  anel aberto, anel curto, gravata borboleta, tipo errado, posição fora da
  faixa, polígono largo demais, nome duplicado, `CHECK` do banco e a área nas
  cinco medidas.
- **PlansIT**: com 20 000 lugares e `ANALYZE`, o índice usado em cada
  consulta, o Seq Scan do `ST_Distance` no WHERE, o cast que ignora o índice
  de `geometry`, o KNN sem Sort, as mesmas linhas com e sem índice, o
  `SET LOCAL` que não vaza para o pool e o plano em cache que não engole a
  comparação.
- **ApiIT**: contrato HTTP, validação, GeoJSON de volta na ordem certa, 404,
  409 e o endpoint de comparação.

```bash
./gradlew clean build
```

## Medições

Setup: tudo numa única sessão, com o jar empacotado (`bootJar`, JDK 21), o
daemon do Gradle parado e PostGIS 16-3.5 em container no mesmo host (12
núcleos), com a configuração padrão da imagem. 200 000 lugares aleatórios na
Grande São Paulo, gerados no banco com semente fixa em 3,8 s, seguidos de
`ANALYZE`. Tabela de 19 MB; índice GiST de `geometry` com 8 MB, de
`geography` com 12 MB. O cliente é `scripts/measure.py`, em Python só com a
biblioteca padrão; as latências são medidas no cliente, passando pela API, em
20 pontos de consulta alternados, com 20 chamadas de aquecimento descartadas e
60 medidas por série.

### Raio

| Raio | Linhas (média) | `ST_DWithin` com índice | mesma consulta sem índice | `ST_Distance <= r` |
|---|---|---|---|---|
| 500 m | 60 | 2,8 ms | 225,7 ms | 235,5 ms |
| 1 km | 244 | 3,1 ms | 224,4 ms | 234,2 ms |
| 5 km | 6 192 | 32,1 ms | 230,7 ms | 244,1 ms |

Valores em p50. Sem índice, o custo é o mesmo
para qualquer raio: a distância no esferoide é calculada para as 200 000
linhas. Com índice, o custo acompanha o número de candidatos, e num raio de 5 km
a maior parte do tempo já é ordenar e serializar 6 mil linhas. O plano sem
índice passa do limite de custo do JIT do PostgreSQL, e parte do tempo é
compilação: no `EXPLAIN ANALYZE` a mesma consulta levou 211 a 257 ms com JIT e
165 a 173 ms sem ele. Continua 50 vezes mais lenta.

A API real, com `limit` de 50: busca por raio de 1 km em 3,2 ms (p95 3,5 ms).

### Vizinho mais próximo (k = 10)

| | p50 | p95 | Plano |
|---|---|---|---|
| `<->` com índice | 1,6 ms | 1,9 ms | Limit → Index Scan |
| sem índice | 97,3 ms | 100,2 ms | Limit → Gather Merge → Sort → Seq Scan |

O índice devolve as linhas já em ordem de distância e para na décima. Sem ele,
o banco calcula a distância de todas e ordena.

### Polígono

Zona de 4,52 km² (perímetro de 8,5 km) na Paulista, cobrindo 343 lugares:
2,9 ms com o índice de `location`, 231,0 ms sem ele.

### Graus contra metros

Na mesma massa, a partir do centro de São Paulo, `ST_DWithin` com 0,009° (que
corresponde a 1 km no sentido norte-sul) encontra 244 lugares; com 1 000 m em
`geography`, 269. A elipse perde 9% dos lugares, nas laterais leste e oeste.

## Estrutura

```
com.gkcontas.geo
├── spatial    fábrica de geometria (SRID 4326, lon/lat), GeoJSON, leitura de plano
├── place      entidade, serviço e as consultas espaciais em SQL
├── zone       entidade, validação, cobertura e área por projeção
├── baseline   as mesmas consultas com e sem índice, para comparação
├── seed       carga em massa com semente fixa
└── api        controllers, DTOs e tratamento de erros
```
