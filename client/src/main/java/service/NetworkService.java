package service;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import controller.GameController;
import dto.Message;
import dto.MsgType;

public class NetworkService {
    private Socket socket;
    // [수정] 객체 스트림 대신 원시 바이트를 제어하기 위해 Data 스트림 사용
    private DataOutputStream out;
    private DataInputStream in;
    private volatile boolean suppressDisconnectError = false;
    private boolean isRunning = false;
    private String nickname;


    private BlockingQueue<Message> sendQueue = new LinkedBlockingQueue<>();
    private Thread senderThread;

    private static NetworkService inst = new NetworkService();
    public static NetworkService getInstance() { return inst; }
    private NetworkService() {}

    public boolean connect(String ip, int port, String nickname) {
        this.nickname = nickname;
        try {
            socket = new Socket(ip, port);
            
            // [중요] NIO 서버와 통신하기 위해 기본 스트림 생성
            out = new DataOutputStream(socket.getOutputStream());
            in = new DataInputStream(socket.getInputStream());

            isRunning = true;
            startSenderThread();

            // 입장 신고
            send(new Message(MsgType.SYSTEM, nickname, "JOIN"));

            // 수신 전담 스레드
            Thread receiverThread = new Thread(() -> {
                try {
                    while (isRunning) {
                        // 1. 서버가 보낸 4바이트 길이 정보 먼저 읽기
                        int length = in.readInt(); 
                        
                        if (length > 0) {
                            // 2. 길이만큼 바이트 배열 생성 후 읽기
                            byte[] data = new byte[length];
                            in.readFully(data); // 데이터가 다 올 때까지 대기
                            
                            // 3. 바이트를 다시 객체로 역직렬화
                            Message msg = deserialize(data);
                            if (msg != null) {
                                GameController.getInstance().onMessageReceived(msg);
                            }
                        }
                    }
                } catch (Exception e) {
                    if (!suppressDisconnectError) {
                        System.err.println("수신 루프 종료: " + e.getMessage());
                        handleDisconnection();
                    }
                } finally {
                    stop();
                }
            }, "ReceiverThread");
            receiverThread.start();

            return true;
        } catch (Exception e) {
            System.err.println("서버 연결 실패: " + e.getMessage());
            stop();
            return false;
        }
    }
    public void prepareForPlannedDisconnect() {
        this.suppressDisconnectError = true;
    }
    private void startSenderThread() {
        senderThread = new Thread(() -> {
            try {
                while (isRunning) {
                    Message msg = sendQueue.take();
                    
                    // [핵심] 객체를 바이트로 바꿔서 길이를 먼저 보냄
                    byte[] data = serialize(msg);
                    synchronized (out) {
                        out.writeInt(data.length); // 4바이트 길이 전송
                        out.write(data);           // 실제 데이터 전송
                        out.flush();
                    }
                }
            } catch (InterruptedException | IOException e) {
                System.out.println("송신 스레드 종료");
            }
        }, "SenderThread");
        senderThread.start();
    }

    public void send(Message msg) {
        if (!isRunning) return;
        sendQueue.offer(msg);
    }

    // --- 직렬화 / 역직렬화 유틸리티 ---

    private byte[] serialize(Message msg) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(msg);
            oos.flush();
            return bos.toByteArray();
        }
    }

    private Message deserialize(byte[] data) throws Exception {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (Message) ois.readObject();
        }
    }

    // --- 나머지 메서드들 (기존과 동일) ---

    public void disconnectGracefully() {
        if (!isRunning) return;
        suppressDisconnectError = true;
        try {
            send(new Message(MsgType.LEAVE, nickname, "LEAVE"));
            Thread.sleep(100); // 전송될 시간 잠시 대기
        } catch (Exception ignored) {}
        finally { stop(); }
    }

    private void handleDisconnection() {
        isRunning = false;
        if (senderThread != null)
            senderThread.interrupt(); 

        // [핵심] 정기적으로 예정된 종료(true)가 아닐 때만 에러 메시지를 띄움
        if (!suppressDisconnectError) {
            GameController.getInstance().onMessageReceived(
                new Message(MsgType.ERROR, "System", "서버와 연결이 끊어졌습니다.")
            );
        }
    }

    public void stop() {
        isRunning = false;
        if (senderThread != null) {
            senderThread.interrupt();
            senderThread = null;
        }
        sendQueue.clear();
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            in = null; out = null; socket = null;
        }
    }
	public String getNickname() {
		return nickname;
	}
	public void setNickname(String nickname) {
		this.nickname = nickname;
	}
}