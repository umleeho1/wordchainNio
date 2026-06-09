package controller;

import java.util.HashMap;
import java.util.Map;
import javax.swing.SwingUtilities;
import dto.Message;
import dto.MsgType;
import dto.WordStatus;
import front.WordChainView;
import service.NetworkService;

public class GameController {
    private static GameController inst = new GameController();
    private WordChainView view; 
    private Map<MsgType, MessageStrategy> strategies = new HashMap<>();

    public static GameController getInstance() {
        return inst;
    }

    private GameController() {
        this.view = WordChainView.getInstance();

        // --- 1. 채팅 메시지 처리 ---
        strategies.put(MsgType.CHAT, (msg, v) -> {
            v.appendChat("[" + msg.getSender() + "] " + msg.getContent());
        });

        // --- 2. 시스템 공지 처리 (핵심 트리거) ---
        strategies.put(MsgType.SYSTEM, (msg, v) -> {
            String content = msg.getContent();
            v.appendChat("▶ [공지] " + content);
            
            // [트리거 1] 전체 게임 시작 시점
            if (content.contains("게임을 시작합니다")) {
                v.initGlobalTimer(300);  // 로컬 전체 타이머(300초) 가동
                v.resetAndStartTimer(10); // 첫 번째 턴 타이머 가동
                v.setInputEnable(true);   // 입력창 활성화
            }

            // [트리거 2] 서버에 의한 턴 강제 전환 (시간 초과 등)
            if (content.contains("시간 초과")) {
                v.resetAndStartTimer(10); // 턴 타이머 리셋
            }

            // [트리거 3] 게임 전체 종료 (서버 terminateGame 결과)
            if (content.contains("종료되었습니다")) {
                v.setInputEnable(false);  // 더 이상 입력 못 하게 차단
                NetworkService.getInstance().prepareForPlannedDisconnect(); // 곧 서버가 연결을 정리하므로 에러 메시지 방지
                v.returnToMenuAfterDelay(5); // 3초 후 메인 메뉴 복귀
            }

            if (msg.getScore() != null) {
                v.updatePlayerUI(msg.getScore());
            }
        });

        // --- 3. 게임 데이터 처리 (정답 성공 및 턴 교체) ---
        strategies.put(MsgType.GAME_DATA, (msg, v) -> {
            // 제시어 업데이트
            if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                v.updateWord(msg.getContent());
            }

            // 턴 교체 시 타이머 리셋 (서버 턴 타이머와 동기화)
            v.resetAndStartTimer(10); 

            // 점수판 및 UI 갱신
            if (msg.getScore() != null) {
                v.updatePlayerUI(msg.getScore());
            }

            // 핵심 로직: 성공 알림과 턴 변경 안내를 분리
            if (msg.getW() == WordStatus.SUCCESS) {
                // [케이스 A] 방금 누군가 정답을 맞힌 순간
                v.appendChat("√ [정답 성공] " + msg.getSender() + ": " + msg.getContent());
                // 이때는 "누구 차례입니다"를 출력하지 않고 넘어갑니다. 
                // 곧이어 올 다음 turn 메시지가 출력할 것이기 때문입니다.
            } else {
                // [케이스 B] 순수하게 턴이 넘어갔을 때 (nextTurn 호출 결과)
                if (msg.isTurn()) {
                    v.appendChat("▶ [나의 턴] 단어를 입력하세요!");
                } else {
                    v.appendChat("▷ [" + msg.getSender() + "] 님의 차례입니다.");
                }
            }
        });

        // --- 4. 에러 처리 ---
        strategies.put(MsgType.ERROR, (msg, v) -> {
            if (msg.getW() != null && msg.getW() != WordStatus.NONE) {
                v.appendChat("! [규칙 위반] " + msg.getW().getMsg());
            } else {
                v.appendChat("! [서버 오류] " + msg.getContent());
            }
        });
    }

    public void onMessageReceived(Message msg) {
        MessageStrategy strategy = strategies.get(msg.getType());
        if (strategy != null) {
            SwingUtilities.invokeLater(() -> {
                strategy.handle(msg, view);
            });
        }
    }

    public void sendWord(String text) {
        if (text == null || text.trim().isEmpty()) return;
        
        // 모든 메시지는 기본 CHAT으로 보내며, 서버가 검증 후 GAME_DATA로 격상시킴
        Message msg = new Message(MsgType.CHAT, NetworkService.getInstance().getNickname(), text);
        NetworkService.getInstance().send(msg);
    }
}

@FunctionalInterface
interface MessageStrategy {
    void handle(Message msg, WordChainView view);
}