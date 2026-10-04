# Neon Arena: real-time gaming leaderboard

```
Browser --HTTP/WebSocket--> LB (Spring Boot, serves the frontend, proxies /api and /ws)
                              |-- API node 1 --.
                              '-- API node 2 --+-- PostgreSQL (players, ratings, history)
                                                '-- Redis (sorted sets for ranks, Pub/Sub for live pushes)
```

| Folder | What |
|---|---|
| `frontend/` | HTML + CSS + vanilla JS (WebSocket client). Baked into the LB image. |
| `api/` | Java 21 + Spring Boot: REST, WebSocket, Flyway migrations, Lichess ingestor, simulator |
| `lb/` | Java 21 + Spring Boot load balancer: round-robin or least-connections, health checks, WebSocket proxy |
| `simulator/` | Python load generator |
| `postman/` | Postman collection |
| `docker-compose.yml`, `render.yaml` | Local stack and Render Blueprint |

## Run locally

```bash
docker compose up --build
# open http://localhost:8080
python3 -m pip install -r simulator/requirements.txt
python3 simulator/simulate.py --url http://localhost:8080
```

Check balancing at `http://localhost:8080/lb/status`. Each `/api` response carries `X-Backend` (from the LB) and `X-Instance` (from the API).

## API

| Method | Path | Notes |
|---|---|---|
| POST | `/api/scores` | `{"username","mode","rating"}` or `{"username","mode","delta"}` |
| POST | `/api/scores/batch` | array of the above, max 500 |
| GET | `/api/leaderboard/{mode}?limit=50` | modes: blitz, bullet, rapid |
| GET | `/api/leaderboard/{mode}/player/{username}?radius=3` | rank and neighbours |
| GET | `/api/players/{username}` and `/history` | profile from Postgres |
| GET | `/lb/status` | backend health and connection counts |
| WS | `/ws` | pushes `snapshot` messages per mode |

## Config

API: `DB_HOST DB_PORT DB_NAME DB_USER DB_PASSWORD`, `REDIS_URL`, `INGEST_MODE` (`hybrid` | `lichess` | `simulator`), `INSTANCE_NAME`.
LB: `BACKENDS` (comma-separated URLs), `LB_STRATEGY` (`round-robin` | `least-connections`).

`hybrid` seeds each board from the Lichess top 50 and then nudges ratings so the page keeps moving. If Lichess is unreachable, generated players are used. Only one API node ingests at a time (Redis lease).

## Deploy on Render

1. Push this folder to a GitHub repo.
2. Render dashboard > New > Blueprint > pick the repo > Apply. This creates Postgres, Key Value (Redis), `leaderboard-api-1`, `leaderboard-api-2` and `leaderboard-lb`. The LB will ask for `BACKENDS`; leave it blank for now.
3. When both API services show Live, copy their public URLs (Render may add a suffix if the name is taken).
4. Open `leaderboard-lb` > Environment > set `BACKENDS` to `https://<api-1-url>,https://<api-2-url>` > Save (it redeploys).
5. Open the LB URL. Check `/lb/status` shows both backends healthy.
6. Run the simulator against the LB URL.

Free tier notes: services sleep after about 15 minutes idle (first request takes up to a minute), and the free Postgres expires after 30 days. The database can be inspected with psql, pgAdmin or DBeaver (SSMS only works with SQL Server).
