package khalid.oopgame.server;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

final class RoomEngine {
    private static final float FLEET_SPEED_PIXELS = 75f;
    private static final float SHIP_RANGE = 0.42f;
    private static final float BASE_RANGE = 0.6f;
    private static final int TOWER_HP = 30;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RoomState state;
    private final ObjectMapper mapper;
    private final RoomRepository repository;
    private final int maximumPlayers;
    private final long reconnectTtlMillis;

    RoomEngine(
        RoomState state,
        ObjectMapper mapper,
        RoomRepository repository,
        int maximumPlayers,
        int reconnectTtlSeconds
    ) {
        this.state = state;
        this.mapper = mapper;
        this.repository = repository;
        this.maximumPlayers = maximumPlayers;
        reconnectTtlMillis = reconnectTtlSeconds * 1000L;
    }

    synchronized RoomState state() {
        return state;
    }

    synchronized JoinResult addGuest(String name) {
        validateName(name);
        if (state.players.size() >= maximumPlayers) {
            throw new IllegalArgumentException("La sala está llena.");
        }
        RoomState.PlayerState player = new RoomState.PlayerState();
        player.id = UUID.randomUUID().toString();
        player.name = name.trim();
        player.connected = true;
        player.reconnectTokenHash = "";
        int homeIndex = availableHomeSystemIndex();
        if (homeIndex < 0 || homeIndex >= UniverseLayout.homeSystemCount()) {
            throw new IllegalArgumentException("La sala ya no tiene sistemas natales disponibles.");
        }
        player.homeMapX = UniverseLayout.homeMapX(homeIndex);
        player.homeMapY = UniverseLayout.homeMapY(homeIndex);
        String token = createToken();
        player.reconnectTokenHash = hash(token);
        RoomState.FleetState fleet = new RoomState.FleetState();
        fleet.id = UUID.randomUUID().toString();
        fleet.ownerId = player.id;
        fleet.mapX = player.homeMapX;
        fleet.mapY = player.homeMapY;
        fleet.x = 0.08f + (homeIndex % 4) * 0.22f;
        fleet.y = 0.08f + (homeIndex / 4) * 0.22f;
        fleet.targetX = fleet.x;
        fleet.targetY = fleet.y;
        RoomState.ShipState initialShip = new RoomState.ShipState();
        initialShip.id = UUID.randomUUID().toString();
        fleet.ships.add(initialShip);
        player.fleets.add(fleet);
        state.players.put(player.id, player);
        for (RoomState.PlanetState planet : state.planets) {
            boolean homeSystem = planet.mapX == player.homeMapX
                && planet.mapY == player.homeMapY;
            if (homeSystem && (planet.id.endsWith(":planet:0")
                || planet.id.endsWith(":planet:1"))) {
                planet.ownerId = player.id;
            }
        }
        state.revision++;
        repository.saveGuest(player);
        persist();
        return new JoinResult(player, token);
    }

    synchronized JoinResult reconnect(String playerId, String token) {
        RoomState.PlayerState player = state.players.get(playerId);
        if (player == null || token == null || !hash(token).equals(player.reconnectTokenHash)) {
            throw new IllegalArgumentException("La sesión de reconexión no es válida.");
        }
        if (player.connected) {
            throw new IllegalArgumentException("El jugador ya está conectado.");
        }
        if (System.currentTimeMillis() - player.disconnectedAt > reconnectTtlMillis) {
            throw new IllegalArgumentException("La sesión de reconexión ha caducado.");
        }
        player.connected = true;
        player.disconnectedAt = 0L;
        state.revision++;
        persist();
        return new JoinResult(player, token);
    }

    synchronized void disconnect(String playerId) {
        RoomState.PlayerState player = state.players.get(playerId);
        if (player == null) {
            return;
        }
        player.connected = false;
        player.disconnectedAt = System.currentTimeMillis();
        state.revision++;
        repository.updateGuestSeen(player.id);
        persist();
    }

    synchronized void command(String playerId, String action, String fleetId, String targetId,
                              Integer mapX, Integer mapY, Float x, Float y, Integer index,
                              String itemType) {
        command(
            playerId, action, fleetId, targetId, mapX, mapY, x, y, index, itemType,
            1280f, 720f
        );
    }

    synchronized void command(String playerId, String action, String fleetId, String targetId,
                              Integer mapX, Integer mapY, Float x, Float y, Integer index,
                              String itemType, Float worldWidth, Float worldHeight) {
        RoomState.PlayerState player = requirePlayer(playerId);
        RoomState.FleetState fleet = requiresFleet(action) ? ownedFleet(player, fleetId) : null;
        float movementWidth = worldWidth == null ? 1280f : worldWidth;
        float movementHeight = worldHeight == null ? 720f : worldHeight;
        switch (action) {
            case "move":
                if (mapX == null || mapY == null || x == null || y == null
                    || mapX < -3 || mapX > 3 || mapY < -3 || mapY > 3
                    || Math.abs(mapX - fleet.mapX) + Math.abs(mapY - fleet.mapY) > 1
                    || !normalized(x) || !normalized(y)
                    || !validWorldSize(movementWidth) || !validWorldSize(movementHeight)) {
                    throw new IllegalArgumentException("La orden de movimiento no es válida.");
                }
                validateMapTransition(
                    fleet, mapX, mapY, x, y, movementWidth, movementHeight
                );
                fleet.mapX = mapX;
                fleet.mapY = mapY;
                fleet.targetX = x;
                fleet.targetY = y;
                fleet.movementWidth = movementWidth;
                fleet.movementHeight = movementHeight;
                fleet.moving = true;
                break;
            case "fire":
                fire(player, fleet, targetId);
                break;
            case "recruit":
                if (player.metal < 50 || player.food < 30) {
                    throw new IllegalArgumentException("No hay recursos suficientes para reclutar.");
                }
                player.metal -= 50;
                player.food -= 30;
                RoomState.ShipState ship = new RoomState.ShipState();
                ship.id = UUID.randomUUID().toString();
                fleet.ships.add(ship);
                break;
            case "claim":
                claim(player, fleet, targetId);
                break;
            case "attack_planet":
                attackPlanet(player, fleet, targetId);
                break;
            case "build_building":
                buildBuilding(player, targetId, itemType, x, y);
                break;
            case "remove_building":
                removeBuilding(player, targetId, index);
                break;
            case "build_tower":
                buildTower(player, targetId, itemType, x, y);
                break;
            case "attack_tower":
                attackTower(player, fleet, targetId, index);
                break;
            case "recruit_planet_ship":
                recruitPlanetShip(player, fleet, targetId);
                break;
            default:
                throw new IllegalArgumentException("Orden de partida desconocida.");
        }
        state.revision++;
    }

    synchronized boolean tick(float delta) {
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (RoomState.PlayerState player : state.players.values()) {
            for (RoomState.FleetState fleet : player.fleets) {
                if (fleet.moving) {
                    float dx = (fleet.targetX - fleet.x) * fleet.movementWidth;
                    float dy = (fleet.targetY - fleet.y) * fleet.movementHeight;
                    float distance = (float) Math.sqrt(dx * dx + dy * dy);
                    float step = FLEET_SPEED_PIXELS * Math.min(delta, 0.1f);
                    if (distance <= step || distance == 0f) {
                        fleet.x = fleet.targetX;
                        fleet.y = fleet.targetY;
                        fleet.moving = false;
                    } else {
                        fleet.x += dx / distance * step / fleet.movementWidth;
                        fleet.y += dy / distance * step / fleet.movementHeight;
                    }
                    changed = true;
                }
            }
        }
        changed |= updatePlanetProduction(delta);
        changed |= updatePlanetDefenses(delta, now);
        for (int index = state.projectiles.size() - 1; index >= 0; index--) {
            RoomState.ProjectileState projectile = state.projectiles.get(index);
            float dx = (projectile.targetX - projectile.x) * projectile.movementWidth;
            float dy = (projectile.targetY - projectile.y) * projectile.movementHeight;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            float step = projectile.speed * Math.min(delta, 0.1f);
            if (distance <= step || distance == 0f) {
                RoomState.FleetState target = findFleet(projectile.targetFleetId);
                if (target != null) {
                    applyDamage(target, projectile.damage);
                }
                state.projectiles.remove(index);
            } else {
                projectile.x += dx / distance * step / projectile.movementWidth;
                projectile.y += dy / distance * step / projectile.movementHeight;
            }
            changed = true;
        }
        java.util.Set<String> expiredPlayerIds = new java.util.HashSet<>();
        for (RoomState.PlayerState player : state.players.values()) {
            if (!player.connected && now - player.disconnectedAt > reconnectTtlMillis) {
                expiredPlayerIds.add(player.id);
            }
        }
        if (!expiredPlayerIds.isEmpty()) {
            state.players.keySet().removeAll(expiredPlayerIds);
            for (RoomState.PlanetState planet : state.planets) {
                if (expiredPlayerIds.contains(planet.ownerId)) {
                    planet.ownerId = null;
                    planet.hp = planet.maxHp;
                }
            }
            changed = true;
        }
        if (changed) {
            state.revision++;
        }
        return changed;
    }

    synchronized String snapshotJson() {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode snapshot =
                mapper.valueToTree(state);
            com.fasterxml.jackson.databind.node.ObjectNode players =
                (com.fasterxml.jackson.databind.node.ObjectNode) snapshot.get("players");
            java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> playerIterator =
                players.elements();
            while (playerIterator.hasNext()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) playerIterator.next())
                    .remove("reconnectTokenHash");
            }

            return mapper.writeValueAsString(snapshot);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo serializar el estado de sala.", exception);
        }
    }

    synchronized RoomState persistenceSnapshot() {
        try {
            return mapper.readValue(mapper.writeValueAsBytes(state), RoomState.class);
        } catch (Exception exception) {
            throw new IllegalStateException(
                "No se pudo copiar el estado de sala para persistencia.", exception
            );
        }
    }

    private void fire(
        RoomState.PlayerState player,
        RoomState.FleetState attacker,
        String targetId
    ) {
        RoomState.FleetState target = findFleet(targetId);
        if (target == null || target.ownerId.equals(player.id)
            || target.mapX != attacker.mapX || target.mapY != attacker.mapY) {
            throw new IllegalArgumentException("El objetivo no es válido o está fuera del sistema.");
        }
        float distance = distance(attacker.x, attacker.y, target.x, target.y);
        long now = System.currentTimeMillis();
        if (distance > BASE_RANGE || now - attacker.lastFireAt < 500L) {
            throw new IllegalArgumentException("Objetivo fuera de alcance o disparo demasiado rápido.");
        }
        attacker.lastFireAt = now;
        RoomState.ProjectileState projectile = new RoomState.ProjectileState();
        projectile.id = UUID.randomUUID().toString();
        projectile.ownerId = player.id;
        projectile.targetFleetId = target.id;
        projectile.mapX = attacker.mapX;
        projectile.mapY = attacker.mapY;
        projectile.movementWidth = attacker.movementWidth;
        projectile.movementHeight = attacker.movementHeight;
        projectile.x = attacker.x;
        projectile.y = attacker.y;
        projectile.targetX = target.x;
        projectile.targetY = target.y;
        projectile.damage = 10 + Math.min(attacker.ships.size(), 10);
        state.projectiles.add(projectile);
        for (RoomState.ShipState ship : attacker.ships) {
            if (distance <= SHIP_RANGE) {
                RoomState.ProjectileState shipShot = new RoomState.ProjectileState();
                shipShot.id = UUID.randomUUID().toString();
                shipShot.ownerId = player.id;
                shipShot.targetFleetId = target.id;
                shipShot.mapX = attacker.mapX;
                shipShot.mapY = attacker.mapY;
                shipShot.movementWidth = attacker.movementWidth;
                shipShot.movementHeight = attacker.movementHeight;
                shipShot.x = attacker.x;
                shipShot.y = attacker.y;
                shipShot.targetX = target.x;
                shipShot.targetY = target.y;
                shipShot.speed = 300f;
                shipShot.damage = 4;
                state.projectiles.add(shipShot);
            }
        }
    }

    private void claim(RoomState.PlayerState player, RoomState.FleetState fleet, String planetId) {
        RoomState.PlanetState planet = findPlanet(planetId);
        if (planet == null || planet.mapX != fleet.mapX || planet.mapY != fleet.mapY
            || distance(planet.x, planet.y, fleet.x, fleet.y) > 0.12f) {
            throw new IllegalArgumentException("Debes acercar tu flota al planeta para conquistarlo.");
        }
        if (hasActiveTowers(planet)) {
            throw new IllegalArgumentException("Debes destruir las torretas antes de conquistar el planeta.");
        }
        if (planet.ownerId != null && !planet.ownerId.equals(player.id)) {
            throw new IllegalArgumentException("El planeta está ocupado. Debes derrotar sus defensas.");
        }
        planet.ownerId = player.id;
    }

    private void attackPlanet(
        RoomState.PlayerState player,
        RoomState.FleetState fleet,
        String planetId
    ) {
        RoomState.PlanetState planet = findPlanet(planetId);
        if (planet == null || planet.mapX != fleet.mapX || planet.mapY != fleet.mapY
            || distance(planet.x, planet.y, fleet.x, fleet.y) > 0.12f
            || planet.ownerId == null || planet.ownerId.equals(player.id)
            || hasActiveTowers(planet)) {
            throw new IllegalArgumentException("El planeta no es un objetivo enemigo cercano.");
        }
        long now = System.currentTimeMillis();
        if (now - planet.lastSiegeAt < 1000L) {
            throw new IllegalArgumentException("La flota todavía está preparando el siguiente ataque.");
        }
        planet.lastSiegeAt = now;
        planet.hp = Math.max(0, planet.hp - 12 - fleet.ships.size() * 4);
        if (planet.hp == 0) {
            planet.ownerId = player.id;
            planet.hp = planet.maxHp;
            planet.buildings.clear();
            planet.towers.clear();
        }
    }

    private static boolean hasActiveTowers(RoomState.PlanetState planet) {
        return planet.towers.stream().anyMatch(tower -> tower.hp > 0);
    }

    private RoomState.PlanetState findPlanet(String planetId) {
        if (planetId == null) {
            return null;
        }
        for (RoomState.PlanetState candidate : state.planets) {
            if (planetId.equals(candidate.id)) {
                return candidate;
            }
        }
        return null;
    }

    private RoomState.PlanetState ownedPlanet(RoomState.PlayerState player, String planetId) {
        RoomState.PlanetState planet = findPlanet(planetId);
        if (planet == null || !player.id.equals(planet.ownerId)) {
            throw new IllegalArgumentException("El planeta no pertenece a tu civilización.");
        }
        return planet;
    }

    private void buildBuilding(
        RoomState.PlayerState player,
        String planetId,
        String type,
        Float x,
        Float y
    ) {
        RoomState.PlanetState planet = ownedPlanet(player, planetId);
        if (type == null || x == null || y == null || !normalized(x) || !normalized(y)
            || x < 0.04f || x > 0.96f || y < 0.04f || y > 0.96f
            || planet.buildings.size() >= Math.min(6, Math.max(1, 1 + planet.size / 25))) {
            throw new IllegalArgumentException("La posición o capacidad para construir no es válida.");
        }
        BuildingCost cost = BuildingCost.forType(type);
        for (RoomState.BuildingState existing : planet.buildings) {
            float dx = (x - existing.x) * 1.5f;
            float dy = y - existing.y;
            if (dx * dx + dy * dy < 0.0081f) {
                throw new IllegalArgumentException("Ese espacio está ocupado.");
            }
        }
        spend(player, cost.metal, cost.food, cost.energy);
        RoomState.BuildingState building = new RoomState.BuildingState();
        building.type = cost.type;
        building.x = x;
        building.y = y;
        planet.buildings.add(building);
    }

    private void removeBuilding(
        RoomState.PlayerState player,
        String planetId,
        Integer index
    ) {
        RoomState.PlanetState planet = ownedPlanet(player, planetId);
        if (index == null || index < 0 || index >= planet.buildings.size()) {
            throw new IllegalArgumentException("El edificio seleccionado no existe.");
        }
        planet.buildings.remove(index.intValue());
    }

    private void buildTower(
        RoomState.PlayerState player,
        String planetId,
        String type,
        Float x,
        Float y
    ) {
        RoomState.PlanetState planet = ownedPlanet(player, planetId);
        if (!"SOLID".equals(planet.type)) {
            throw new IllegalArgumentException("Solo se pueden construir torretas en planetas sólidos.");
        }
        if (type == null || !isTowerType(type) || x == null || y == null
            || !normalized(x) || !normalized(y) || x < 0.04f || x > 0.96f
            || y < 0.04f || y > 0.96f) {
            throw new IllegalArgumentException("La torreta o su posición no son válidas.");
        }
        spend(player, 20, 10, 0);
        RoomState.TowerState tower = new RoomState.TowerState();
        tower.type = type.toUpperCase(Locale.ROOT);
        tower.ownerId = player.id;
        tower.x = x;
        tower.y = y;
        planet.towers.add(tower);
    }

    private void attackTower(
        RoomState.PlayerState player,
        RoomState.FleetState fleet,
        String planetId,
        Integer index
    ) {
        RoomState.PlanetState planet = findPlanet(planetId);
        if (planet == null || planet.ownerId == null || planet.ownerId.equals(player.id)
            || planet.mapX != fleet.mapX || planet.mapY != fleet.mapY
            || distance(planet.x, planet.y, fleet.x, fleet.y) > 0.12f
            || index == null || index < 0 || index >= planet.towers.size()) {
            throw new IllegalArgumentException("La torreta no es un objetivo enemigo cercano.");
        }
        long now = System.currentTimeMillis();
        if (now - fleet.lastTowerAttackAt < 700L) {
            throw new IllegalArgumentException("Espera antes de volver a disparar a la torreta.");
        }
        RoomState.TowerState tower = planet.towers.get(index);
        if (tower.hp <= 0) {
            throw new IllegalArgumentException("La torreta ya ha sido destruida.");
        }
        fleet.lastTowerAttackAt = now;
        tower.hp = Math.max(0, tower.hp - Math.max(8, 8 + fleet.ships.size() * 2));
    }

    private void recruitPlanetShip(
        RoomState.PlayerState player,
        RoomState.FleetState fleet,
        String planetId
    ) {
        RoomState.PlanetState planet = ownedPlanet(player, planetId);
        if (planet.mapX != fleet.mapX || planet.mapY != fleet.mapY
            || planet.buildings.stream().noneMatch(building -> "SHIPYARD".equals(building.type))) {
            throw new IllegalArgumentException("Se necesita un astillero y una flota propia en el sistema.");
        }
        spend(player, 50, 30, 0);
        RoomState.ShipState ship = new RoomState.ShipState();
        ship.id = UUID.randomUUID().toString();
        fleet.ships.add(ship);
    }

    private boolean updatePlanetProduction(float delta) {
        boolean changed = false;
        for (RoomState.PlanetState planet : state.planets) {
            if (planet.ownerId == null) {
                continue;
            }
            RoomState.PlayerState owner = state.players.get(planet.ownerId);
            if (owner == null || !owner.connected) {
                continue;
            }
            int naturalProductionRate = naturalProductionPerMinute(planet);
            if (naturalProductionRate > 0) {
                planet.naturalProductionFraction += Math.max(0f, delta)
                    * naturalProductionRate * planet.size / 100f / 60f;
                int naturalUnits = (int) planet.naturalProductionFraction;
                if (naturalUnits > 0) {
                    switch (planet.type) {
                        case "SOLID":
                            owner.food += naturalUnits;
                            break;
                        case "GASEOUS":
                            owner.metal += naturalUnits;
                            break;
                        case "SUN":
                        case "STAR":
                        case "ENERGY_RICH":
                            owner.energy += naturalUnits;
                            break;
                        default:
                            break;
                    }
                    planet.naturalProductionFraction -= naturalUnits;
                    changed = true;
                }
            }
            for (RoomState.BuildingState building : planet.buildings) {
                int rate = BuildingCost.productionPerMinute(building.type);
                if (rate == 0) {
                    continue;
                }
                building.productionFraction += Math.max(0f, delta) * rate / 60f;
                int produced = (int) building.productionFraction;
                if (produced > 0) {
                    if ("FARM".equals(building.type)) {
                        owner.food += produced;
                    } else if ("MINE".equals(building.type)) {
                        owner.metal += produced;
                    } else {
                        owner.energy += produced;
                    }
                    building.productionFraction -= produced;
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static int naturalProductionPerMinute(RoomState.PlanetState planet) {
        if (planet.type == null) {
            return 0;
        }
        switch (planet.type) {
            case "SOLID":
            case "GASEOUS":
                return 20;
            case "SUN":
                return 60;
            case "STAR":
                return 45;
            case "ENERGY_RICH":
                return 30;
            default:
                return 0;
        }
    }

    private boolean updatePlanetDefenses(float delta, long now) {
        boolean changed = false;
        for (RoomState.PlanetState planet : state.planets) {
            if (planet.ownerId == null || planet.towers.stream().noneMatch(tower -> tower.hp > 0)) {
                continue;
            }
            for (RoomState.PlayerState player : state.players.values()) {
                if (player.id.equals(planet.ownerId)) {
                    continue;
                }
                for (int fleetIndex = player.fleets.size() - 1; fleetIndex >= 0; fleetIndex--) {
                    RoomState.FleetState fleet = player.fleets.get(fleetIndex);
                    if (fleet.mapX != planet.mapX || fleet.mapY != planet.mapY
                        || distance(planet.x, planet.y, fleet.x, fleet.y) > 0.16f) {
                        continue;
                    }
                    if (now - planet.lastDefenseAt >= 1000L) {
                        applyDamage(fleet, Math.max(2, (int) (delta * 20f)));
                        planet.lastDefenseAt = now;
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    private static void spend(
        RoomState.PlayerState player,
        int metal,
        int food,
        int energy
    ) {
        if (player.metal < metal || player.food < food || player.energy < energy) {
            throw new IllegalArgumentException("No hay recursos suficientes para esta acción.");
        }
        player.metal -= metal;
        player.food -= food;
        player.energy -= energy;
    }

    private static boolean requiresFleet(String action) {
        return "move".equals(action) || "fire".equals(action) || "recruit".equals(action)
            || "claim".equals(action) || "attack_planet".equals(action)
            || "attack_tower".equals(action) || "recruit_planet_ship".equals(action);
    }

    private static boolean isTowerType(String type) {
        return "G79".equalsIgnoreCase(type) || "DX41".equalsIgnoreCase(type)
            || "MK531".equalsIgnoreCase(type);
    }

    private static final class BuildingCost {
        final String type;
        final int metal;
        final int food;
        final int energy;

        BuildingCost(String type, int metal, int food, int energy) {
            this.type = type;
            this.metal = metal;
            this.food = food;
            this.energy = energy;
        }

        static BuildingCost forType(String rawType) {
            switch (rawType.toUpperCase(Locale.ROOT)) {
                case "FARM":
                    return new BuildingCost("FARM", 20, 15, 0);
                case "MINE":
                    return new BuildingCost("MINE", 30, 10, 5);
                case "REACTOR":
                    return new BuildingCost("REACTOR", 25, 10, 0);
                case "SHIPYARD":
                    return new BuildingCost("SHIPYARD", 50, 30, 10);
                default:
                    throw new IllegalArgumentException("Tipo de edificio desconocido.");
            }
        }

        static int productionPerMinute(String type) {
            if ("FARM".equals(type)) {
                return 8;
            }
            if ("MINE".equals(type)) {
                return 6;
            }
            if ("REACTOR".equals(type)) {
                return 5;
            }
            return 0;
        }
    }

    private static void validateMapTransition(
        RoomState.FleetState fleet,
        int nextMapX,
        int nextMapY,
        float targetX,
        float targetY,
        float worldWidth,
        float worldHeight
    ) {
        if (nextMapX == fleet.mapX && nextMapY == fleet.mapY) {
            return;
        }
        float westThreshold = 42f / worldWidth;
        float eastThreshold = 1f - 90f / worldWidth;
        float southThreshold = 42f / worldHeight;
        float northThreshold = 1f - 150f / worldHeight;
        boolean atWestEdge = (fleet.x <= westThreshold || fleet.targetX <= westThreshold)
            && nextMapX < fleet.mapX && nextMapY == fleet.mapY;
        boolean atEastEdge = (fleet.x >= eastThreshold || fleet.targetX >= eastThreshold)
            && nextMapX > fleet.mapX && nextMapY == fleet.mapY;
        boolean atSouthEdge = (fleet.y <= southThreshold || fleet.targetY <= southThreshold)
            && nextMapY < fleet.mapY && nextMapX == fleet.mapX;
        boolean atNorthEdge = (fleet.y >= northThreshold || fleet.targetY >= northThreshold)
            && nextMapY > fleet.mapY && nextMapX == fleet.mapX;
        if (!(atWestEdge || atEastEdge || atSouthEdge || atNorthEdge)) {
            throw new IllegalArgumentException("La flota debe alcanzar el borde antes de cambiar de cuadrícula.");
        }
        boolean targetIsOppositeEdge = atWestEdge && targetX >= 1f - 60f / worldWidth
            || atEastEdge && targetX <= 12f / worldWidth
            || atSouthEdge && targetY >= 1f - 118f / worldHeight
            || atNorthEdge && targetY <= 12f / worldHeight;
        if (!targetIsOppositeEdge) {
            throw new IllegalArgumentException("La posición de entrada en la nueva cuadrícula no es válida.");
        }
    }

    private void applyDamage(RoomState.FleetState fleet, int damage) {
        int remaining = damage;
        for (int index = fleet.ships.size() - 1; index >= 0 && remaining > 0; index--) {
            RoomState.ShipState ship = fleet.ships.get(index);
            int hit = Math.min(ship.hp, remaining);
            ship.hp -= hit;
            remaining -= hit;
            if (ship.hp <= 0) {
                fleet.ships.remove(index);
            }
        }
        if (remaining > 0) {
            fleet.baseHp = Math.max(0, fleet.baseHp - remaining);
        }
        if (fleet.baseHp == 0) {
            RoomState.PlayerState owner = state.players.get(fleet.ownerId);
            if (owner != null) {
                owner.fleets.remove(fleet);
            }
        }
    }

    private RoomState.PlayerState requirePlayer(String playerId) {
        RoomState.PlayerState player = state.players.get(playerId);
        if (player == null || !player.connected) {
            throw new IllegalArgumentException("Jugador no conectado a esta sala.");
        }
        return player;
    }

    private RoomState.FleetState ownedFleet(RoomState.PlayerState player, String fleetId) {
        for (RoomState.FleetState fleet : player.fleets) {
            if (fleet.id.equals(fleetId)) {
                return fleet;
            }
        }
        throw new IllegalArgumentException("La flota no pertenece a este jugador.");
    }

    private RoomState.FleetState findFleet(String fleetId) {
        for (RoomState.PlayerState player : state.players.values()) {
            for (RoomState.FleetState fleet : player.fleets) {
                if (fleet.id.equals(fleetId)) {
                    return fleet;
                }
            }
        }
        return null;
    }

    private void persist() {
        repository.saveRoom(state);
    }

    private int availableHomeSystemIndex() {
        for (int index = 0; index < UniverseLayout.homeSystemCount(); index++) {
            int mapX = UniverseLayout.homeMapX(index);
            int mapY = UniverseLayout.homeMapY(index);
            boolean occupied = false;
            for (RoomState.PlayerState existing : state.players.values()) {
                if (existing.homeMapX == mapX && existing.homeMapY == mapY) {
                    occupied = true;
                    break;
                }
            }
            if (!occupied) {
                return index;
            }
        }
        return -1;
    }

    private static void validateName(String name) {
        if (name == null || name.trim().isEmpty() || name.trim().length() > 24) {
            throw new IllegalArgumentException("El nombre debe tener entre 1 y 24 caracteres.");
        }
    }

    private static boolean normalized(float value) {
        return Float.isFinite(value) && value >= 0f && value <= 1f;
    }

    private static boolean validWorldSize(float value) {
        return Float.isFinite(value) && value >= 320f && value <= 8192f;
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static String createToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo proteger el token de sesión.", exception);
        }
    }

    static final class JoinResult {
        final RoomState.PlayerState player;
        final String reconnectToken;

        JoinResult(RoomState.PlayerState player, String reconnectToken) {
            this.player = player;
            this.reconnectToken = reconnectToken;
        }
    }
}
