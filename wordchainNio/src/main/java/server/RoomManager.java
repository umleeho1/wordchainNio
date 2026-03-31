package server;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;

public class RoomManager {
    private final CopyOnWriteArrayList<GameSession> rooms = new CopyOnWriteArrayList<>();
    private final int roomCapacity;
    private final ScheduledExecutorService scheduler;
    
    private static final int TURN_TIME = 11; // 턴 제한 11초
    private static final int GAME_LIMIT = 299; // 게임 종료 제한 299
    
    public RoomManager(int roomCapacity, ScheduledExecutorService scheduler) {
        this.roomCapacity = roomCapacity;
        this.scheduler = scheduler;
    }

    // 빈 자리가 있는 방을 찾거나, 없으면 새로 만듭니다.
    public synchronized GameSession findOrCreateRoom() {
        for (GameSession room : rooms) {
            // 게임이 시작되지 않았고, 자리가 남은 방 찾기
            if (!room.getGameStarted() && room.getPlayerCount() < roomCapacity) {
                return room;
            }
        }

        // 모든 방이 꽉 찼으면 새 방 생성
        // 방마다 전용 클라이언트 리스트를 생성해줍니다.
        GameSession newRoom = new GameSession(roomCapacity, TURN_TIME, GAME_LIMIT, new CopyOnWriteArrayList<>(), scheduler);
        rooms.add(newRoom);
        System.out.println("[시스템] 새 게임 룸이 생성되었습니다. (현재 방 개수: " + rooms.size() + ")");
        return newRoom;
    }

    public CopyOnWriteArrayList<GameSession> getAllRooms() {
        return rooms;
    }

    // 방에 아무도 없으면 방을 삭제
    public void removeEmptyRooms() {
        rooms.removeIf(room -> room.getPlayerCount() == 0);
    }
}