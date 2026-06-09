package server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
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

import util.FastSerializer;

// [책임] 서버의 메인 진입점(Entry Point)이자 NIO 이벤트 루프(Reactor Pattern)를 실행하는 최상위 네트워크 관리자
// [Why] 수천 개의 클라이언트 연결을 단일 스레드(Selector)로 제어하여 컨텍스트 스위칭 오버헤드를 최소화하고 고성능 네트워크 멀티플렉싱을 구현하기 위함
public class KkuGameServer {
    // 서버 설정 상수
    private static final int MAX_PLAYERS_PER_ROOM = 5; // 한 방당 최대 인원
    private static final int PORT = 5000;
    private static final int HEARTBEAT_TIMEOUT = 100000; // 100초 (생존 확인 기준)
    private static final int BufferSize = 1024 * 256; // 256KB 버퍼

    // [책임] 게임 로직 및 메시지 처리를 전담하는 비동기 워커 스레드 풀
    // [Why] 네트워크 I/O와 비즈니스 로직이 혼재된 환경에서 대기 시간(블로킹)을 고려해 코어 수의 2배로 설정함으로써, Selector 스레드의 병목을 막고 CPU 활용률을 극대화함
    private static final ExecutorService workerPool = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() * 2 
    );
    
    public static FastSerializer getSerializer() {
        return FastSerializer.getInstance();
    }

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
            scheduler.scheduleAtFixedRate(KkuGameServer::checkAliveClients, 10, 50, TimeUnit.SECONDS);

            System.out.println(">>> 끄투 게임 서버(NIO 멀티룸) 준비 완료. 포트: " + PORT);

            // [책임] OS 레벨의 네트워크 이벤트를 감지하고 각 채널의 상태(Accept, Read, Write)에 따라 적절한 핸들러로 작업을 분배(Dispatch)
            // [Why] select()를 통해 준비된 채널만 처리함으로써 무의미한 반복 확인(Busy Waiting)을 방지하고 CPU 점유율을 최적화함
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

    // [책임] 신규 클라이언트 연결을 수락하고, 비차단 모드 설정 및 소켓 버퍼 크기를 조정한 뒤 빈 방에 배정함
    // [Why] SO_RCVBUF와 SO_SNDBUF를 명시적으로 설정하여 대용량 메시지 브로드캐스트 시 발생할 수 있는 네트워크 병목을 완화하고 일관된 처리량을 보장하기 위함
    private static void handleAccept(ServerSocketChannel serverChannel) throws IOException {
        SocketChannel clientChannel = serverChannel.accept();
        if (clientChannel == null) return;

        clientChannel.configureBlocking(false);
        clientChannel.setOption(StandardSocketOptions.SO_RCVBUF, BufferSize);    
        clientChannel.setOption(StandardSocketOptions.SO_SNDBUF, BufferSize);

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

    // [책임] 주기적으로 모든 클라이언트의 마지막 응답 시간(Heartbeat)을 검사하여 연결이 끊긴 '좀비 유저'를 강제 퇴장시킴
    // [Why] 클라이언트가 비정상 종료(랜선 뽑힘, 강제 종료 등)되었을 때 FIN 패킷이 오지 않아 서버 자원(소켓, 힙 메모리)을 영구적으로 점유하는 메모리 누수(Leak)를 방지하기 위함
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

    // [책임] 유저 퇴장 시 세션 데이터, 점수, 인덱스를 안전하게 정리하고 이탈 이벤트를 세션에 전파함
    // [Why] 리스트에서 요소를 제거할 때 발생하는 인덱스 밀림 현상을 보정(`decrementTurnIndex`)하여, 다음 턴의 유저가 억울하게 턴을 스킵당하거나 OutOfBounds 에러가 발생하는 것을 방지함
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

    // 클라이언트 헨들러 접근용 get 워커풀
    public static ExecutorService getWorkerPool() {
        return workerPool;
    }
}