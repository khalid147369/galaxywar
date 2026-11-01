package khalid.oopgame.server;

interface RoomRepository {
    void saveGuest(RoomState.PlayerState player);

    void updateGuestSeen(String playerId);

    void deleteGuest(String playerId);

    void saveRoom(RoomState state);

    RoomState loadRoom(String roomCode);
}
