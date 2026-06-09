package server;

import dto.Message;
import dto.MsgType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

// [책임] 단일 게임 방(Room)의 생명주기, 턴 제어, 타이머, 그리고 게임 규칙(WordChainRules)을 관리하는 세션 컨트롤러
// [Why] 전체 서버 로직(KkuGameServer)에서 개별 방의 상태 관리를 분리하여, 여러 방이 동시에 각자의 규칙과 시간의 흐름대로 독립적으로 동작하게 만들기 위함
public class GameSession {
    private final int MAX_PLAYERS;
    private final int TURN_TIME;
    private final int TOTAL_GAME_TIME;

    private final WordChainRules logic;
    private final CopyOnWriteArrayList<ClientHandler> clients;

    private int currentTurnIndex = -1;
    private boolean gameStarted = false;

    private ScheduledFuture<?> globalTimer;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> turnTimer;

    public GameSession(int maxPlayers, int turnTime, int totalGameTime,
                       CopyOnWriteArrayList<ClientHandler> clients,
                       ScheduledExecutorService scheduler) {
        this.MAX_PLAYERS = maxPlayers;
        this.TURN_TIME = turnTime;
        this.TOTAL_GAME_TIME = totalGameTime;
        this.clients = clients;
        this.scheduler = scheduler;
        this.logic = new WordChainRules();
    }

    // --- RoomManager 및 서버 관리 도구들을 위한 메서드 ---
    
    public int getPlayerCount() {
        return clients.size();
    }

    public CopyOnWriteArrayList<ClientHandler> getClients() {
        return clients;
    }

    public boolean getGameStarted() {
        return this.gameStarted;
    }

    public WordChainRules getRules() {
        return logic;
    }

    // ------------------------------------------

    // [책임] 방에 정원이 찼을 때 게임 시작 플래그를 켜고 초기 타이머를 세팅함
    // [Why] 유저들이 입장하자마자 게임이 시작되어 메시지를 놓치지 않도록, 스케줄러를 통해 1초의 딜레이(Think Time)를 주고 턴을 시작함
    public void tryStartGame() {
        if (clients.size() == MAX_PLAYERS && !gameStarted) {
            gameStarted = true;
            startGlobalTimer();

            // 유저들이 입장 메시지를 다 볼 수 있게 1초 뒤 시작
            scheduler.schedule(() -> {
                broadcast(new Message(MsgType.SYSTEM, "서버", MAX_PLAYERS + "명이 모두 모였습니다! 게임을 시작합니다."));
                nextTurn();
            }, 1, TimeUnit.SECONDS);
        }
    }

    // [책임] 한 게임의 전체 제한 시간(TOTAL_GAME_TIME)을 측정하고 종료를 스케줄링
    // [Why] 무한정 길어지는 게임을 방지하고, 서버 자원을 순환시키기 위해 정해진 시간이 지나면 게임을 강제 종료함
    private synchronized void startGlobalTimer() {
        if (globalTimer != null) globalTimer.cancel(false);

        globalTimer = scheduler.schedule(() -> {
            terminateGame("게임 시간이 모두 종료되었습니다. 서버를 닫습니다.");
        }, TOTAL_GAME_TIME, TimeUnit.SECONDS);

        System.out.println("[세션] 전체 게임 타이머 시작: " + TOTAL_GAME_TIME + "초");
    }

    // [책임] 현재 턴인 유저가 제한 시간(TURN_TIME) 내에 단어를 입력하는지 감시
    // [Why] 특정 유저가 단어를 입력하지 않고 잠수(AFK)를 타서 전체 게임의 흐름이 멈추는 것을 방지함
    public synchronized void startTurnTimer() {
        stopTurnTimer();

        turnTimer = scheduler.schedule(() -> {
            if (currentTurnIndex >= 0 && currentTurnIndex < clients.size()) {
                handleTurnFailure(clients.get(currentTurnIndex));
            }
        }, TURN_TIME, TimeUnit.SECONDS);
    }

    // [책임] 게임 강제 종료 시 알림을 보내고 세션 데이터 및 연결을 완전히 정리함
    // [Why] 타이머나 로직에 의한 게임 종료 시, 남은 타이머를 해제하고 클라이언트들이 정리 메시지를 받을 수 있는 유예 시간(3초)을 준 뒤 연결을 끊음
    public synchronized void terminateGame(String reason) {
        broadcast(new Message(MsgType.SYSTEM, "서버", reason));
        stopTurnTimer();

        gameStarted = false;

        // 메시지가 전송될 시간을 준 뒤 연결 정리
        scheduler.schedule(() -> {
            List<ClientHandler> allClients = new ArrayList<>(clients);
            for (ClientHandler c : allClients) {
                c.handleDisconnection();
            }

            currentTurnIndex = -1;
            logic.resetData();

            System.out.println("[세션] 서버 리셋 및 세션 초기화 완료.");
        }, 3, TimeUnit.SECONDS);
    }

    public synchronized void stopTurnTimer() {
        if (turnTimer != null) turnTimer.cancel(false);
    }

    // [책임] 다음 플레이어를 지정하고 그에 맞는 힌트 메시지와 개별 턴 타이머를 갱신
    // [Why] % 연산자(Modulo)를 사용해 인원수에 맞춰 인덱스를 순환시켜 무한 턴 방식을 구현함
    public synchronized void nextTurn() {
        if (clients.isEmpty() || !gameStarted) return;

        // 인원 수에 맞춰 인덱스 순환
        currentTurnIndex = (currentTurnIndex + 1) % clients.size();

        ClientHandler currentPos = clients.get(currentTurnIndex);
        String currentPlayerName = currentPos.getName();

        // 제시어 힌트 구성
        String hint = logic.getLastWord().isEmpty() ? "첫 단어를 입력하세요!" : logic.getLastWord();

        broadcast(new Message(dto.MsgType.GAME_DATA, currentPlayerName, hint));
        startTurnTimer();
    }

    // [책임] 턴 타이머 초과(시간 초과) 시 페널티를 부여하고 다음 턴으로 넘김
    // [Why] 시간 초과 시 벌점(-20점)을 부여하고, 이어갈 단어가 없으므로 이전 단어 기록을 초기화하여 새로운 판이 시작되도록 룰을 강제함
    public void handleTurnFailure(ClientHandler failedPlayer) {
        // 점수 감점 (-20점)
        logic.addScore(failedPlayer.getName(), -20);

        broadcast(new Message(MsgType.SYSTEM, "서버",
                failedPlayer.getName() + "님이 방어에 실패하여 -20점 감점되었습니다! 판을 초기화합니다."));

        // 단어 기록 초기화 및 턴 넘기기
        logic.updateWordHistory("");
        nextTurn();
    }

    // [책임] 유저가 자발적으로 퇴장하거나 접속이 끊겼을 때, 남은 유저들의 세션 상태를 복구하거나 방을 폭파함
    // [Why] '진행 중인 턴 유저 퇴장', '최소 인원(2명) 미달' 등 다양한 예외 상황을 처리하여 세션의 상태 무결성을 유지함
    public synchronized void cleanupOnPlayerExit(ClientHandler exitedPlayer, boolean wasCurrentTurn) {
        // 1. 게임 진행 중인데 1명 이하가 되면 방 종료
        if (gameStarted && clients.size() <= 1) {
            gameStarted = false;
            stopTurnTimer();

            if (globalTimer != null) {
                globalTimer.cancel(false);
                globalTimer = null;
            }

            currentTurnIndex = -1;
            logic.resetData();

            if (clients.size() == 1) {
                broadcast(new Message(MsgType.SYSTEM, "서버", "상대 플레이어가 모두 나가 게임이 종료되었습니다."));
            }
            return;
        }

        // 2. 대기 상태에서 인원이 부족해지면 초기화
        if (!gameStarted && clients.size() < 2) {
            logic.resetData();
            currentTurnIndex = -1;
            return;
        }

        // 3. 내 차례였던 사람이 나간 경우 즉시 다음 턴으로
        if (gameStarted && wasCurrentTurn) {
            currentTurnIndex--; // 리스트가 줄어들므로 인덱스 보정
            stopTurnTimer();

            broadcast(new Message(MsgType.SYSTEM, "서버",
                    exitedPlayer.getName() + "님이 퇴장하여 다음 플레이어 차례로 넘어갑니다."));

            nextTurn();
            return;
        }

        // 4. 게임 중인 다른 유저의 퇴장 알림
        if (gameStarted) {
            broadcast(new Message(MsgType.SYSTEM, "서버",
                    exitedPlayer.getName() + "님이 퇴장했습니다. 남은 인원으로 계속 진행합니다."));
        }
    }

    public int getCurrentTurnIndex() {
        return currentTurnIndex;
    }

    // [책임] 요청한 유저(이름)가 현재 턴의 주인이 맞는지 검증
    public boolean isMyTurn(String name) {
        if (clients.isEmpty() || !gameStarted || currentTurnIndex < 0) {
            return false;
        }

        try {
            int idx = currentTurnIndex % clients.size();
            return clients.get(idx).getName().equals(name);
        } catch (Exception e) {
            return false;
        }
    }

    // [책임] 방에 있는 모든 유저에게 메시지를 복사하여 각자의 큐에 전송 예약 (직렬화 전 단계)
    // [Why] 하나의 메시지 객체를 그대로 돌려쓰면, 유저마다 다른 `isMyTurn` 플래그 값이 덮어씌워지는 동시성 문제가 발생하므로 개별 복사본(Deep-copy 유사)을 만들어 전송함
    public void broadcast(Message msg) {
        // 모든 메시지에 최신 스코어 맵 포함
        msg.setScore(logic.getAllScores());

        for (ClientHandler c : clients) {
            // 메시지 원본 오염 방지를 위해 복사본 생성 (개별 유저의 턴 여부 표시를 위해)
            Message individualMsg = copyMessage(msg);
            individualMsg.setTurn(isMyTurn(c.getName()));
            c.enqueue(individualMsg);
        }
    }

    // [책임] 메시지 객체의 얕은 복사본(Shallow Copy)을 생성
    // [Why] 브로드캐스트 시 `broadcast` 내에서 원본 객체의 상태(Turn 플래그 등)가 변형되는 것을 막기 위함
    private Message copyMessage(Message origin) {
        Message copy = new Message(origin.getType(), origin.getSender(), origin.getContent());
        copy.setScore(origin.getScore());
        copy.setW(origin.getW());
        return copy;
    }

    // [책임] 유저가 리스트에서 삭제될 때, 현재 턴 인덱스가 범위를 벗어나지 않도록 보정함
    // [Why] `CopyOnWriteArrayList`에서 요소가 삭제되면 뒤의 인덱스가 앞당겨지므로, 현재 턴 포인터가 잘못된 대상을 가리키거나 OutOfBounds 에러를 내는 것을 방지함
    public synchronized void decrementTurnIndex() {
        if (currentTurnIndex > 0) {
            currentTurnIndex--;
        }
    }
}