package khalid.oopgame.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RoomState {
    public String code;
    public long seed;
    public long revision;
    public Map<String, PlayerState> players = new LinkedHashMap<>();
    public List<PlanetState> planets = new ArrayList<>();
    public List<ProjectileState> projectiles = new ArrayList<>();

    public static final class PlayerState {
        public String id;
        public String name;
        public String reconnectTokenHash;
        public boolean connected;
        public long disconnectedAt;
        public int homeMapX;
        public int homeMapY;
        public int metal = 100;
        public int food = 100;
        public int energy = 30;
        public List<FleetState> fleets = new ArrayList<>();
    }

    public static final class FleetState {
        public String id;
        public String ownerId;
        public int mapX;
        public int mapY;
        public float x;
        public float y;
        public float targetX;
        public float targetY;
        public boolean moving;
        public int baseHp = 100;
        public List<ShipState> ships = new ArrayList<>();
        public long lastFireAt;
        public long lastTowerAttackAt;
    }

    public static final class ShipState {
        public String id;
        public int hp = 10;
    }

    public static final class PlanetState {
        public String id;
        public String name;
        public String type;
        public int mapX;
        public int mapY;
        public float x;
        public float y;
        public int size;
        public int maxHp = 100;
        public String ownerId;
        public int hp = 100;
        public long lastSiegeAt;
        public long lastDefenseAt;
        public float naturalProductionFraction;
        public float buildingProductionFraction;
        public List<BuildingState> buildings = new ArrayList<>();
        public List<TowerState> towers = new ArrayList<>();
    }

    public static final class BuildingState {
        public String type;
        public float x;
        public float y;
        public float productionFraction;
    }

    public static final class TowerState {
        public String type;
        public String ownerId;
        public float x;
        public float y;
        public int hp = 30;
    }

    public static final class ProjectileState {
        public String id;
        public String ownerId;
        public String targetFleetId;
        public float x;
        public float y;
        public float targetX;
        public float targetY;
        public float speed = 0.55f;
        public int damage;
    }
}
