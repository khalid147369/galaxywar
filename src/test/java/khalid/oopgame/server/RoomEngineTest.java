package khalid.oopgame.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

final class RoomEngineTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TestRepository repository = new TestRepository();

    @Test
    void guestCreationAssignsFleetAndReconnectSecret() {
        RoomEngine engine = new RoomEngine(room(), mapper, repository, 2, 300);
        RoomEngine.JoinResult result = engine.addGuest("Pilot");

        assertNotNull(result.player.id);
        assertEquals(1, result.player.fleets.size());
        assertEquals(64, result.player.reconnectTokenHash.length());
        assertTrue(!result.reconnectToken.isEmpty());
    }

    @Test
    void roomAssignsEachGuestAUniqueHomeSystem() {
        RoomEngine engine = new RoomEngine(room(), mapper, repository, 4, 300);
        Set<String> homeSystems = new HashSet<>();

        for (int index = 0; index < 4; index++) {
            RoomEngine.JoinResult result = engine.addGuest("Pilot" + index);
            homeSystems.add(result.player.homeMapX + ":" + result.player.homeMapY);
        }

        assertEquals(4, homeSystems.size());
    }

    @Test
    void movementRequiresOwnedFleetAndValidGridTransition() {
        RoomEngine engine = new RoomEngine(room(), mapper, repository, 2, 300);
        RoomEngine.JoinResult pilot = engine.addGuest("Pilot");
        RoomState.FleetState fleet = pilot.player.fleets.get(0);

        engine.command(pilot.player.id, "move", fleet.id, null, 0, 0, 0.6f, 0.5f, null, null);
        assertTrue(fleet.moving);
        assertThrows(IllegalArgumentException.class, () ->
            engine.command(pilot.player.id, "move", fleet.id, null, 1, 0, 0.1f, 0.5f, null, null)
        );
        assertThrows(IllegalArgumentException.class, () ->
            engine.command("not-owner", "move", fleet.id, null, 0, 0, 0.2f, 0.2f, null, null)
        );
    }

    @Test
    void fleetCanEnterGridAboveAfterReachingVisibleNorthEdge() {
        RoomEngine engine = new RoomEngine(room(), mapper, repository, 2, 300);
        RoomEngine.JoinResult pilot = engine.addGuest("Pilot");
        RoomState.FleetState fleet = pilot.player.fleets.get(0);
        int currentMapY = fleet.mapY;
        fleet.y = 0.80f;
        fleet.targetY = 0.90f;

        engine.command(
            pilot.player.id, "move", fleet.id, null, fleet.mapX, fleet.mapY + 1,
            fleet.x, 12f / 720f, null, null, 1280f, 720f
        );

        assertEquals(currentMapY + 1, fleet.mapY);
        assertEquals(12f / 720f, fleet.targetY, 0.0001f);
    }

    @Test
    void fleetCanEnterGridBelowAtTheClientsVisibleSouthEntryPosition() {
        RoomEngine engine = new RoomEngine(room(), mapper, repository, 2, 300);
        RoomEngine.JoinResult pilot = engine.addGuest("Pilot");
        RoomState.FleetState fleet = pilot.player.fleets.get(0);
        int currentMapY = fleet.mapY;
        fleet.y = 0.04f;
        fleet.targetY = 0.02f;

        engine.command(
            pilot.player.id, "move", fleet.id, null, fleet.mapX, currentMapY - 1,
            0.5f, (720f - 48f - 70f) / 720f, null, null, 1280f, 720f
        );

        assertEquals(currentMapY - 1, fleet.mapY);
        assertEquals((720f - 48f - 70f) / 720f, fleet.targetY, 0.0001f);
    }

    @Test
    void fleetCanEnterGridToTheRightAtTheClientsVisibleEntryPosition() {
        RoomEngine engine = new RoomEngine(room(), mapper, repository, 2, 300);
        RoomEngine.JoinResult pilot = engine.addGuest("Pilot");
        RoomState.FleetState fleet = pilot.player.fleets.get(0);
        int currentMapX = fleet.mapX;
        fleet.x = 0.04f;
        fleet.targetX = 0.02f;

        engine.command(
            pilot.player.id, "move", fleet.id, null, currentMapX - 1, fleet.mapY,
            (1280f - 48f - 12f) / 1280f, 0.5f, null, null, 1280f, 720f
        );

        assertEquals(currentMapX - 1, fleet.mapX);
        assertEquals((1280f - 48f - 12f) / 1280f, fleet.targetX, 0.0001f);
    }

    @Test
    void fleetCanEnterGridToTheLeftAtTheClientsVisibleEntryPosition() {
        RoomEngine engine = new RoomEngine(room(), mapper, repository, 2, 300);
        RoomEngine.JoinResult pilot = engine.addGuest("Pilot");
        RoomState.FleetState fleet = pilot.player.fleets.get(0);
        int currentMapX = fleet.mapX;
        fleet.x = 0.94f;
        fleet.targetX = 0.98f;

        engine.command(
            pilot.player.id, "move", fleet.id, null, currentMapX + 1, fleet.mapY,
            12f / 1280f, 0.5f, null, null, 1280f, 720f
        );

        assertEquals(currentMapX + 1, fleet.mapX);
        assertEquals(12f / 1280f, fleet.targetX, 0.0001f);
    }

    @Test
    void movementTickAdvancesAtSmoothClientCompatibleRate() {
        RoomEngine engine = new RoomEngine(room(), mapper, repository, 2, 300);
        RoomEngine.JoinResult pilot = engine.addGuest("Pilot");
        RoomState.FleetState fleet = pilot.player.fleets.get(0);

        engine.command(
            pilot.player.id, "move", fleet.id, null, 0, 0, 0.6f, fleet.y, null, null
        );

        assertTrue(engine.tick(0.05f));
        assertEquals(0.083f, fleet.x, 0.0001f);
    }

    @Test
    void verticalMovementMatchesClientPixelSpeedAtSuppliedViewportSize() {
        RoomEngine engine = new RoomEngine(room(), mapper, repository, 2, 300);
        RoomEngine.JoinResult pilot = engine.addGuest("Pilot");
        RoomState.FleetState fleet = pilot.player.fleets.get(0);
        float startY = fleet.y;

        engine.command(
            pilot.player.id, "move", fleet.id, null, 0, 0, fleet.x, 0.6f, null, null,
            1920f, 1080f
        );
        engine.tick(0.05f);

        assertEquals(3.75f, (fleet.y - startY) * 1080f, 0.001f);
    }

    @Test
    void movementCommandsDoNotBlockOnRoomPersistence() {
        RoomEngine engine = new RoomEngine(room(), mapper, repository, 2, 300);
        RoomEngine.JoinResult pilot = engine.addGuest("Pilot");
        RoomState.FleetState fleet = pilot.player.fleets.get(0);
        int roomSavesBeforeMove = repository.roomSaveCount;

        engine.command(
            pilot.player.id, "move", fleet.id, null, 0, 0, 0.6f, fleet.y, null, null
        );

        assertEquals(roomSavesBeforeMove, repository.roomSaveCount);
    }

    @Test
    void serverResolvesShotsAndRejectsOutOfRangeTargets() {
        RoomEngine engine = new RoomEngine(room(), mapper, repository, 2, 300);
        RoomEngine.JoinResult first = engine.addGuest("Pilot");
        RoomEngine.JoinResult second = engine.addGuest("Rival");
        RoomState.FleetState attacker = first.player.fleets.get(0);
        RoomState.FleetState defender = second.player.fleets.get(0);
        defender.mapX = attacker.mapX;
        defender.mapY = attacker.mapY;
        defender.x = 0.3f;
        defender.y = 0.08f;

        engine.command(first.player.id, "fire", attacker.id, defender.id,
            null, null, null, null, null, null);
        assertEquals(2, engine.state().projectiles.size());
        assertThrows(IllegalArgumentException.class, () ->
            engine.command(first.player.id, "fire", attacker.id, "unknown",
                null, null, null, null, null, null)
        );
        defender.x = 0.9f;
        attacker.lastFireAt = 0L;
        assertThrows(IllegalArgumentException.class, () ->
            engine.command(first.player.id, "fire", attacker.id, defender.id,
                null, null, null, null, null, null)
        );
    }

    @Test
    void validShotEventuallyDamagesEnemyFleet() {
        RoomEngine engine = new RoomEngine(room(), mapper, repository, 2, 300);
        RoomEngine.JoinResult first = engine.addGuest("Pilot");
        RoomEngine.JoinResult second = engine.addGuest("Rival");
        RoomState.FleetState attacker = first.player.fleets.get(0);
        RoomState.FleetState defender = second.player.fleets.get(0);
        defender.mapX = attacker.mapX;
        defender.mapY = attacker.mapY;
        defender.x = 0.3f;
        defender.y = attacker.y;

        engine.command(first.player.id, "fire", attacker.id, defender.id,
            null, null, null, null, null, null);
        for (int tick = 0; tick < 50; tick++) {
            engine.tick(0.05f);
        }

        assertTrue(defender.baseHp < 100);
        assertTrue(engine.state().projectiles.isEmpty());
    }

    @Test
    void claimingPlanetRequiresFleetInSameSystemAndNearby() {
        RoomState state = room();
        RoomState.PlanetState planet = new RoomState.PlanetState();
        planet.id = "home-planet";
        planet.mapX = 0;
        planet.mapY = 0;
        planet.x = 0.15f;
        planet.y = 0.08f;
        state.planets.add(planet);
        RoomEngine engine = new RoomEngine(state, mapper, repository, 2, 300);
        RoomEngine.JoinResult pilot = engine.addGuest("Pilot");
        planet.ownerId = pilot.player.id;
        RoomState.FleetState fleet = pilot.player.fleets.get(0);

        engine.command(pilot.player.id, "claim", fleet.id, planet.id,
            null, null, null, null, null, null);
        assertEquals(pilot.player.id, planet.ownerId);

        assertThrows(IllegalArgumentException.class, () ->
            engine.command(pilot.player.id, "claim", fleet.id, "missing",
                null, null, null, null, null, null)
        );
    }

    @Test
    void attackingEnemyPlanetCapturesItAfterItsHealthIsDepleted() {
        RoomState state = room();
        RoomState.PlanetState planet = new RoomState.PlanetState();
        planet.id = "enemy-home";
        planet.mapX = 0;
        planet.mapY = 0;
        planet.x = 0.15f;
        planet.y = 0.08f;
        planet.hp = 16;
        planet.maxHp = 16;
        state.planets.add(planet);
        RoomEngine engine = new RoomEngine(state, mapper, repository, 2, 300);
        RoomEngine.JoinResult pilot = engine.addGuest("Pilot");
        RoomEngine.JoinResult rival = engine.addGuest("Rival");
        RoomState.FleetState fleet = pilot.player.fleets.get(0);
        RoomState.FleetState rivalFleet = rival.player.fleets.get(0);
        rivalFleet.mapX = 0;
        rivalFleet.mapY = 0;
        planet.ownerId = rival.player.id;

        engine.command(
            pilot.player.id,
            "attack_planet",
            fleet.id,
            planet.id,
            null,
            null,
            null,
            null,
            null,
            null
        );

        assertEquals(pilot.player.id, planet.ownerId);
        assertEquals(planet.maxHp, planet.hp);
    }

    @Test
    void snapshotsNeverExposeReconnectTokenHashes() {
        RoomEngine engine = new RoomEngine(room(), mapper, repository, 2, 300);
        RoomEngine.JoinResult pilot = engine.addGuest("Pilot");

        assertTrue(!engine.snapshotJson().contains(pilot.player.reconnectTokenHash));
    }

    @Test
    void expiredGuestIsRemovedAndItsPlanetsBecomeNeutral() {
        RoomState state = room();
        RoomState.PlanetState planet = new RoomState.PlanetState();
        planet.id = "owned-world";
        planet.hp = 25;
        planet.maxHp = 80;
        state.planets.add(planet);
        RoomEngine engine = new RoomEngine(state, mapper, repository, 2, 10);
        RoomEngine.JoinResult pilot = engine.addGuest("Pilot");
        planet.ownerId = pilot.player.id;
        engine.disconnect(pilot.player.id);
        pilot.player.disconnectedAt = System.currentTimeMillis() - 11000L;

        engine.tick(0.05f);

        assertTrue(!engine.state().players.containsKey(pilot.player.id));
        assertNull(planet.ownerId);
        assertEquals(planet.maxHp, planet.hp);
    }

    @Test
    void buildingIsAuthorizedChargedAndProducesResourcesOnServer() {
        RoomState state = room();
        RoomState.PlanetState planet = ownedPlanet("farm-world", "STANDARD");
        state.planets.add(planet);
        RoomEngine engine = new RoomEngine(state, mapper, repository, 2, 300);
        RoomEngine.JoinResult pilot = engine.addGuest("Pilot");
        planet.ownerId = pilot.player.id;

        engine.command(pilot.player.id, "build_building", null, planet.id,
            null, null, 0.5f, 0.5f, null, "FARM");
        assertEquals(80, pilot.player.metal);
        assertEquals(85, pilot.player.food);
        assertEquals("FARM", planet.buildings.get(0).type);

        engine.tick(60f);

        assertEquals(93, pilot.player.food);
        assertThrows(IllegalArgumentException.class, () ->
            engine.command("not-owner", "build_building", null, planet.id,
                null, null, 0.2f, 0.2f, null, "MINE")
        );
    }

    @Test
    void ownedPlanetTypesProduceTheirResourceOnServer() {
        RoomState state = room();
        RoomState.PlanetState solid = ownedPlanet("food-world", "SOLID");
        RoomState.PlanetState sun = ownedPlanet("energy-world", "SUN");
        state.planets.add(solid);
        state.planets.add(sun);
        RoomEngine engine = new RoomEngine(state, mapper, repository, 2, 300);
        RoomEngine.JoinResult pilot = engine.addGuest("Pilot");
        solid.ownerId = pilot.player.id;
        sun.ownerId = pilot.player.id;

        engine.tick(60f);

        assertEquals(120, pilot.player.food);
        assertEquals(90, pilot.player.energy);
    }

    @Test
    void towerBuildAndAttackAreValidatedAndSynced() {
        RoomState state = room();
        RoomState.PlanetState planet = ownedPlanet("fortified-world", "SOLID");
        state.planets.add(planet);
        RoomEngine engine = new RoomEngine(state, mapper, repository, 2, 300);
        RoomEngine.JoinResult defender = engine.addGuest("Defender");
        RoomEngine.JoinResult attacker = engine.addGuest("Attacker");
        planet.ownerId = defender.player.id;
        RoomState.FleetState fleet = attacker.player.fleets.get(0);
        fleet.mapX = planet.mapX;
        fleet.mapY = planet.mapY;
        fleet.x = planet.x;
        fleet.y = planet.y;

        engine.command(defender.player.id, "build_tower", null, planet.id,
            null, null, 0.4f, 0.4f, null, "G79");
        assertEquals(80, defender.player.metal);
        assertEquals(90, defender.player.food);
        assertEquals(defender.player.id, planet.towers.get(0).ownerId);
        assertThrows(IllegalArgumentException.class, () ->
            engine.command(defender.player.id, "build_tower", null, planet.id,
                null, null, 0.6f, 0.6f, null, "XN22")
        );

        engine.command(attacker.player.id, "attack_tower", fleet.id, planet.id,
            null, null, null, null, 0, null);
        assertEquals(20, planet.towers.get(0).hp);
        assertThrows(IllegalArgumentException.class, () ->
            engine.command(attacker.player.id, "attack_tower", fleet.id, planet.id,
                null, null, null, null, 0, null)
        );
    }

    private RoomState.PlanetState ownedPlanet(String id, String type) {
        RoomState.PlanetState planet = new RoomState.PlanetState();
        planet.id = id;
        planet.type = type;
        planet.mapX = 0;
        planet.mapY = 0;
        planet.x = 0.08f;
        planet.y = 0.08f;
        planet.size = 100;
        planet.hp = 100;
        planet.maxHp = 100;
        return planet;
    }

    private RoomState room() {
        RoomState room = new RoomState();
        room.code = "ABC234";
        return room;
    }

    private static final class TestRepository implements RoomRepository {
        private final Map<String, RoomState> rooms = new HashMap<>();
        private int roomSaveCount;

        @Override
        public void saveGuest(RoomState.PlayerState player) {
        }

        @Override
        public void updateGuestSeen(String playerId) {
        }

        @Override
        public void deleteGuest(String playerId) {
        }

        @Override
        public void saveRoom(RoomState state) {
            roomSaveCount++;
            rooms.put(state.code, state);
        }

        @Override
        public RoomState loadRoom(String roomCode) {
            return rooms.get(roomCode);
        }
    }
}
