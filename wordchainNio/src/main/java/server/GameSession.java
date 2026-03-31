package server;

import dto.Message;
import dto.MsgType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 게임 세션(흐름 제어 및 상태 관리)을 담당하는 클래스
 */
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

    /**
     * 인원이 다 찼을 때 게임 시작을 시도함
     */
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

    /**
     * 방 전체의 게임 제한 시간 타이머
     */
    private synchronized void startGlobalTimer() {
        if (globalTimer != null) globalTimer.cancel(false);

        globalTimer = scheduler.schedule(() -> {
            terminateGame("게임 시간이 모두 종료되었습니다. 서버를 닫습니다.");
        }, TOTAL_GAME_TIME, TimeUnit.SECONDS);

        System.out.println("[세션] 전체 게임 타이머 시작: " + TOTAL_GAME_TIME + "초");
    }

    /**
     * 현재 턴인 유저의 제한 시간 타이머
     */
    public synchronized void startTurnTimer() {
        stopTurnTimer();

        turnTimer = scheduler.schedule(() -> {
            if (currentTurnIndex >= 0 && currentTurnIndex < clients.size()) {
                handleTurnFailure(clients.get(currentTurnIndex));
            }
        }, TURN_TIME, TimeUnit.SECONDS);
    }

    /**
     * 게임 강제 종료 및 정산
     */
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

    /**
     * 다음 유저로 턴을 넘김
     */
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

    /**
     * 유저가 제한 시간을 넘겼을 때 처리
     */
    public void handleTurnFailure(ClientHandler failedPlayer) {
        // 점수 감점 (-20점)
        logic.addScore(failedPlayer.getName(), -20);

        broadcast(new Message(MsgType.SYSTEM, "서버",
                failedPlayer.getName() + "님이 방어에 실패하여 -20점 감점되었습니다! 판을 초기화합니다."));

        // 단어 기록 초기화 및 턴 넘기기
        logic.updateWordHistory("");
        nextTurn();
    }

    /**
     * 유저 퇴장 시 세션 상태 정리
     */
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

    /**
     * 방 안의 모든 유저에게 메시지 전송
     */
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

    /**
     * 객체 참조 문제를 막기 위한 메시지 복사 유틸리티
     */
    private Message copyMessage(Message origin) {
        Message copy = new Message(origin.getType(), origin.getSender(), origin.getContent());
        copy.setScore(origin.getScore());
        copy.setW(origin.getW());
        return copy;
    }

    /**
     * 유저가 삭제될 때 인덱스를 하나 당겨줌
     */
    public synchronized void decrementTurnIndex() {
        if (currentTurnIndex > 0) {
            currentTurnIndex--;
        }
    }
}