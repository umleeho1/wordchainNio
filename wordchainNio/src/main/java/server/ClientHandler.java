package server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
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

// 각 클라이언트를 1대1로 관리하는 NIO 핸들러
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

    public void setSelectionKey(SelectionKey selectionKey) {
        this.selectionKey = selectionKey;
    }

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

    // [이벤트] OP_READ 신호가 왔을 때 호출
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

                Message msg = deserializeMessage(data);
                if (msg == null) {
                    handleDisconnection();
                    return;
                }

                lastHeartbeat = System.currentTimeMillis();
                onMessage(msg);
            }
        } catch (Exception e) {
            handleDisconnection();
        }
    }

    // [이벤트] OP_WRITE 신호가 왔을 때 호출
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

 // [수정된 부분] 패킷이 완성되었을 때 호출되는 지점
    private void onMessage(Message msg) {
        // Selector 스레드가 여기서 멈추지 않도록, 모든 로직을 워커 풀에 던집니다.
        KkuGameServer.getWorkerPool().execute(() -> {
            try {
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
                System.err.println(name + "의 로직 처리 중 오류: " + e.getMessage());
            }
        });
    }

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

    private ByteBuffer encodeMessage(Message msg) throws IOException {
        byte[] body = serializeMessage(msg);
        ByteBuffer packet = ByteBuffer.allocate(4 + body.length);
        packet.putInt(body.length);
        packet.put(body);
        packet.flip();
        return packet;
    }

    private byte[] serializeMessage(Message msg) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(msg);
            oos.flush();
            return bos.toByteArray();
        }
    }

    private Message deserializeMessage(byte[] data) {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (Message) ois.readObject();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSpamming() {
        long currentTime = System.currentTimeMillis();
        while (!messageTimestamps.isEmpty() && (currentTime - messageTimestamps.peekFirst() > TIME_WINDOW)) {
            messageTimestamps.pollFirst();
        }
        messageTimestamps.addLast(currentTime);
        return messageTimestamps.size() > MAX_MSG_COUNT;
    }

    public void handleDisconnection() {
        if (!running) return;
        running = false;

        // [수정] 다중 방 시스템에 맞게 세션 정보를 포함하여 유저 제거
        KkuGameServer.removePlayerFromSession(this, this.session);

        if (name != null) {
            session.broadcast(new Message(MsgType.SYSTEM, "서버", name + "님의 연결이 끊어졌습니다."));
        }

        closeResources();
    }

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

    private void enableWriteInterest() {
        SelectionKey key = this.selectionKey;
        if (key == null || !key.isValid()) return;

        synchronized (key) {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        }
        selector.wakeup(); // Selector가 즉시 새로운 관심사를 반영하도록 깨움
    }

    private void disableWriteInterest() {
        SelectionKey key = this.selectionKey;
        if (key == null || !key.isValid()) return;

        synchronized (key) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
        }
    }
}