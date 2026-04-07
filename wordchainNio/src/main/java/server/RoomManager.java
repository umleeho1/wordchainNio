package server;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;

// [책임] 다중 게임 방(Multi-Room)의 생성, 탐색, 삭제 등 전체 생명주기를 관리하는 매치메이킹 시스템
// [Why] 1,000명 이상의 대규모 접속자를 하나의 방에 몰아넣지 않고 소규모(예: 5명) 세션으로 파티셔닝(Partitioning)하여, 네트워크 브로드캐스트 비용을 줄이고 게임 로직을 독립적으로 실행하기 위함
public class RoomManager {
    // [책임] 스레드 안전성(Thread-Safe)이 보장된 방 목록 관리
    // [Why] 방 목록은 신규 접속(추가)이나 빈 방 삭제보다, 서버 관리자(KkuGameServer)가 생존 확인(Heartbeat)을 위해 순회(Read)하는 빈도가 훨씬 높습니다. 따라서 읽기 성능에 최적화된 CopyOnWriteArrayList를 사용했습니다.
    private final CopyOnWriteArrayList<GameSession> rooms = new CopyOnWriteArrayList<>();
    
    private final int roomCapacity;
    private final ScheduledExecutorService scheduler;
    
    private static final int TURN_TIME = 11; // 턴 제한 11초
    private static final int GAME_LIMIT = 299; // 게임 종료 제한 299
    
    public RoomManager(int roomCapacity, ScheduledExecutorService scheduler) {
        this.roomCapacity = roomCapacity;
        this.scheduler = scheduler;
    }

    // [책임] 신규 접속한 유저를 수용할 수 있는 빈 방을 찾거나, 조건에 맞는 방이 없으면 새로운 방을 생성
    // [Why] 여러 NIO 워커 스레드가 동시에 이 메서드를 호출할 때 발생할 수 있는 경쟁 상태(Race Condition)를 막기 위해 `synchronized`를 걸어, 정원을 초과하여 유저가 배정되는 동시성 이슈를 완벽히 차단함
    public synchronized GameSession findOrCreateRoom() {
        for (GameSession room : rooms) {
            // 게임이 시작되지 않았고, 자리가 남은 방 찾기
            if (!room.getGameStarted() && room.getPlayerCount() < roomCapacity) {
                return room;
            }
        }

        // 모든 방이 꽉 찼으면 새 방 생성
        // [책임] 방마다 독립적인 클라이언트 리스트(동시성 컬렉션)를 주입하여 격리된 세션 생성
        GameSession newRoom = new GameSession(roomCapacity, TURN_TIME, GAME_LIMIT, new CopyOnWriteArrayList<>(), scheduler);
        rooms.add(newRoom);
        System.out.println("[시스템] 새 게임 룸이 생성되었습니다. (현재 방 개수: " + rooms.size() + ")");
        return newRoom;
    }

    public CopyOnWriteArrayList<GameSession> getAllRooms() {
        return rooms;
    }

    // [책임] 주기적으로 호출되어 인원이 0명인 유령 방(Zombie Room)을 리스트에서 안전하게 제거
    // [Why] 유저가 모두 퇴장한 빈 방 객체가 계속 리스트에 남아 힙 메모리를 점유하는 메모리 누수(Memory Leak) 현상을 방지하기 위한 자체 가비지 컬렉션(GC) 로직
    public void removeEmptyRooms() {
        rooms.removeIf(room -> room.getPlayerCount() == 0);
    }
}