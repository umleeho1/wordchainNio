package server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 서버의 네트워크 관리자(접속 수락, 이벤트 분배)를 담당하는 최상위 클래스
 */
public class KkuGameServer {
    // 서버 설정 상수
    private static final int MAX_PLAYERS_PER_ROOM = 5; // 한 방당 최대 인원
    private static final int PORT = 5000;
    private static final int HEARTBEAT_TIMEOUT = 100000; // 100초 (생존 확인 기준)
    private static final ExecutorService workerPool = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() * 2 //기본 1코어2스레드 네트워크경우 거기에*2를 해주는게관행
    );


	// 멀티룸 관리를 위한 매니저
    private static RoomManager roomManager;
    private static Selector selector;
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public static void main(String[] args) {
        try {
            selector = Selector.open();

            // 서버 소켓 채널 설정 (NIO)
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.bind(new InetSocketAddress(PORT));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            // RoomManager 초기화
            roomManager = new RoomManager(MAX_PLAYERS_PER_ROOM, scheduler);

            // 주기적인 생존 확인(Heartbeat) 스케줄링
            scheduler.scheduleAtFixedRate(KkuGameServer::checkAliveClients, 10, 10, TimeUnit.SECONDS);

            System.out.println(">>> 끄투 게임 서버(NIO 멀티룸) 준비 완료. 포트: " + PORT);

            // 메인 이벤트 루프
            while (true) {
                selector.select(); // 이벤트가 발생할 때까지 대기

                Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();

                    if (!key.isValid()) continue;

                    try {
                        if (key.isAcceptable()) {
                            handleAccept(serverChannel);
                        } else {
                            ClientHandler handler = (ClientHandler) key.attachment();
                            if (handler == null) continue;

                            if (key.isReadable()) {
                                handler.readReady();
                            }

                            if (key.isValid() && key.isWritable()) {
                                handler.writeReady();
                            }
                        }
                    } catch (CancelledKeyException ignored) {
                    } catch (Exception e) {
                        // 예외 발생 시 해당 클라이언트 연결 종료 처리
                        Object attachment = key.attachment();
                        if (attachment instanceof ClientHandler) {
                            ((ClientHandler) attachment).handleDisconnection();
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("서버 실행 중 치명적 오류 발생: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 새로운 클라이언트의 접속을 수락하고 방을 배정함
     */
    private static void handleAccept(ServerSocketChannel serverChannel) throws IOException {
        SocketChannel clientChannel = serverChannel.accept();
        if (clientChannel == null) return;

        clientChannel.configureBlocking(false);

        // 빈 자리가 있는 방을 찾거나 새로 생성함
        GameSession availableSession = roomManager.findOrCreateRoom();

        // 클라이언트 핸들러 생성 및 등록
        ClientHandler handler = new ClientHandler(clientChannel, selector, availableSession);
        SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ, handler);
        handler.setSelectionKey(clientKey);

        // 해당 방의 유저 리스트에 추가
        availableSession.getClients().add(handler);

        System.out.println("[접속] 새로운 유저 입장. 배정된 방 인원: " + 
                           availableSession.getPlayerCount() + "/" + MAX_PLAYERS_PER_ROOM);

        // 인원이 다 찼다면 게임 시작 시도
        availableSession.tryStartGame();
    }

    /**
     * 모든 방의 유저들을 순회하며 생존 여부를 확인
     */
    private static void checkAliveClients() {
        long now = System.currentTimeMillis();

        for (GameSession room : roomManager.getAllRooms()) {
            for (ClientHandler c : room.getClients()) {
                if (now - c.getLastHeartbeat() > HEARTBEAT_TIMEOUT) {
                    System.out.println("[관리] 좀비 유저 감지 및 제거: " + c.getName());
                    c.handleDisconnection();
                }
            }
        }
        // 빈 방 정리 (선택 사항)
        roomManager.removeEmptyRooms();
    }

    /**
     * 클라이언트 연결 종료 시 호출되는 정적 메서드
     */
    public static void removePlayerFromSession(ClientHandler c, GameSession session) {
        if (session == null) return;

        int currentTurnIndex = session.getCurrentTurnIndex();
        int removedIdx = session.getClients().indexOf(c);
        
        if (removedIdx == -1) return;

        boolean wasCurrentTurn = (removedIdx == currentTurnIndex);

        // 세션에서 유저 제거
        session.getClients().remove(c);

        // 점수 데이터 제거
        if (c.getName() != null) {
            session.getRules().removeScore(c.getName());
        }

        // 현재 턴보다 앞선 유저가 나갔다면 인덱스 보정
        if (removedIdx < currentTurnIndex) {
            session.decrementTurnIndex();
        }

        // 세션 내 퇴장 처리 로직 실행 (남은 인원 체크 및 게임 종료 여부 판단)
        session.cleanupOnPlayerExit(c, wasCurrentTurn);

        System.out.println("[퇴장] " + (c.getName() != null ? c.getName() : "미등록 유저") + 
                           " 제거 완료. 남은 인원: " + session.getPlayerCount());

        if (selector != null) {
            selector.wakeup(); // 변경사항 반영을 위해 셀렉터 깨움
        }
    }
    //클라이언트 헨들러접근용 get워커풀
    public static ExecutorService getWorkerPool() {
		return workerPool;
	}
}