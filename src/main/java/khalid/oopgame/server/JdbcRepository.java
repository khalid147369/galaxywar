package khalid.oopgame.server;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

final class JdbcRepository implements AutoCloseable, RoomRepository {
    private final HikariDataSource dataSource;
    private final ObjectMapper mapper;

    JdbcRepository(ServerConfig config, ObjectMapper mapper) {
        this.mapper = mapper;
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.databaseUser);
        hikari.setPassword(config.databasePassword);
        hikari.setMaximumPoolSize(config.databasePoolSize);
        hikari.setPoolName("odyssey-postgres");
        hikari.setConnectionTimeout(10000L);
        dataSource = new HikariDataSource(hikari);
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    DataSource dataSource() {
        return dataSource;
    }

    public synchronized void saveGuest(RoomState.PlayerState player) {
        execute(
            "INSERT INTO guest_players(player_id, display_name, reconnect_token_hash, last_seen_at) "
                + "VALUES (?::uuid, ?, ?, now()) ON CONFLICT(player_id) DO UPDATE SET "
                + "display_name=EXCLUDED.display_name, reconnect_token_hash=EXCLUDED.reconnect_token_hash, "
                + "last_seen_at=now()",
            statement -> {
                statement.setString(1, player.id);
                statement.setString(2, player.name);
                statement.setString(3, player.reconnectTokenHash);
            }
        );
    }

    public synchronized void updateGuestSeen(String playerId) {
        execute("UPDATE guest_players SET last_seen_at=now() WHERE player_id=?::uuid", statement ->
            statement.setString(1, playerId)
        );
    }

    public synchronized void deleteGuest(String playerId) {
        execute("DELETE FROM guest_players WHERE player_id=?::uuid", statement ->
            statement.setString(1, playerId)
        );
    }

    public synchronized void saveRoom(RoomState state) {
        String json;
        try {
            json = mapper.writeValueAsString(state);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo serializar la partida para persistencia.", exception);
        }
        execute(
            "INSERT INTO game_rooms(room_code,status,state,updated_at) VALUES (?, ?, ?::jsonb, now()) "
                + "ON CONFLICT(room_code) DO UPDATE SET status=EXCLUDED.status, "
                + "state=EXCLUDED.state, updated_at=now()",
            statement -> {
                statement.setString(1, state.code);
                statement.setString(2, state.players.size() > 1 ? "active" : "waiting");
                statement.setString(3, json);
            }
        );
    }

    public synchronized RoomState loadRoom(String roomCode) {
        try (java.sql.Connection connection = dataSource.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                 "SELECT state::text FROM game_rooms WHERE room_code=?"
             )) {
            statement.setString(1, roomCode);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                return result.next() ? mapper.readValue(result.getString(1), RoomState.class) : null;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo cargar la sala " + roomCode + ".", exception);
        }
    }

    private void execute(String sql, StatementBinder binder) {
        try (java.sql.Connection connection = dataSource.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("Error al persistir el estado multijugador.", exception);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(java.sql.PreparedStatement statement) throws java.sql.SQLException;
    }
}
