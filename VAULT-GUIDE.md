# Vault + AppRole + Agent: полное руководство проекта (Redis + Postgres + Keycloak)

Единый источник правды по настройке Vault. Ручное управление через CLI и
bootstrap-контейнеры, без Terraform.

Redis и Postgres используют **статичные KV-секреты** (раздел 6). Keycloak —
первый сервис в проекте, использующий **динамические** Postgres-креды через
Database Secrets Engine (раздел 6.1) — паттерн, который позже будет
скопирован на job-service и subtitle-service.

---

## Содержание

1. [Зачем нужен Vault](#1-зачем-нужен-vault)
2. [Установка Vault CLI](#2-установка-vault-cli)
3. [Общая архитектура](#3-общая-архитектура)
   3.1. [Терминология: "роль" на трёх разных уровнях](#31-терминология-роль-на-трёх-разных-уровнях)
4. [Жизненный цикл секретов](#4-жизненный-цикл-секретов)
5. [Полное дерево файлов проекта](#5-полное-дерево-файлов-проекта)
6. [Первоначальная настройка](#6-первоначальная-настройка-один-раз-за-всю-жизнь-проекта)
  * [6.1 Database Secrets Engine и Keycloak — что происходит внутри Шага 6](#61-database-secrets-engine-и-keycloak-что-происходит-внутри-шага-6)
7. [Обычный (повторный) запуск](#7-обычный-повторный-запуск-проекта)
8. [Перезапуск / пересоздание контейнеров и volume](#8-перезапуск--пересоздание-контейнеров-и-volume)
9. [Добавление нового сервиса](#9-добавление-нового-сервиса)
10. [Удаление сервиса](#10-удаление-сервиса)
11. [Все файлы конфигурации](#11-все-файлы-конфигурации)
12. [Альтернатива: доставка secret_id через CI/CD](#12-альтернатива-доставка-secret_id-через-cicd)
13. [Частые ошибки](#13-частые-ошибки)
14. [Шпаргалка команд](#14-шпаргалка-команд)

---

## 1. Зачем нужен Vault

Правило проекта: **ни один секрет не должен лежать в `.env`, docker-compose.yaml
или закоммиченном конфиг-файле.** Redis и Postgres — готовые (stock) образы,
у них нет встроенной поддержки чтения секретов из внешнего хранилища.
Единственный способ передать им пароль, не коммитя его в репозиторий, —
сгенерировать конфиг-файл с реальным значением **во время старта контейнера**,
беря пароль из Vault. Этим занимается Vault Agent.

Секрет физически существует только:
- внутри самого Vault (зашифрованное хранилище, volume `vault-data`);
- в оперативной памяти / временном файле контейнера в момент работы.

Он никогда не существует в git, в образе Docker или в CI-логах, если всё
настроено правильно.

**Три роли в системе:**

| Компонент | Что делает |
|---|---|
| **Vault (сервер)** | Хранит секреты (KV), хранит правила доступа (policy), выдаёт временные учётные данные (AppRole) |
| **AppRole** | Аутентификация "для машин, не для людей": `role_id` (кто ты) + `secret_id` (докажи это) → временный токен |
| **Vault Agent** | Сайдкар рядом с каждым сервисом: логинится в Vault вместо приложения, следит за сроком токена, рендерит секрет в обычный файл на диске |

**Почему не подключать Redis/Postgres к Vault напрямую?** Потому что
`redis-server` и `postgres` не умеют разговаривать с Vault API. Проще дать
им то, что они и так понимают — обычный конфиг-файл. Vault Agent берёт на
себя логин, TTL, ротацию; сервис просто читает файл, как будто это
статичный конфиг.

---

## 2. Установка Vault CLI

Отдельный бинарник, который ставится **на хост-машину** (или CI-раннер),
чтобы разговаривать с Vault-сервером (который работает в контейнере) по
HTTP API через проброшенный порт `8200:8200`.

### macOS
```bash
brew tap hashicorp/tap
brew install hashicorp/tap/vault
```

### Ubuntu / Debian
```bash
wget -O- https://apt.releases.hashicorp.com/gpg | sudo gpg --dearmor -o /usr/share/keyrings/hashicorp-archive-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/hashicorp-archive-keyring.gpg] https://apt.releases.hashicorp.com $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/hashicorp.list
sudo apt update && sudo apt install vault
```

### Проверка
```bash
vault version
```

CLI — это только клиент. Сам сервер работает в Docker-контейнере (раздел 6).

---

## 3. Общая архитектура

```
Vault (сервер): KV secrets (secret/redis, secret/postgres),
                Policies (redis-policy, postgres-policy, bootstrap-policy),
                AppRole roles (redis-role, postgres-role)
        ▲                                              ▲
        │ (1) читает role-id, создаёт secret-id         │ (2) логин через
        │     (право: bootstrap-policy)                  │     role-id + secret-id
vault-bootstrap-redis                              vault-agent-redis
  запускается,                     volume:            читает role-id/secret-id,
  пишет role-id/secret-id    vault-approle-redis       логинится, получает токен,
  в volume, завершается      ──────────────────>       рендерит redis.conf
                                                              │
                                                              │ volume:
                                                              │ redis-config-rendered
                                                              ▼
                                                        redis
                                                  читает готовый файл,
                                                  ничего не знает про Vault
```

То же самое параллельно происходит для `postgres` через
`vault-bootstrap-postgres` → `vault-agent-postgres` → `postgres`.

Для Keycloak цепочка AppRole (bootstrap → agent → рендер файла) — **та же
самая**. Разница в том, ЧТО именно агент читает и рендерит:

```
Vault: Database Secrets Engine (database/config/sublio-postgres,
       database/roles/keycloak_role) + KV (secret/keycloak — только admin_user/
       admin_password консоли, БЕЗ postgres-креды)
        ▲
        │ логин role_id + secret_id (как у redis/postgres)
vault-agent-keycloak
  читает database/creds/keycloak_role  ──> Vault СОЗДАЁТ на лету новую
  (динамика) + secret/data/keycloak         Postgres-роль внутри БД `keycloak`
  (статика, только admin creds)             на ОБЩЕМ инстансе `postgres`,
        │                                    с TTL=1h/max=4h
        │ volume: keycloak-config-rendered
        ▼
      keycloak
читает keycloak.env один раз при старте, ничего не знает про Vault
```

**Ключевое отличие от Redis/Postgres:** там Vault Agent просто **перечитывает
неизменный** пароль из KV. Здесь каждый успешный `vault agent` login может
означать, что Vault только что **создал новую Postgres-роль с нуля** (и
удалит её из Postgres, когда лиз истечёт/будет отозван) — см. раздел 6.1.

---

## 3.1 Терминология: "роль" на трёх разных уровнях

Слово "роль" в этом проекте означает три совершенно разные вещи. Путаница
между ними — самый частый источник недопонимания, поэтому явная таблица
до того, как двигаться дальше:

| Уровень | Пример | Что это | Где живёт | Когда исчезает |
|---|---|---|---|---|
| **1. AppRole** | `keycloak-role` (в `roles:`) | Способ аутентификации *во* Vault — "кто ты" | Внутри Vault (auth backend) | Никогда сама по себе; удаляется только явной командой (раздел 10) |
| **2. Database role** | `keycloak_role` (в `database_roles:`) | Шаблон/инструкция *внутри* Vault: какой SQL выполнить, чтобы выдать креды | Внутри Vault (secrets engine) | Никогда сама по себе; это конфигурация, а не выданный секрет |
| **3a. Стабильная Postgres-роль** | `keycloak_owner` | Реальный `NOLOGIN`-пользователь БД, владеет таблицами/схемой | Внутри Postgres | Никогда — создаётся один раз `keycloak-db-init`, живёт всю жизнь проекта |
| **3b. Эфемерная Postgres-роль** | `v-token-xxxx` | Реальный `LOGIN`-пользователь БД, состоит в 3a через `IN ROLE`, ничем не владеет | Внутри Postgres | Через `default_ttl`/`max_ttl` (раздел 4) — `DROP ROLE` от Vault |

Уровни 1 и 2 не выполняют SQL и не логинятся в Postgres сами — это чистая
конфигурация Vault. Уровни 3a/3b — единственные, что физически существуют
в Postgres. AppRole (1) отвечает на вопрос "кому вообще можно спросить";
database role (2) отвечает на вопрос "что выдать, если спросили"; 3a/3b —
результат выполнения этой инструкции.

**Аналогия:** AppRole — пропуск на входе в здание. Database role — рецепт
в кулинарной книге. `keycloak_owner` — сейф с ценностями, стоит на месте
всегда. `v-token-xxxx` — временный ключ от сейфа, который выдают на
несколько часов и потом отбирают; сам сейф при этом никуда не девается.

---

## 4. Жизненный цикл секретов

```
role_id     — статичный, не секретный, "логин" роли
secret_id   — временный (TTL=10m), одноразовый (num_uses=1), "пароль" роли
token       — выдаётся Vault ПОСЛЕ успешного логина role_id+secret_id,
              живёт token_ttl (1h), агент сам продлевает пока работает
```

```
apply-vault-config.sh (руками, редко)
    │
    ├──> создаёт policy "redis-policy" (что можно читать)
    └──> создаёт role "redis-role" (кто, с каким TTL, с какой policy)
              │
              ▼
    role_id известен всегда (можно вывести командой в любой момент)

bootstrap-approle.sh (автоматически, при КАЖДОМ старте docker compose)
    │
    ├──> читает role_id
    └──> генерирует НОВЫЙ secret_id (предыдущий уже мог быть использован
         или истечь — не переиспользуется)
              │
              ▼
    vault-agent логинится role_id + secret_id ──> получает token
              │
              ▼
    token используется для чтения secret/data/redis
              │
              ▼
    результат рендерится в redis.conf / postgres_user.txt / postgres_password.txt
```

**Для `database/creds/keycloak_role` шаг "читает secret/data/X" выше не
подходит буквально** — вместо чтения готового значения, сам факт запроса
запускает на стороне Vault `CREATE ROLE ... WITH LOGIN PASSWORD ...` в
Postgres. TTL здесь — не "как долго secret_id действителен", а "как долго
эта конкретная Postgres-роль будет существовать до того, как Vault сам её
удалит" (`DROP ROLE`). Vault Agent продлевает лиз в фоне сам, без действий
со стороны sidecar-скриптов; когда лиз больше нельзя продлить (`max_ttl`
исчерпан), агент запрашивает СОВСЕМ НОВУЮ роль и перерендеривает файл —
см. предостережение об этом в разделе 13.

### 4.1 Renew ≠ Re-create — почему файл не перерендеривается каждый час

Важный нюанс, который легко упустить: `default_ttl` — это НЕ период, с
которым `creation_statements` выполняется заново. Это период, через
который Vault пытается **продлить** уже существующий лиз, а не создать
новый. Продление (renew) и пересоздание (re-create) — разные SQL-операции:

| | Renew (по `default_ttl`) | Re-create (по `max_ttl`) |
|---|---|---|
| Когда | Каждые `default_ttl` (напр. 1h), пока не исчерпан `max_ttl` | Когда `max_ttl` (напр. 4h) достигнут и продлевать больше нельзя |
| Что выполняется | `renew_statements` (если не задан явно — дефолт плагина: `ALTER ROLE "{{name}}" VALID UNTIL '{{expiration}}'`) | `revocation_statements` (DROP старой) → `creation_statements` (CREATE новой) |
| Имя/пароль роли | Не меняются — та же `v-token-xxxx` | Меняются — новая `v-token-yyyy` с новым паролем |
| Файл на диске (`*.env`) | НЕ перерендеривается (значения не изменились) | Перерендеривается — сервис получает новые creds |

```
t=0h  CREATE ROLE v-token-abc123 ... IN ROLE keycloak_owner   (файл рендерится)
t=1h  RENEW  → ALTER ROLE v-token-abc123 VALID UNTIL <+1h>    (файл НЕ меняется)
t=2h  RENEW  → ALTER ROLE v-token-abc123 VALID UNTIL <+1h>    (файл НЕ меняется)
t=3h  RENEW  → ALTER ROLE v-token-abc123 VALID UNTIL <+1h>    (файл НЕ меняется)
t=4h  max_ttl исчерпан → DROP ROLE v-token-abc123
                        → CREATE ROLE v-token-def456 ...       (файл ПЕРЕРЕНДЕРИВАЕТСЯ)
```

Практический вывод: с `default_ttl=1h`/`max_ttl=4h` реальная смена
username/password в файле происходит раз в **4 часа**, а не раз в час —
именно этот момент нужно учитывать, решая, переживёт ли уже запущенный
процесс сервиса смену credentials (см. предупреждение про Keycloak в
разделе 13).

---

## 5. Полное дерево файлов проекта

```
project-root/
├── infra/
│   ├── postgres/
│   │   └── init.sql
│   ├── keycloak/
│   │   └── realm-export.json
│   └── vault/
│       ├── vault-server.hcl
│       ├── roles.yaml
│       ├── policies/
│       │   ├── redis-policy.hcl
│       │   ├── postgres-policy.hcl
│       │   ├── keycloak-policy.hcl        # KV (admin creds) + database/creds/keycloak_role
│       │   └── bootstrap-policy.hcl
│       ├── scripts/
│       │   ├── apply-vault-config.sh      # руками, один раз / при добавлении роли
│       │   ├── bootstrap-approle.sh       # автоматически, контейнером, при каждом up
│       │   └── init-keycloak-db.sh        # автоматически, контейнером keycloak-db-init, при каждом up
│       ├── agents/
│       │   ├── redis-agent.hcl
│       │   ├── postgres-agent.hcl
│       │   └── keycloak-agent.hcl
│       └── templates/
│           ├── redis.conf.tpl
│           ├── postgres_password.tpl
│           └── keycloak.env.tpl           # database/creds/keycloak_role + secret/data/keycloak
│
├── docker-compose.yaml
│
└── (НЕ в git — только в рантайме, внутри Docker volumes):
    ├── vault-data/                    # хранилище самого Vault (зашифровано)
    ├── vault-approle-redis/
    │   ├── role-id                    # кладёт vault-bootstrap-redis
    │   └── secret-id                  # свежий при каждом старте, удаляется агентом после чтения
    ├── vault-approle-postgres/
    │   ├── role-id
    │   └── secret-id
    ├── vault-approle-keycloak/
    │   ├── role-id
    │   └── secret-id
    ├── redis-config-rendered/
    │   └── redis.conf                 # пишет vault-agent-redis
    ├── postgres-config-rendered/
    │   └── postgres_password.txt      # пишет vault-agent-postgres (POSTGRES_USER берётся из .env, не из Vault)
    └── keycloak-config-rendered/
        └── keycloak.env                # пишет vault-agent-keycloak (KC_DB_* динамические + KC_BOOTSTRAP_ADMIN_* статичные)
```

Keycloak **не получает отдельный Postgres-контейнер** — он живёт на том же
`postgres`, что и `sublio` (см. раздел 6.1). `postgres_data` том теперь
содержит обе базы: `sublio` и `keycloak`.

Верхняя часть дерева (`infra/vault/...`) — статичные файлы, закоммиченные в
git. Нижняя часть — данные, которые появляются только в рантайме внутри
Docker volumes, их не видно в репозитории вообще.

---

## 6. Первоначальная настройка (один раз за всю жизнь проекта)

### Шаг 1 — поднять только Vault

```yaml
# фрагмент docker-compose.yaml
services:
  vault:
    image: hashicorp/vault:1.17
    container_name: vault
    ports:
      - "8200:8200"        # проброшен на хост, поэтому CLI с хоста тоже работает
    volumes:
      - ./infra/vault/vault-server.hcl:/vault/config/vault.hcl:ro
      - vault-data:/vault/data
    cap_add:
      - IPC_LOCK
    command: server
```

```hcl
# infra/vault/vault-server.hcl
storage "file" {
  path = "/vault/file"
}

listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = true   # локальная разработка; на проде TLS обязателен
}

ui = true
```

```bash
docker compose up -d vault
```

Сервер запущен, но **запечатан (sealed)** — ничего нельзя делать до
инициализации и unseal.

### Шаг 2 — инициализация и unseal

```bash
export VAULT_ADDR="http://localhost:8200"
vault operator init
```

Вывод:
```
Unseal Key 1: abcd1111...
...
Unseal Key 5: qrst5555...
Initial Root Token: hvs.AbCdEfGhIjKlMnOpQrSt
```

**Это единственный момент, когда эти значения существуют** — сохраните
немедленно в password manager. Не в git, не в чат, не в текстовый файл.

```bash
vault operator unseal abcd1111...
vault operator unseal efgh2222...
vault operator unseal ijkl3333...

export VAULT_TOKEN="hvs.AbCdEfGhIjKlMnOpQrSt"
```

> `export VAULT_TOKEN` действует только в рамках текущей сессии терминала.
> При новой сессии — экспортировать заново. Для root-токена это осознанное
> ограничение: в проде его отзывают (`vault token revoke`) сразу после
> первичной настройки и заводят именные админские токены.

### Шаг 3 — KV v2 и реальные секреты

```bash
# POSTGRES_USER/POSTGRES_DB должны совпадать с тем, что Postgres получит
# при initdb (infra/.env) — подтягиваем их оттуда вместо повторного набора
# вручную, чтобы не разъехались два независимых литерала
set -a && source infra/.env && set +a

vault secrets enable -path=secret -version=2 kv

vault kv put secret/redis password="$(openssl rand -base64 24)"

vault kv put secret/postgres \
    username="${POSTGRES_USER}" \
    password="$(openssl rand -base64 24)" \
    database="${POSTGRES_DB}"

vault kv put secret/keycloak \
  admin_user="admin" \
  admin_password="$(openssl rand -base64 24)"
  
vault kv put secret/auth \
  client_id="from keycloak" \
  secret_id="from keycloak"

vault kv put secret/job \
  redis_pass="$(vault kv get -field=password secret/redis)"

vault kv put secret/media \
  redis_pass="$(vault kv get -field=password secret/redis)"

vault kv put secret/worker \
  redis_pass="$(vault kv get -field=password secret/redis)"
```

`secret/keycloak` хранит **только** логин/пароль админ-консоли Keycloak
(`KC_BOOTSTRAP_ADMIN_*`). Postgres-креды для самого Keycloak сюда **не
кладутся** — они не статичный секрет, а динамический лиз из Database Secrets
Engine, настройка которого — отдельный раздел 6.1 (после того как здесь
поднят сам Vault и KV). **Тот же принцип для `job` и `subtitle-service`:**
их Postgres-креды тоже приходят из `database/creds/job_role` /
`database/creds/subtitle_role` (динамика, раздел 6.1), поэтому в KV для них
никогда не пишутся `DB_USERNAME`/`DB_PASSWORD` — только то, для чего
динамического движка нет (`job` использует Redis, отсюда `redis_pass`).
`subtitle-service` не использует Redis и не читает никакой KV-путь вообще
(только `database/creds/subtitle_role`) — поэтому `secret/subtitle` не
создаётся, KV-секрет для него просто не нужен.

Проверка:
```bash
vault kv get secret/redis
vault kv get secret/postgres
vault kv get secret/keycloak
vault kv get secret/auth
vault kv get secret/job
vault kv get secret/media
vault kv get secret/worker
```

### Шаг 4 — bootstrap-policy и bootstrap-токен

Нужен ограниченный токен для контейнеров, которые будут выпускать
`secret_id` — без прав на сами KV-секреты, только на механику логина.

```hcl
# infra/vault/policies/bootstrap-policy.hcl
path "auth/approle/role/+/role-id" {
  capabilities = ["read"]
}

path "auth/approle/role/+/secret-id" {
  capabilities = ["create", "update"]
}
```

```bash
vault policy write bootstrap-policy vault/policies/bootstrap-policy.hcl
vault token create -policy=bootstrap-policy -period=768h -field=token
```

Результат → в `.env` (не в git):
```
BOOTSTRAP_TOKEN=hvs.xxxxxxxx
```

### Шаг 5 — применить policies и roles из манифеста

```yaml
# infra/vault/roles.yaml
roles:
  - name: redis-role
    policy_file: redis-policy.hcl
    token_ttl: 1h
    token_max_ttl: 4h
    secret_id_ttl: 10m
    secret_id_num_uses: 1

  - name: postgres-role
    policy_file: postgres-policy.hcl
    token_ttl: 1h
    token_max_ttl: 4h
    secret_id_ttl: 10m
    secret_id_num_uses: 1
```

| Параметр | Смысл |
|---|---|
| `token_policies` | какая policy привязана к токену, полученному через эту роль |
| `token_ttl` | сколько живёт токен после логина |
| `token_max_ttl` | максимум, на сколько токен можно продлить |
| `secret_id_ttl` | сколько живёт сам secret_id, если его не использовали |
| `secret_id_num_uses` | сколько раз можно использовать secret_id для логина (1 — одноразовый) |

if neccessary add perms for write - chmod +x apply-vault-config.sh
```bash
export VAULT_TOKEN="<root-token>"   # если новая сессия — экспортировать заново
vault/scripts/apply-vault-config.sh
```

Скрипт идемпотентен (безопасно гонять повторно) и печатает `role_id`
каждой роли — не секрет, можно класть в `.env` для справки.

Тот же скрипт, помимо AppRole-ролей из `roles:`, применяет и
`database_connections:`/`database_roles:` из манифеста (Database Secrets
Engine — динамические Postgres-креды) — см. Шаг 5.1 сразу ниже, это часть
одного и того же прогона `apply-vault-config.sh`, отдельно запускать
ничего не нужно.

if neccessary add perms for write - chmod +x bootstrap-approle.sh

### Шаг 5.1 — Database Secrets Engine для Keycloak (автоматически, как часть Шага 5)

**Важно: `apply-vault-config.sh` (Шаг 5) нужно прогнать ДО Шага 6.**
`vault-agent-keycloak` запрашивает `database/creds/keycloak_role` сразу при
старте, как только поднимется Vault — если роль ещё не определена в Vault
на этот момент, агент не сможет отрендерить `keycloak.env`, и вся цепочка
`keycloak-db-init` → `keycloak` зависнет/упадёт при первом же
`docker compose up -d`.

Раздельных `vault write database/...` руками — не требуется. Скрипт сам
включает `database` secrets engine (`vault secrets enable database`,
идемпотентно) и применяет то, что описано в манифесте:

```yaml
# infra/vault/roles.yaml
database_connections:
  - name: sublio-postgres
    plugin_name: postgresql-database-plugin
    connection_url: "postgresql://{{username}}:{{password}}@postgres:5432/sublio?sslmode=disable"
    admin_kv_path: postgres        # vault kv get secret/<admin_kv_path>
    admin_username_field: username
    admin_password_field: password
    allowed_roles: keycloak_role

database_roles:
  - name: keycloak_role
    db_connection: sublio-postgres
    creation_statements: >-
      CREATE ROLE "{{name}}" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}' IN ROLE keycloak_owner;
    revocation_statements: >-
      REASSIGN OWNED BY "{{name}}" TO keycloak_owner; DROP OWNED BY "{{name}}"; DROP ROLE IF EXISTS "{{name}}";
    default_ttl: 1h
    max_ttl: 4h
```

`admin_kv_path`/`admin_username_field`/`admin_password_field` говорят
скрипту, откуда взять management-креды для подключения к Postgres — он
сам делает `vault kv get -field=username secret/postgres` и
`vault kv get -field=password secret/postgres` (тот же `sublio`-суперюзер,
что уже используется для загрузки контейнера `postgres`), подставляет их
в `vault write database/config/sublio-postgres` и следом
`vault write database/roles/keycloak_role` — теми же параметрами, что были
бы у ручных команд, просто из манифеста, а не из командной строки.

**Важный нюанс: `database/config/sublio-postgres` пишется с
`verify_connection=false`.** На Шаге 5 (до Шага 6) поднят только `vault` —
контейнера `postgres` ещё не существует, и hostname `postgres` из
`connection_url` физически не резолвится в Docker DNS. Без этого флага
`vault write` упадёт с `hostname resolving error`. `verify_connection=false`
просто откладывает реальную проверку соединения до первого настоящего
запроса `database/creds/keycloak_role` — а к этому моменту (внутри Шага 6)
`postgres` уже поднят и здоров, потому что `keycloak-db-init` (который от
него зависит) идёт в цепочке раньше, чем `vault-agent-keycloak`.

> `sublio-postgres` здесь — это **имя подключения внутри Vault**
> (`database/config/<имя>`), а не имя базы данных. Не путать с
> `POSTGRES_DB=sublio` — это два разных пространства имён. `allowed_roles`
> позже расширится до `"keycloak_role,job_role,subtitle_role"`, когда эти
> роли появятся (Phase 2 / Phase 5).

`database_roles` только ОПРЕДЕЛЯЕТ роль в Vault — `creation_statements`
физически ничего не выполняет в Postgres в момент прогона скрипта, они
выполнятся позже, когда кто-то (в нашем случае `vault-agent-keycloak` на
Шаге 6) реально запросит `database/creds/keycloak_role`. К этому моменту
роль `keycloak_owner`, упомянутая в `IN ROLE keycloak_owner`, уже должна
существовать в Postgres — её создаёт `keycloak-db-init` (раздел 6.1).
Поэтому в docker-compose `vault-agent-keycloak` зависит не только от
bootstrap, но и от `keycloak-db-init: condition: service_completed_successfully`
— см. раздел 6.1, полная причина там же.

Зачем вообще `IN ROLE keycloak_owner`, а не просто `GRANT ALL ON DATABASE`
на временную Vault-роль напрямую — см. раздел 6.1 ("owner trick").

Добавление новой dynamic-роли (например, позже для `job_role`) — просто
новая запись в `database_connections`/`database_roles` и повторный прогон
`apply-vault-config.sh`, без ручных `vault write`.

### Шаг 6 — поднять весь остальной стек

```bash
docker compose up -d
```

С этого момента bootstrap → agent → сервис отрабатывают автоматически при
каждом старте — **включая** `keycloak-db-init` → `vault-agent-keycloak` →
`keycloak` (детали раздел 6.1). Никаких дополнительных ручных команд для
Keycloak после Шага 5.1 не требуется.

---

## 6.1 Database Secrets Engine и Keycloak (что происходит внутри Шага 6)

Redis и Postgres выше используют **статичный** секрет: значение один раз
записано в KV и не меняется, пока кто-то явно не перезапишет его
(`vault kv put`). Keycloak — первый сервис в проекте, которому Vault сам
**генерирует и удаляет** учётку в Postgres по требованию. Это тот механизм,
который позже будет скопирован на `job_role`/`subtitle_role`
(см. `memory-bank/backend/09-secrets-management.md`).

### Почему на ОБЩЕМ Postgres-инстансе, а не в отдельном контейнере

Keycloak получает **отдельную базу `keycloak`**, но на том же самом
`postgres`-сервисе, что и `sublio` — не отдельный Postgres-контейнер.
Управление миграциями обеих баз остаётся независимым (у каждой свои
таблицы, никакого пересечения схем), но не плодится второй инстанс СУБД,
второй volume, второй healthcheck и т.д. ради изоляции, которая и так
достигается на уровне базы данных, а не контейнера.

> Ручная настройка Vault (`vault secrets enable database` + connection +
> `database/roles/keycloak_role`) — это **Шаг 5.1 раздела 6**, выполняется
> ДО `docker compose up -d`. Здесь — что происходит автоматически ПОСЛЕ,
> когда Шаг 6 (`docker compose up -d`) реально стартует контейнеры.

**Почему `keycloak_role` определена с `IN ROLE keycloak_owner`, а не просто
`GRANT ALL ON DATABASE` (см. Шаг 5.1)?** Если сделать выданную Vault-ролью
**владельцем** базы `keycloak` напрямую, то через 1–4 часа, когда Vault
попытается `DROP ROLE` на истёкшем лизе, Postgres откажет — роль-владелец
не может быть удалена, пока ей что-то принадлежит (таблицы, которые
Keycloak успел создать через свои миграции). Решение — стабильная
group-роль `keycloak_owner` (`NOLOGIN`, никогда не удаляется), которая
**фактически владеет** базой и всеми объектами в ней; каждая временная
Vault-роль лишь **состоит в этой группе** (`IN ROLE`) и наследует её права
через `INHERIT` (Postgres default). Ничего не принадлежит самой временной
роли → `DROP ROLE` при истечении лиза проходит чисто. `REASSIGN OWNED` в
`revocation_statements` — защита на случай, если что-то всё же оказалось
создано с явным owner = временная роль. Развёрнутая версия этого механизма
(с сравнением "владеет" vs "состоит в", и почему одной роли недостаточно)
— раздел 3.1.

`keycloak_owner` — обычная Postgres-роль, а не что-то из Vault, и в Vault
её создавать/регистрировать не нужно. Она должна существовать в Postgres
**раньше**, чем `vault-agent-keycloak` первый раз запросит
`database/creds/keycloak_role` (иначе `CREATE ROLE ... IN ROLE
keycloak_owner` упадёт, потому что `keycloak_owner` ещё не существует).
Её создаёт `keycloak-db-init` (шаг 1 ниже) — поэтому в docker-compose
`vault-agent-keycloak` явно ждёт его:

```yaml
vault-agent-keycloak:
  depends_on:
    vault-bootstrap-keycloak:
      condition: service_completed_successfully
    keycloak-db-init:                        # <-- критично: keycloak_owner уже должен существовать
      condition: service_completed_successfully
```

### Шаг 1 — создание базы `keycloak` и роли `keycloak_owner`

Официальный образ `postgres` создаёт ровно одну БД из `POSTGRES_DB` при
самом первом старте контейнера. Второй базе (`keycloak`) на том же
инстансе нужен отдельный, идемпотентный шаг — короткоживущий контейнер
`keycloak-db-init` (тот же паттерн, что уже используется для `liquibase`):

```bash
# infra/vault/scripts/init-keycloak-db.sh
# Выполняется контейнером keycloak-db-init при КАЖДОМ docker compose up.
# Идемпотентно: безопасно гонять повторно, ничего не пересоздаёт, если уже есть.

set -eu
export PGPASSWORD="$(cat /run/secrets/postgres_password.txt)"
PSQL="psql -h postgres -U ${POSTGRES_USER} -d ${POSTGRES_DB} -v ON_ERROR_STOP=1"

$PSQL -tc "SELECT 1 FROM pg_roles WHERE rolname = 'keycloak_owner'" | grep -q 1 || \
  $PSQL -c "CREATE ROLE keycloak_owner NOLOGIN"

$PSQL -tc "SELECT 1 FROM pg_database WHERE datname = '${KEYCLOAK_POSTGRES_DB}'" | grep -q 1 || \
  $PSQL -c "CREATE DATABASE \"${KEYCLOAK_POSTGRES_DB}\" OWNER keycloak_owner"

$PSQL -d "${KEYCLOAK_POSTGRES_DB}" -c "GRANT ALL ON SCHEMA public TO keycloak_owner"
$PSQL -d "${KEYCLOAK_POSTGRES_DB}" -c \
  "ALTER DEFAULT PRIVILEGES FOR ROLE keycloak_owner IN SCHEMA public GRANT ALL ON TABLES TO keycloak_owner"
```

Использует ту же `postgres-config-rendered:/run/secrets:ro`, что и
`liquibase` — те же management-креды `sublio`, без нового секрета.

### Шаг 2 — что рендерит `vault-agent-keycloak`

```
# infra/vault/templates/keycloak.env.tpl
{{ with secret "database/creds/keycloak_role" }}
KC_DB_USERNAME={{ .Data.username }}
KC_DB_PASSWORD={{ .Data.password }}
{{ end }}
{{ with secret "secret/data/keycloak" }}
KC_BOOTSTRAP_ADMIN_USERNAME={{ .Data.data.admin_user }}
KC_BOOTSTRAP_ADMIN_PASSWORD={{ .Data.data.admin_password }}
{{ end }}
```

Обратите внимание на форму ответа: у `database/creds/*` поля лежат прямо в
`.Data.username`/`.Data.password` — **без** вложенного `.data`, в отличие
от KV v2 (`.Data.data.admin_user`). Это два разных secrets engine с разным
форматом ответа, перепутать легко (см. раздел 13). Также обратите внимание,
что поля `database`/`db_name` в ответе `database/creds/*` **нет вообще** —
только `username` и `password`; имя базы, если оно нужно в файле, должно
приходить из другого источника (env-переменная контейнера через `env "..."`
в шаблоне, или отдельный статичный KV-путь), а не из этого блока.

### Шаг 3 — сам сервис `keycloak`

```yaml
keycloak:
  image: quay.io/keycloak/keycloak:26.2
  entrypoint: [ "/bin/sh", "-c" ]
  command:
    - |
      set -a
      . /vault/rendered/keycloak.env
      set +a
      exec /opt/keycloak/bin/kc.sh start-dev --import-realm --metrics-enabled=true
  environment:
    KC_DB: postgres
    KC_DB_URL: jdbc:postgresql://postgres:5432/${KEYCLOAK_POSTGRES_DB}   # ОБЩИЙ инстанс, не keycloak-postgres
  depends_on:
    vault-agent-keycloak:
      condition: service_healthy
    keycloak-db-init:
      condition: service_completed_successfully
  ports:
    - "8090:8080"   # admin console: localhost:8090 — dev-only, НЕ через gateway
    - "9000:9000"   # health/metrics
```

### Проверка

```bash
vault read database/creds/keycloak_role     # видно новую username/password + lease_id
open http://localhost:8090                  # admin console
docker compose logs keycloak-db-init        # подтверждает, что база/owner уже существовали или были созданы
```

---

## 7. Обычный (повторный) запуск проекта

Если Vault не перезапускался и его volume цел:

```bash
docker compose up -d
```

Если Vault был sealed (после рестарта контейнера) — сначала unseal (раздел 8),
иначе `vault-bootstrap-*` зависнет в ожидании.

---

## 8. Перезапуск / пересоздание контейнеров и volume

### 8.1 `docker compose restart` / `up -d --force-recreate` (без `-v`)

Volume'ы не удаляются — все данные (`vault-data`, `postgres_data`,
`redis_data`) сохранены. Но Vault-сервер после перезапуска процесса
**всегда снова sealed** — unseal-статус хранится только в памяти
работающего процесса, не на диске.

```bash
export VAULT_ADDR="http://localhost:8200"
vault operator unseal <key1>
vault operator unseal <key2>
vault operator unseal <key3>
```

Пока это не сделано, `vault-bootstrap-*` виснет в цикле
`until vault status ...; do sleep 1; done` — это ожидаемо, не баг. После
unseal контейнеры сами доедут до конца цепочки. Секреты, policies, roles
пересоздавать не нужно — они пережили рестарт вместе с `vault-data`.

### 8.2 `docker compose down -v` — полное уничтожение состояния

Удаляются все named volumes, включая `vault-data`:
- Vault возвращается в неинициализированное состояние
- Все KV-секреты, policies, roles потеряны
- `postgres_data`/`redis_data` тоже стёрты

Требуется повторить весь раздел 6 заново, от `vault operator init`.

### 8.3 Точечное удаление одного volume (например, только для redis)

```bash
docker compose rm -sf vault-bootstrap-redis vault-agent-redis redis
docker volume rm <project>_vault-approle-redis <project>_redis-config-rendered
docker compose up -d
```

Безопасно — `vault-bootstrap-redis` сгенерирует новый `secret_id`, сам
Vault и его данные не затронуты.

---

## 9. Добавление нового сервиса

Пример: `auth-service`.

```bash
# 1. Policy
cat > infra/vault/policies/auth-service-policy.hcl << 'EOF'
path "secret/data/auth-service" {
  capabilities = ["read"]
}
EOF

# 2. Секрет
vault kv put secret/auth-service jwt_secret="$(openssl rand -base64 32)"

# 3. Роль — добавить в roles.yaml
#    - name: auth-service-role
#      policy_file: auth-service-policy.hcl
#      token_ttl: 1h
#      token_max_ttl: 4h
#      secret_id_ttl: 10m
#      secret_id_num_uses: 1

# 4. Применить манифест
export VAULT_TOKEN="<root-token>"
./infra/vault/scripts/apply-vault-config.sh

# 5. Добавить в docker-compose.yaml:
#    vault-bootstrap-auth-service, vault-agent-auth-service, auth-service
#    + agents/auth-service-agent.hcl + templates/auth-service.tpl

# 6. Поднять только новые сервисы
docker compose up -d vault-bootstrap-auth-service vault-agent-auth-service auth-service
```

Остальные сервисы при этом не трогаются.

> Если новому сервису нужен не статичный KV-пароль, а **динамический**
> Postgres-креденшл (как job-service/subtitle-service будут делать позже) —
> шаги 1–4 те же, но policy указывает на `database/creds/<role>_role`, а
> роль создаётся через `database/roles/...`, а не `vault kv put`. Полный
> пример со всеми нюансами (group-роль владелец, `IN ROLE`, форма ответа
> API) — раздел 6.1, на примере `keycloak_role`.

---

## 10. Удаление сервиса

```bash
export VAULT_TOKEN="<root-token>"

vault delete auth/approle/role/auth-service-role
vault policy delete auth-service-policy
vault kv metadata delete secret/auth-service   # насовсем, с историей версий

# убрать запись из roles.yaml
# убрать блоки из docker-compose.yaml

docker compose down vault-bootstrap-auth-service vault-agent-auth-service auth-service
docker volume rm <project>_vault-approle-auth-service <project>_auth-service-config-rendered
```

`apply-vault-config.sh` не удаляет то, чего нет в манифесте — только
применяет то, что там есть. Удаление всегда делается явными командами.

---

## 11. Все файлы конфигурации

> Это раздел-справочник по базовому Redis + Postgres (статичные KV-секреты).
> Файлы, специфичные для Keycloak (`keycloak-agent.hcl`, `keycloak.env.tpl`,
> `keycloak-policy.hcl`, `init-keycloak-db.sh`) и связанная с ними
> Database Secrets Engine — см. раздел 6.1, там же и полный
> `docker-compose.yaml`-фрагмент для `keycloak`/`keycloak-db-init`.

### `apply-vault-config.sh`

```bash
#!/usr/bin/env bash
# infra/vault/scripts/apply-vault-config.sh
#
# Идемпотентно применяет все policy и AppRole roles из roles.yaml.
# Запускается ВРУЧНУЮ, С ХОСТА: один раз при первой настройке и повторно
# при добавлении новой роли. НЕ часть docker-compose up.
#
# Требует: vault CLI, yq, VAULT_ADDR и VAULT_TOKEN в окружении

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST="${SCRIPT_DIR}/../roles.yaml"
POLICIES_DIR="${SCRIPT_DIR}/../policies"

if ! command -v yq &> /dev/null; then
  echo "Ошибка: требуется yq (https://github.com/mikefarah/yq)" >&2
  exit 1
fi

if [[ -z "${VAULT_ADDR:-}" || -z "${VAULT_TOKEN:-}" ]]; then
  echo "Ошибка: VAULT_ADDR и VAULT_TOKEN должны быть заданы в окружении" >&2
  exit 1
fi

vault auth enable approle 2>/dev/null || true

count=$(yq '.roles | length' "${MANIFEST}")
echo "Найдено ролей в манифесте: ${count}"
echo "---"

declare -A ROLE_IDS

for i in $(seq 0 $((count - 1))); do
  name=$(yq -r ".roles[$i].name" "${MANIFEST}")
  policy_file=$(yq -r ".roles[$i].policy_file" "${MANIFEST}")
  token_ttl=$(yq -r ".roles[$i].token_ttl" "${MANIFEST}")
  token_max_ttl=$(yq -r ".roles[$i].token_max_ttl" "${MANIFEST}")
  secret_id_ttl=$(yq -r ".roles[$i].secret_id_ttl" "${MANIFEST}")
  secret_id_num_uses=$(yq -r ".roles[$i].secret_id_num_uses" "${MANIFEST}")

  policy_name="${policy_file%.hcl}"
  policy_path="${POLICIES_DIR}/${policy_file}"

  echo "==> [${name}] policy: ${policy_name}"
  vault policy write "${policy_name}" "${policy_path}" > /dev/null

  echo "==> [${name}] approle role"
  vault write "auth/approle/role/${name}" \
      token_policies="${policy_name}" \
      token_ttl="${token_ttl}" \
      token_max_ttl="${token_max_ttl}" \
      secret_id_ttl="${secret_id_ttl}" \
      secret_id_num_uses="${secret_id_num_uses}" \
      > /dev/null

  role_id=$(vault read -field=role_id "auth/approle/role/${name}/role-id")
  ROLE_IDS["${name}"]="${role_id}"
  echo "    role_id: ${role_id}"
  echo "---"
done

echo ""
echo "Готово. Применено ролей: ${count}"
for name in "${!ROLE_IDS[@]}"; do
  env_var=$(echo "${name}" | tr '[:lower:]-' '[:upper:]_')
  echo "${env_var}_ID=${ROLE_IDS[${name}]}"
done
```

### `bootstrap-approle.sh`

```bash
#!/usr/bin/env sh
# infra/vault/scripts/bootstrap-approle.sh
#
# Кладёт role_id (статичный) и СВЕЖИЙ одноразовый secret_id в volume,
# который читает vault-agent при старте. Запускается как отдельный
# short-lived контейнер ПЕРЕД vault-agent-*, при КАЖДОМ поднятии стека.
#
# Аргументы: $1 = имя роли, $2 = директория для role-id/secret-id

set -eu

ROLE_NAME="$1"
OUTPUT_DIR="$2"

echo "[$ROLE_NAME] Ожидание доступности Vault..."
until vault status > /dev/null 2>&1; do
  sleep 1
done

vault read -field=role_id "auth/approle/role/${ROLE_NAME}/role-id" \
  > "${OUTPUT_DIR}/role-id"

vault write -f -field=secret_id "auth/approle/role/${ROLE_NAME}/secret-id" \
  > "${OUTPUT_DIR}/secret-id"

echo "[$ROLE_NAME] role-id и secret-id записаны в ${OUTPUT_DIR}"
```

### Policies

```hcl
# infra/vault/policies/redis-policy.hcl
path "secret/data/redis" {
  capabilities = ["read"]
}
```

```hcl
# infra/vault/policies/postgres-policy.hcl
path "secret/data/postgres" {
  capabilities = ["read"]
}
```

> **Нюанс KV v2:** реальный API-путь для чтения `secret/redis` через KV v2
> звучит как `secret/data/redis` — версионирующий движок добавляет сегмент
> `data`. Команды `vault kv put`/`vault kv get` добавляют этот сегмент сами,
> незаметно — а вот в policy и в шаблонах Vault Agent (`{{ with secret "..." }}`)
> его нужно указывать явно. В KV v1 сегмента `data` не было бы вообще.

### Agents

```hcl
# infra/vault/agents/redis-agent.hcl
pid_file = "/vault/config/pidfile"

vault {
  address = "http://vault:8200"
}

auto_auth {
  method "approle" {
    mount_path = "auth/approle"
    config = {
      role_id_file_path                   = "/vault/config/role-id"
      secret_id_file_path                 = "/vault/config/secret-id"
      remove_secret_id_file_after_reading = true
    }
  }
  sink "file" {
    config = { path = "/vault/config/token" }
  }
}

template {
  source      = "/vault/templates/redis.conf.tpl"
  destination = "/vault/rendered/redis.conf"
}
```

```hcl
# infra/vault/agents/postgres-agent.hcl
pid_file = "/vault/config/pidfile"

vault {
  address = "http://vault:8200"
}

auto_auth {
  method "approle" {
    mount_path = "auth/approle"
    config = {
      role_id_file_path                   = "/vault/config/role-id"
      secret_id_file_path                 = "/vault/config/secret-id"
      remove_secret_id_file_after_reading = true
    }
  }
  sink "file" {
    config = { path = "/vault/config/token" }
  }
}

template {
  source      = "/vault/templates/postgres_user.tpl"
  destination = "/vault/rendered/postgres_user.txt"
}

template {
  source      = "/vault/templates/postgres_password.tpl"
  destination = "/vault/rendered/postgres_password.txt"
}
```

> `remove_secret_id_file_after_reading = true` — агент сам удаляет файл
> `secret-id` сразу после логина (он одноразовый — держать его на диске
> дальше бессмысленно). Значит при перезапуске **самого агента** (не всего
> стека) понадобится заново прогнать bootstrap-контейнер, чтобы получить
> новый `secret_id` — сам агент восстановить его не может.

### Templates

```
{{- with secret "secret/data/redis" -}}
requirepass {{ .Data.data.password }}
bind 0.0.0.0
protected-mode yes
maxmemory 256mb
maxmemory-policy noeviction
{{- end -}}
```

```
{{- with secret "secret/data/postgres" -}}{{ .Data.data.username }}{{- end -}}
```

```
{{- with secret "secret/data/postgres" -}}{{ .Data.data.password }}{{- end -}}
```

### `docker-compose.yaml` (полностью)

```yaml
services:

  vault:
    image: hashicorp/vault:1.17
    container_name: vault
    ports:
      - "8200:8200"
    volumes:
      - ./infra/vault/vault-server.hcl:/vault/config/vault.hcl:ro
      - vault-data:/vault/data
    cap_add:
      - IPC_LOCK
    command: server -config=/vault/config/vault.hcl
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://127.0.0.1:8200/v1/sys/health?standbyok=true&sealedcode=200 || exit 1"]
      interval: 5s
      timeout: 3s
      retries: 20
    networks: [sublio_internal]

  vault-bootstrap-redis:
    image: hashicorp/vault:1.17
    container_name: vault-bootstrap-redis
    depends_on:
      vault:
        condition: service_healthy
    environment:
      VAULT_ADDR: http://vault:8200
      VAULT_TOKEN: ${BOOTSTRAP_TOKEN}
    entrypoint: ["/scripts/bootstrap-approle.sh", "redis-role", "/vault/config"]
    volumes:
      - ./infra/vault/scripts/bootstrap-approle.sh:/scripts/bootstrap-approle.sh:ro
      - vault-approle-redis:/vault/config
    networks: [sublio_internal]
    restart: "no"

  vault-bootstrap-postgres:
    image: hashicorp/vault:1.17
    container_name: vault-bootstrap-postgres
    depends_on:
      vault:
        condition: service_healthy
    environment:
      VAULT_ADDR: http://vault:8200
      VAULT_TOKEN: ${BOOTSTRAP_TOKEN}
    entrypoint: ["/scripts/bootstrap-approle.sh", "postgres-role", "/vault/config"]
    volumes:
      - ./infra/vault/scripts/bootstrap-approle.sh:/scripts/bootstrap-approle.sh:ro
      - vault-approle-postgres:/vault/config
    networks: [sublio_internal]
    restart: "no"

  vault-agent-redis:
    image: hashicorp/vault:1.17
    container_name: vault-agent-redis
    depends_on:
      vault-bootstrap-redis:
        condition: service_completed_successfully
    volumes:
      - ./infra/vault/agents/redis-agent.hcl:/vault/agents/redis-agent.hcl:ro
      - ./infra/vault/templates/redis.conf.tpl:/vault/templates/redis.conf.tpl:ro
      - redis-config-rendered:/vault/rendered
      - vault-approle-redis:/vault/config
    command: agent -config=/vault/agents/redis-agent.hcl
    healthcheck:
      test: ["CMD-SHELL", "test -f /vault/rendered/redis.conf"]
      interval: 2s
      timeout: 2s
      retries: 30
      start_period: 5s
    networks: [sublio_internal]

  vault-agent-postgres:
    image: hashicorp/vault:1.17
    container_name: vault-agent-postgres
    depends_on:
      vault-bootstrap-postgres:
        condition: service_completed_successfully
    volumes:
      - ./infra/vault/agents/postgres-agent.hcl:/vault/agents/postgres-agent.hcl:ro
      - ./infra/vault/templates/postgres_user.tpl:/vault/templates/postgres_user.tpl:ro
      - ./infra/vault/templates/postgres_password.tpl:/vault/templates/postgres_password.tpl:ro
      - postgres-config-rendered:/vault/rendered
      - vault-approle-postgres:/vault/config
    command: agent -config=/vault/agents/postgres-agent.hcl
    healthcheck:
      test: ["CMD-SHELL", "test -f /vault/rendered/postgres_user.txt && test -f /vault/rendered/postgres_password.txt"]
      interval: 2s
      timeout: 2s
      retries: 30
      start_period: 5s
    networks: [sublio_internal]

  redis:
    image: redis:7-alpine
    container_name: redis
    depends_on:
      vault-agent-redis:
        condition: service_healthy
    command: redis-server /usr/local/etc/redis/redis.conf
    volumes:
      - redis-config-rendered:/usr/local/etc/redis
      - redis_data:/data
    networks: [sublio_internal]

  postgres:
    image: postgres:16-alpine
    container_name: postgres
    depends_on:
      vault-agent-postgres:
        condition: service_healthy
    environment:
      POSTGRES_USER_FILE: /run/secrets/postgres_user
      POSTGRES_DB: sublio
      POSTGRES_PASSWORD_FILE: /run/secrets/postgres_password
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - postgres-config-rendered:/run/secrets:ro
      - ./infra/postgres/init.sql:/docker-entrypoint-initdb.d/init.sql:ro
    networks: [sublio_internal]

volumes:
  vault-data:
  vault-approle-redis:
  vault-approle-postgres:
  redis-config-rendered:
  postgres-config-rendered:
  redis_data:
  postgres_data:

networks:
  sublio_internal:
```

---

## 12. Альтернатива: доставка secret_id через CI/CD

Bootstrap-контейнеры (разделы 1–11) — удобное решение для single-host
docker-compose: `secret_id` генерируется локально, внутри той же сети,
никуда не покидая хост. Но если деплой идёт через CI/CD-пайплайн на
удалённый сервер, есть альтернативный паттерн — **генерировать `secret_id`
на этапе деплоя** и доставлять его на сервер вместе с остальным релизом,
без bootstrap-контейнеров в самом compose:

```yaml
# .github/workflows/deploy.yml (фрагмент)
- name: Generate fresh SecretIDs
  env:
    VAULT_ADDR: ${{ secrets.VAULT_ADDR }}
    VAULT_TOKEN: ${{ secrets.VAULT_CI_TOKEN }}
  run: |
    vault write -f -field=secret_id auth/approle/role/redis-role/secret-id > redis_secret_id.txt
    vault write -f -field=secret_id auth/approle/role/postgres-role/secret-id > postgres_secret_id.txt

- name: Upload credentials to server
  env:
    SSH_KEY: ${{ secrets.DEPLOY_SSH_KEY }}
  run: |
    echo "$SSH_KEY" > deploy_key && chmod 600 deploy_key
    scp -i deploy_key redis_secret_id.txt deploy@your-server:/opt/app/vault-approle-redis-src/
    scp -i deploy_key postgres_secret_id.txt deploy@your-server:/opt/app/vault-approle-postgres-src/
    rm -f redis_secret_id.txt postgres_secret_id.txt deploy_key

- name: Deploy
  run: ssh -i deploy_key deploy@your-server "cd /opt/app && docker compose up -d"
```

Ключевые отличия от bootstrap-подхода:

| | Bootstrap-контейнер | CI/CD-доставка |
|---|---|---|
| Когда генерируется `secret_id` | При каждом `docker compose up` | При каждом деплое из пайплайна |
| Нужен ли отдельный сервис в compose | Да (`vault-bootstrap-*`) | Нет |
| Нужен ли отдельный CI-токен | Нет | Да (`VAULT_CI_TOKEN`, с той же ограниченной policy) |
| Риск | Секрет никогда не покидает docker-сеть | Секрет проходит через раннер CI и SSH-канал — больше поверхность |

`VAULT_CI_TOKEN` должен иметь ту же `bootstrap-policy`, что и bootstrap-
контейнеры — **не root-токен**. Важно не логировать `secret_id` в вывод
шага (`echo "$X" > file`, а не `echo "SecretID: $X"`) — GitHub Actions
маскирует значения из `secrets.*`, но собственные переменные вроде
`REDIS_SECRET_ID` не маскируются автоматически.

Для текущего этапа проекта (single-host docker-compose) bootstrap-подход
проще и достаточен — этот раздел на будущее, если появится отдельный
CI/CD-пайплайн на удалённый сервер.

---

## 13. Частые ошибки

- **`hostname resolving error (lookup postgres ...)` на Шаге 5.1.** Это
  ожидаемо, если `apply-vault-config.sh` запускается ДО `docker compose up
  -d` (Шаг 6) — контейнера `postgres` ещё нет, DNS-имя `postgres` не
  резолвится. Скрипт уже пишет `database/config/sublio-postgres` с
  `verify_connection=false` для этого случая. Если ошибка всё равно
  вылезла — значит правки скрипта нет в рабочей копии (например, старая
  версия `apply-vault-config.sh`), а не то, что нужно поднимать `postgres`
  раньше.

- **Vault sealed после перезапуска.** Без auto-unseal (AWS KMS / GCP KMS /
  Azure Key Vault) — каждый рестарт контейнера `vault` требует заново 3
  unseal-ключа вручную. Для прода имеет смысл настроить auto-unseal.

- **`secret/data/...` vs `secret/...`.** См. нюанс в разделе 11 про KV v2 —
  CLI (`vault kv put/get`) добавляет сегмент `data` сам, а в policy и
  шаблонах Vault Agent его нужно писать явно.

- **Сервис стартует раньше, чем агент отрендерил файл.** Не используйте
  самодельный `until [ -f ... ]; do sleep 1; done` в `command:` сервиса —
  это временный костыль. Правильно — `healthcheck` у `vault-agent-*` +
  `depends_on: condition: service_healthy` у самого сервиса (как в
  compose-файле раздела 11).

- **`remove_secret_id_file_after_reading = true`.** Хорошо для безопасности
  (secret_id и так одноразовый), но означает: если перезапустить только
  `vault-agent-*` (не весь стек) — понадобится заново прогнать
  соответствующий `vault-bootstrap-*`, чтобы выдать новый `secret_id`.

- **Секреты в логах CI**, если используете альтернативу из раздела 12.
  Собственные переменные (результат `vault write -f -field=secret_id`) не
  маскируются платформой автоматически — никогда не делайте `echo $SECRET_ID`
  без перенаправления в файл.

- **role_id не нужно ротировать, secret_id — обязательно** при каждом
  запуске/деплое. Если secret_id один раз получен и захардкожен "навсегда" —
  это сводит на нет всю модель безопасности одноразового токена.

- **`database/creds/*` vs `secret/data/*` — разная форма ответа.** Динамика
  отдаёт `.Data.username`/`.Data.password` без вложенности; KV v2 —
  `.Data.data.<поле>`. Скопировать шаблон одного типа для другого — рабочая,
  но тихо неверная ошибка: агент либо не находит поле и пишет пустую
  строку, либо падает без явной причины в логе.

- **Роль-владелец базы не должна быть той же ролью, что выдаёт Vault.**
  Если временная Vault-роль становится владельцем базы/таблиц напрямую,
  `DROP ROLE` при истечении лиза упадёт — Postgres не удаляет роль, которой
  что-то принадлежит. Решение — стабильная `NOLOGIN`-роль-владелец
  (`keycloak_owner`), временная роль лишь состоит в ней через `IN ROLE`.
  Подробности — раздел 6.1 и 3.1.

- **"TTL истёк" не значит "роль пересоздана".** `default_ttl` — это цикл
  renew (`ALTER ROLE ... VALID UNTIL`, та же роль, файл не меняется);
  реальная пересоздание с новым username/password происходит только на
  `max_ttl`. Полная разница и таймлайн — раздел 4.1. Путать эти два
  события — вторая по частоте причина "почему сервис не видит новые креды
  вовремя/видит их слишком рано".

- **Keycloak читает `keycloak.env` один раз при старте контейнера.** Если
  `max_ttl` (4ч) истёк и Vault Agent перевыпустил СОВСЕМ НОВУЮ роль
  (новый username, не просто новый пароль той же роли) — уже запущенный
  процесс Keycloak об этом не узнает, пока его не перезапустят. Для
  dev-стенда это осознанный, задокументированный пробел (тот же класс, что
  и остальные пункты "documented, not built" в
  `memory-bank/backend/08-scalability.md`), а не решённая проблема.

- **Postgres проще Redis технически**: официальный образ `postgres` умеет
  `_FILE`-суффикс для `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`
  из коробки — не нужен полный `.conf`, достаточно текстовых файлов с
  значениями. Redis такой встроенной поддержки не имеет, поэтому для него
  рендерится полноценный `redis.conf` целиком.

---

## 14. Шпаргалка команд

| Действие | Команда |
|---|---|
| Проверить, sealed ли Vault | `vault status` |
| Распечатать Vault | `vault operator unseal <key>` (x3) |
| Посмотреть role_id роли | `vault read -field=role_id auth/approle/role/<role>/role-id` |
| Сгенерировать новый secret_id вручную | `vault write -f auth/approle/role/<role>/secret-id` |
| Посмотреть текущий секрет | `vault kv get secret/redis` |
| Обновить секрет | `vault kv put secret/redis password="новый"` |
| Посмотреть все policy | `vault policy list` |
| Посмотреть все AppRole roles | `vault list auth/approle/role` |
| Отозвать роль целиком | `vault delete auth/approle/role/<role>` |
| Посмотреть логи агента | `docker compose logs -f vault-agent-redis` |
| Запросить динамический Postgres-креденшл вручную | `vault read database/creds/keycloak_role` |
| Посмотреть все database roles | `vault list database/roles` |
| Отозвать конкретный лиз досрочно | `vault lease revoke database/creds/keycloak_role/<lease_id>` |
| Отозвать ВСЕ активные лизы роли сразу | `vault lease revoke -prefix database/creds/keycloak_role` |
| Посмотреть конфиг подключения к Postgres | `vault read database/config/sublio-postgres` |
