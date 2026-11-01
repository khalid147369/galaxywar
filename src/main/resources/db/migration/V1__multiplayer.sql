CREATE TABLE guest_players (
    player_id UUID PRIMARY KEY,
    display_name VARCHAR(24) NOT NULL,
    reconnect_token_hash CHAR(64) NOT NULL UNIQUE,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE game_rooms (
    room_code VARCHAR(8) PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    state JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
