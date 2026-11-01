package khalid.oopgame.server;

final class UniverseLayout {
    private static final int[][] HOME_SYSTEMS = {
        {0, 0}, {3, 3}, {-3, -3}, {3, -3},
        {-3, 3}, {0, 3}, {0, -3}, {3, 0},
        {-3, 0}, {2, 2}, {-2, -2}, {2, -2},
        {-2, 2}, {1, 3}, {-1, -3}, {3, 1}
    };

    private UniverseLayout() {
    }

    static int homeSystemCount() {
        return HOME_SYSTEMS.length;
    }

    static int homeMapX(int index) {
        return HOME_SYSTEMS[index][0];
    }

    static int homeMapY(int index) {
        return HOME_SYSTEMS[index][1];
    }

    static boolean isHomeSystem(int mapX, int mapY) {
        for (int[] coordinates : HOME_SYSTEMS) {
            if (coordinates[0] == mapX && coordinates[1] == mapY) {
                return true;
            }
        }
        return false;
    }
}
