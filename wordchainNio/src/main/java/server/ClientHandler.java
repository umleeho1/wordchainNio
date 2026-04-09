package server;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import dto.Message;
import dto.MsgType;
import dto.WordStatus;
import util.FastSerializer;

// [책임] 각 클라이언트를 1대1로 전담하는 NIO 핸들러로, 독립적인 네트워크 I/O(수신/송신) 및 세션 상태를 관리
// [Why] 수천 명의 유저가 접속해도 한 유저의 지연이 다른 유저에게 영향을 주지 않도록 상태와 큐를 격리하기 위함
public class ClientHandler {
    private final SocketChannel channel;
    private final Selector selector;
    private final GameSession session;

    private SelectionKey selectionKey;

    // 송신 대기열 (ConcurrentLinkedQueue로 스레드 안전성 확보)
    private final Queue<ByteBuffer> sendQueue = new ConcurrentLinkedQueue<>();

    private String name;
    private volatile boolean running = true;
    private volatile long lastHeartbeat = System.currentTimeMillis();

    // 수신 버퍼링용 변수
    private final ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
    private ByteBuffer payloadBuffer = null;

    // 도배 방지용 슬라이딩 윈도우
    private final LinkedList<Long> messageTimestamps = new LinkedList<>();
    private static final int MAX_MSG_COUNT = 20; // 5초당 최대 20개
    private static final long TIME_WINDOW = 5000L;

    public ClientHandler(SocketChannel channel, Selector selector, GameSession session) {
        this.channel = channel;
        this.selector = selector;
        this.session = session;
    }
    //셀렉터키 이벤트감시상태(read/write) 제어위한설정
    public void setSelectionKey(SelectionKey selectionKey) {
        this.selectionKey = selectionKey;
    }
    //실제 네트워크i/o 를 수행하는 소켓통로 반환
    public SocketChannel getChannel() {
        return channel;
    }

    public long getLastHeartbeat() {
        return lastHeartbeat;
    }

    public String getName() {
        return name;
    }

    public boolean isRunning() {
        return running;
    }

    // [책임] OP_READ 이벤트 발생 시 호출되어, 클라이언트가 보낸 데이터를 비차단(Non-blocking) 방식으로 읽어 완전한 패킷으로 조립
    // [Why] TCP 특성상 데이터가 끊겨서 도착할 수 있으므로(Fragmentation), 4바이트 길이 정보를 먼저 읽고 그만큼의 Payload가 다 찰 때까지 대기하여 패킷 깨짐을 방지함
    public void readReady() {
        if (!running) return;

        try {
            while (true) {
                // 1) 길이 정보 읽기
                if (payloadBuffer == null) {
                    int read = channel.read(lengthBuffer);

                    if (read == -1) {
                        handleDisconnection();
                        return;
                    }

                    if (read == 0) return; // 더 이상 읽을 데이터 없음

                    if (lengthBuffer.hasRemaining()) return; // 4바이트가 다 안 참

                    lengthBuffer.flip();
                    int length = lengthBuffer.getInt();
                    lengthBuffer.clear();

                    if (length <= 0 || length > 10 * 1024 * 1024) {
                        handleDisconnection();
                        return;
                    }

                    payloadBuffer = ByteBuffer.allocate(length);
                }

                // 2) 실제 데이터(Payload) 읽기
                int read = channel.read(payloadBuffer);

                if (read == -1) {
                    handleDisconnection();
                    return;
                }

                if (read == 0) return;

                if (payloadBuffer.hasRemaining()) return; // 데이터가 아직 덜 옴

                // 3) 패킷 완성 및 처리
                payloadBuffer.flip();
                byte[] data = new byte[payloadBuffer.remaining()];
                payloadBuffer.get(data);
                payloadBuffer = null;

                // [수정] 역직렬화(CPU 병목)를 여기서 하지 않고 데이터 덩어리를 바로 넘깁니다.
                lastHeartbeat = System.currentTimeMillis();
                onMessage(data); 
            }
        } catch (Exception e) {
            handleDisconnection();
        }
    }

    // [책임] OP_WRITE 이벤트 발생 시 호출되어, 송신 큐에 쌓인 데이터를 클라이언트의 수신 상태에 맞춰 안전하게 전송
    // [Why] 클라이언트의 수신 버퍼가 가득 차서(Zero Window) 데이터를 다 보내지 못한 경우, 블로킹되지 않고 남은 데이터를 큐에 유지한 채 다음 기회에 이어서 보내기 위함
    public void writeReady() {
        if (!running) return;

        try {
            while (true) {
                ByteBuffer buf = sendQueue.peek();
                if (buf == null) {
                    disableWriteInterest(); // 보낼 게 없으면 쓰기 관심 해제
                    return;
                }

                channel.write(buf);

                if (buf.hasRemaining()) {
                    // 네트워크 버퍼가 꽉 차서 다 못 보냄 -> 다음에 이어서 보냄
                    enableWriteInterest();
                    return;
                }

                // 다 보냈으면 큐에서 제거
                sendQueue.poll();
            }
        } catch (Exception e) {
            handleDisconnection();
        }
    }

    // [책임] 조립이 완성된 패킷(Message)을 받아 네트워크 I/O 스레드와 비즈니스 로직 스레드의 작업을 분리
    // [Why] Selector 스레드가 복잡한 게임 룰 검증을 직접 처리하면 다른 유저들의 네트워크 입출력이 멈추게 되므로, Worker Pool에 작업을 위임하여 서버의 동시 처리량을 극대화함
    private void onMessage(byte[] data) {
        // Selector 스레드가 여기서 멈추지 않도록, 모든 로직을 워커 풀에 던집니다.
        KkuGameServer.getWorkerPool().execute(() -> {
            try {
                // 역직렬화를 워커 스레드 내부에서 수행하여 셀렉터의 병목을 완전히 제거합니다.
                Message msg = deserializeMessage(data);
                if (msg == null) {
                    handleDisconnection();
                    return;
                }

                // 1. 닉네임 등록 로직 (첫 메시지인 경우)
                if (name == null) {
                    this.name = msg.getSender();
                    session.getRules().addScore(this.name, 0);
                    session.broadcast(new Message(MsgType.SYSTEM, "서버", name + "님이 입장하셨습니다!"));
                    return;
                }

                // 2. 일반 게임/채팅 로직 처리
                handleMessage(msg);
                
            } catch (Exception e) {
                System.err.println((name != null ? name : "알 수 없는 유저") + "의 로직 처리 중 오류: " + e.getMessage());
            }
        });
    }

    // [책임] 메시지 타입에 따른 로직 분류 및 도배 방지 처리를 수행하고, 유저의 입력 검증과 끝말잇기 게임 상태 업데이트
    // [Why] 클라이언트의 요청이 서버 룰(순서, 올바른 단어)에 부합하는지 중앙에서 통제하여 게임의 무결성을 유지하기 위함
    private void handleMessage(Message msg) {
        if (isSpamming()) {
            Message spamError = new Message(MsgType.ERROR, "서버", "경고: 메시지 전송이 너무 빠릅니다!");
            spamError.setW(WordStatus.NONE);
            enqueue(spamError);
            return;
        }

        String content = (msg.getContent() != null) ? msg.getContent().trim() : "";
        if (content.isEmpty()) return;

        boolean isActualTurn = session.isMyTurn(this.name);
        //정답을 입력하는 chat일경우 정답처리로직
        if (isActualTurn && !content.contains(" ")) {
            WordStatus result = session.getRules().checkWord(content);

            if (result == WordStatus.SUCCESS) {
                session.stopTurnTimer();
                session.getRules().updateWordHistory(content);
                session.getRules().addScore(this.name, 10);

                Message success = new Message(MsgType.GAME_DATA, this.name, content);
                success.setScore(session.getRules().getAllScores());
                success.setW(WordStatus.SUCCESS);
                session.broadcast(success);

                session.nextTurn();
                return;
            } else {
                Message fail = new Message(MsgType.ERROR, "서버", "단어 규칙 위반: " + result.getMsg());
                fail.setW(result);
                enqueue(fail);
            }
        }

        Message chat = new Message(MsgType.CHAT, this.name, content);
        chat.setScore(session.getRules().getAllScores());
        session.broadcast(chat);
    }

    // [책임] 전송할 Message 객체를 버퍼로 직렬화하여 Lock-free 큐에 안전하게 삽입
    // [Why] GC 최적화가 적용된 FastSerializer를 통해 오버헤드를 줄이고, 큐 삽입 후 Selector를 깨워 OP_WRITE 이벤트를 즉각적으로 처리하게 함
    public void enqueue(Message msg) {
        try {
            // 1. 현재 워커 스레드의 전용 도구를 가져와서 바로 직렬화 
            ByteBuffer packet = FastSerializer.getInstance().serialize(msg);
            
            sendQueue.offer(packet);
            enableWriteInterest();
        } catch (IOException e) {
            handleDisconnection();
        }
    }

    // [책임] 수신된 바이트 배열을 역직렬화하여 게임 로직에서 다룰 수 있는 Message 객체로 변환
    private Message deserializeMessage(byte[] data) {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (Message) ois.readObject();
        } catch (Exception e) {
            return null;
        }
    }

    // [책임] 슬라이딩 윈도우 알고리즘을 이용한 비정상적인 트래픽(도배) 탐지 및 방지
    // [Why] 악의적인 클라이언트가 초당 수천 개의 메시지를 보내 서버 자원을 고갈시키거나 정상적인 게임 진행을 방해하는 것을 방어함
    private boolean isSpamming() {
        long currentTime = System.currentTimeMillis();
        while (!messageTimestamps.isEmpty() && (currentTime - messageTimestamps.peekFirst() > TIME_WINDOW)) {
            messageTimestamps.pollFirst();
        }
        messageTimestamps.addLast(currentTime);
        return messageTimestamps.size() > MAX_MSG_COUNT;
    }

    // [책임] 클라이언트 이탈 시 관련된 세션 정보, 큐, 소켓 자원을 안전하게 회수 및 정리
    // [Why] 메모리 누수(Leak)를 방지하고, 좀비 커넥션을 끊어내며, 같은 방의 다른 유저들에게 퇴장 사실을 알리기 위함
    public void handleDisconnection() {
        if (!running) return;
        running = false;

        // 다중 방 시스템에 맞게 세션 정보를 포함하여 유저 제거
        KkuGameServer.removePlayerFromSession(this, this.session);

        if (name != null) {
            session.broadcast(new Message(MsgType.SYSTEM, "서버", name + "님의 연결이 끊어졌습니다."));
        }

        closeResources();
    }

    // [책임] 핸들러의 OS 레벨 소켓 자원 반환 및 Selector 감시 목록(Key) 취소 등 리소스 종료 처리
    private void closeResources() {
        try {
            if (selectionKey != null) {
                selectionKey.cancel();
            }
        } catch (Exception ignored) {}

        try {
            channel.close();
        } catch (IOException ignored) {}
    }

    // [책임] Selector에게 해당 채널이 '쓰기(OP_WRITE)' 가능 상태인지 확인해달라고 관심사 등록
    // [Why] 보낼 데이터가 있을 때만 활성화하여, Selector의 불필요한 공회전(Busy Waiting)으로 인한 CPU 100% 점유 현상을 방지함
    private void enableWriteInterest() {
        SelectionKey key = this.selectionKey;
        if (key == null || !key.isValid()) return;

        synchronized (key) {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        }
        selector.wakeup(); // Selector가 즉시 새로운 관심사를 반영하도록 깨움
    }

    // [책임] 송신 큐가 비었을 때 OP_WRITE 관심사 제거
    private void disableWriteInterest() {
        SelectionKey key = this.selectionKey;
        if (key == null || !key.isValid()) return;

        synchronized (key) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
        }
    }
}