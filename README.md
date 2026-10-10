# People & connections API

A small HTTP API that stores people and their mutual connections in memory and
answers how two people are connected (shortest path, at most **3 hops**).

Built for the Nada Network take-home assignment with **core Java 17+ only**: the
JDK's built-in `com.sun.net.httpserver`, no frameworks and no runtime
dependencies (JUnit 5 is used for tests only). JSON is handled by a small strict
parser/writer in this repo (`Json.java`).

## Prerequisites

- JDK 17 or newer (developed and tested on JDK 21, compiled with `--release 17`)
- Maven (to build and run tests). Built and tested with Maven 3.8.7; the pom only
  uses standard plugins, so any recent Maven 3.x should work. Running the server
  without Maven is also possible, see below.

## Build

```bash
mvn package            # compiles, runs tests, creates target/people-api.jar
mvn package -DskipTests   # same, without running tests
```

## Run the server

```bash
java -jar target/people-api.jar          # listens on port 8080
java -jar target/people-api.jar 9000     # custom port as first argument
PORT=9000 java -jar target/people-api.jar   # or via the PORT environment variable
```

The server prints `People & connections API listening on http://localhost:<port>`.
Data lives in memory and is lost when the server stops.

No Maven? Compile and run with the JDK alone:

```bash
mkdir out && javac --release 17 -d out $(find src/main/java -name "*.java")
java -cp out com.nada.people.Main
```

(On Windows PowerShell:
`javac --release 17 -d out (Get-ChildItem -Recurse src/main/java -Filter *.java).FullName`)

## Quick start (copy-paste walkthrough)

With the server running on port 8080, in a second terminal (bash/zsh quoting; on
Windows use Git Bash or WSL, or adapt the quotes):

```bash
for n in Asha Rohan Meera Dev Kabir; do
  curl -s -X POST localhost:8080/people -d "{\"name\": \"$n\"}"; echo
done
curl -s -i -X POST localhost:8080/knows -d '{"a": 1, "b": 2}' | head -1 | tr -d '\r'
curl -s -X POST localhost:8080/knows -d '{"a": 1, "b": 3}'
curl -s -X POST localhost:8080/knows -d '{"a": 3, "b": 5}'
curl -s localhost:8080/people/1/contacts; echo
curl -s "localhost:8080/path?from=1&to=5"; echo
curl -s "localhost:8080/path?from=2&to=5"; echo
curl -s "localhost:8080/path?from=4&to=5"; echo
```

Output (the `/knows` calls return `204 No Content`, so they print nothing):

```
{"id":1,"name":"Asha"}
{"id":2,"name":"Rohan"}
{"id":3,"name":"Meera"}
{"id":4,"name":"Dev"}
{"id":5,"name":"Kabir"}
HTTP/1.1 204 No Content
[{"id":2,"name":"Rohan"},{"id":3,"name":"Meera"}]
{"connected":true,"hops":2,"path":[{"id":1,"name":"Asha"},{"id":3,"name":"Meera"},{"id":5,"name":"Kabir"}]}
{"connected":true,"hops":3,"path":[{"id":2,"name":"Rohan"},{"id":1,"name":"Asha"},{"id":3,"name":"Meera"},{"id":5,"name":"Kabir"}]}
{"connected":false}
```

Person 2 (Rohan) reaches Kabir in exactly 3 hops, which is the limit; Dev (4) has
no connections, so he is not connected to anyone.

## Run the tests

```bash
mvn test
```

The tests run in a few seconds:

| Test class | What it covers |
|---|---|
| `NetworkServiceTest` | Graph logic: **3-hop boundary** (A→D connected, A→E not), both directions, shortest path preferred, cycles, same start/end, duplicate connections, validation |
| `ApiTest` | End-to-end over real HTTP on a free port: every endpoint, every status code, Hindi/emoji names, bad JSON/types/params, 404/405, the **3-hop boundary** again via `/path` |
| `JsonTest` | The JSON parser/writer: strictness, escapes, surrogate pairs, number types, round trips |

## API

All request and response bodies are JSON encoded as UTF-8. Errors look like
`{"error": "no person 9"}`.

### `POST /people` → 201

```bash
curl -i -X POST localhost:8080/people -d '{"name": "Asha"}'
```
```
HTTP/1.1 201 Created
{"id":1,"name":"Asha"}
```

### `POST /knows` → 204

Creates a mutual connection. Sending it again (in either direction) is not an error.

```bash
curl -i -X POST localhost:8080/knows -d '{"a": 1, "b": 2}'
```
```
HTTP/1.1 204 No Content
```

### `GET /people/{id}/contacts` → 200

Direct contacts ordered by id, or `[]`.

```bash
curl localhost:8080/people/1/contacts
```
```
[{"id":2,"name":"Rohan"}]
```

### `GET /path?from=1&to=5` → 200

Shortest path of **3 hops or fewer**, including both end points.

```bash
curl "localhost:8080/path?from=1&to=5"
```
```
{"connected":true,"hops":2,"path":[{"id":1,"name":"Asha"},{"id":3,"name":"Meera"},{"id":5,"name":"Kabir"}]}
```

No path within 3 hops: `{"connected":false}`. Same existing person for both
ends: `{"connected":true,"hops":0,"path":[{"id":1,"name":"Asha"}]}`.

### Status codes

| Endpoint | Success | Errors |
|---|---|---|
| `POST /people` | 201 with `{"id","name"}` | 400 invalid JSON / not an object / missing or non-string `name` / empty or blank `name`; 413 body over 1 MB |
| `POST /knows` | 204 (also when the connection already exists) | 400 invalid JSON / missing or non-integer `a`/`b` / `a` equals `b`; 404 unknown person; 413 |
| `GET /people/{id}/contacts` | 200 with a list (possibly `[]`) | 400 non-integer id; 404 unknown person |
| `GET /path?from=&to=` | 200 (connected or `{"connected": false}`) | 400 missing/malformed `from` or `to`; 404 unknown person |
| any other URL | | 404 (unknown endpoint), 405 (wrong method, with `Allow` header) |

Error bodies are always `{"error": "<message>"}`.

## Design

```
Main            parses the port, starts the server
PeopleServer    wraps the JDK HttpServer
ApiHandler      routing, request parsing/validation, status codes, JSON responses
NetworkService  in-memory store + BFS path search (no HTTP knowledge)
Person          record: id + name
Json            small strict JSON parser/writer
BadRequestException / PersonNotFoundException   mapped to 400 / 404 by ApiHandler
```

- **Shortest path** is a breadth-first search that stops expanding at depth 3
  (`NetworkService.MAX_HOPS`). BFS reaches each person by the fewest hops first,
  so the first time it reaches the target is a shortest path. Neighbours are
  visited in id order, so results are deterministic.
- **Thread safety:** the JDK server handles requests on a thread pool, so every
  `NetworkService` method is `synchronized`.
- **Unicode:** requests are decoded as strict UTF-8, responses are written as
  UTF-8, and non-ASCII text is never `\u`-escaped. Names are stored untouched
  (no trimming or normalization), so `"  आशा "` comes back exactly as sent.

### Assumptions (where the task was silent)

1. IDs are assigned sequentially starting at 1 (matches the example). A rejected
   request never consumes an id.
2. "Blank" name means only whitespace or space characters (including `\u00A0`
   and `\u3000`). A non-blank name is stored unchanged.
3. `a` and `b` must be JSON integers. `"1"` or `1.5` give 400. IDs outside the
   64-bit range give 400.
4. `/knows` also returns 404 for an unknown person. The 400 checks (`a == b`,
   bad types) happen before the 404 check.
5. A non-numeric id in `/people/{id}/contacts`, or a repeated `from`/`to`
   query parameter, gives 400.
6. `Content-Type` of requests is not checked; the body is always read as UTF-8
   JSON. Extra JSON fields are ignored.
7. For `/path`, malformed parameters (400) are reported before unknown people (404).
8. Default port is 8080.
9. Request bodies over 1 MB (`ApiHandler.MAX_BODY_BYTES`) get 413. Real requests
   are a few dozen bytes; the limit just stops a huge POST from exhausting memory.

## What I would do next with more time

- Persistence (a database or snapshot file) so data survives restarts.
- Limit request body size and name length; add basic request logging.
- Replace `synchronized` with a read/write lock if reads dominate.
- Bidirectional BFS or caching if the network grows large.
- Make the hop limit a request parameter or configuration value.
- Pagination for contacts; endpoints to list/get/delete people and connections.
- Authentication and rate limiting.
- OpenAPI description and a CI workflow running `mvn verify`.

## AI tools used

Claude (Anthropic) was used to write the code and tests.
