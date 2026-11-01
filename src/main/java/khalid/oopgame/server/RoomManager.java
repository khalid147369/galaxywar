package khalid.oopgame.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;

final class RoomManager {
    private final Map<String, ActiveRoom> rooms = new ConcurrentHashMap<>();
    private final Map<String, ActiveRoom> playerRooms = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final RoomRepository repository;
    private final ServerConfig config;

    RoomManager(ObjectMapper mapper, RoomRepository repository, ServerConfig config) {
        this.mapper = mapper;
        this.repository = repository;
        this.config = config;
    }

    synchronized RoomEngine.JoinResult create(String name, Channel channel) {
        String code;
        do {
            code = randomCode();
        } while (rooms.containsKey(code) || repository.loadRoom(code) != null);

        RoomState state = new RoomState();
        state.code = code;
        state.seed = ThreadLocalRandom.current().nextLong();
        createUniverse(state);
        RoomEngine engine = new RoomEngine(
            state,
            mapper,
            repository,
            config.roomMaxPlayers,
            config.reconnectTtlSeconds
        );
        RoomEngine.JoinResult joined = engine.addGuest(name);
        ActiveRoom room = new ActiveRoom(engine);
        rooms.put(code, room);
        attach(room, joined.player.id, channel);
        return joined;
    }

    synchronized RoomEngine.JoinResult join(String code, String name, Channel channel) {
        ActiveRoom room = loadOrFind(code);
        RoomEngine.JoinResult joined = room.engine.addGuest(name);
        attach(room, joined.player.id, channel);
        return joined;
    }

    synchronized RoomEngine.JoinResult reconnect(
        String code,
        String playerId,
        String token,
        Channel channel
    ) {
        ActiveRoom room = loadOrFind(code);
        RoomEngine.JoinResult joined = room.engine.reconnect(playerId, token);
        attach(room, playerId, channel);
        return joined;
    }

    synchronized void command(
        String playerId,
        String action,
        String fleetId,
        String targetId,
        Integer mapX,
        Integer mapY,
        Float x,
        Float y,
        Integer index,
        String itemType
    ) {
        ActiveRoom room = roomFor(playerId);
        room.engine.command(playerId, action, fleetId, targetId, mapX, mapY, x, y, index, itemType);
        broadcast(room);
    }

    synchronized void tick(float delta) {
        for (ActiveRoom room : rooms.values()) {
            java.util.Set<String> playerIds = new java.util.HashSet<>(
                room.engine.state().players.keySet()
            );
            room.engine.tick(delta);
            playerIds.removeAll(room.engine.state().players.keySet());
            for (String expiredPlayerId : playerIds) {
                playerRooms.remove(expiredPlayerId, room);
                room.channels.remove(expiredPlayerId);
                repository.deleteGuest(expiredPlayerId);
            }
            broadcast(room);
        }
    }

    synchronized String roomCodeFor(String playerId) {
        return roomFor(playerId).engine.state().code;
    }

    synchronized void persistAll() {
        for (ActiveRoom room : rooms.values()) {
            repository.saveRoom(room.engine.state());
        }
    }

    synchronized void disconnect(String playerId, Channel channel) {
        ActiveRoom room = playerRooms.get(playerId);
        if (room == null) {
            return;
        }
        Channel activeChannel = room.channels.get(playerId);
        if (activeChannel != channel) {
            return;
        }
        room.channels.remove(playerId);
        room.engine.disconnect(playerId);
        room.engine.state().revision++;
        broadcast(room);
    }

    synchronized String snapshot(String playerId) {
        return roomFor(playerId).engine.snapshotJson();
    }

    private ActiveRoom loadOrFind(String rawCode) {
        String code = normalizeCode(rawCode);
        ActiveRoom active = rooms.get(code);
        if (active != null) {
            return active;
        }
        RoomState persisted = repository.loadRoom(code);
        if (persisted == null) {
            throw new IllegalArgumentException("No existe una sala con ese código.");
        }
        long recoveredAt = System.currentTimeMillis();
        for (RoomState.PlayerState player : persisted.players.values()) {
            player.connected = false;
            player.disconnectedAt = recoveredAt;
        }
        RoomEngine engine = new RoomEngine(
            persisted,
            mapper,
            repository,
            config.roomMaxPlayers,
            config.reconnectTtlSeconds
        );
        ActiveRoom restored = new ActiveRoom(engine);
        rooms.put(code, restored);
        return restored;
    }

    private ActiveRoom roomFor(String playerId) {
        ActiveRoom room = playerRooms.get(playerId);
        if (room == null) {
            throw new IllegalArgumentException("El jugador no está unido a ninguna sala.");
        }
        return room;
    }

    private void attach(ActiveRoom room, String playerId, Channel channel) {
        room.channels.put(playerId, channel);
        playerRooms.put(playerId, room);
    }

    private void broadcast(ActiveRoom room) {
        String snapshot = room.engine.snapshotJson();
        for (Channel channel : room.channels.values()) {
            if (channel.isActive()) {
                channel.writeAndFlush("{\"type\":\"snapshot\",\"snapshot\":" + snapshot + "}\n");
            }
        }
    }

    private static void createUniverse(RoomState state) {
        Random random = new Random(state.seed);
        for (int mapX = -3; mapX <= 3; mapX++) {
            for (int mapY = -3; mapY <= 3; mapY++) {
                boolean homeMap = UniverseLayout.isHomeSystem(mapX, mapY);
                int count = homeMap ? 4 : 1 + random.nextInt(4);
                for (int index = 0; index < count; index++) {
                    RoomState.PlanetState planet = new RoomState.PlanetState();
                    planet.id = mapX + ":" + mapY + ":planet:" + index;
                    planet.mapX = mapX;
                    planet.mapY = mapY;
                    if (homeMap && index == 0) {
                        planet.type = "SOLID";
                        planet.name = "Mundo natal";
                        planet.size = 76;
                        planet.x = 0.22f;
                        planet.y = 0.38f;
                    } else if (homeMap && index == 1) {
                        planet.type = "SUN";
                        planet.name = "Sol amarillo";
                        planet.size = 148;
                        planet.x = 0.68f;
                        planet.y = 0.46f;
                    } else {
                        int visual = random.nextInt(8);
                        planet.type = visual <= 2 ? "SOLID" : visual <= 5 ? "GASEOUS" : "SUN";
                        planet.name = "Planeta " + (index + 1);
                        planet.size = 42 + random.nextInt(101);
                        planet.x = 0.1f + random.nextFloat() * 0.8f;
                        planet.y = 0.1f + random.nextFloat() * 0.8f;
                    }
                    planet.maxHp = planet.size * 3;
                    planet.hp = planet.maxHp;
                    state.planets.add(planet);
                }
            }
        }
    }

    private static String randomCode() {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder(6);
        for (int index = 0; index < 6; index++) {
            code.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return code.toString();
    }

    private static String normalizeCode(String code) {
        if (code == null || code.trim().length() != 6) {
            throw new IllegalArgumentException("El código de sala debe tener seis caracteres.");
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static final class ActiveRoom {
        final RoomEngine engine;
        final Map<String, Channel> channels = new ConcurrentHashMap<>();

        ActiveRoom(RoomEngine engine) {
            this.engine = engine;
        }
    }
}
