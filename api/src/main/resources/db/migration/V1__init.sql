CREATE TABLE players (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(40) NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_players_lower_username ON players (lower(username));

CREATE TABLE player_mode_stats (
    player_id   BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    mode        VARCHAR(10) NOT NULL,
    rating      INT NOT NULL,
    games       INT NOT NULL DEFAULT 0,
    peak        INT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (player_id, mode)
);
CREATE INDEX idx_stats_mode_rating ON player_mode_stats (mode, rating DESC);

CREATE TABLE score_history (
    id          BIGSERIAL PRIMARY KEY,
    player_id   BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    mode        VARCHAR(10) NOT NULL,
    rating      INT NOT NULL,
    delta       INT NOT NULL,
    source      VARCHAR(16) NOT NULL DEFAULT 'api',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_history_player ON score_history (player_id, mode, created_at DESC);
